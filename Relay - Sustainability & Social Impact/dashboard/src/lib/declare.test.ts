import { describe, expect, it } from 'vitest';
import { circleRing, declarationAreas, districtArea, inForce, lifeLabel } from './declare';
import { DISTRICTS } from './districts';
import type { Broadcast } from '../types';

const T0 = 1_800_000_000;
const b = (over: Partial<Broadcast>): Broadcast => ({
  id: Math.random().toString(16).slice(2), kind: 'disaster', disaster: 'flood', issued_at: T0, expires_at: T0 + 86400, ...over,
});

describe('declarations', () => {
  it('a cleared ends earlier declarations of that disaster only', () => {
    const flood = b({ id: 'f1' });
    const quake = b({ id: 'q1', disaster: 'earthquake' });
    const cleared = b({ kind: 'cleared', issued_at: T0 + 60, expires_at: T0 + 60 + 86400 });
    const later = b({ id: 'f2', issued_at: T0 + 120, expires_at: T0 + 120 + 86400 });
    expect(inForce([flood, quake, cleared], T0 + 100).map((x) => x.id)).toEqual(['q1']);
    expect(inForce([flood, quake, cleared, later], T0 + 200).map((x) => x.id)).toEqual(['f2', 'q1']);
  });

  it('drops what has expired and marks the second half of the day as aging', () => {
    expect(inForce([b({})], T0 + 86400)).toEqual([]);
    expect(lifeLabel(b({}), T0 + 3600)).toBe('23 h left');
    expect(lifeLabel(b({}), T0 + 13 * 3600)).toBe('aging, 11 h left');
    expect(lifeLabel(b({}), T0 + 86400 - 90)).toBe('aging, 2 min left');
  });

  it('defaults the area to the district view', () => {
    expect(districtArea(DISTRICTS[0]!)).toEqual({ name: 'Puri', lat: 19.81, lon: 85.83, radius_km: 60 });
  });

  it('draws a closed circle of the right size, and none for a network-wide declaration', () => {
    const ring = circleRing(20, 85, 10);
    expect(ring[0]).toEqual(ring[ring.length - 1]);
    expect(ring[0]![0] - 85).toBeCloseTo(10 / (111.32 * Math.cos((20 * Math.PI) / 180)), 6);
    const fc = declarationAreas([b({ area: { lat: 20, lon: 85, radius_km: 10 } }), b({})]);
    expect(fc.features).toHaveLength(1);
  });
});
