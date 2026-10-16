/** Relative time between two unix-second timestamps, e.g. "5 min ago". */
export function formatRelative(nowSec: number, thenSec: number): string {
  const d = Math.floor(nowSec - thenSec);
  if (!Number.isFinite(d) || d < 5) return 'just now'; // also covers clock skew (negative)
  if (d < 60) return `${d}s ago`;
  const m = Math.floor(d / 60);
  if (m < 60) return `${m} min ago`;
  const h = Math.floor(m / 60);
  if (h < 24) return m % 60 && h < 6 ? `${h}h ${m % 60}m ago` : `${h}h ago`;
  const days = Math.floor(h / 24);
  return `${days}d ago`;
}
