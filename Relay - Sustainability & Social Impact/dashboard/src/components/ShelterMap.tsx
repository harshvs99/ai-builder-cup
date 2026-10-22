import { useEffect, useRef } from 'react';
import { Map as MapLibreMap, Marker } from 'maplibre-gl';
import type { District } from '../lib/districts';
import type { ShelterEntry } from '../types';

interface Props {
  district: District;
  entries: ShelterEntry[];
  /** Entry waiting for a map click to get its coordinates. */
  selectedId: string | null;
  onSelect: (id: string) => void;
  onPick: (lat: number, lon: number) => void;
}

/** Small map of a shelter draft: a dot per entry that has coordinates; with an entry selected, a click sets its position. */
export function ShelterMap({ district, entries, selectedId, onSelect, onPick }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const markers = useRef<Marker[]>([]);
  const live = useRef({ selectedId, onSelect, onPick });
  live.current = { selectedId, onSelect, onPick };

  useEffect(() => {
    if (!box.current) return;
    const map = new MapLibreMap({
      container: box.current,
      center: district.center,
      zoom: district.zoom,
      attributionControl: { compact: true },
      style: {
        version: 8,
        sources: {
          osm: { type: 'raster', tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], tileSize: 256, maxzoom: 19, attribution: '© OpenStreetMap contributors' },
        },
        layers: [{ id: 'osm', type: 'raster', source: 'osm' }],
      },
    });
    mapRef.current = map;
    map.on('click', (e) => {
      if (live.current.selectedId) live.current.onPick(e.lngLat.lat, e.lngLat.lng);
    });
    return () => {
      markers.current.forEach((m) => m.remove());
      markers.current = [];
      map.remove();
      mapRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [district.id]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map) return;
    markers.current.forEach((m) => m.remove());
    markers.current = entries
      .filter((e) => e.lat != null && e.lon != null)
      .map((e) => {
        const el = document.createElement('button');
        el.type = 'button';
        el.title = e.name;
        el.setAttribute('aria-label', e.name);
        const sel = e.id === selectedId;
        const d = sel ? 18 : 12;
        el.style.cssText = `width:${d}px;height:${d}px;border-radius:50%;border:2px solid #fff;padding:0;cursor:pointer;` +
          `background:${e.verified ? '#2a9d5c' : '#f08c2e'};box-shadow:0 0 0 ${sel ? 3 : 1}px ${sel ? '#2f6fed' : 'rgba(0,0,0,.4)'};`;
        el.addEventListener('click', (ev) => {
          ev.stopPropagation();
          live.current.onSelect(e.id);
        });
        return new Marker({ element: el }).setLngLat([e.lon as number, e.lat as number]).addTo(map);
      });
  }, [entries, selectedId, district.id]);

  return <div ref={box} className="shelter-map" role="application" aria-label="Shelter map" />;
}
