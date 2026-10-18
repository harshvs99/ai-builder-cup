import { describe, expect, it } from 'vitest';
import type { DamageArea, RecoverReport } from '../types';
import {
  DAMAGE_COLOR, DAMAGE_LEGEND, areaFeatures, areaRadius, evidenceFileName, evidenceIndexFileName, evidenceIndexJson,
  minutesLabel, percentLabel, reportClipboardText, slug, sortAreas,
} from './recover';

function area(over: Partial<DamageArea> = {}): DamageArea {
  return {
    area_id: 'place:a', name: 'A', district: 'puri', lat: 19.8, lon: 85.8, tickets: 1, reports: 1, duplicates_merged: 0,
    households: 1, people: 1, households_reached: 0, open: 1, dispatched: 0, resolved: 0, needs_confirmed_closed: 0,
    by_category: {}, photo_tags: {}, houses_damaged: 0, houses_collapsed: 0, median_minutes_to_dispatch: null,
    share_with_location: 1, share_with_gps_fix: 1, damage_level: 'light', damage_reason: 'Light: 1 report in all.', ...over,
  };
}

describe('damage legend', () => {
  it('lists the levels worst first, each with its own colour', () => {
    expect(DAMAGE_LEGEND.map((l) => l.level)).toEqual(['severe', 'moderate', 'light', 'none']);
    expect(new Set(DAMAGE_LEGEND.map((l) => l.color)).size).toBe(4);
    expect(DAMAGE_LEGEND[0]).toEqual({ level: 'severe', label: 'Severe', color: DAMAGE_COLOR.severe });
  });
});

describe('sortAreas', () => {
  const a = area({ area_id: '1', name: 'Alpha', damage_level: 'light', reports: 9, households_reached: 1 });
  const b = area({ area_id: '2', name: 'Bravo', damage_level: 'severe', reports: 2, households_reached: 5 });
  const c = area({ area_id: '3', name: 'Charlie', damage_level: 'severe', reports: 6, households_reached: 0 });
  it('puts the worst damage first and breaks ties on reports', () => {
    expect(sortAreas([a, b, c]).map((x) => x.name)).toEqual(['Charlie', 'Bravo', 'Alpha']);
  });
  it('sorts by the other columns and does not mutate the input', () => {
    const input = [a, b, c];
    expect(sortAreas(input, 'reports').map((x) => x.name)).toEqual(['Alpha', 'Charlie', 'Bravo']);
    expect(sortAreas(input, 'reached').map((x) => x.name)).toEqual(['Bravo', 'Alpha', 'Charlie']);
    expect(sortAreas(input, 'name').map((x) => x.name)).toEqual(['Alpha', 'Bravo', 'Charlie']);
    expect(input.map((x) => x.name)).toEqual(['Alpha', 'Bravo', 'Charlie']);
  });
});

describe('map helpers', () => {
  it('sizes circles by reports, clamped', () => {
    expect(areaRadius(0)).toBe(9);
    expect(areaRadius(4)).toBeGreaterThan(areaRadius(1));
    expect(areaRadius(10_000)).toBe(34);
  });
  it('leaves the Unlocated area off the map and marks the selection', () => {
    const fc = areaFeatures([area(), area({ area_id: 'unlocated', name: 'Unlocated', lat: null, lon: null })], 'place:a');
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0]!.properties).toMatchObject({ id: 'place:a', level: 'light', selected: 1 });
    expect(fc.features[0]!.geometry).toEqual({ type: 'Point', coordinates: [85.8, 19.8] });
  });
});

describe('download names', () => {
  it('matches the backend slug', () => {
    expect(slug('place:jagannath-temple')).toBe('place-jagannath-temple');
    expect(evidenceFileName('ticket', 101)).toBe('relay-evidence-ticket-101.json');
    expect(evidenceFileName('area', 'place:jagannath-temple')).toBe('relay-evidence-area-place-jagannath-temple.json');
    expect(evidenceFileName('area', 'grid:1099:4556')).toBe('relay-evidence-area-grid-1099-4556.json');
    expect(evidenceIndexFileName('all')).toBe('relay-evidence-index-all.json');
  });
});

describe('report helpers', () => {
  const report: RecoverReport = {
    generated_at: 1790000000, district: 'puri', counts: { totals: {}, areas: [] }, text_en: 'EN text', text_local: 'LOCAL text',
    local_lang: 'or', model: 'template', evidence_digests: [{ ticket_no: 101, digest: 'ab'.repeat(32) }],
  };
  it('writes the evidence index with the digests', () => {
    const j = JSON.parse(evidenceIndexJson(report));
    expect(j).toMatchObject({ algorithm: 'sha256', district: 'puri', evidence_digests: [{ ticket_no: 101 }] });
  });
  it('puts both languages on the clipboard', () => {
    const t = reportClipboardText(report, 'Puri district');
    expect(t).toContain('Puri district');
    expect(t.indexOf('EN text')).toBeLessThan(t.indexOf('LOCAL text'));
  });
  it('formats minutes and shares', () => {
    expect(minutesLabel(null)).toBe('–');
    expect(minutesLabel(7.4)).toBe('7 min');
    expect(minutesLabel(65)).toBe('1 h 5 min');
    expect(minutesLabel(120)).toBe('2 h');
    expect(percentLabel(0.875)).toBe('88%');
    expect(percentLabel(undefined)).toBe('–');
  });
});
