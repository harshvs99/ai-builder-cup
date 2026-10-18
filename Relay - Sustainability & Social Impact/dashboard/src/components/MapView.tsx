import { useEffect, useImperativeHandle, useRef, type Ref } from 'react';
import {
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  setWorkerUrl,
  type ExpressionSpecification,
  type GeoJSONSource,
  type MapMouseEvent,
} from 'maplibre-gl';
// maplibre-gl v6 ships an ES-module worker that imports a shared chunk by relative
// path; let Vite bundle it into one self-contained file and point maplibre at it.
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import type { Team, Ticket } from '../types';
import type { Theme } from '../hooks';
import { PULSE_MS } from '../hooks';
import {
  estimateBounds, estimatedTickets, hasMeshEstimate, isMappable, plottableBounds, toEstimateAreas, toFeatures,
} from '../lib/location';
import { tierOf } from '../lib/sort';
import { ticketRef } from '../lib/triage';

setWorkerUrl(workerUrl);

export interface MapHandle {
  fitAll: () => void;
  zoomBy: (delta: number) => void;
  centreOn: (t: Ticket) => void;
}

export interface MapLayers {
  requests: boolean;
  teams: boolean;
  heat: boolean;
}

interface Props {
  ref?: Ref<MapHandle>;
  tickets: Ticket[];
  teams: Team[];
  layers: MapLayers;
  theme: Theme;
  fresh: ReadonlyMap<number, number>;
  selected: number | null;
  onSelect: (no: number) => void;
}

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };
const PULSE_PERIOD_MS = 1300;

/** Urgency colours, shared with the legend in styles.css (--life, --urgent, --soon, --wait). */
export const TIER_COLOR = { P1: '#e5484d', P2: '#f08c2e', P3: '#e9c23b', P4: '#9aa3ad' } as const;
const TEAM_COLOR = '#2f6fed';

const colorExpr: ExpressionSpecification = [
  'match', ['get', 'tier'],
  'P1', TIER_COLOR.P1, 'P2', TIER_COLOR.P2, 'P3', TIER_COLOR.P3,
  TIER_COLOR.P4,
];

// Radius grows with the number of people; clamped outside the stops.
const radiusExpr: ExpressionSpecification = ['interpolate', ['linear'], ['get', 'people'], 1, 7, 6, 11, 20, 16, 60, 22];

function peopleRadius(people: number): number {
  const stops: [number, number][] = [[1, 7], [6, 11], [20, 16], [60, 22]];
  if (people <= 1) return 7;
  for (let i = 1; i < stops.length; i++) {
    const [x0, y0] = stops[i - 1]!;
    const [x1, y1] = stops[i]!;
    if (people <= x1) return y0 + ((people - x0) / (x1 - x0)) * (y1 - y0);
  }
  return 22;
}

/** OSM raster restyled per theme: muted in light, inverted (hue kept) in dark. */
function rasterPaint(theme: Theme) {
  return theme === 'dark'
    ? { 'raster-brightness-min': 0.92, 'raster-brightness-max': 0.08, 'raster-saturation': -0.55, 'raster-hue-rotate': 180, 'raster-contrast': 0.1 }
    : { 'raster-brightness-min': 0.05, 'raster-brightness-max': 1, 'raster-saturation': -0.7, 'raster-hue-rotate': 0, 'raster-contrast': -0.05 };
}

function teamFeatures(teams: readonly Team[]): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: teams.map((t) => ({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: [t.base_lon, t.base_lat] },
      properties: { id: t.id, name: t.name, off: t.status === 'off' ? 1 : 0 },
    })),
  };
}

export function MapView({ ref, tickets, teams, layers, theme, fresh, selected, onSelect }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const ready = useRef(false);
  const fitted = useRef(false);
  const ticketsRef = useRef(tickets);
  const teamsRef = useRef(teams);
  const layersRef = useRef(layers);
  const themeRef = useRef(theme);
  const selectedRef = useRef(selected);
  const freshRef = useRef(fresh);
  const onSelectRef = useRef(onSelect);
  const raf = useRef<number | null>(null);
  const estMarkers = useRef(new Map<number, { marker: Marker; el: HTMLDivElement }>());
  ticketsRef.current = tickets;
  teamsRef.current = teams;
  layersRef.current = layers;
  themeRef.current = theme;
  selectedRef.current = selected;
  freshRef.current = fresh;
  onSelectRef.current = onSelect;

  const fitAll = () => {
    const map = mapRef.current;
    const box4 = plottableBounds(ticketsRef.current); // GPS fixes + mesh-estimate circles
    if (!map || !box4) return;
    const b = new LngLatBounds([box4[0], box4[1]], [box4[2], box4[3]]);
    const ne = b.getNorthEast();
    const sw = b.getSouthWest();
    if (ne.lng - sw.lng < 1e-4 && ne.lat - sw.lat < 1e-4) {
      map.easeTo({ center: b.getCenter(), zoom: 13 });
    } else {
      map.fitBounds(b, { padding: 60, maxZoom: 14, duration: 600 });
    }
  };
  const centreOn = (t: Ticket) => {
    const map = mapRef.current;
    if (!map) return;
    if (isMappable(t)) {
      map.easeTo({ center: [t.lon, t.lat], zoom: Math.max(map.getZoom(), 13), duration: 500 });
    } else if (hasMeshEstimate(t)) {
      // Zoom to the whole uncertainty disc (a point when the backend sent no radius).
      map.resize(); // the detail panel may have just narrowed the map pane
      const [w, s, e, n] = estimateBounds(t.geo);
      if (e - w < 1e-7 && n - s < 1e-7) {
        map.easeTo({ center: [t.geo.lon, t.geo.lat], zoom: Math.max(map.getZoom(), 15), duration: 500 });
      } else {
        map.fitBounds([[w, s], [e, n]], { padding: 90, maxZoom: 18, duration: 500 });
      }
    }
  };

  /** Dashed centre markers for mesh-estimated tickets (circle layers cannot be dashed). */
  const syncEstimateMarkers = (map: MapLibreMap) => {
    const live = new Set<number>();
    for (const t of estimatedTickets(ticketsRef.current)) {
      live.add(t.ticket_no);
      let entry = estMarkers.current.get(t.ticket_no);
      if (!entry) {
        const el = document.createElement('div');
        el.className = 'est-marker';
        el.setAttribute('role', 'button');
        const no = t.ticket_no;
        el.addEventListener('click', (ev) => {
          ev.stopPropagation();
          onSelectRef.current(no);
        });
        entry = { marker: new Marker({ element: el }).setLngLat([t.geo.lon, t.geo.lat]).addTo(map), el };
        estMarkers.current.set(no, entry);
      }
      const d = Math.round(peopleRadius(t.people) * 2);
      entry.marker.setLngLat([t.geo.lon, t.geo.lat]);
      entry.el.style.setProperty('--c', TIER_COLOR[tierOf(t)]);
      entry.el.style.width = entry.el.style.height = `${d}px`;
      entry.el.style.display = layersRef.current.requests ? '' : 'none';
      entry.el.classList.toggle('selected', t.ticket_no === selectedRef.current);
      entry.el.classList.toggle('dispatched', t.status !== 'OPEN');
      entry.el.title = `${ticketRef(t.ticket_no)}: approximate location (estimate, not a GPS fix)`;
      entry.el.setAttribute('aria-label', `${ticketRef(t.ticket_no)} approximate location`);
    }
    for (const [no, { marker }] of estMarkers.current) {
      if (!live.has(no)) {
        marker.remove();
        estMarkers.current.delete(no);
      }
    }
  };
  useImperativeHandle(ref, () => ({
    fitAll,
    centreOn,
    zoomBy: (d: number) => mapRef.current?.easeTo({ zoom: mapRef.current.getZoom() + d, duration: 250 }),
  }));

  const pushData = () => {
    const map = mapRef.current;
    if (!map || !ready.current) return;
    (map.getSource('tickets') as GeoJSONSource | undefined)?.setData(toFeatures(ticketsRef.current, selectedRef.current));
    (map.getSource('teams') as GeoJSONSource | undefined)?.setData(teamFeatures(teamsRef.current));
    (map.getSource('estimates') as GeoJSONSource | undefined)?.setData(
      toEstimateAreas(ticketsRef.current, selectedRef.current),
    );
    syncEstimateMarkers(map);
    if (!fitted.current && ticketsRef.current.some((t) => isMappable(t) || hasMeshEstimate(t))) {
      fitted.current = true; // first load only
      fitAll();
    }
  };

  const applyLayers = () => {
    const map = mapRef.current;
    if (!map || !ready.current) return;
    const l = layersRef.current;
    const vis = (on: boolean) => (on ? 'visible' : 'none');
    for (const id of ['tickets-circle', 'pulse-ring', 'estimate-fill', 'estimate-line']) map.setLayoutProperty(id, 'visibility', vis(l.requests));
    map.setLayoutProperty('teams-base', 'visibility', vis(l.teams));
    map.setLayoutProperty('heat', 'visibility', vis(l.heat));
    syncEstimateMarkers(map);
  };

  const applyTheme = () => {
    const map = mapRef.current;
    if (!map || !ready.current) return;
    for (const [k, v] of Object.entries(rasterPaint(themeRef.current))) {
      map.setPaintProperty('osm', k as 'raster-saturation', v);
    }
    const ring = themeRef.current === 'dark' ? '#0d1117' : '#ffffff';
    map.setPaintProperty('tickets-circle', 'circle-stroke-color', ['case', ['==', ['get', 'selected'], 1], '#2f6fed', ring]);
  };

  const pulseFrame = () => {
    raf.current = null;
    const map = mapRef.current;
    if (!map || !ready.current) return;
    const now = Date.now();
    const byNo = new Map(ticketsRef.current.map((t) => [t.ticket_no, t]));
    const features: GeoJSON.Feature[] = [];
    for (const [no, start] of freshRef.current) {
      const t = byNo.get(no);
      if (!t || !isMappable(t) || now - start >= PULSE_MS) continue;
      features.push({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [t.lon, t.lat] },
        properties: { tier: tierOf(t), r: peopleRadius(t.people), phase: ((now - start) % PULSE_PERIOD_MS) / PULSE_PERIOD_MS },
      });
    }
    (map.getSource('pulses') as GeoJSONSource | undefined)?.setData({ type: 'FeatureCollection', features });
    if (features.length > 0) raf.current = requestAnimationFrame(pulseFrame);
  };

  // Map lifecycle.
  useEffect(() => {
    if (!box.current) return;
    const map = new MapLibreMap({
      container: box.current,
      center: [85.83, 19.81],
      zoom: 11,
      attributionControl: { compact: true },
      style: {
        version: 8,
        sources: {
          osm: {
            type: 'raster',
            tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
            tileSize: 256,
            maxzoom: 19,
            attribution: '© OpenStreetMap contributors',
          },
        },
        layers: [{ id: 'osm', type: 'raster', source: 'osm', paint: rasterPaint(themeRef.current) }],
      },
    });
    mapRef.current = map;

    map.on('load', () => {
      map.addSource('tickets', { type: 'geojson', data: EMPTY });
      map.addSource('pulses', { type: 'geojson', data: EMPTY });
      map.addSource('teams', { type: 'geojson', data: EMPTY });
      map.addSource('estimates', { type: 'geojson', data: EMPTY });
      // Mesh-estimate uncertainty discs: translucent fill + dashed outline, beneath the GPS dots.
      map.addLayer({
        id: 'estimate-fill',
        type: 'fill',
        source: 'estimates',
        paint: {
          'fill-color': colorExpr,
          'fill-opacity': ['case', ['==', ['get', 'selected'], 1], 0.2, ['==', ['get', 'dispatched'], 1], 0.07, 0.14],
        },
      });
      map.addLayer({
        id: 'estimate-line',
        type: 'line',
        source: 'estimates',
        paint: {
          'line-color': colorExpr,
          'line-width': ['case', ['==', ['get', 'selected'], 1], 3, 2],
          'line-dasharray': [2, 2],
          'line-opacity': ['case', ['==', ['get', 'dispatched'], 1], 0.45, 0.95],
        },
      });
      map.addLayer({
        id: 'heat',
        type: 'heatmap',
        source: 'tickets',
        filter: ['==', ['get', 'dispatched'], 0],
        paint: {
          'heatmap-weight': ['interpolate', ['linear'], ['get', 'score'], 0, 0.1, 20, 1],
          'heatmap-radius': ['interpolate', ['linear'], ['zoom'], 8, 18, 14, 46],
          'heatmap-opacity': 0.6,
          'heatmap-color': [
            'interpolate', ['linear'], ['heatmap-density'],
            0, 'rgba(0,0,0,0)', 0.2, 'rgba(233,194,59,0.5)', 0.5, 'rgba(240,140,46,0.75)', 1, 'rgba(229,72,77,0.9)',
          ],
        },
      });
      map.addLayer({
        id: 'teams-base',
        type: 'circle',
        source: 'teams',
        paint: {
          'circle-color': TEAM_COLOR,
          'circle-radius': 6,
          'circle-opacity': ['case', ['==', ['get', 'off'], 1], 0.35, 0.95],
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 2,
        },
      });
      map.addLayer({
        id: 'pulse-ring',
        type: 'circle',
        source: 'pulses',
        paint: {
          'circle-color': colorExpr,
          'circle-radius': ['+', ['get', 'r'], ['*', ['get', 'phase'], 30]],
          'circle-opacity': ['*', 0.35, ['-', 1, ['get', 'phase']]],
          'circle-stroke-color': colorExpr,
          'circle-stroke-width': 2.5,
          'circle-stroke-opacity': ['-', 1, ['get', 'phase']],
        },
      });
      map.addLayer({
        id: 'tickets-circle',
        type: 'circle',
        source: 'tickets',
        paint: {
          'circle-color': colorExpr,
          'circle-radius': radiusExpr,
          'circle-opacity': ['case', ['==', ['get', 'dispatched'], 1], 0.35, 0.95],
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': ['case', ['==', ['get', 'selected'], 1], 4, 2],
          'circle-stroke-opacity': ['case', ['==', ['get', 'dispatched'], 1], 0.5, 1],
        },
      });
      map.on('click', 'tickets-circle', (e: MapMouseEvent) => {
        const f = map.queryRenderedFeatures(e.point, { layers: ['tickets-circle'] })[0];
        const no = f?.properties?.ticket_no;
        if (typeof no === 'number') onSelectRef.current(no);
      });
      map.on('click', 'estimate-fill', (e: MapMouseEvent) => {
        // A GPS dot on top wins; the disc only handles clicks on its own area.
        if (map.queryRenderedFeatures(e.point, { layers: ['tickets-circle'] }).length > 0) return;
        const no = map.queryRenderedFeatures(e.point, { layers: ['estimate-fill'] })[0]?.properties?.ticket_no;
        if (typeof no === 'number') onSelectRef.current(no);
      });
      map.on('mouseenter', 'tickets-circle', () => (map.getCanvas().style.cursor = 'pointer'));
      map.on('mouseleave', 'tickets-circle', () => (map.getCanvas().style.cursor = ''));
      ready.current = true;
      applyTheme();
      applyLayers();
      pushData();
      pulseFrame();
    });

    const ro = new ResizeObserver(() => map.resize());
    ro.observe(box.current);

    return () => {
      ro.disconnect();
      estMarkers.current.clear();
      if (raf.current !== null) cancelAnimationFrame(raf.current);
      raf.current = null;
      ready.current = false;
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(pushData, [tickets, teams, selected]);
  useEffect(applyLayers, [layers]);
  useEffect(applyTheme, [theme]);

  useEffect(() => {
    if (fresh.size > 0 && raf.current === null) raf.current = requestAnimationFrame(pulseFrame);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fresh]);

  // Centre on selection.
  useEffect(() => {
    const t = selected === null ? null : ticketsRef.current.find((x) => x.ticket_no === selected);
    if (t) centreOn(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected]);

  return <div ref={box} className="map" />;
}
