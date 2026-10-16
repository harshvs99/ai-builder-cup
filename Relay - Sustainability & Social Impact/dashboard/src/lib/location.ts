import type { Ticket } from '../types';

export type MappableTicket = Ticket & { lat: number; lon: number };

/**
 * True only for tickets with a real GPS fix. Tolerates backends that predate has_location
 * (it is then inferred from the coordinates, where exactly 0,0 is the phones' no-fix
 * placeholder). Null / non-finite / 0,0 are never plotted.
 */
export function isMappable(t: Ticket): t is MappableTicket {
  if (t.has_location === false) return false;
  const { lat, lon } = t;
  if (typeof lat !== 'number' || typeof lon !== 'number') return false;
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return false;
  return !(lat === 0 && lon === 0);
}

export function mappableTickets(tickets: readonly Ticket[]): MappableTicket[] {
  return tickets.filter(isMappable);
}

export function countWithoutLocation(tickets: readonly Ticket[]): number {
  return tickets.reduce((n, t) => n + (isMappable(t) ? 0 : 1), 0);
}

/** Queue / detail badge for a ticket without a fix; null for tickets that have one. */
export function noLocationBadge(t: Ticket): string | null {
  if (isMappable(t)) return null;
  const lm = t.landmark?.trim();
  return lm ? `📍 No location — near ${lm}` : '📍 No location';
}

/** Accuracy label for a ticket with a fix ("<10 m"), else null. */
export function accuracyLabel(t: Ticket): string | null {
  return isMappable(t) && t.loc_acc_m ? t.loc_acc_m : null;
}

/** [west, south, east, north] of the plottable tickets, or null when there are none. */
export function boundsOf(tickets: readonly Ticket[]): [number, number, number, number] | null {
  const pts = mappableTickets(tickets);
  if (pts.length === 0) return null;
  let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
  for (const t of pts) {
    w = Math.min(w, t.lon); e = Math.max(e, t.lon);
    s = Math.min(s, t.lat); n = Math.max(n, t.lat);
  }
  return [w, s, e, n];
}

/** GeoJSON for the ticket layer: only tickets with a fix become features. */
export function toFeatures(tickets: readonly Ticket[], selected: number | null): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: mappableTickets(tickets).map((t) => ({
      type: 'Feature',
      id: t.ticket_no,
      geometry: { type: 'Point', coordinates: [t.lon, t.lat] },
      properties: {
        ticket_no: t.ticket_no,
        category: t.category,
        score: t.priority_score,
        dispatched: t.status === 'DISPATCHED' ? 1 : 0,
        selected: t.ticket_no === selected ? 1 : 0,
      },
    })),
  };
}
