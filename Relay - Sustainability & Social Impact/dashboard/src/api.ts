import type { Stats, Ticket } from './types';

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

export function createClient(base: string) {
  const root = base.replace(/\/+$/, '');

  async function request<T>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      res = await fetch(`${root}${path}`, init);
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
    approve(no: number): Promise<Ticket> {
      return request<Ticket>(`/v1/tickets/${no}/approve`, { method: 'POST' });
    },
    getStats(signal?: AbortSignal): Promise<Stats> {
      return request<Stats>('/v1/stats', { signal });
    },
  };
}

export const API_BASE: string = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
export const api = createClient(API_BASE);
