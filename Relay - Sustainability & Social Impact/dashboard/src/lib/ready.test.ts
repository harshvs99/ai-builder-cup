import { describe, expect, it } from 'vitest';
import { localInputToUnix, whenLabel } from './ready';

describe('ready helpers', () => {
  it('turns a datetime-local value into unix seconds', () => {
    const v = '2026-10-19T12:00';
    expect(localInputToUnix(v)).toBe(Math.floor(new Date(v).getTime() / 1000));
  });
  it('is undefined for empty or invalid input', () => {
    expect(localInputToUnix('')).toBeUndefined();
    expect(localInputToUnix('soon')).toBeUndefined();
  });
  it('labels a missing time with a dash', () => {
    expect(whenLabel(null)).toBe('–');
    expect(whenLabel(1_790_000_000)).not.toBe('–');
  });
});
