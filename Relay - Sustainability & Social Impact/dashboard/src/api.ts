import { parseLanguages, type Language } from './lib/languages';
import { loadOperatorKey } from './lib/operatorKey';
import type {
  Announcement, Broadcast, BroadcastArea, DeclarationDraft, DisasterKey, CapImportResult, DamageMap, Gateway, NewWarning, PlanRequest, ReadyPlan, ReadyShelter, ReadyWarning, RecoverReport, ShelterEntry, ShelterList, ShelterPublishResult, Sitrep, Stats, Team, Ticket,
} from './types';

export class ApiError extends Error {
  /** HTTP status, or 0 for network failures. */
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function errorMessage(res: Response): Promise<string> {
  const fallback = `${res.status} ${res.statusText}`.trim();
  try {
    const text = await res.text();
    if (!text) return fallback;
    try {
      const j = JSON.parse(text) as Record<string, unknown>;
      const m = j.detail ?? j.error ?? j.message;
      if (typeof m === 'string' && m) return `${fallback}: ${m}`;
    } catch {
      /* not JSON */
    }
    return `${fallback}: ${text.slice(0, 200)}`;
  } catch {
    return fallback;
  }
}

const post = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

export function createClient(base: string, operatorKey?: string | null) {
  const root = base.replace(/\/+$/, '');

  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      const withKey = operatorKey ? { ...init, headers: { ...init?.headers, 'X-Operator-Key': operatorKey } } : init;
      res = await fetch(`${root}${path}`, withKey);
    } catch (e) {
      throw new ApiError(0, `Backend unreachable (${e instanceof Error ? e.message : String(e)})`);
    }
    if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
    try {
      return (await res.json()) as T;
    } catch {
      throw new ApiError(res.status, 'Invalid JSON from backend');
    }
  }

  /** A file download (evidence pack): fetched with the operator key, so it cannot be a bare link. */
  async function requestBlob(path: string): Promise<Blob> {
    let res: Response;
    try {
      res = await fetch(`${root}${path}`, operatorKey ? { headers: { 'X-Operator-Key': operatorKey } } : undefined);
    } catch (e) {
      throw new ApiError(0, `Backend unreachable (${e instanceof Error ? e.message : String(e)})`);
    }
    if (!res.ok) throw new ApiError(res.status, await errorMessage(res));
    return res.blob();
  }

  return {
    async listTickets(signal?: AbortSignal): Promise<Ticket[]> {
      const body = await request<{ tickets?: Ticket[] }>('/v1/tickets', { signal });
      if (!body || !Array.isArray(body.tickets)) throw new ApiError(200, 'Malformed /v1/tickets response');
      return body.tickets;
    },
    getTicket(no: number, signal?: AbortSignal): Promise<Ticket> {
      return request<Ticket>(`/v1/tickets/${no}`, { signal });
    },
    /** Approve; `team_id` overrides the proposed team (the backend recomputes the ETA). */
    approve(no: number, body?: { team_id?: string }): Promise<Ticket> {
      return request<Ticket>(`/v1/tickets/${no}/approve`, body ? post(body) : { method: 'POST' });
    },
    onTheWay(no: number): Promise<Ticket> {
      return request<Ticket>(`/v1/tickets/${no}/on-the-way`, { method: 'POST' });
    },
    resolve(no: number): Promise<Ticket> {
      return request<Ticket>(`/v1/tickets/${no}/resolve`, { method: 'POST' });
    },
    /** Estimate a no-fix ticket's position from its landmark now (gazetteer, then Google Maps). */
    locate(no: number): Promise<Ticket> {
      return request<Ticket>(`/v1/tickets/${no}/locate`, { method: 'POST' });
    },
    /** Propose a team for one open ticket; other tickets' proposals are left alone. */
    proposeDispatch(ticketNo: number): Promise<{ proposals: unknown[] }> {
      return request(`/v1/dispatch/propose?ticket_no=${ticketNo}`, { method: 'POST' });
    },
    listTeams(signal?: AbortSignal): Promise<Team[]> {
      return request<Team[]>('/v1/teams', { signal });
    },
    async listGateways(signal?: AbortSignal): Promise<Gateway[]> {
      const body = await request<{ gateways?: Gateway[] }>('/v1/gateways', { signal });
      return Array.isArray(body?.gateways) ? body.gateways : [];
    },
    /** The district's newest sitrep; null when none has been generated yet. */
    async latestSitrep(district: string, signal?: AbortSignal): Promise<Sitrep | null> {
      try {
        return await request<Sitrep>(`/v1/sitreps/latest?district=${encodeURIComponent(district)}`, { signal });
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    runSitrep(district: string): Promise<Sitrep> {
      return request<Sitrep>(`/v1/sitreps/run?district=${encodeURIComponent(district)}`, { method: 'POST' });
    },
    /** The language registry (no auth); null if the body does not look right. */
    async listLanguages(signal?: AbortSignal): Promise<Language[] | null> {
      return parseLanguages(await request<unknown>('/v1/languages', { signal }));
    },
    createAnnouncement(body: { district: string; text: string; source_lang?: string }): Promise<Announcement> {
      return request<Announcement>('/v1/announcements', post(body));
    },
    async listAnnouncements(signal?: AbortSignal): Promise<Announcement[]> {
      const body = await request<{ announcements?: Announcement[] }>('/v1/announcements', { signal });
      return Array.isArray(body?.announcements) ? body.announcements : [];
    },
    /** `langs` omitted = every language whose status is ok; `edits` = operator-corrected texts. */
    approveAnnouncement(id: number, body: { langs?: string[]; edits?: Record<string, string> }): Promise<Announcement> {
      return request<Announcement>(`/v1/announcements/${id}/approve`, post(body));
    },
    withdrawAnnouncement(id: number): Promise<Announcement> {
      return request<Announcement>(`/v1/announcements/${id}/withdraw`, { method: 'POST' });
    },
    async listWarnings(district: string, signal?: AbortSignal): Promise<ReadyWarning[]> {
      const body = await request<{ warnings?: ReadyWarning[] }>(`/v1/ready/warnings?district=${encodeURIComponent(district)}`, { signal });
      return Array.isArray(body?.warnings) ? body.warnings : [];
    },
    issueWarning(body: NewWarning): Promise<ReadyWarning> {
      return request<ReadyWarning>('/v1/ready/warnings', post(body));
    },
    cancelWarning(id: number): Promise<ReadyWarning> {
      return request<ReadyWarning>(`/v1/ready/warnings/${id}/cancel`, { method: 'POST' });
    },
    importCap(district: string, capXml: string): Promise<CapImportResult> {
      return request<CapImportResult>('/v1/ready/warnings/import-cap', post({ district, cap_xml: capXml }));
    },
    async listShelters(district: string, signal?: AbortSignal): Promise<ReadyShelter[]> {
      const body = await request<{ shelters?: ReadyShelter[] }>(`/v1/ready/shelters?district=${encodeURIComponent(district)}`, { signal });
      return Array.isArray(body?.shelters) ? body.shelters : [];
    },
    async listShelterLists(district: string, signal?: AbortSignal): Promise<ShelterList[]> {
      const body = await request<{ lists?: ShelterList[] }>(`/v1/ready/shelter-lists?district=${encodeURIComponent(district)}`, { signal });
      return Array.isArray(body?.lists) ? body.lists : [];
    },
    /** Gemini + Google Search drafts the district's official shelter list (slow: up to a minute). */
    discoverShelters(district: string): Promise<ShelterList> {
      return request<ShelterList>('/v1/ready/shelter-lists/discover', post({ district }));
    },
    createShelterList(district: string, entries: Partial<ShelterEntry>[] = []): Promise<ShelterList> {
      return request<ShelterList>('/v1/ready/shelter-lists', post({ district, entries }));
    },
    saveShelterList(id: number, entries: ShelterEntry[]): Promise<ShelterList> {
      return request<ShelterList>(`/v1/ready/shelter-lists/${id}`, { ...post({ entries }), method: 'PATCH' });
    },
    publishShelterList(id: number, note = ''): Promise<ShelterPublishResult> {
      return request<ShelterPublishResult>(`/v1/ready/shelter-lists/${id}/publish`, post({ note }));
    },
    previewPlan(body: PlanRequest): Promise<ReadyPlan> {
      return request<ReadyPlan>('/v1/ready/plan/preview', post(body));
    },
    /** Damage map by village for a district id or "all". */
    getDamage(district: string, signal?: AbortSignal): Promise<DamageMap> {
      return request<DamageMap>(`/v1/recover/damage?district=${encodeURIComponent(district)}`, { signal });
    },
    /** Relief and insurer report, generated now and not stored. */
    recoverReport(district: string): Promise<RecoverReport> {
      return request<RecoverReport>(`/v1/recover/report?district=${encodeURIComponent(district)}`, { method: 'POST' });
    },
    ticketEvidence(no: number): Promise<Blob> {
      return requestBlob(`/v1/recover/evidence/${no}?download=1`);
    },
    areaEvidence(areaId: string, district: string): Promise<Blob> {
      return requestBlob(`/v1/recover/evidence?area=${encodeURIComponent(areaId)}&district=${encodeURIComponent(district)}&download=1`);
    },
    getStats(signal?: AbortSignal): Promise<Stats> {
      return request<Stats>('/v1/stats', { signal });
    },
    /** Live control-room broadcasts; [] from a backend that predates them. */
    async listBroadcasts(signal?: AbortSignal): Promise<Broadcast[]> {
      try {
        const body = await request<{ broadcasts?: Broadcast[] }>('/v1/broadcasts', { signal });
        return Array.isArray(body?.broadcasts) ? body.broadcasts : [];
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return [];
        throw e;
      }
    },
    /** The operator's words as a draft declaration (Gemini on the backend). Sends nothing. */
    interpretDeclaration(text: string, district?: { name: string; lat: number; lon: number; radius_km: number }): Promise<DeclarationDraft> {
      return request<DeclarationDraft>('/v1/broadcasts/interpret', post({ text, district }));
    },
    /** Puts a declaration on every gateway's next sync, and from there on every phone's mesh. */
    declare(body: { disaster: DisasterKey; area?: BroadcastArea | null; label?: string }): Promise<Broadcast> {
      const { area, ...rest } = body;
      return request<Broadcast>('/v1/broadcasts', post(area ? { ...rest, kind: 'disaster', ...area } : { ...rest, kind: 'disaster' }));
    },
    /** Ends every declaration of `disaster` issued so far, wherever it applied. */
    clearDisaster(disaster: DisasterKey, label?: string): Promise<Broadcast> {
      return request<Broadcast>('/v1/broadcasts', post({ kind: 'cleared', disaster, label }));
    },
  };
}

export const API_BASE: string = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
export const api = createClient(API_BASE, loadOperatorKey());
