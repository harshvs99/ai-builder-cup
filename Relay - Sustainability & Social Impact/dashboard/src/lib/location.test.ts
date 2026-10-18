import { describe, expect, it } from 'vitest';
import {
  accuracyLabel, boundsOf, canLocateByLandmark, circleRing, countEstimated, countWithoutLocation, estimateBounds, estimatedTickets,
  hasMapsEstimate, hasMeshEstimate, isMappable, mappableTickets, mapsEstimateText, mapsLink, meshEstimateLabel, meshEstimateShort, noLocationBadge,
  plottableBounds, toEstimateAreas, toFeatures,
} from './location';
import type { Geo } from '../types';
import { mkTicket } from '../test-utils';

const nofix = (over = {}) =>
  mkTicket({ ticket_no: 104, lat: null, lon: null, has_location: false, loc_acc: 0, loc_acc_m: null, ...over });
const fix = (over = {}) =>
  mkTicket({ ticket_no: 101, lat: 20.29, lon: 85.82, has_location: true, loc_acc: 1, loc_acc_m: '<10 m', ...over });

describe('isMappable / mappableTickets', () => {
  it('accepts only tickets with a fix and finite coordinates', () => {
    expect(isMappable(fix())).toBe(true);
    expect(isMappable(nofix())).toBe(false);
    expect(isMappable(fix({ lat: Number.NaN }))).toBe(false);
    expect(isMappable(fix({ lon: Infinity }))).toBe(false);
    expect(isMappable(fix({ lat: null }))).toBe(false);
    expect(isMappable(fix({ has_location: false }))).toBe(false); // flag wins over coordinates
  });
  it('never plots 0,0, even from a backend without has_location', () => {
    expect(isMappable(mkTicket({ lat: 0, lon: 0 }))).toBe(false);
    expect(isMappable(mkTicket({ lat: 0, lon: 85.8 }))).toBe(true);  // equator is a place
    expect(isMappable(mkTicket({ lat: 20.29, lon: 85.82 }))).toBe(true); // legacy: flag absent
  });
  it('filters a mixed list', () => {
    const list = [fix(), nofix(), fix({ ticket_no: 5 })];
    expect(mappableTickets(list).map((t) => t.ticket_no)).toEqual([101, 5]);
    expect(countWithoutLocation(list)).toBe(1);
  });
});

describe('toFeatures', () => {
  it('makes a feature only for tickets with a fix, in [lon, lat] order', () => {
    const fc = toFeatures([nofix(), fix({ ticket_no: 7 }), mkTicket({ ticket_no: 9, lat: 0, lon: 0 })], 7);
    expect(fc.features).toHaveLength(1);
    const f = fc.features[0]!;
    expect(f.id).toBe(7);
    expect(f.geometry).toEqual({ type: 'Point', coordinates: [85.82, 20.29] });
    expect(f.properties).toMatchObject({ ticket_no: 7, selected: 1, dispatched: 0 });
  });
  it('is empty when no ticket has a fix', () => {
    expect(toFeatures([nofix()], null).features).toEqual([]);
  });
});

describe('boundsOf (fit-bounds input)', () => {
  it('ignores no-fix tickets, so the ocean point cannot widen the view', () => {
    const b = boundsOf([
      fix({ ticket_no: 1, lat: 20.0, lon: 85.0 }),
      fix({ ticket_no: 2, lat: 21.0, lon: 86.0 }),
      nofix(),
      mkTicket({ ticket_no: 3, lat: 0, lon: 0 }),
    ]);
    expect(b).toEqual([85.0, 20.0, 86.0, 21.0]);
  });
  it('is null when nothing can be plotted', () => {
    expect(boundsOf([])).toBeNull();
    expect(boundsOf([nofix()])).toBeNull();
  });
});

describe('noLocationBadge / accuracyLabel', () => {
  it('names the landmark when there is one', () => {
    expect(noLocationBadge(nofix({ landmark: 'school' }))).toBe('📍 No location — near school');
  });
  it('omits the landmark when absent or blank', () => {
    expect(noLocationBadge(nofix({ landmark: '' }))).toBe('📍 No location');
    expect(noLocationBadge(nofix({ landmark: '  ' }))).toBe('📍 No location');
    expect(noLocationBadge(nofix({ landmark: null }))).toBe('📍 No location');
  });
  it('has no badge for tickets with a fix, which show their accuracy instead', () => {
    expect(noLocationBadge(fix())).toBeNull();
    expect(accuracyLabel(fix())).toBe('<10 m');
    expect(accuracyLabel(nofix())).toBeNull();
  });
});

// ---- mesh estimates -------------------------------------------------------------------------

const mesh = (over: Partial<Geo> = {}): Geo => ({
  lat: 20.3, lon: 85.8, source: 'mesh', radius_m: 130, hint_count: 2, ...over,
});
const estimated = (over = {}, geo: Partial<Geo> = {}) => nofix({ geo: mesh(geo), ...over });

/** Haversine distance in metres. */
function dist(a: [number, number], b: [number, number]): number {
  const R = 6_371_000, rad = Math.PI / 180;
  const dLat = (b[1] - a[1]) * rad, dLon = (b[0] - a[0]) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

describe('circleRing', () => {
  it.each([[0, 130], [20.3, 130], [20.3, 5000], [60, 130], [78, 1000], [-70, 250], [85, 130]])(
    'is a closed ring with the right radius at lat %s, r=%s m',
    (lat, r) => {
      const ring = circleRing(85.8, lat, r);
      expect(ring.length).toBeGreaterThanOrEqual(33);
      expect(ring[0]).toEqual(ring[ring.length - 1]); // closed
      for (const p of ring) expect(Math.abs(dist([85.8, lat], p) - r) / r).toBeLessThan(0.01);
    },
  );
  it('is counter-clockwise and encloses the centre', () => {
    const ring = circleRing(85.8, 20.3, 130);
    let area = 0; // shoelace, positive = CCW
    for (let i = 0; i < ring.length - 1; i++) {
      area += ring[i]![0] * ring[i + 1]![1] - ring[i + 1]![0] * ring[i]![1];
    }
    expect(area).toBeGreaterThan(0);
    const xs = ring.map((p) => p[0]), ys = ring.map((p) => p[1]);
    expect(Math.min(...xs)).toBeLessThan(85.8);
    expect(Math.max(...xs)).toBeGreaterThan(85.8);
    expect(Math.min(...ys)).toBeLessThan(20.3);
    expect(Math.max(...ys)).toBeGreaterThan(20.3);
  });
  it('is wider in degrees of longitude at high latitude (metres stay constant)', () => {
    const span = (lat: number) => {
      const xs = circleRing(0, lat, 500).map((p) => p[0]);
      return Math.max(...xs) - Math.min(...xs);
    };
    expect(span(70)).toBeGreaterThan(span(0) * 2.5);
  });
  it('stays finite and keeps longitudes continuous across the antimeridian', () => {
    const ring = circleRing(179.999, 10, 500);
    for (const [x, y] of ring) {
      expect(Number.isFinite(x) && Number.isFinite(y)).toBe(true);
    }
    const xs = ring.map((p) => p[0]);
    expect(Math.max(...xs) - Math.min(...xs)).toBeLessThan(0.02);
  });
  it('honours the step count', () => {
    expect(circleRing(0, 0, 100, 16)).toHaveLength(17);
  });
});

describe('hasMeshEstimate / estimatedTickets', () => {
  it('is true only for a no-fix ticket with a mesh geo', () => {
    expect(hasMeshEstimate(estimated())).toBe(true);
    expect(hasMeshEstimate(nofix())).toBe(false);
    expect(hasMeshEstimate(nofix({ geo: null }))).toBe(false);
    expect(hasMeshEstimate(nofix({ geo: mesh({ source: 'gazetteer', query: 'school' }) }))).toBe(false);
    expect(hasMeshEstimate(nofix({ geo: mesh({ source: 'gemini_maps' }) }))).toBe(false);
  });
  it('ignores unusable coordinates', () => {
    expect(hasMeshEstimate(estimated({}, { lat: Number.NaN }))).toBe(false);
    expect(hasMeshEstimate(estimated({}, { lon: Infinity }))).toBe(false);
    expect(hasMeshEstimate(estimated({}, { lat: 0, lon: 0 }))).toBe(false);
    expect(hasMeshEstimate(estimated({}, { lat: 91 }))).toBe(false);
  });
  it('is not an estimate when the ticket has a real fix (GPS wins)', () => {
    expect(hasMeshEstimate(fix({ geo: mesh() }))).toBe(false);
  });
  it('a mesh estimate never makes a ticket mappable', () => {
    const t = estimated();
    expect(isMappable(t)).toBe(false);
    expect(mappableTickets([t])).toEqual([]);
    expect(toFeatures([t], null).features).toEqual([]);
    expect(boundsOf([t])).toBeNull();
    expect(noLocationBadge(t)).toBe('📍 No location — near school');
  });
  it('lists estimated tickets', () => {
    expect(estimatedTickets([fix(), nofix(), estimated({ ticket_no: 9 })]).map((t) => t.ticket_no)).toEqual([9]);
  });
});

describe('count honesty', () => {
  it('estimated tickets still count as without location; the estimate count is a subset', () => {
    const list = [fix(), nofix(), estimated({ ticket_no: 9 }), estimated({ ticket_no: 10 }), fix({ ticket_no: 5 })];
    expect(countWithoutLocation(list)).toBe(3);
    expect(countEstimated(list)).toBe(2);
    expect(countWithoutLocation(list)).toBeGreaterThanOrEqual(countEstimated(list));
  });
  it('is unchanged by adding or removing geo', () => {
    expect(countWithoutLocation([nofix()])).toBe(countWithoutLocation([estimated()]));
    expect(countEstimated([nofix(), fix()])).toBe(0);
  });
});

describe('meshEstimateLabel / meshEstimateShort', () => {
  it('formats radius and phone count', () => {
    expect(meshEstimateLabel(mesh())).toBe('about 130 m · from 2 nearby phones');
    expect(meshEstimateShort(mesh())).toBe('≈130 m · 2 phones');
  });
  it('uses the singular for one phone', () => {
    expect(meshEstimateLabel(mesh({ hint_count: 1 }))).toBe('about 130 m · from 1 nearby phone');
    expect(meshEstimateShort(mesh({ hint_count: 1 }))).toBe('≈130 m · 1 phone');
  });
  it('degrades gracefully when fields are missing or bad', () => {
    expect(meshEstimateLabel(mesh({ hint_count: undefined }))).toBe('about 130 m');
    expect(meshEstimateLabel(mesh({ radius_m: undefined }))).toBe('from 2 nearby phones');
    expect(meshEstimateLabel(mesh({ radius_m: undefined, hint_count: undefined }))).toBe('from nearby phones');
    expect(meshEstimateLabel(mesh({ radius_m: 0, hint_count: 0 }))).toBe('from nearby phones');
    expect(meshEstimateLabel(mesh({ radius_m: Number.NaN }))).toBe('from 2 nearby phones');
    expect(meshEstimateShort(mesh({ radius_m: undefined, hint_count: undefined }))).toBe('from nearby phones');
  });
});

describe('estimate geometry for the map', () => {
  it('toEstimateAreas makes one closed polygon per estimate with a radius', () => {
    const fc = toEstimateAreas(
      [fix(), nofix(), estimated({ ticket_no: 9 }), estimated({ ticket_no: 10 }, { radius_m: undefined })],
      9,
    );
    expect(fc.features).toHaveLength(1);
    const f = fc.features[0]!;
    expect(f.id).toBe(9);
    expect(f.properties).toMatchObject({ ticket_no: 9, selected: 1, radius_m: 130, category: 'MEDICAL', tier: 'P2' });
    expect(f.geometry.type).toBe('Polygon');
    const ring = (f.geometry as GeoJSON.Polygon).coordinates[0]!;
    expect(ring[0]).toEqual(ring[ring.length - 1]);
  });
  it('estimateBounds spans the circle, or is a point without a radius', () => {
    const [w, s, e, n] = estimateBounds(mesh());
    expect(dist([w, 20.3], [e, 20.3])).toBeGreaterThan(250);
    expect(dist([85.8, s], [85.8, n])).toBeLessThan(270);
    expect(estimateBounds(mesh({ radius_m: undefined }))).toEqual([85.8, 20.3, 85.8, 20.3]);
  });
  it('plottableBounds merges GPS fixes and estimate circles', () => {
    expect(plottableBounds([nofix()])).toBeNull();
    const only = plottableBounds([estimated()])!;
    expect(only[0]).toBeLessThan(85.8);
    expect(only[2]).toBeGreaterThan(85.8);
    const both = plottableBounds([fix({ lat: 21, lon: 86 }), estimated()])!;
    expect(both[0]).toBeLessThan(85.8);
    expect(both[3]).toBeGreaterThanOrEqual(21);
    expect(both[2]).toBeGreaterThanOrEqual(86);
    // GPS-only behaviour unchanged
    expect(plottableBounds([fix({ lat: 21, lon: 86 })])).toEqual([86, 21, 86, 21]);
  });
});

describe('Google Maps estimates', () => {
  const maps = (over: Partial<Geo> = {}): Geo => ({
    lat: 19.81, lon: 85.83, source: 'gemini_maps', query: 'pipili bus stand', name: 'Pipili Bus Stand',
    place_id: 'ChIJabc', maps_uri: 'https://maps.google.com/?cid=42', district: 'puri', ...over,
  });
  it('hasMapsEstimate: only a no-fix ticket with a gemini_maps geo', () => {
    expect(hasMapsEstimate(nofix({ geo: maps() }))).toBe(true);
    expect(hasMapsEstimate(nofix({ geo: maps({ source: 'gazetteer' }) }))).toBe(false);
    expect(hasMapsEstimate(fix({ geo: maps() }))).toBe(false);
    expect(hasMapsEstimate(nofix())).toBe(false);
    expect(hasMeshEstimate(nofix({ geo: maps() }))).toBe(false); // never drawn as a mesh circle
    expect(countWithoutLocation([nofix({ geo: maps() })])).toBe(1); // still "without location"
  });
  it('mapsEstimateText names the place and the landmark, and degrades', () => {
    expect(mapsEstimateText(maps())).toEqual({ place: 'Pipili Bus Stand', rest: 'found on Google Maps from the landmark “pipili bus stand”' });
    expect(mapsEstimateText(maps({ name: undefined, query: undefined }))).toEqual({ place: null, rest: 'found on Google Maps' });
  });
  it('mapsLink only returns https URLs', () => {
    expect(mapsLink(maps())).toBe('https://maps.google.com/?cid=42');
    expect(mapsLink(maps({ maps_uri: 'javascript:alert(1)' }))).toBeNull();
    expect(mapsLink(maps({ maps_uri: 'http://maps.google.com/' }))).toBeNull();
    expect(mapsLink(maps({ maps_uri: undefined }))).toBeNull();
  });
  it('canLocateByLandmark: no fix, a landmark, no mesh estimate', () => {
    expect(canLocateByLandmark(nofix({ landmark: 'school' }))).toBe(true);
    expect(canLocateByLandmark(nofix({ landmark: 'school', geo: maps() }))).toBe(true);
    expect(canLocateByLandmark(nofix({ landmark: '  ' }))).toBe(false);
    expect(canLocateByLandmark(nofix({ landmark: null }))).toBe(false);
    expect(canLocateByLandmark(fix({ landmark: 'school' }))).toBe(false);
    expect(canLocateByLandmark(nofix({ landmark: 'school', geo: { lat: 1, lon: 1, source: 'mesh' } }))).toBe(false);
  });
});
