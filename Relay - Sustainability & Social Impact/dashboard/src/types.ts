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

/**
 * Estimated position for a ticket with no GPS fix. Never a fix: has_location stays false.
 * - "gazetteer" / "gemini_maps": looked up from the landmark text (`query`).
 * - "mesh": from nearby relay phones' location hints; `radius_m` (a multiple of 10) bounds the
 *   uncertainty and `hint_count` is how many phones contributed. No `query`.
 */
export type GeoSource = 'gazetteer' | 'gemini_maps' | 'mesh';
export interface Geo {
  lat: number;
  lon: number;
  source: GeoSource;
  query?: string;
  radius_m?: number;
  hint_count?: number;
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
  /** Optional estimate for a ticket without a fix; absent when unset. */
  geo?: Geo | null;
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

export type DisasterKey = 'flood' | 'cyclone' | 'earthquake' | 'landslide';

/** A circle on the wire (SPEC 5.6): the declaration applies within radius_km of the centre. */
export interface BroadcastArea {
  lat: number;
  lon: number;
  radius_km: number;
}

/** A control-room broadcast as GET /v1/broadcasts lists it (live for 24 h from issued_at). */
export interface Broadcast {
  id: string;
  kind: 'disaster' | 'cleared';
  disaster: DisasterKey;
  /** unix seconds */
  issued_at: number;
  expires_at: number;
  /** absent = every phone, whatever its position */
  area?: BroadcastArea;
  /** The operator's description; stays in the control room. */
  label?: string;
}

/** POST /v1/broadcasts/interpret: the operator's words as a draft. Nothing is sent until it is confirmed. */
export interface DeclarationDraft {
  kind: 'disaster' | 'cleared';
  /** null when none of the four codes fits */
  disaster: DisasterKey | null;
  area: (BroadcastArea & { source: 'gazetteer' | 'model' | 'district' | 'rules'; name: string }) | null;
  label: string;
  reason: string;
  /** 'model' = Gemini read it, 'rules' = keyword fallback */
  source: 'model' | 'rules';
  phone_effect: string;
  notes: string[];
  wire_bytes: number;
}
