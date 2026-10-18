import type { Team, Ticket } from '../types';
import { isMappable } from './location';

export interface District {
  id: string;
  name: string;
  /** [lon, lat] the map opens on when the district is picked. */
  center: [number, number];
  zoom: number;
}

/** The backend has no district field: a ticket or team belongs to the nearest centre below. */
export const DISTRICTS: readonly District[] = [
  { id: 'puri', name: 'Puri', center: [85.83, 19.81], zoom: 11 },
  { id: 'delhi', name: 'Delhi', center: [77.209, 28.614], zoom: 10.5 },
];

export const ALL_DISTRICTS = 'all';
export const DEFAULT_DISTRICT = 'puri';

export function districtById(id: string): District | null {
  return DISTRICTS.find((d) => d.id === id) ?? null;
}

/** Display name for a selection id ("all" or unknown ids read as every district). */
export function districtName(id: string): string {
  return districtById(id)?.name ?? 'All districts';
}

function nearest(lat: number, lon: number): District {
  // Equirectangular is plenty to rank centres that are hundreds of km apart.
  const score = (d: District) => {
    const dx = (lon - d.center[0]) * Math.cos((lat * Math.PI) / 180);
    const dy = lat - d.center[1];
    return dx * dx + dy * dy;
  };
  return DISTRICTS.reduce((best, d) => (score(d) < score(best) ? d : best));
}

const ok = (n: unknown): n is number => typeof n === 'number' && Number.isFinite(n);

/**
 * District id of a ticket from its GPS fix, else its estimated position; null when it has
 * neither. Unlocated tickets are deliberately not tied to a district (see `inDistrict`).
 */
export function ticketDistrict(t: Ticket): string | null {
  if (isMappable(t)) return nearest(t.lat, t.lon).id;
  const g = t.geo;
  if (g && ok(g.lat) && ok(g.lon) && !(g.lat === 0 && g.lon === 0)) return nearest(g.lat, g.lon).id;
  return null;
}

export function teamDistrict(t: Team): string {
  return nearest(t.base_lat, t.base_lon).id;
}

/**
 * Whether a ticket shows under the selection. A ticket with no location at all is shown in every
 * district: hiding a request because we cannot place it would be the worse failure.
 */
export function ticketInDistrict(t: Ticket, selection: string): boolean {
  if (selection === ALL_DISTRICTS) return true;
  const d = ticketDistrict(t);
  return d === null || d === selection;
}

export function teamInDistrict(t: Team, selection: string): boolean {
  return selection === ALL_DISTRICTS || teamDistrict(t) === selection;
}
