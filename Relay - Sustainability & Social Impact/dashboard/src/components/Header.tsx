import type { RefObject } from 'react';
import type { Gateway, Stats, Ticket } from '../types';
import type { Theme } from '../hooks';
import { tierOf } from '../lib/sort';
import { median } from '../lib/triage';

export type View = 'board' | 'teams' | 'sitrep' | 'gateways';

const TABS: { id: View; label: string }[] = [
  { id: 'board', label: 'Live board' },
  { id: 'teams', label: 'Teams' },
  { id: 'sitrep', label: 'Situation report' },
  { id: 'gateways', label: 'Gateways' },
];

interface Props {
  view: View;
  onView: (v: View) => void;
  tickets: Ticket[];
  stats: Stats | null;
  gateways: Gateway[] | null;
  connected: boolean;
  nowSec: number;
  search: string;
  onSearch: (q: string) => void;
  searchRef: RefObject<HTMLInputElement | null>;
  theme: Theme;
  onToggleTheme: () => void;
}

/** Most recent gateway sync (unix seconds), null when none has synced. */
export function lastUploadSec(gateways: Gateway[] | null): number | null {
  if (!gateways || gateways.length === 0) return null;
  return Math.max(...gateways.map((g) => g.last_seen));
}

function ago(nowSec: number, then: number | null): string {
  if (then == null) return '–';
  const d = Math.max(0, Math.floor(nowSec - then));
  if (d < 60) return `${d} s ago`;
  if (d < 3600) return `${Math.floor(d / 60)} min ago`;
  return `${Math.floor(d / 3600)} h ago`;
}

export function Header(p: Props) {
  const { tickets, stats, gateways, nowSec } = p;
  const open = stats?.open ?? tickets.filter((t) => t.status === 'OPEN').length;
  const lifeAtRisk = stats?.p1_open ?? tickets.filter((t) => t.status === 'OPEN' && tierOf(t) === 'P1').length;
  const gwOnline = gateways ? gateways.filter((g) => g.online).length : stats?.gateways_online;
  const gwTotal = gateways?.length;
  const waits = tickets.filter((t) => t.status === 'OPEN').map((t) => nowSec - t.first_reported_at);
  const medianWait = median(waits);

  return (
    <header className="topbar">
      <div className="brand">
        <span className="logo" aria-hidden>
          <svg viewBox="0 0 24 24" width="14" height="14"><path d="M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3Zm-6 9a6 6 0 0 0 12 0h-2a4 4 0 0 1-8 0H6Zm5 7v2h2v-2h-2Z" fill="currentColor" /></svg>
        </span>
        <b>Relay</b>
        <span className="where">Control room · Puri district</span>
      </div>
      <nav className="tabs" aria-label="Views">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            className={p.view === t.id ? 'tab active' : 'tab'}
            aria-current={p.view === t.id ? 'page' : undefined}
            onClick={() => p.onView(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>
      <dl className="kpis">
        <div><dt>Open</dt><dd>{open}</dd></div>
        <div className={lifeAtRisk ? 'risk' : ''}><dt>Life at risk</dt><dd>{lifeAtRisk}</dd></div>
        <div>
          <dt>Gateways online</dt>
          <dd className="mono">{gwOnline ?? '–'}{gwTotal != null ? ` / ${gwTotal}` : ''}</dd>
        </div>
        <div className="opt"><dt>Median wait</dt><dd className="mono">{medianWait == null ? '–' : `${Math.round(medianWait / 60)} min`}</dd></div>
        <div className="opt"><dt>Last upload</dt><dd className="mono">{ago(nowSec, lastUploadSec(gateways))}</dd></div>
      </dl>
      <div className="topbar-right">
        <label className="search">
          <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden><path d="M10 4a6 6 0 1 0 3.7 10.7l4.8 4.8 1.4-1.4-4.8-4.8A6 6 0 0 0 10 4Zm0 2a4 4 0 1 1 0 8 4 4 0 0 1 0-8Z" fill="currentColor" /></svg>
          <input
            ref={p.searchRef}
            type="search"
            placeholder="Search id, landmark, need"
            value={p.search}
            onChange={(e) => p.onSearch(e.target.value)}
            aria-label="Search requests"
          />
        </label>
        <span className={`conn ${p.connected ? 'live' : 'down'}`} role="status" title={p.connected ? 'Live: updating every 2 s' : 'Backend unreachable, retrying'}>
          <i aria-hidden /> {p.connected ? 'Live' : 'Offline, retrying'}
        </span>
        <button type="button" className="icon-btn" onClick={p.onToggleTheme} aria-label={`Switch to ${p.theme === 'dark' ? 'light' : 'dark'} theme`}>
          {p.theme === 'dark' ? '☀' : '☾'}
        </button>
      </div>
    </header>
  );
}
