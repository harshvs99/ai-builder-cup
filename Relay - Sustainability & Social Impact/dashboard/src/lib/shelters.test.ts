import { describe, expect, it } from 'vitest';
import { emptyEntry, nextEntryId, parseCoord, publishableCount, suggestionsDoc } from './shelters';

describe('shelter list helpers', () => {
  it('picks the next free entry id', () => {
    expect(nextEntryId([])).toBe('e1');
    expect(nextEntryId([emptyEntry('e1'), emptyEntry('e7'), emptyEntry('x')])).toBe('e8');
  });

  it('parses coordinates strictly', () => {
    expect(parseCoord(' 19.8 ')).toBe(19.8);
    expect(parseCoord('')).toBeNull();
    expect(parseCoord('abc')).toBeNull();
  });

  it('counts only verified entries with coordinates', () => {
    const a = { ...emptyEntry('e1'), verified: true, lat: 19.8, lon: 85.8 };
    const b = { ...emptyEntry('e2'), verified: true };
    const c = { ...emptyEntry('e3'), lat: 19.8, lon: 85.8 };
    expect(publishableCount([a, b, c])).toBe(1);
  });

  it('wraps suggestions so links open in a new tab', () => {
    expect(suggestionsDoc('<b>x</b>')).toContain('<base target="_blank">');
  });
});
