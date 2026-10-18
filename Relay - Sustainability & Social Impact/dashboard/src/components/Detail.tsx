import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import type { Team, Ticket } from '../types';
import { formatRelative } from '../lib/time';
import {
  accuracyLabel,
  canLocateByLandmark,
  hasMapsEstimate,
  hasMeshEstimate,
  isMappable,
  mapsEstimateText,
  mapsLink,
  meshEstimateLabel,
} from '../lib/location';
import {
  capitalise,
  clockTime,
  formatMinutes,
  headline,
  languageName,
  rowState,
  shortNode,
  ticketRef,
  urgencyOf,
} from '../lib/triage';

export type DetailAction = 'approve' | 'onTheWay' | 'resolve';

interface Props {
  ticket: Ticket;
  teams: Team[];
  seen: ReadonlySet<number>;
  nowSec: number;
  onClose: () => void;
  onCentre: () => void;
  /** Called with the updated ticket after any successful action. */
  onChanged: (t: Ticket) => void;
  /** Ref the parent uses to run an action from a keyboard shortcut. */
  actionRef?: { current: ((a: DetailAction) => void) | null };
}

export function Detail({ ticket: t, teams, seen, nowSec, onClose, onCentre, onChanged, actionRef }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [teamOverride, setTeamOverride] = useState<string | null>(null);
  const [picking, setPicking] = useState(false);
  const u = urgencyOf(t);
  const state = rowState(t, seen);

  useEffect(() => {
    setError(null);
    setTeamOverride(null);
    setPicking(false);
  }, [t.ticket_no]);

  const run = async (label: string, fn: () => Promise<Ticket>) => {
    setBusy(label);
    setError(null);
    try {
      onChanged(await fn());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(null);
    }
  };

  const chosenTeam = teamOverride ?? t.proposal?.team_id ?? null;
  const canApprove = t.status === 'OPEN';
  const canOnTheWay = t.status === 'DISPATCHED';
  const canResolve = t.status === 'DISPATCHED' || t.status === 'ON_THE_WAY';

  const approve = () =>
    run('approve', () =>
      api.approve(t.ticket_no, teamOverride && teamOverride !== t.proposal?.team_id ? { team_id: teamOverride } : undefined),
    );
  const onTheWay = () => run('onTheWay', () => api.onTheWay(t.ticket_no));
  const resolve = () => run('resolve', () => api.resolve(t.ticket_no));
  const locate = () => run('locate', () => api.locate(t.ticket_no));
  const propose = () =>
    run('propose', async () => {
      await api.proposeDispatch(t.ticket_no);
      return api.getTicket(t.ticket_no);
    });

  // Opening an open ticket that has no proposal asks the dispatch agent for one, once per ticket per page load, so the
  // nearest free team is already there. A manual pick still wins (teamOverride), and "Ask dispatch agent" re-asks.
  const autoProposed = useRef(new Set<number>());
  useEffect(() => {
    if (t.status !== 'OPEN' || t.proposal || autoProposed.current.has(t.ticket_no)) return;
    autoProposed.current.add(t.ticket_no);
    void propose();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [t.ticket_no, t.status, t.proposal]);

  if (actionRef) {
    actionRef.current = (a) => {
      if (busy) return;
      if (a === 'approve' && canApprove) void approve();
      if (a === 'onTheWay' && canOnTheWay) void onTheWay();
      if (a === 'resolve' && canResolve) void resolve();
    };
  }

  const pkts = t.packets ?? [];
  const firstPacket = pkts[0];
  const delays = pkts.filter((p) => p.reported_at != null).map((p) => p.received_at - p.reported_at!);
  const vulns = t.vuln_flags.map((f) => f.toLowerCase());
  const subtitle = [
    t.summary?.trim() ? capitalise(t.summary.trim()) : null,
    vulns.length ? vulns.join(', ') : null,
    t.lang ? `spoke ${languageName(t.lang)}` : null,
  ].filter(Boolean).join(' · ');

  const order = t.dispatch?.order_en ?? t.proposal?.order_en ?? t.dispatch_draft?.en ?? null;
  const orderLocal = t.proposal?.order_local ?? t.dispatch_draft?.local ?? null;
  const eligible = teams.filter((tm) => tm.status !== 'off');

  return (
    <section className="detail panel" aria-label={`Request ${ticketRef(t.ticket_no)}`}>
      <div className="detail-head">
        <span className="ref mono">{ticketRef(t.ticket_no)}</span>
        <span className={`pill u-${u.key}`}>{u.label}</span>
        <span className={`pill state s-${state.replace(/ /g, '-').toLowerCase()}`}>{capitalise(state.toLowerCase())}</span>
        <span className="received muted">
          Received {clockTime(t.first_reported_at)} · {formatRelative(nowSec, t.first_reported_at)}
        </span>
        <button type="button" className="close" onClick={onClose} aria-label="Close request">×</button>
      </div>

      <div className="detail-body">
        <h2>{headline(t)}</h2>
        {subtitle && <p className="muted sub">{subtitle}</p>}

        <h3>Why priority {Math.round(t.priority_score)}</h3>
        <p>{t.priority_reason || `${u.label}. Score ${t.priority_score.toFixed(1)} from category, urgency, people and wait.`}</p>
        {t.report_count > 1 && <p className="muted small">{t.report_count} independent reports merged into this request.</p>}

        <h3>Location</h3>
        <p>
          {t.landmark ? `Near ${t.landmark}` : 'No landmark given'}
          {isMappable(t) ? (
            <>
              {accuracyLabel(t) ? ` · GPS ${accuracyLabel(t)!.replace('<', '±')}` : ' · GPS'}{' '}
              <button type="button" className="link" onClick={onCentre}>Centre map</button>
            </>
          ) : (
            <span className="warn"> · no GPS fix{hasMeshEstimate(t) ? '' : ', locate through the landmark or ward office'}</span>
          )}
        </p>
        {hasMeshEstimate(t) && (
          <p className="est-line">
            <span className="approx-badge" title="Estimated from nearby relay phones, not a GPS fix">Approximate</span>{' '}
            <b>{meshEstimateLabel(t.geo)}</b>{' '}
            <button type="button" className="link" onClick={onCentre}>Zoom to area</button>
            <span className="muted small block">
              Dashed circle on the map, around {t.geo.lat.toFixed(4)}, {t.geo.lon.toFixed(4)}. Confirm with the ward office before sending a team.
            </span>
          </p>
        )}

        {hasMapsEstimate(t) && t.geo && (
          <p className="est-line">
            <span className="approx-badge" title="Estimated from the landmark text, not a GPS fix">Approximate</span>{' '}
            Approx. location: {mapsEstimateText(t.geo).place && <b>{mapsEstimateText(t.geo).place}</b>}
            {mapsEstimateText(t.geo).place ? ' — ' : ''}{mapsEstimateText(t.geo).rest}{' '}
            {mapsLink(t.geo) && (
              <a className="link" href={mapsLink(t.geo)!} target="_blank" rel="noopener">Open in Google Maps</a>
            )}
            <span className="muted small block">
              Google Maps · {t.geo.lat.toFixed(4)}, {t.geo.lon.toFixed(4)}. Confirm with the ward office before sending a team.
            </span>
          </p>
        )}
        {canLocateByLandmark(t) && (
          <p>
            <button type="button" className="btn ghost sm" disabled={!!busy} onClick={locate}>
              {busy === 'locate' ? 'Locating…' : 'Locate with Maps'}
            </button>
          </p>
        )}

        <h3>Mesh path</h3>
        <p className="path">
          <i className="dot ok" aria-hidden /> Requester's phone <span aria-hidden>→</span> {t.max_hops} hop{t.max_hops === 1 ? '' : 's'}{' '}
          <span aria-hidden>→</span> <i className="sq" aria-hidden /> Gateway{' '}
          {t.gateways.length ? t.gateways.map((g) => `GW-${shortNode(g)}`).join(', ') : '–'}
        </p>
        <p className="muted small">
          {firstPacket ? `uploaded ${clockTime(firstPacket.received_at)}` : 'uploaded'}
          {delays.length ? ` · ${formatMinutes(Math.min(...delays) / 60)} phone to upload` : ''} · identities hidden
        </p>

        <h3>Dispatch</h3>
        <div className="dispatch-card">
          {t.dispatch ? (
            <div className="dispatch-row">
              <div>
                <p className="muted small">Approved {clockTime(t.dispatch.approved_at)}</p>
                <p className="team">{t.dispatch.team_name ?? 'No team named'}</p>
                {t.dispatch.eta_min != null && (
                  <p className="small">
                    ETA {formatMinutes(t.dispatch.eta_min)} · expected by{' '}
                    {clockTime(t.dispatch.approved_at + t.dispatch.eta_min * 60)}
                  </p>
                )}
              </div>
            </div>
          ) : t.proposal || teamOverride ? (
            <div className="dispatch-row">
              <div>
                <p className="muted small">{teamOverride && teamOverride !== t.proposal?.team_id ? 'Chosen by you' : 'Proposed by the dispatch agent'}</p>
                <p className="team">
                  {teams.find((tm) => tm.id === chosenTeam)?.name ?? t.proposal?.team_name ?? chosenTeam}
                </p>
                {t.proposal && chosenTeam === t.proposal.team_id && (
                  <p className="small">
                    ETA {formatMinutes(t.proposal.eta_min)}
                    {t.proposal.reason ? ` · ${t.proposal.reason}` : ''}
                  </p>
                )}
                {(() => {
                  const tm = teams.find((x) => x.id === chosenTeam);
                  return tm ? <p className="small muted">{tm.capacity - tm.load} of {tm.capacity} slots free</p> : null;
                })()}
              </div>
              {canApprove && (
                <button type="button" className="btn ghost sm" onClick={() => setPicking((v) => !v)}>Change team</button>
              )}
            </div>
          ) : (
            <div className="dispatch-row">
              <p className="muted small">No team proposed yet.</p>
              {canApprove && (
                <span className="row-actions">
                  <button type="button" className="btn ghost sm" disabled={!!busy} onClick={propose}>
                    {busy === 'propose' ? 'Asking…' : 'Ask dispatch agent'}
                  </button>
                  <button type="button" className="btn ghost sm" onClick={() => setPicking((v) => !v)}>Pick team</button>
                </span>
              )}
            </div>
          )}
          {picking && canApprove && (
            <ul className="team-pick" aria-label="Choose a team">
              {eligible.length === 0 && <li className="muted small">No teams on duty.</li>}
              {eligible.map((tm) => (
                <li key={tm.id}>
                  <button
                    type="button"
                    className={tm.id === chosenTeam ? 'on' : ''}
                    disabled={tm.load >= tm.capacity}
                    onClick={() => {
                      setTeamOverride(tm.id);
                      setPicking(false);
                    }}
                  >
                    <span>{tm.name}</span>
                    <span className="muted small">{tm.load >= tm.capacity ? 'full' : `${tm.capacity - tm.load} free`}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          {(order || orderLocal) && (
            <div className="message">
              <p className="muted small">Dispatch order</p>
              {order && <p>{order}</p>}
              {orderLocal && orderLocal !== order && <p lang={t.proposal?.local_lang ?? t.lang}>{orderLocal}</p>}
              <p className="muted small">The requester gets a confirmation in {languageName(t.lang) || 'their language'} over the mesh.</p>
            </div>
          )}
        </div>
        {error && <p className="err" role="alert">{error}</p>}
      </div>

      <div className="detail-foot">
        <div className="actions">
          {canApprove && (
            <button type="button" className="btn primary" disabled={!!busy} onClick={approve}>
              {busy === 'approve' ? 'Sending…' : 'Approve and notify'}
            </button>
          )}
          {canOnTheWay && (
            <button type="button" className="btn primary" disabled={!!busy} onClick={onTheWay}>
              {busy === 'onTheWay' ? 'Sending…' : 'Mark on the way'}
            </button>
          )}
          <button type="button" className="btn soft" disabled={!canResolve || !!busy} onClick={resolve}
            title={canResolve ? undefined : 'Approve a team first'}>
            {t.status === 'RESOLVED' ? 'Delivered ✓' : busy === 'resolve' ? 'Saving…' : 'Mark delivered'}
          </button>
        </div>
        <div className="foot-row">
          <button type="button" className="link" onClick={onClose}>Close</button>
          <span className="muted small keys">A approve · D delivered · J / K next</span>
        </div>
      </div>
    </section>
  );
}
