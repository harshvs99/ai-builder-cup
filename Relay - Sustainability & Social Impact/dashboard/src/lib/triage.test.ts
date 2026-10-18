import { describe, expect, it } from 'vitest';
import { mkTicket } from '../test-utils';
import type { Team } from '../types';
import {
  formatMinutes,
  headline,
  matchesFilter,
  matchesSearch,
  median,
  openByArea,
  orderQueue,
  rowState,
  teamRows,
  urgencyOf,
} from './triage';

const team = (over: Partial<Team> = {}): Team => ({
  id: 'boat-1', name: 'Boat 1', kind: 'boat', capacity: 2, load: 0, base_lat: 0, base_lon: 0, status: 'free', ...over,
});

describe('triage helpers', () => {
  it('maps tiers to plain-language urgency', () => {
    expect(urgencyOf(mkTicket({ priority_tier: 'P1' })).label).toBe('Life at risk');
    expect(urgencyOf(mkTicket({ priority_tier: 'P4' })).key).toBe('wait');
  });

  it('derives the row state from status and what the operator has opened', () => {
    const seen = new Set([2]);
    expect(rowState(mkTicket({ ticket_no: 1 }), seen)).toBe('NEW');
    expect(rowState(mkTicket({ ticket_no: 2 }), seen)).toBe('SEEN');
    expect(rowState(mkTicket({ ticket_no: 1, status: 'RESOLVED' }), seen)).toBe('DELIVERED');
    expect(rowState(mkTicket({ ticket_no: 1, status: 'ON_THE_WAY' }), seen)).toBe('ON THE WAY');
  });

  it('builds a headline from item and people', () => {
    expect(headline(mkTicket({ item: 'drinking water', people: 6 }))).toBe('Drinking water · 6 people');
    expect(headline(mkTicket({ item: 'boat', category: 'RESCUE', people: 1 }))).toBe('Rescue · 1 person');
    expect(headline(mkTicket({ item: null, category: 'FOOD', people: 0 }))).toBe('Food');
  });

  it('filters by urgency, category and missing location', () => {
    const t = mkTicket({ priority_tier: 'P2', category: 'WATER', lat: null, lon: null });
    expect(matchesFilter(t, { kind: 'urgency', key: 'urgent' })).toBe(true);
    expect(matchesFilter(t, { kind: 'category', category: 'WATER' })).toBe(true);
    expect(matchesFilter(t, { kind: 'noloc' })).toBe(true);
    expect(matchesFilter(mkTicket(), { kind: 'noloc' })).toBe(false);
  });

  it('searches by ref, landmark and text', () => {
    const t = mkTicket({ ticket_no: 104, landmark: 'Primary school' });
    expect(matchesSearch(t, 'R-104')).toBe(true);
    expect(matchesSearch(t, '10')).toBe(true);
    expect(matchesSearch(t, '2')).toBe(false);
    expect(matchesSearch(t, 'school')).toBe(true);
    expect(matchesSearch(t, 'temple')).toBe(false);
  });

  it('keeps open requests above closed ones in every order', () => {
    const a = mkTicket({ ticket_no: 1, first_reported_at: 10, status: 'RESOLVED' });
    const b = mkTicket({ ticket_no: 2, first_reported_at: 30 });
    const c = mkTicket({ ticket_no: 3, first_reported_at: 20 });
    expect(orderQueue([a, b, c], 'oldest').map((t) => t.ticket_no)).toEqual([3, 2, 1]);
    expect(orderQueue([a, b, c], 'newest').map((t) => t.ticket_no)).toEqual([2, 3, 1]);
  });

  it('groups open requests by landmark, life-at-risk first', () => {
    const rows = openByArea([
      mkTicket({ ticket_no: 1, landmark: 'school', category: 'WATER' }),
      mkTicket({ ticket_no: 2, landmark: 'school', category: 'WATER' }),
      mkTicket({ ticket_no: 3, landmark: 'temple', category: 'RESCUE', priority_tier: 'P1' }),
      mkTicket({ ticket_no: 4, landmark: 'temple', status: 'RESOLVED' }),
    ]);
    expect(rows.map((r) => [r.area, r.open, r.lifeAtRisk, r.topNeed])).toEqual([
      ['Temple', 1, 1, 'RESCUE'],
      ['School', 2, 0, 'WATER'],
    ]);
  });

  it('summarises team activity and the soonest ETA', () => {
    const t = mkTicket({
      status: 'DISPATCHED',
      dispatch: { team_id: 'boat-1', team_name: 'Boat 1', eta_min: 30, approved_at: 1000 },
    });
    const [row] = teamRows([team({ load: 1 })], [t], 1000 + 10 * 60);
    expect(row!.activity).toBe('Assigned');
    expect(row!.nextEtaMin).toBe(20);
    expect(teamRows([team({ status: 'off' })], [], 0)[0]!.activity).toBe('Off duty');
  });

  it('formats minutes and medians', () => {
    expect(formatMinutes(40)).toBe('40 min');
    expect(formatMinutes(70)).toBe('1 h 10 min');
    expect(formatMinutes(120)).toBe('2 h');
    expect(median([5, 1, 3])).toBe(3);
    expect(median([])).toBeNull();
  });
});
