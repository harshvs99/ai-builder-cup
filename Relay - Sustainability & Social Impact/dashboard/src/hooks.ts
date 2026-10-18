import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import { detectNewTickets } from './lib/diff';
import { ALL_DISTRICTS, DEFAULT_DISTRICT, districtById } from './lib/districts';
import type { DamageMap, Gateway, RecoverReport, Sitrep, Stats, Team, Ticket } from './types';

export const POLL_MS = 2000;
export const PULSE_MS = 4000;

export interface LiveData {
  tickets: Ticket[];
  stats: Stats | null;
  teams: Team[];
  /** null when the backend predates GET /v1/gateways. */
  gateways: Gateway[] | null;
  /** Epoch ms of the last successful ticket poll. */
  updatedAt: number | null;
  connected: boolean;
  loaded: boolean;
  /** ticket_no -> epoch ms when it was first seen as new */
  fresh: ReadonlyMap<number, number>;
  refresh: () => void;
}

/** Polls tickets, stats, teams and gateways every 2 s (non-overlapping). Only the ticket list
 *  drives the connection state; the others keep their last value when a call fails. */
export function useLiveData(): LiveData {
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [stats, setStats] = useState<Stats | null>(null);
  const [teams, setTeams] = useState<Team[]>([]);
  const [gateways, setGateways] = useState<Gateway[] | null>(null);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [connected, setConnected] = useState(true);
  const [loaded, setLoaded] = useState(false);
  const [fresh, setFresh] = useState<ReadonlyMap<number, number>>(new Map());
  const seen = useRef<Set<number> | null>(null);
  const tick = useRef<() => void>(() => {});

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const ctl = new AbortController();

    const run = async () => {
      const [t, s, tm, gw] = await Promise.allSettled([
        api.listTickets(ctl.signal),
        api.getStats(ctl.signal),
        api.listTeams(ctl.signal),
        api.listGateways(ctl.signal),
      ]);
      if (stopped) return;
      if (t.status === 'fulfilled') {
        const list = t.value;
        const added = detectNewTickets(seen.current, list);
        seen.current = new Set(list.map((x) => x.ticket_no));
        const now = Date.now();
        setFresh((prev) => {
          const next = new Map<number, number>();
          for (const [k, v] of prev) if (now - v < PULSE_MS) next.set(k, v);
          for (const k of added) next.set(k, now);
          return next.size === prev.size && added.length === 0 ? prev : next;
        });
        setTickets(list);
        setConnected(true);
        setLoaded(true);
        setUpdatedAt(now);
      } else {
        setConnected(false);
      }
      if (s.status === 'fulfilled') setStats(s.value);
      if (tm.status === 'fulfilled' && Array.isArray(tm.value)) setTeams(tm.value);
      if (gw.status === 'fulfilled') setGateways(gw.value);
      timer = setTimeout(run, POLL_MS);
    };
    tick.current = () => {
      clearTimeout(timer);
      void run();
    };
    void run();
    return () => {
      stopped = true;
      ctl.abort();
      clearTimeout(timer);
    };
  }, []);

  const refresh = useCallback(() => tick.current(), []);
  return { tickets, stats, teams, gateways, updatedAt, connected, loaded, fresh, refresh };
}

/** Current unix time in seconds, re-rendering every `ms`. */
export function useNowSec(ms = 1000): number {
  const [now, setNow] = useState(() => Date.now() / 1000);
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now() / 1000), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}

/** Ticket detail (with packets), refreshed every poll while selected. */
export function useTicketDetail(no: number | null) {
  const [detail, setDetail] = useState<Ticket | null>(null);
  useEffect(() => {
    setDetail(null);
    if (no === null) return;
    let stopped = false;
    const ctl = new AbortController();
    const load = () =>
      api
        .getTicket(no, ctl.signal)
        .then((t) => !stopped && setDetail(t))
        .catch(() => {
          /* list data still renders; connection state is driven by the list poll */
        });
    void load();
    const id = setInterval(load, POLL_MS);
    return () => {
      stopped = true;
      ctl.abort();
      clearInterval(id);
    };
  }, [no]);
  return [detail, setDetail] as const;
}

/** Latest situation report plus a regenerate action. */
export function useSitrep() {
  const [sitrep, setSitrep] = useState<Sitrep | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const ctl = new AbortController();
    api
      .latestSitrep(ctl.signal)
      .then((r) => setSitrep(r))
      .catch((e) => !ctl.signal.aborted && setError(e instanceof Error ? e.message : String(e)))
      .finally(() => !ctl.signal.aborted && setLoaded(true));
    return () => ctl.abort();
  }, []);
  const regenerate = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      setSitrep(await api.runSitrep());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, []);
  return { sitrep, loaded, busy, error, regenerate };
}

export const RECOVER_POLL_MS = 10_000;

/** Damage map for a district id or "all", refreshed every 10 s (the backend computes it on request). */
export function useDamage(district: string) {
  const [damage, setDamage] = useState<DamageMap | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setDamage(null);
    setError(null);
    let stopped = false;
    const ctl = new AbortController();
    const load = () =>
      api
        .getDamage(district, ctl.signal)
        .then((d) => {
          if (stopped) return;
          setDamage(d);
          setError(null);
        })
        .catch((e) => !stopped && setError(e instanceof Error ? e.message : String(e)));
    void load();
    const id = setInterval(load, RECOVER_POLL_MS);
    return () => {
      stopped = true;
      ctl.abort();
      clearInterval(id);
    };
  }, [district]);
  return { damage, error };
}

/** The relief and insurer report for a district: generated on demand, kept only in this page. */
export function useRecoverReport(district: string) {
  const [report, setReport] = useState<RecoverReport | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setReport(null); // a report is for the district it was generated for
    setError(null);
  }, [district]);
  const generate = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      setReport(await api.recoverReport(district));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [district]);
  return { report, busy, error, generate };
}

const SEEN_KEY = 'relay.seenTickets';

/** Tickets this operator has opened (per browser), so the queue can tell NEW from SEEN. */
export function useSeen() {
  const [seen, setSeen] = useState<ReadonlySet<number>>(() => {
    try {
      const raw = JSON.parse(localStorage.getItem(SEEN_KEY) ?? '[]') as unknown;
      return new Set(Array.isArray(raw) ? raw.filter((x): x is number => typeof x === 'number') : []);
    } catch {
      return new Set();
    }
  });
  const markSeen = useCallback((no: number) => {
    setSeen((prev) => {
      if (prev.has(no)) return prev;
      const next = new Set(prev).add(no);
      try {
        localStorage.setItem(SEEN_KEY, JSON.stringify([...next].slice(-2000)));
      } catch {
        /* private window: seen state lasts for the session only */
      }
      return next;
    });
  }, []);
  return [seen, markSeen] as const;
}

export type Theme = 'light' | 'dark';
const THEME_KEY = 'relay.theme';

/** Light / dark, defaulting to the system setting; an explicit choice is remembered. */
export function useTheme() {
  const system = () =>
    typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      const v = localStorage.getItem(THEME_KEY);
      if (v === 'light' || v === 'dark') return v;
    } catch {
      /* ignore */
    }
    return system();
  });
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);
  const toggle = useCallback(() => {
    setTheme((t) => {
      const next = t === 'dark' ? 'light' : 'dark';
      try {
        localStorage.setItem(THEME_KEY, next);
      } catch {
        /* ignore */
      }
      return next;
    });
  }, []);
  return [theme, toggle] as const;
}

const DISTRICT_KEY = 'relay.district';

/** Which district the board is scoped to ("all" or a district id); the choice is remembered. */
export function useDistrict() {
  const [district, setDistrict] = useState<string>(() => {
    try {
      const v = localStorage.getItem(DISTRICT_KEY);
      if (v === ALL_DISTRICTS || (v && districtById(v))) return v;
    } catch {
      /* ignore */
    }
    return DEFAULT_DISTRICT;
  });
  const choose = useCallback((id: string) => {
    setDistrict(id);
    try {
      localStorage.setItem(DISTRICT_KEY, id);
    } catch {
      /* ignore */
    }
  }, []);
  return [district, choose] as const;
}
