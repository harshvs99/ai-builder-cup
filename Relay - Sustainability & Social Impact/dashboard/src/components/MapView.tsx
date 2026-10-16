import { useEffect, useImperativeHandle, useRef, type Ref } from 'react';
import {
  LngLatBounds,
  Map as MapLibreMap,
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
import { boundsOf, isMappable, toFeatures } from '../lib/location';

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
  ticketsRef.current = tickets;
  selectedRef.current = selected;
  freshRef.current = fresh;
  onSelectRef.current = onSelect;

  const fitAll = () => {
    const map = mapRef.current;
    const list = ticketsRef.current;
    const box4 = boundsOf(list); // only tickets with a GPS fix
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

  const pushData = () => {
    const map = mapRef.current;
    if (!map || !ready.current) return;
    (map.getSource('tickets') as GeoJSONSource | undefined)?.setData(
      toFeatures(ticketsRef.current, selectedRef.current),
    );
    if (!fitted.current && ticketsRef.current.some(isMappable)) {
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
    if (map && t && isMappable(t)) map.easeTo({ center: [t.lon, t.lat], zoom: Math.max(map.getZoom(), 11), duration: 500 });
  }, [selected]);

  return <div ref={box} className="map" />;
}
