import type { Ticket } from '../types';
import { deriveTier } from './categories';

/** priority_score desc; ties: waiting longest first, then ticket_no. Pure. */
export function sortTickets(tickets: readonly Ticket[]): Ticket[] {
  return [...tickets].sort(
    (a, b) =>
      b.priority_score - a.priority_score ||
      a.first_reported_at - b.first_reported_at ||
      a.ticket_no - b.ticket_no,
  );
}

export function tierOf(t: Ticket) {
  return t.priority_tier ?? deriveTier(t.priority_score);
}

export function ticketTitle(t: Ticket): string {
  if (t.summary && t.summary.trim()) return t.summary;
  const parts = [t.item, t.landmark ? `near ${t.landmark}` : null].filter(Boolean);
  return parts.length ? parts.join(' ') : `${t.category.toLowerCase().replace('_', ' ')} request`;
}
