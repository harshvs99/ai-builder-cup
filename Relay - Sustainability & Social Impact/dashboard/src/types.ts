export const CATEGORIES = [
  'MEDICAL',
  'RESCUE',
  'WATER',
  'FOOD',
  'SHELTER',
  'MISSING_PERSON',
  'OTHER',
] as const;
export type Category = (typeof CATEGORIES)[number];

export type Tier = 'P1' | 'P2' | 'P3' | 'P4';
export type Status = 'OPEN' | 'DISPATCHED' | 'ON_THE_WAY' | 'RESOLVED';

export interface PacketInfo {
  msg_id: string;
  origin_node: string;
  gateway_node: string;
  hops: number;
  /** 0 = no GPS fix, 1 <10 m ... 5 >=1 km (SPEC §5.1). */
  loc_acc?: number;
  received_at: number;
  /** When the phone recorded the need (clock-sanitised); absent on older backends. */
  reported_at?: number;
  summary?: string;
}

export interface Ticket {
  ticket_no: number;
  category: Category;
  item?: string | null;
  landmark?: string | null;
  summary?: string | null;
  /** null when the ticket has no GPS fix (never plot it). */
  lat: number | null;
  lon: number | null;
  /** False when no linked packet had a GPS fix. Absent on backends that predate the field. */
  has_location?: boolean;
  /** Best accuracy bucket among the ticket's packets; 0 = no fix. */
  loc_acc?: number;
  /** Human label for loc_acc ("<10 m", ... "≥1 km"), null when there is no fix. */
  loc_acc_m?: string | null;
  urgency: number;
  people: number;
  vuln_flags: string[];
  lang: string;
  report_count: number;
  priority_score: number;
  /** Added by the backend on top of SPEC §7; derived client-side when absent. */
  priority_tier?: Tier;
  priority_reason?: string | null;
  status: Status;
  dispatch_draft?: { en?: string; local?: string } | null;
  first_reported_at: number; // unix seconds
  max_hops: number;
  gateways: string[];
  msg_ids: string[];
  /** Pending team proposal from the dispatch agent; absent until proposed. */
  proposal?: Proposal;
  /** Approved dispatch; absent until approved. */
  dispatch?: Dispatch;
  /** Detail endpoint only. */
  packets?: PacketInfo[];
}

export interface Stats {
  open_by_category: Partial<Record<Category, number>>;
  gateways_online: number;
  tickets: number;
  open?: number;
  p1_open?: number;
  oldest_open_at?: number | null;
  teams_free?: number;
  teams_total?: number;
}

export interface Proposal {
  team_id: string;
  team_name: string;
  eta_min: number;
  order_en?: string;
  order_local?: string;
  local_lang?: string;
  reason?: string;
  proposed_at: number;
}

export interface Dispatch {
  team_id: string | null;
  team_name: string | null;
  eta_min: number | null;
  approved_at: number;
  order_en?: string;
}

export interface Team {
  id: string;
  name: string;
  kind: 'boat' | 'medical' | 'supply' | string;
  capacity: number;
  load: number;
  base_lat: number;
  base_lon: number;
  status: 'free' | 'busy' | 'off' | string;
}

export interface Gateway {
  node: string;
  /** unix seconds */
  last_seen: number;
  online: boolean;
}

export interface SitrepCounts {
  open: number;
  open_by_category: Partial<Record<Category, number>>;
  p1_open: number;
  dispatched: number;
  resolved: number;
  gateways_online: number;
  oldest_open_ticket_no: number | null;
  oldest_open_age_s: number | null;
}

export interface Sitrep {
  period_start: number;
  period_end: number;
  generated_at: number;
  counts: SitrepCounts;
  text_en: string;
  text_local: string;
  local_lang: string;
  ticket_nos: number[];
}
