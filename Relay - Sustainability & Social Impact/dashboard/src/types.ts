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

/**
 * The fixed photo tag table (SPEC §5.1), in bit order. A requester's phone classifies a photo on the device and only
 * these tags travel; no image is ever sent. Never renumbered (bit 15 is reserved).
 */
export const PHOTO_TAGS = [
  'floodwater',
  'house_damaged',
  'house_collapsed',
  'roof_damaged',
  'person_injured',
  'people_stranded',
  'road_blocked',
  'tree_fallen',
  'power_line_down',
  'boat_damaged',
  'crops_damaged',
  'livestock_lost',
  'vehicle_damaged',
  'fire',
  'landslide',
] as const;
export type PhotoTag = (typeof PHOTO_TAGS)[number];

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
  /** Tags this report's photo was classified into on the phone; absent when it had none. */
  photo_tags?: PhotoTag[];
}

/**
 * Estimated position for a ticket with no GPS fix. Never a fix: has_location stays false.
 * - "gazetteer" / "gemini_maps": looked up from the landmark text (`query`). A "gemini_maps" estimate
 *   also names the Google Maps place (`name`), its `place_id` and a link to it (`maps_uri`), and the
 *   `district` id it was validated against. Showing one requires crediting Google Maps.
 * - "mesh": from nearby relay phones' location hints; `radius_m` (a multiple of 10) bounds the
 *   uncertainty and `hint_count` is how many phones contributed. No `query`.
 */
export type GeoSource = 'gazetteer' | 'gemini_maps' | 'mesh';
export interface Geo {
  lat: number;
  lon: number;
  source: GeoSource;
  query?: string;
  name?: string;
  place_id?: string;
  maps_uri?: string;
  district?: string;
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
  /**
   * What the requesters' phones saw in their photos: tag -> number of reports that carried it. An observation from an
   * on-device classifier, not a verified assessment. Absent when no report had a photo.
   */
  photo_tags?: Partial<Record<PhotoTag, number>>;
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
  /** District id; absent on reports written before sitreps were per district. */
  district?: string;
  counts: SitrepCounts;
  text_en: string;
  text_local: string;
  local_lang: string;
  ticket_nos: number[];
}

// ---------------------------------------------------------------------------- Recover (backend app/recover.py)

export type DamageLevel = 'severe' | 'moderate' | 'light' | 'none';

/** Figures shared by one area and the totals of `GET /v1/recover/damage`. */
export interface RecoverMetrics {
  tickets: number;
  reports: number;
  /** reports minus tickets: duplicates merged before dispatch. */
  duplicates_merged: number;
  /** Distinct reporting phones; one phone stands for one household. */
  households: number;
  people: number;
  /** Distinct phones on resolved tickets. */
  households_reached: number;
  open: number;
  dispatched: number;
  resolved: number;
  needs_confirmed_closed: number;
  by_category: Partial<Record<Category, number>>;
  photo_tags: Partial<Record<PhotoTag, number>>;
  houses_damaged: number;
  houses_collapsed: number;
  /** null until a dispatch with a recorded approval time exists. */
  median_minutes_to_dispatch: number | null;
  /** 0 to 1: tickets with a GPS fix or an approximate position. */
  share_with_location: number;
  share_with_gps_fix: number;
}

export interface DamageArea extends RecoverMetrics {
  area_id: string;
  name: string;
  /** null for the Unlocated bucket. */
  district: string | null;
  /** Centroid of the located tickets; null for Unlocated. */
  lat: number | null;
  lon: number | null;
  damage_level: DamageLevel;
  damage_reason: string;
}

export interface DamageMap {
  generated_at: number;
  district: string;
  areas: DamageArea[];
  totals: RecoverMetrics;
}

export interface ReportCounts {
  totals: Record<string, number | null>;
  areas: Array<Record<string, string | number | null>>;
}

/** `POST /v1/recover/report`: generated on request, never stored. */
export interface RecoverReport {
  generated_at: number;
  district: string;
  counts: ReportCounts;
  text_en: string;
  text_local: string;
  local_lang: string;
  model: 'gemini' | 'template';
  evidence_digests: Array<{ ticket_no: number; digest: string }>;
}

export type AnnouncementLang = 'en' | 'hi' | 'or' | 'tl' | 'war';
export type AnnouncementLangStatus = 'ok' | 'needs_review' | 'missing';

export interface AnnouncementTranslation {
  text: string;
  back_translation: string;
  faithful: boolean;
  issues: string;
  checks_passed: boolean;
  status: AnnouncementLangStatus;
  edited?: boolean;
}

export interface Announcement {
  id: number;
  district: string;
  source_text: string;
  source_lang: AnnouncementLang;
  created_at: number;
  translations: Record<AnnouncementLang, AnnouncementTranslation>;
  status: 'draft' | 'approved' | 'withdrawn';
  approved_at: number | null;
  approved_langs: AnnouncementLang[];
}

export type WarningSource = 'IMD' | 'PAGASA' | 'NDMA' | 'manual';
export type WarningSeverity = 'watch' | 'warning' | 'alert';

/** A Ready-mode warning for one district (issued by an operator or imported from a CAP alert). */
export interface ReadyWarning {
  id: number;
  district: string;
  source: WarningSource;
  severity: WarningSeverity;
  headline: string;
  /** unix seconds */
  issued_at: number;
  expected_landfall_at: number | null;
  expires_at: number | null;
  cancelled_at: number | null;
  /** Not cancelled and not expired, as the backend sees it. */
  active: boolean;
  cap_identifier?: string | null;
  area?: string;
}

export interface NewWarning {
  district: string;
  source: WarningSource;
  severity: WarningSeverity;
  headline: string;
  expected_landfall_at?: number;
  expires_at?: number;
}

export interface CapImportResult {
  imported: ReadyWarning[];
  skipped: { identifier: string | null; reason: string }[];
}

export type ShelterKind = 'cyclone_shelter' | 'school' | 'relief_camp' | 'hospital';

export interface ReadyShelter {
  id: string;
  name: string;
  name_en: string;
  kind: ShelterKind;
  lat: number;
  lon: number;
  capacity: number;
  facilities: string[];
  block: string;
  /** Placeholder entry, not verified: replace with the official list before a pilot. */
  demo: boolean;
}

export interface Household {
  people: number;
  elderly: boolean;
  children: boolean;
  infants: boolean;
  pregnant: boolean;
  disability: boolean;
  medical_needs: string;
  livestock: boolean;
}

export interface PlanRequest {
  lang: string;
  district?: string;
  lat?: number;
  lon?: number;
  household: Household;
}

export interface ReadyPlan {
  plan_id: string;
  lang: string;
  /** Language the steps are actually in (English when the requested one has no template). */
  steps_lang: string;
  district: string;
  steps: string[];
  source: 'gemini' | 'template';
  warning_id: number | null;
  nearest_shelters: { id: string; name: string; distance_km: number }[];
  audio_url: string | null;
}

export type ShelterEntryKind = ShelterKind | 'other';

/** One row of a shelter list draft (backend `app/shelters.py`). The model's text is data; `issues` come from the server's checks. */
export interface ShelterEntry {
  id: string;
  name: string;
  name_local: string | null;
  kind: ShelterEntryKind;
  address: string;
  block: string | null;
  capacity: number | null;
  lat: number | null;
  lon: number | null;
  coords_from: 'source' | 'estimate' | 'officer' | 'none';
  source_url: string | null;
  verified: boolean;
  issues: string[];
}

export interface ShelterSource {
  uri: string;
  title: string;
}

export interface ShelterList {
  id: number;
  district: string;
  status: 'draft' | 'published' | 'archived';
  created_at: number;
  entries: ShelterEntry[];
  sources: ShelterSource[];
  /** Google's rendered Search Suggestions; must be shown wherever grounded results are shown. */
  search_suggestions_html: string;
  model: string | null;
  notes: string[];
  origin: 'gemini' | 'manual';
  published_at: number | null;
  published_by_note: string | null;
}

export interface ShelterPublishResult {
  list: ShelterList;
  published: number;
  skipped: { id: string; name: string; reason: string }[];
}
