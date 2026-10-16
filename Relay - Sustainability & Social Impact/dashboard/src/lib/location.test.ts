import { describe, expect, it } from 'vitest';
import {
  accuracyLabel, boundsOf, countWithoutLocation, isMappable, mappableTickets, noLocationBadge, toFeatures,
} from './location';
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
