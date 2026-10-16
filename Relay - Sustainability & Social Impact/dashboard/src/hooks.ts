import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api';
import { detectNewTickets } from './lib/diff';
import type { Stats, Ticket } from './types';

export const POLL_MS = 2000;
export const PULSE_MS = 4000;

export interface LiveData {
  tickets: Ticket[];
  stats: Stats | null;
  connected: boolean;
  loaded: boolean;
  /** ticket_no -> epoch ms when it was first seen as new */
  fresh: ReadonlyMap<number, number>;
  refresh: () => void;
}

/** Polls /v1/tickets + /v1/stats every 2 s (non-overlapping). */
export function useLiveData(): LiveData {
  const [tickets, setTickets] = useState<Ticket[]>([]);
  const [stats, setStats] = useState<Stats | null>(null);
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
      const [t, s] = await Promise.allSettled([api.listTickets(ctl.signal), api.getStats(ctl.signal)]);
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
      } else {
        setConnected(false);
      }
      if (s.status === 'fulfilled') setStats(s.value);
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
  return { tickets, stats, connected, loaded, fresh, refresh };
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
