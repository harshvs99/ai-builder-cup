import { useEffect, useImperativeHandle, useRef, type Ref } from 'react';
import {
  LngLatBounds,
  Map as MapLibreMap,
  Marker,
  NavigationControl,
  setWorkerUrl,
  type ExpressionSpecification,
  type GeoJSONSource,
  type MapMouseEvent,
} from 'maplibre-gl';
// maplibre-gl v6 ships an ES-module worker that imports a shared chunk by relative
// path; let Vite bundle it into one self-contained file and point maplibre at it.
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';
import type { Ticket } from '../types';
import { CATEGORIES } from '../types';
import { CATEGORY_META } from '../lib/categories';
import { PULSE_MS } from '../hooks';
import {
  estimateBounds, estimatedTickets, hasMeshEstimate, isMappable, plottableBounds, toEstimateAreas, toFeatures,
} from '../lib/location';

setWorkerUrl(workerUrl);

export interface MapHandle {
  fitAll: () => void;
}

interface Props {
  ref?: Ref<MapHandle>;
  tickets: Ticket[];
  fresh: ReadonlyMap<number, number>;
  selected: number | null;
  onSelect: (no: number) => void;
}

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };
const PULSE_PERIOD_MS = 1300;

const colorExpr: ExpressionSpecification = [
  'match',
  ['get', 'category'],
  ...CATEGORIES.flatMap((c) => [c, CATEGORY_META[c].color]),
  CATEGORY_META.OTHER.color,
] as unknown as ExpressionSpecification;

// Radius grows with priority_score; clamped outside the stops.
const radiusExpr: ExpressionSpecification = [
  'interpolate', ['linear'], ['get', 'score'], 0, 6, 5, 10, 10, 16, 20, 22,
];

function scoreRadius(score: number): number {
  const stops: [number, number][] = [[0, 6], [5, 10], [10, 16], [20, 22]];
  if (score <= 0) return 6;
  for (let i = 1; i < stops.length; i++) {
    const [x0, y0] = stops[i - 1]!;
    const [x1, y1] = stops[i]!;
    if (score <= x1) return y0 + ((score - x0) / (x1 - x0)) * (y1 - y0);
  }
  return 22;
}

export function MapView({ ref, tickets, fresh, selected, onSelect }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const ready = useRef(false);
  const fitted = useRef(false);
  const ticketsRef = useRef(tickets);
  const selectedRef = useRef(selected);
  const freshRef = useRef(fresh);
  const onSelectRef = useRef(onSelect);
  const raf = useRef<number | null>(null);
  const estMarkers = useRef(new Map<number, { marker: Marker; el: HTMLDivElement }>());
  ticketsRef.current = tickets;
  selectedRef.current = selected;
  freshRef.current = fresh;
  onSelectRef.current = onSelect;

  const fitAll = () => {
    const map = mapRef.current;
    const list = ticketsRef.current;
    const box4 = plottableBounds(list); // GPS fixes + mesh-estimate circles
    if (!map || !box4) return;
    const b = new LngLatBounds([box4[0], box4[1]], [box4[2], box4[3]]);
    const ne = b.getNorthEast();
    const sw = b.getSouthWest();
    if (ne.lng - sw.lng < 1e-4 && ne.lat - sw.lat < 1e-4) {
      map.easeTo({ center: b.getCenter(), zoom: 13 });
    } else {
      map.fitBounds(b, { padding: 70, maxZoom: 14, duration: 600 });
    }
  };
  useImperativeHandle(ref, () => ({ fitAll }));

  /** Dashed, translucent centre markers for mesh-estimated tickets (circle layers cannot be dashed). */
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
        const marker = new Marker({ element: el }).setLngLat([t.geo.lon, t.geo.lat]).addTo(map);
        entry = { marker, el };
        estMarkers.current.set(t.ticket_no, entry);
      }
      entry.marker.setLngLat([t.geo.lon, t.geo.lat]);
      entry.el.style.setProperty('--c', CATEGORY_META[t.category]?.color ?? CATEGORY_META.OTHER.color);
      entry.el.classList.toggle('selected', t.ticket_no === selectedRef.current);
      entry.el.classList.toggle('dispatched', t.status !== 'OPEN');
      entry.el.title = `Ticket #${t.ticket_no}: approximate location (estimate, not a GPS fix)`;
      entry.el.setAttribute('aria-label', `Ticket ${t.ticket_no} approximate location`);
    }
    for (const [no, { marker }] of estMarkers.current) {
      if (!live.has(no)) {
        marker.remove();
        estMarkers.current.delete(no);
      }
    }
  };

  const pushData = () => {
    const map = mapRef.current;
    if (!map || !ready.current) return;
    (map.getSource('tickets') as GeoJSONSource | undefined)?.setData(
      toFeatures(ticketsRef.current, selectedRef.current),
    );
    (map.getSource('estimates') as GeoJSONSource | undefined)?.setData(
      toEstimateAreas(ticketsRef.current, selectedRef.current),
    );
    syncEstimateMarkers(map);
    if (!fitted.current && ticketsRef.current.some((t) => isMappable(t) || hasMeshEstimate(t))) {
      fitted.current = true; // first load only
      fitAll();
    }
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
        properties: {
          category: t.category,
          r: scoreRadius(t.priority_score),
          phase: ((now - start) % PULSE_PERIOD_MS) / PULSE_PERIOD_MS,
        },
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
      center: [85.82, 20.29],
      zoom: 5,
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
        layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
      },
    });
    mapRef.current = map;
    map.addControl(new NavigationControl({ showCompass: false }), 'top-left');

    map.on('load', () => {
      map.addSource('tickets', { type: 'geojson', data: EMPTY });
      map.addSource('pulses', { type: 'geojson', data: EMPTY });
      map.addSource('estimates', { type: 'geojson', data: EMPTY });
      // Mesh-estimate uncertainty discs: translucent fill + dashed outline, beneath the GPS dots.
      map.addLayer({
        id: 'estimate-fill',
        type: 'fill',
        source: 'estimates',
        paint: {
          'fill-color': colorExpr,
          'fill-opacity': [
            'case', ['==', ['get', 'selected'], 1], 0.2, ['==', ['get', 'dispatched'], 1], 0.07, 0.15,
          ],
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
          'circle-opacity': ['case', ['==', ['get', 'dispatched'], 1], 0.3, 0.85],
          'circle-stroke-color': ['case', ['==', ['get', 'selected'], 1], '#111111', '#ffffff'],
          'circle-stroke-width': ['case', ['==', ['get', 'selected'], 1], 3, 1.5],
          'circle-stroke-opacity': ['case', ['==', ['get', 'dispatched'], 1], 0.4, 1],
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

  useEffect(pushData, [tickets, selected]);

  useEffect(() => {
    if (fresh.size > 0 && raf.current === null) raf.current = requestAnimationFrame(pulseFrame);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fresh]);

  // Centre on selection.
  useEffect(() => {
    const map = mapRef.current;
    const t = selected === null ? null : ticketsRef.current.find((x) => x.ticket_no === selected);
    if (!map || !t) return;
    if (isMappable(t)) {
      map.easeTo({ center: [t.lon, t.lat], zoom: Math.max(map.getZoom(), 11), duration: 500 });
    } else if (hasMeshEstimate(t)) {
      // Zoom to the whole uncertainty disc (a point when the backend sent no radius).
      const [w, s, e, n] = estimateBounds(t.geo);
      if (e - w < 1e-7 && n - s < 1e-7) {
        map.easeTo({ center: [t.geo.lon, t.geo.lat], zoom: Math.max(map.getZoom(), 15), duration: 500 });
      } else {
        map.fitBounds([[w, s], [e, n]], { padding: 90, maxZoom: 18, duration: 500 });
      }
    }
  }, [selected]);

  return <div ref={box} className="map" />;
}
