import { describe, expect, it } from 'vitest';
import { formatRelative } from './time';

describe('formatRelative', () => {
  const now = 100_000;
  it.each([
    [0, 'just now'],
    [4, 'just now'],
    [-30, 'just now'],
    [5, '5s ago'],
    [59, '59s ago'],
    [60, '1 min ago'],
    [3599, '59 min ago'],
    [3600, '1h ago'],
    [3600 + 25 * 60, '1h 25m ago'],
    [7 * 3600 + 20 * 60, '7h ago'],
    [24 * 3600, '1d ago'],
    [3 * 24 * 3600 + 5, '3d ago'],
  ])('%is -> %s', (delta, expected) => {
    expect(formatRelative(now, now - delta)).toBe(expected);
  });
  it('handles NaN', () => {
    expect(formatRelative(now, NaN)).toBe('just now');
  });
});
