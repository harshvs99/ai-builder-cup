import type { Category, Team, Ticket, Tier } from '../types';
import { categoryMeta } from './categories';
import { isMappable } from './location';
import { tierOf } from './sort';

/** Plain-language urgency for each tier, as the control room says it. */
export const URGENCY: Record<Tier, { label: string; key: UrgencyKey }> = {
  P1: { label: 'Life at risk', key: 'life' },
  P2: { label: 'Urgent', key: 'urgent' },
  P3: { label: 'Soon', key: 'soon' },
  P4: { label: 'Can wait', key: 'wait' },
};
export type UrgencyKey = 'life' | 'urgent' | 'soon' | 'wait';

export const urgencyOf = (t: Ticket) => URGENCY[tierOf(t)] ?? URGENCY.P4;

/** Short reference shown to operators, e.g. "R-104". */
export const ticketRef = (no: number) => `R-${no}`;

export type RowState = 'NEW' | 'SEEN' | 'PROPOSED' | 'DISPATCHED' | 'ON THE WAY' | 'DELIVERED';

export function rowState(t: Ticket, seen: ReadonlySet<number>): RowState {
  switch (t.status) {
    case 'DISPATCHED':
      return 'DISPATCHED';
    case 'ON_THE_WAY':
      return 'ON THE WAY';
    case 'RESOLVED':
      return 'DELIVERED';
    default:
      if (t.proposal && seen.has(t.ticket_no)) return 'PROPOSED';
      return seen.has(t.ticket_no) ? 'SEEN' : 'NEW';
  }
}

/** "Drinking water · 6 people" */
export function headline(t: Ticket): string {
  // For rescue and missing-person requests the item ("boat", "rope") is what helps, not what is needed.
  const byCategory = t.category === 'RESCUE' || t.category === 'MISSING_PERSON' || !t.item?.trim();
  const what = byCategory ? categoryMeta(t.category).label : capitalise(t.item!.trim());
  const who = t.people > 0 ? `${t.people} ${t.people === 1 ? 'person' : 'people'}` : null;
  return who ? `${what} · ${who}` : what;
}

export const capitalise = (s: string) => (s ? s[0]!.toUpperCase() + s.slice(1) : s);

const LANGS: Record<string, string> = {
  en: 'English', hi: 'Hindi', or: 'Odia', bn: 'Bengali', te: 'Telugu', ta: 'Tamil',
  tl: 'Tagalog', ceb: 'Cebuano', war: 'Waray',
};
export const languageName = (code: string) => LANGS[code?.toLowerCase()] ?? code?.toUpperCase() ?? '';

// ---------------------------------------------------------------------------- queue filters

export type QueueFilter =
  | { kind: 'all' }
  | { kind: 'urgency'; key: UrgencyKey }
  | { kind: 'category'; category: Category }
  | { kind: 'noloc' };

export const QUEUE_FILTERS: { id: string; label: string; filter: QueueFilter }[] = [
  { id: 'all', label: 'All', filter: { kind: 'all' } },
  { id: 'life', label: 'Life at risk', filter: { kind: 'urgency', key: 'life' } },
  { id: 'urgent', label: 'Urgent', filter: { kind: 'urgency', key: 'urgent' } },
  { id: 'soon', label: 'Soon', filter: { kind: 'urgency', key: 'soon' } },
  { id: 'wait', label: 'Can wait', filter: { kind: 'urgency', key: 'wait' } },
  { id: 'water', label: 'Water', filter: { kind: 'category', category: 'WATER' } },
  { id: 'medical', label: 'Medicine', filter: { kind: 'category', category: 'MEDICAL' } },
  { id: 'rescue', label: 'Rescue', filter: { kind: 'category', category: 'RESCUE' } },
  { id: 'noloc', label: 'No location', filter: { kind: 'noloc' } },
];

export function matchesFilter(t: Ticket, f: QueueFilter): boolean {
  switch (f.kind) {
    case 'all':
      return true;
    case 'urgency':
      return urgencyOf(t).key === f.key;
    case 'category':
      return t.category === f.category;
    case 'noloc':
      return !isMappable(t);
  }
}

/** Case-insensitive match on ref ("R-104" or "104"), landmark, item, summary or category. */
export function matchesSearch(t: Ticket, q: string): boolean {
  const s = q.trim().toLowerCase();
  if (!s) return true;
  const ref = s.replace(/^r-?/, '');
  if (/^\d+$/.test(ref)) return String(t.ticket_no).startsWith(ref);
  return [t.landmark, t.item, t.summary, categoryMeta(t.category).label]
    .some((v) => v?.toLowerCase().includes(s));
}

export type SortKey = 'priority' | 'oldest' | 'newest';
export const SORT_LABEL: Record<SortKey, string> = { priority: 'priority', oldest: 'longest wait', newest: 'newest' };

/** Input is already priority-sorted; open requests stay above closed ones in every order. */
export function orderQueue(tickets: readonly Ticket[], key: SortKey): Ticket[] {
  const rank = (t: Ticket) => (t.status === 'OPEN' ? 0 : t.status === 'RESOLVED' ? 2 : 1);
  const out = [...tickets];
  if (key === 'oldest') out.sort((a, b) => a.first_reported_at - b.first_reported_at);
  if (key === 'newest') out.sort((a, b) => b.first_reported_at - a.first_reported_at);
  return out.sort((a, b) => rank(a) - rank(b)); // stable
}

// ---------------------------------------------------------------------------- reports

export interface AreaRow {
  area: string;
  open: number;
  lifeAtRisk: number;
  topNeed: Category;
  teams: string[];
}

/** Open requests grouped by landmark (the closest thing to an area the mesh carries). */
export function openByArea(tickets: readonly Ticket[], limit = 6): AreaRow[] {
  const groups = new Map<string, Ticket[]>();
  for (const t of tickets) {
    if (t.status !== 'OPEN') continue;
    const area = t.landmark?.trim() ? capitalise(t.landmark.trim()) : 'No landmark given';
    groups.set(area, [...(groups.get(area) ?? []), t]);
  }
  const rows = [...groups].map(([area, list]): AreaRow => {
    const byCat = new Map<Category, number>();
    for (const t of list) byCat.set(t.category, (byCat.get(t.category) ?? 0) + 1);
    const topNeed = [...byCat].sort((a, b) => b[1] - a[1])[0]![0];
    const teams = [...new Set(list.map((t) => t.proposal?.team_name).filter((x): x is string => !!x))];
    return { area, open: list.length, lifeAtRisk: list.filter((t) => tierOf(t) === 'P1').length, topNeed, teams };
  });
  return rows.sort((a, b) => b.lifeAtRisk - a.lifeAtRisk || b.open - a.open || a.area.localeCompare(b.area)).slice(0, limit);
}

export interface TeamRow {
  team: Team;
  assigned: Ticket[];
  /** Minutes until the soonest expected arrival among its active tickets, null when none. */
  nextEtaMin: number | null;
  activity: 'Free' | 'Assigned' | 'On the way' | 'Full' | 'Off duty';
}

export function teamRows(teams: readonly Team[], tickets: readonly Ticket[], nowSec: number): TeamRow[] {
  return teams.map((team) => {
    const assigned = tickets.filter(
      (t) => t.dispatch?.team_id === team.id && (t.status === 'DISPATCHED' || t.status === 'ON_THE_WAY'),
    );
    const etas = assigned
      .filter((t) => t.dispatch?.eta_min != null)
      .map((t) => Math.max(0, Math.round((t.dispatch!.approved_at + t.dispatch!.eta_min! * 60 - nowSec) / 60)));
    const activity: TeamRow['activity'] =
      team.status === 'off' ? 'Off duty'
        : assigned.some((t) => t.status === 'ON_THE_WAY') ? 'On the way'
          : team.status === 'busy' ? 'Full'
            : assigned.length ? 'Assigned' : 'Free';
    return { team, assigned, nextEtaMin: etas.length ? Math.min(...etas) : null, activity };
  });
}

export function median(xs: readonly number[]): number | null {
  if (xs.length === 0) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2;
}

/** "40 min", "1 h 10 min" */
export function formatMinutes(min: number): string {
  const m = Math.max(0, Math.round(min));
  if (m < 60) return `${m} min`;
  return m % 60 ? `${Math.floor(m / 60)} h ${m % 60} min` : `${m / 60} h`;
}

/** Wall-clock time, e.g. "2:05 PM". */
export const clockTime = (sec: number) =>
  new Date(sec * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });

export const shortNode = (id: string) => id.slice(0, 4).toUpperCase();
