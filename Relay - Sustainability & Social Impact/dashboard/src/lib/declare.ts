import type { District } from './districts';
import type { Broadcast, DisasterKey } from '../types';

export const DISASTERS: readonly { key: DisasterKey; label: string }[] = [
  { key: 'flood', label: 'Flood' },
  { key: 'cyclone', label: 'Cyclone' },
  { key: 'earthquake', label: 'Earthquake' },
  { key: 'landslide', label: 'Landslide' },
];

/** What each code switches on at the phones (mirrors the backend's text, so a changed choice updates at once). */
export const PHONE_EFFECT: Record<DisasterKey, string> = {
  flood: 'Phones ask about rising water, water depth and fallen wires; place options start with roof and upper floor.',
  cyclone: 'Phones ask whether the shelter is holding and about fallen wires; place options start with roof and upper floor.',
  earthquake: 'Phones ask about gas smell, building damage, aftershocks and fallen wires; place options start with rubble.',
  landslide: 'Phones ask whether the slope is still moving; place options start with rubble.',
};

export const disasterLabel = (k: DisasterKey): string => DISASTERS.find((d) => d.key === k)?.label ?? k;

/** Phones treat a declaration as fresh for 12 h and drop it at 24 h (SPEC 5.6). */
export const FRESH_S = 12 * 3600;

/** The area a declaration from this district's view covers when the operator names no place. */
export function districtArea(d: District): { name: string; lat: number; lon: number; radius_km: number } {
  return { name: d.name, lat: d.center[1], lon: d.center[0], radius_km: d.radiusKm };
}

/**
 * The declarations still in force, the way a phone sees them: live, and not ended by a later "cleared" of the same
 * disaster (a "cleared" ends every declaration of that disaster issued at or before it, wherever it applied).
 * Newest first.
 */
export function inForce(broadcasts: readonly Broadcast[], nowSec: number): Broadcast[] {
  const live = broadcasts.filter((b) => nowSec < b.expires_at);
  const clearedAt = new Map<DisasterKey, number>();
  for (const b of live) {
    if (b.kind === 'cleared') clearedAt.set(b.disaster, Math.max(clearedAt.get(b.disaster) ?? -1, b.issued_at));
  }
  return live
    .filter((b) => b.kind === 'disaster' && b.issued_at > (clearedAt.get(b.disaster) ?? -1))
    .sort((a, b) => b.issued_at - a.issued_at);
}

/** "3 h left", "aging, 5 h left": what the operator needs to know about a declaration's life. */
export function lifeLabel(b: Broadcast, nowSec: number): string {
  const left = Math.max(0, b.expires_at - nowSec);
  const h = Math.floor(left / 3600);
  const text = h >= 1 ? `${h} h left` : `${Math.max(1, Math.ceil(left / 60))} min left`;
  return nowSec - b.issued_at > FRESH_S ? `aging, ${text}` : text;
}

/** A circle as a GeoJSON ring (equirectangular steps are plenty at district scale). */
export function circleRing(lat: number, lon: number, radiusKm: number, steps = 64): [number, number][] {
  const dLat = radiusKm / 111.32;
  const dLon = radiusKm / (111.32 * Math.max(0.01, Math.cos((lat * Math.PI) / 180)));
  const ring: [number, number][] = [];
  for (let i = 0; i <= steps; i++) {
    const a = (i / steps) * 2 * Math.PI;
    ring.push([lon + dLon * Math.cos(a), lat + dLat * Math.sin(a)]);
  }
  return ring;
}

/** Areas of the declarations in force, for the map. A declaration without an area covers every phone: no circle. */
export function declarationAreas(list: readonly Broadcast[]): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: list.flatMap((b) =>
      b.area
        ? [{
            type: 'Feature' as const,
            geometry: { type: 'Polygon' as const, coordinates: [circleRing(b.area.lat, b.area.lon, b.area.radius_km)] },
            properties: { id: b.id, disaster: b.disaster },
          }]
        : [],
    ),
  };
}
