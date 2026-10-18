import { useState } from 'react';
import type { Gateway, Stats, Team, Ticket } from '../types';
import { categoryMeta } from '../lib/categories';
import { formatRelative } from '../lib/time';
import { tierOf } from '../lib/sort';
import {
  clockTime,
  formatMinutes,
  headline,
  languageName,
  median,
  openByArea,
  shortNode,
  teamRows,
  ticketRef,
  urgencyOf,
  type TeamRow,
} from '../lib/triage';
import { useSitrep } from '../hooks';

const KIND_LABEL: Record<string, string> = { boat: 'rescue boat', medical: 'medical', supply: 'supply' };

const ACTIVITY_CLASS: Record<TeamRow['activity'], string> = {
  Free: 'ok', Assigned: 'info', 'On the way': 'ok', Full: 'warn', 'Off duty': 'muted',
};

function TeamsTable({ rows, compact, onOpen }: { rows: TeamRow[]; compact?: boolean; onOpen?: (no: number) => void }) {
  if (rows.length === 0) return <p className="empty">No teams loaded yet.</p>;
  return (
    <table className="grid">
      <thead>
        <tr>
          <th>Team</th>
          <th className="num">Assigned</th>
          <th>Next ETA</th>
          <th>Status</th>
          {!compact && <th>Requests</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.team.id}>
            <td>
              {r.team.name}
              <span className="muted small block">{KIND_LABEL[r.team.kind] ?? r.team.kind} · {r.team.load} / {r.team.capacity} slots</span>
            </td>
            <td className="num">{r.assigned.length}</td>
            <td className="mono">{r.nextEtaMin == null ? '–' : formatMinutes(r.nextEtaMin)}</td>
            <td><span className={`status-text ${ACTIVITY_CLASS[r.activity]}`}>{r.activity}</span></td>
            {!compact && (
              <td>
                {r.assigned.length === 0 ? <span className="muted">–</span> : r.assigned.map((t) => (
                  <button key={t.ticket_no} type="button" className="link ref-link" onClick={() => onOpen?.(t.ticket_no)}>
                    {ticketRef(t.ticket_no)}
                  </button>
                ))}
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ---------------------------------------------------------------------------- Teams

export function TeamsView({ teams, tickets, nowSec, onOpen }: {
  teams: Team[]; tickets: Ticket[]; nowSec: number; onOpen: (no: number) => void;
}) {
  const rows = teamRows(teams, tickets, nowSec);
  const waiting = tickets.filter((t) => t.status === 'OPEN' && t.proposal);
  return (
    <div className="page">
      <div className="page-head">
        <h1>Teams</h1>
        <p className="muted">
          {teams.filter((t) => t.status === 'free').length} of {teams.length} teams have free slots ·{' '}
          {waiting.length} proposal{waiting.length === 1 ? '' : 's'} waiting for approval
        </p>
      </div>
      <div className="card"><TeamsTable rows={rows} onOpen={onOpen} /></div>
      {waiting.length > 0 && (
        <div className="card">
          <h2>Proposed by the dispatch agent</h2>
          <table className="grid">
            <thead><tr><th>Request</th><th>Need</th><th>Team</th><th>ETA</th></tr></thead>
            <tbody>
              {waiting.map((t) => (
                <tr key={t.ticket_no}>
                  <td><button type="button" className="link mono" onClick={() => onOpen(t.ticket_no)}>{ticketRef(t.ticket_no)}</button></td>
                  <td>{headline(t)} <span className={`pill u-${urgencyOf(t).key}`}>{urgencyOf(t).label}</span></td>
                  <td>{t.proposal!.team_name}</td>
                  <td className="mono">{formatMinutes(t.proposal!.eta_min)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------- Situation report

function Kpi({ label, value, note, tone }: { label: string; value: string | number; note?: string; tone?: string }) {
  return (
    <div className={`kpi ${tone ?? ''}`}>
      <span className="kpi-label">{label}</span>
      <span className="kpi-value mono">{value}</span>
      {note && <span className="kpi-note">{note}</span>}
    </div>
  );
}

export function SitrepView({ tickets, teams, stats, gateways, nowSec, onOpen, scope }: {
  scope: string; tickets: Ticket[]; teams: Team[]; stats: Stats | null; gateways: Gateway[] | null; nowSec: number; onOpen: (no: number) => void;
}) {
  const { sitrep, loaded, busy, error, regenerate } = useSitrep();
  const [lang, setLang] = useState<'en' | 'local'>('en');
  const [copied, setCopied] = useState(false);

  const open = tickets.filter((t) => t.status === 'OPEN');
  const life = open.filter((t) => tierOf(t) === 'P1');
  const lifeAssigned = life.filter((t) => t.proposal).length;
  const dayStart = new Date(nowSec * 1000).setHours(0, 0, 0, 0) / 1000;
  const today = tickets.filter((t) => t.first_reported_at >= dayStart);
  const delivered = today.filter((t) => t.status === 'RESOLVED').length;
  const dispatchDelays = tickets.filter((t) => t.dispatch).map((t) => t.dispatch!.approved_at - t.first_reported_at);
  const medDispatch = median(dispatchDelays);
  const gwOnline = gateways ? gateways.filter((g) => g.online).length : stats?.gateways_online ?? 0;
  const silent = (gateways ?? []).filter((g) => !g.online);
  const areas = openByArea(tickets);
  const rows = teamRows(teams, tickets, nowSec);
  const text = sitrep ? (lang === 'en' ? sitrep.text_en : sitrep.text_local) : '';
  const closed = tickets.length - open.length;

  const share = async () => {
    if (!sitrep) return;
    try {
      await navigator.clipboard.writeText(`Relay situation report, ${scope}, ${new Date(sitrep.generated_at * 1000).toLocaleString()}\n\n${sitrep.text_en}\n\n${sitrep.text_local}`);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard blocked: nothing to do */
    }
  };

  return (
    <div className="page">
      <div className="page-head">
        <h1>Situation report</h1>
        <p className="muted">
          {scope} ·{' '}
          {sitrep
            ? `${new Date(sitrep.generated_at * 1000).toLocaleString([], { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' })} · drafted by the sitrep agent`
            : 'no report generated yet'}{' '}
          · live figures from {open.length} open and {closed} closed requests
        </p>
        <div className="row-actions">
          <button type="button" className="btn ghost" disabled={busy} onClick={regenerate}>
            {busy ? 'Generating…' : sitrep ? 'Regenerate' : 'Generate'}
          </button>
          <button type="button" className="btn primary" disabled={!sitrep} onClick={share}>
            {copied ? 'Copied' : 'Copy for the Collector'}
          </button>
        </div>
        {error && <p className="err" role="alert">{error}</p>}
      </div>

      <div className="kpi-row">
        <Kpi label="Open" value={open.length} note={`${today.filter((t) => t.status === 'OPEN').length} reported today`} />
        <Kpi label="Life at risk" value={life.length} tone={life.length ? 'risk' : ''}
          note={life.length ? (lifeAssigned === life.length ? 'all have a team proposed' : `${life.length - lifeAssigned} without a team`) : 'none open'} />
        <Kpi label="Delivered today" value={delivered} note={`of ${today.length} received`} />
        <Kpi label="Median to dispatch" value={medDispatch == null ? '–' : formatMinutes(medDispatch / 60)} note="report to approval" />
        <Kpi label="Gateways online" value={gateways ? `${gwOnline} / ${gateways.length}` : gwOnline}
          note={silent.length ? `${silent.slice(0, 2).map((g) => `GW-${shortNode(g.node)}`).join(', ')} silent` : 'all reporting'} />
      </div>

      <div className="report-grid">
        <div className="card summary-card">
          <div className="card-head">
            <h2>Summary</h2>
            {sitrep && (
              <div className="seg" role="group" aria-label="Language">
                <button type="button" className={lang === 'en' ? 'on' : ''} onClick={() => setLang('en')}>English</button>
                <button type="button" className={lang === 'local' ? 'on' : ''} onClick={() => setLang('local')}>{languageName(sitrep.local_lang)}</button>
              </div>
            )}
          </div>
          {!loaded ? (
            <p className="muted">Loading…</p>
          ) : sitrep ? (
            <>
              {text.split(/\n{2,}/).map((para, i) => <p key={i} lang={lang === 'local' ? sitrep.local_lang : 'en'}>{para}</p>)}
              {sitrep.ticket_nos.length > 0 && (
                <p className="small">
                  Requests named:{' '}
                  {sitrep.ticket_nos.map((n) => (
                    <button key={n} type="button" className="link ref-link" onClick={() => onOpen(n)}>{ticketRef(n)}</button>
                  ))}
                </p>
              )}
              <p className="muted small">
                Covers {clockTime(sitrep.period_start)} to {clockTime(sitrep.period_end)}. Every figure in the text is
                checked against the store; the report names places, teams and gateways only, never a requester or a phone owner.
              </p>
            </>
          ) : (
            <p className="muted">No report yet. Generate one to get a summary in English and Odia from the current figures.</p>
          )}
        </div>

        <div className="stack">
          <div className="card">
            <h2>Open requests by area</h2>
            {areas.length === 0 ? <p className="empty">No open requests.</p> : (
              <table className="grid">
                <thead><tr><th>Area</th><th className="num">Open</th><th className="num">Life at risk</th><th>Top need</th><th>Teams</th></tr></thead>
                <tbody>
                  {areas.map((a) => (
                    <tr key={a.area}>
                      <td>{a.area}</td>
                      <td className="num mono">{a.open}</td>
                      <td className={`num mono ${a.lifeAtRisk ? 'risk-text' : ''}`}>{a.lifeAtRisk}</td>
                      <td>{categoryMeta(a.topNeed).label}</td>
                      <td>{a.teams.length ? a.teams.join(', ') : <span className="muted">none</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          <div className="card">
            <h2>Teams</h2>
            <TeamsTable rows={rows} compact />
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------- Gateways

export function GatewaysView({ gateways, tickets, nowSec }: { gateways: Gateway[] | null; tickets: Ticket[]; nowSec: number }) {
  if (gateways === null) {
    return (
      <div className="page">
        <div className="page-head"><h1>Gateways</h1></div>
        <p className="empty">This backend does not report gateways yet (needs <code>GET /v1/gateways</code>).</p>
      </div>
    );
  }
  const stat = (node: string) => {
    const relayed = tickets.filter((t) => t.gateways.includes(node));
    return { relayed, hops: median(relayed.map((t) => t.max_hops)) };
  };
  const online = gateways.filter((g) => g.online).length;
  return (
    <div className="page">
      <div className="page-head">
        <h1>Gateways</h1>
        <p className="muted">
          {online} of {gateways.length} gateway phones uploaded in the last minute. A gateway is any phone in the mesh that
          found signal; requests hop across phones until one does.
        </p>
      </div>
      <div className="card">
        {gateways.length === 0 ? <p className="empty">No gateway has synced yet.</p> : (
          <table className="grid">
            <thead><tr><th>Gateway</th><th>Status</th><th>Last upload</th><th className="num">Requests relayed</th><th className="num">Median hops</th><th>Latest</th></tr></thead>
            <tbody>
              {gateways.map((g) => {
                const s = stat(g.node);
                const latest = [...s.relayed].sort((a, b) => b.first_reported_at - a.first_reported_at)[0];
                return (
                  <tr key={g.node}>
                    <td className="mono">GW-{shortNode(g.node)} <span className="muted small">{g.node}</span></td>
                    <td><span className={`status-text ${g.online ? 'ok' : 'warn'}`}>{g.online ? 'Online' : 'Silent'}</span></td>
                    <td>{formatRelative(nowSec, g.last_seen)}</td>
                    <td className="num mono">{s.relayed.length}</td>
                    <td className="num mono">{s.hops ?? '–'}</td>
                    <td className="muted">{latest ? `${ticketRef(latest.ticket_no)} · ${headline(latest)}` : '–'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
