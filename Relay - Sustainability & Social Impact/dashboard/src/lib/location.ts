import type { Geo, Ticket } from '../types';
import { tierOf } from './sort';

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
        tier: tierOf(t),
        people: t.people,
        dispatched: t.status !== 'OPEN' ? 1 : 0,
        selected: t.ticket_no === selected ? 1 : 0,
      },
    })),
  };
}

// ---------------------------------------------------------------------------------------------
// Mesh estimates: an approximation from nearby relay phones' location hints. Never a GPS fix, so
// none of this feeds isMappable / mappableTickets / countWithoutLocation.
// ---------------------------------------------------------------------------------------------

export type MeshEstimate = Geo & { source: 'mesh' };
export type EstimatedTicket = Ticket & { geo: MeshEstimate };

const EARTH_M = 6_371_000;
const RING_STEPS = 64;

const finite = (n: unknown): n is number => typeof n === 'number' && Number.isFinite(n);

/**
 * True for a ticket that has NO GPS fix but carries a usable mesh estimate (finite lat/lon).
 * A ticket with a real fix is never "estimated": the fix wins and the estimate is ignored.
 */
export function hasMeshEstimate(t: Ticket): t is EstimatedTicket {
  const g = t.geo;
  if (!g || g.source !== 'mesh' || isMappable(t)) return false;
  if (!finite(g.lat) || !finite(g.lon)) return false;
  if (Math.abs(g.lat) > 90 || Math.abs(g.lon) > 180) return false;
  return !(g.lat === 0 && g.lon === 0);
}

export function estimatedTickets(tickets: readonly Ticket[]): EstimatedTicket[] {
  return tickets.filter(hasMeshEstimate);
}

/** Tickets that are without a location AND have a mesh estimate: a subset of countWithoutLocation. */
export function countEstimated(tickets: readonly Ticket[]): number {
  return tickets.reduce((n, t) => n + (hasMeshEstimate(t) ? 1 : 0), 0);
}

/** Usable radius in metres, or null when the backend sent none (marker only, no circle). */
export function estimateRadiusM(g: Geo): number | null {
  return finite(g.radius_m) && g.radius_m > 0 ? g.radius_m : null;
}

const hintCount = (g: Geo): number | null =>
  finite(g.hint_count) && g.hint_count >= 1 ? Math.round(g.hint_count) : null;

/** "about 130 m · from 2 nearby phones" (singular: "1 nearby phone"); degrades when fields are missing. */
export function meshEstimateLabel(g: Geo): string {
  const r = estimateRadiusM(g);
  const k = hintCount(g);
  const parts: string[] = [];
  if (r !== null) parts.push(`about ${Math.round(r)} m`);
  if (k !== null) parts.push(`from ${k} nearby phone${k === 1 ? '' : 's'}`);
  return parts.length > 0 ? parts.join(' · ') : 'from nearby phones';
}

/** Compact form for a queue row: "≈130 m · 2 phones". */
export function meshEstimateShort(g: Geo): string {
  const r = estimateRadiusM(g);
  const k = hintCount(g);
  const parts: string[] = [];
  if (r !== null) parts.push(`≈${Math.round(r)} m`);
  if (k !== null) parts.push(`${k} phone${k === 1 ? '' : 's'}`);
  return parts.length > 0 ? parts.join(' · ') : 'from nearby phones';
}

/**
 * Closed GeoJSON ring (first point repeated last, counter-clockwise) of the circle of
 * `radiusM` metres around lon/lat, on a sphere (great-circle destinations), so the radius stays
 * right at high latitudes where a degree of longitude is short. MapLibre has no metre circles.
 * Longitudes are continuous (may pass ±180) so the ring never jumps across the map.
 */
export function circleRing(lon: number, lat: number, radiusM: number, steps = RING_STEPS): [number, number][] {
  const n = Math.max(8, Math.floor(steps));
  const rad = Math.PI / 180;
  const d = Math.max(0, radiusM) / EARTH_M; // angular distance
  const lat1 = lat * rad;
  const ring: [number, number][] = [];
  for (let i = 0; i < n; i++) {
    const brg = (2 * Math.PI * i) / n; // 0 = north, clockwise; reversed below for CCW
    const sinLat2 = Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brg);
    const lat2 = Math.asin(Math.max(-1, Math.min(1, sinLat2)));
    const dLon = Math.atan2(
      Math.sin(brg) * Math.sin(d) * Math.cos(lat1),
      Math.cos(d) - Math.sin(lat1) * sinLat2,
    );
    ring.push([lon + dLon / rad, lat2 / rad]);
  }
  ring.reverse(); // clockwise bearings -> counter-clockwise ring (GeoJSON exterior-ring convention)
  ring.push([ring[0]![0], ring[0]![1]]);
  return ring;
}

/** [west, south, east, north] covering an estimate's circle (or just its point without a radius). */
export function estimateBounds(g: Geo): [number, number, number, number] {
  const r = estimateRadiusM(g);
  if (r === null) return [g.lon, g.lat, g.lon, g.lat];
  let w = Infinity, s = Infinity, e = -Infinity, n = -Infinity;
  for (const [x, y] of circleRing(g.lon, g.lat, r)) {
    w = Math.min(w, x); e = Math.max(e, x);
    s = Math.min(s, y); n = Math.max(n, y);
  }
  return [w, s, e, n];
}

/** Bounds of everything plottable: GPS fixes plus mesh-estimate circles. Null when there is nothing. */
export function plottableBounds(tickets: readonly Ticket[]): [number, number, number, number] | null {
  let box = boundsOf(tickets);
  for (const t of estimatedTickets(tickets)) {
    const b = estimateBounds(t.geo);
    box = box ? [Math.min(box[0], b[0]), Math.min(box[1], b[1]), Math.max(box[2], b[2]), Math.max(box[3], b[3])] : b;
  }
  return box;
}

/**
 * GeoJSON for the estimate-circle layers: one polygon per mesh estimate that has a radius. The
 * centre markers are DOM markers (MapLibre circle layers cannot be dashed), built from
 * estimatedTickets().
 */
export function toEstimateAreas(tickets: readonly Ticket[], selected: number | null): GeoJSON.FeatureCollection {
  const features: GeoJSON.Feature[] = [];
  for (const t of estimatedTickets(tickets)) {
    const r = estimateRadiusM(t.geo);
    if (r === null) continue;
    features.push({
      type: 'Feature',
      id: t.ticket_no,
      geometry: { type: 'Polygon', coordinates: [circleRing(t.geo.lon, t.geo.lat, r)] },
      properties: {
        ticket_no: t.ticket_no,
        category: t.category,
        tier: tierOf(t),
        radius_m: r,
        dispatched: t.status !== 'OPEN' ? 1 : 0,
        selected: t.ticket_no === selected ? 1 : 0,
      },
    });
  }
  return { type: 'FeatureCollection', features };
}
