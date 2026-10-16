import type { Ticket } from '../types';

/**
 * Ticket numbers present in `current` but not in `previous`.
 * `previous === null` means this is the first poll: nothing counts as new
 * (otherwise the whole backlog would pulse on page load).
 */
export function detectNewTickets(
  previous: ReadonlySet<number> | null,
  current: readonly Ticket[],
): number[] {
  if (previous === null) return [];
  return current.filter((t) => !previous.has(t.ticket_no)).map((t) => t.ticket_no);
}
