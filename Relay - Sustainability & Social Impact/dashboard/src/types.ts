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
  /** Detail endpoint only. */
  packets?: PacketInfo[];
}

export interface Stats {
  open_by_category: Partial<Record<Category, number>>;
  gateways_online: number;
  tickets: number;
}
