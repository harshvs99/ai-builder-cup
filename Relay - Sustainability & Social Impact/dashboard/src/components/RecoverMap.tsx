import { useEffect, useRef } from 'react';
import { LngLatBounds, Map as MapLibreMap, type ExpressionSpecification, type GeoJSONSource, type MapMouseEvent } from 'maplibre-gl';
import type { DamageArea } from '../types';
import type { Theme } from '../hooks';
import type { District } from '../lib/districts';
import { DAMAGE_COLOR, DAMAGE_LEGEND, areaFeatures } from '../lib/recover';
import { rasterPaint } from './MapView'; // also sets the maplibre worker url

const EMPTY: GeoJSON.FeatureCollection = { type: 'FeatureCollection', features: [] };

const colorExpr: ExpressionSpecification = [
  'match', ['get', 'level'],
  'severe', DAMAGE_COLOR.severe, 'moderate', DAMAGE_COLOR.moderate, 'light', DAMAGE_COLOR.light,
  DAMAGE_COLOR.none,
];

interface Props {
  areas: DamageArea[];
  district: District | null;
  theme: Theme;
  selected: string | null;
  onSelect: (areaId: string) => void;
}

/** Damage map: one circle per area, sized by reports and coloured by damage level (same base map and theme as the board). */
export function RecoverMap({ areas, district, theme, selected, onSelect }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const ready = useRef(false);
  const fittedFor = useRef<string | null>(null);
  const areasRef = useRef(areas);
  const selectedRef = useRef(selected);
  const themeRef = useRef(theme);
  const districtRef = useRef(district);
  const onSelectRef = useRef(onSelect);
  areasRef.current = areas;
  selectedRef.current = selected;
  themeRef.current = theme;
  districtRef.current = district;
  onSelectRef.current = onSelect;

  const fit = (animate: boolean) => {
    const map = mapRef.current;
    if (!map) return;
    const pts = areasRef.current.filter((a) => a.lat != null && a.lon != null);
    if (pts.length === 0) {
      const d = districtRef.current;
      if (d) map.jumpTo({ center: d.center, zoom: d.zoom });
      return;
    }
    const b = new LngLatBounds([pts[0]!.lon!, pts[0]!.lat!], [pts[0]!.lon!, pts[0]!.lat!]);
    for (const a of pts) b.extend([a.lon!, a.lat!]);
    map.resize();
    map.fitBounds(b, { padding: 70, maxZoom: 13, duration: animate ? 600 : 0 });
  };

  const pushData = () => {
    const map = mapRef.current;
    if (!map || !ready.current) return;
    (map.getSource('areas') as GeoJSONSource | undefined)?.setData(areaFeatures(areasRef.current, selectedRef.current));
    // Frame the areas once per district (and once data first arrives), not on every poll.
    const key = districtRef.current?.id ?? 'all';
    if (areasRef.current.length > 0 && fittedFor.current !== key) {
      fittedFor.current = key;
      fit(false);
    }
  };

  const applyTheme = () => {
    const map = mapRef.current;
    if (!map || !ready.current) return;
    for (const [k, v] of Object.entries(rasterPaint(themeRef.current))) map.setPaintProperty('osm', k as 'raster-saturation', v);
    const ring = themeRef.current === 'dark' ? '#0d1117' : '#ffffff';
    map.setPaintProperty('areas-circle', 'circle-stroke-color', ['case', ['==', ['get', 'selected'], 1], '#2f6fed', ring]);
  };

  useEffect(() => {
    if (!box.current) return;
    const map = new MapLibreMap({
      container: box.current,
      center: districtRef.current?.center ?? [85.83, 19.81],
      zoom: districtRef.current?.zoom ?? 10,
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
      map.addSource('areas', { type: 'geojson', data: EMPTY });
      map.addLayer({
        id: 'areas-circle',
        type: 'circle',
        source: 'areas',
        paint: {
          'circle-color': colorExpr,
          'circle-radius': ['get', 'r'],
          'circle-opacity': 0.8,
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': ['case', ['==', ['get', 'selected'], 1], 4, 2],
        },
      });
      map.on('click', 'areas-circle', (e: MapMouseEvent) => {
        const id = map.queryRenderedFeatures(e.point, { layers: ['areas-circle'] })[0]?.properties?.id;
        if (typeof id === 'string') onSelectRef.current(id);
      });
      map.on('mouseenter', 'areas-circle', () => (map.getCanvas().style.cursor = 'pointer'));
      map.on('mouseleave', 'areas-circle', () => (map.getCanvas().style.cursor = ''));
      ready.current = true;
      applyTheme();
      pushData();
    });
    const ro = new ResizeObserver(() => map.resize());
    ro.observe(box.current);
    return () => {
      ro.disconnect();
      ready.current = false;
      fittedFor.current = null;
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(pushData, [areas, selected]);
  useEffect(applyTheme, [theme]);
  useEffect(() => {
    // switching district re-frames: forget the last fit so the next data push fits again
    fittedFor.current = null;
    pushData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [district?.id]);

  return (
    <div className="recover-map">
      <div ref={box} className="map" role="img" aria-label="Damage map by area" />
      <ul className="legend recover-legend" aria-label="Damage level legend">
        {DAMAGE_LEGEND.map((l) => (
          <li key={l.level}><i className="dot" style={{ background: l.color }} />{l.label}</li>
        ))}
        <li className="muted">circle size = reports</li>
      </ul>
    </div>
  );
}
