import { loadOperatorKey } from './lib/operatorKey';
import type { Gateway, Sitrep, Stats, Team, Ticket } from './types';

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
    proposeDispatch(): Promise<{ proposals: unknown[] }> {
      return request('/v1/dispatch/propose', { method: 'POST' });
    },
    listTeams(signal?: AbortSignal): Promise<Team[]> {
      return request<Team[]>('/v1/teams', { signal });
    },
    async listGateways(signal?: AbortSignal): Promise<Gateway[]> {
      const body = await request<{ gateways?: Gateway[] }>('/v1/gateways', { signal });
      return Array.isArray(body?.gateways) ? body.gateways : [];
    },
    /** null when no sitrep has been generated yet. */
    async latestSitrep(signal?: AbortSignal): Promise<Sitrep | null> {
      try {
        return await request<Sitrep>('/v1/sitreps/latest', { signal });
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    runSitrep(): Promise<Sitrep> {
      return request<Sitrep>('/v1/sitreps/run', { method: 'POST' });
    },
    getStats(signal?: AbortSignal): Promise<Stats> {
      return request<Stats>('/v1/stats', { signal });
    },
  };
}

export const API_BASE: string = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
export const api = createClient(API_BASE, loadOperatorKey());
