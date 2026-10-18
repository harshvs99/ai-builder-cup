import { useState } from 'react';
import { api } from '../api';
import type { Ticket } from '../types';
import { categoryMeta } from '../lib/categories';
import { formatRelative } from '../lib/time';
import { tierOf, ticketTitle } from '../lib/sort';
import { accuracyLabel, hasMeshEstimate, isMappable, meshEstimateLabel, noLocationBadge } from '../lib/location';

interface Props {
  ticket: Ticket;
  nowSec: number;
  onClose: () => void;
  onApproved: (t: Ticket) => void;
}

const short = (id: string) => id.slice(0, 8);

export function Detail({ ticket: t, nowSec, onClose, onApproved }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const meta = categoryMeta(t.category);
  const tier = tierOf(t);
  const dispatched = t.status !== 'OPEN';

  const approve = async () => {
    setBusy(true);
    setError(null);
    try {
      onApproved(await api.approve(t.ticket_no));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="detail" aria-label={`Ticket ${t.ticket_no}`}>
      <div className="detail-head">
        <span className={`tier ${tier}`}>{tier}</span>
        <h2>
          {meta.icon} Ticket #{t.ticket_no}
        </h2>
        <button type="button" className="close" onClick={onClose} aria-label="Close detail">
          ×
        </button>
      </div>
      <p className="summary">{ticketTitle(t)}</p>

      <dl>
        <dt>Category</dt>
        <dd>{t.category.replace('_', ' ')}</dd>
        {t.item && (<><dt>Item</dt><dd>{t.item}</dd></>)}
        {t.landmark && (<><dt>Landmark</dt><dd>{t.landmark}</dd></>)}
        <dt>People</dt>
        <dd>
          {t.people}
          {t.vuln_flags.map((f) => (
            <span key={f} className="flag">{f.toLowerCase()}</span>
          ))}
        </dd>
        <dt>Location</dt>
        <dd>
          {isMappable(t) ? (
            <>
              {t.lat.toFixed(5)}, {t.lon.toFixed(5)}{t.landmark ? ` · ${t.landmark}` : ''}
              {accuracyLabel(t) && <span className="acc" title="GPS accuracy"> · accuracy {accuracyLabel(t)}</span>}
            </>
          ) : (
            <>
              <span className="noloc">{noLocationBadge(t)}</span>
              {hasMeshEstimate(t) && (
                <div className="est-line">
                  <span
                    className="approx-badge"
                    title="Estimated from nearby relay phones, not a GPS fix"
                  >
                    Approximate
                  </span>{' '}
                  <span className="est-label">{meshEstimateLabel(t.geo)}</span>
                  <div className="muted small">
                    around {t.geo.lat.toFixed(4)}, {t.geo.lon.toFixed(4)} · dashed circle on the map
                  </div>
                </div>
              )}
            </>
          )}
        </dd>
        <dt>Priority</dt>
        <dd>
          <b>{t.priority_score.toFixed(1)}</b> · {tier} · urgency {t.urgency}/3
        </dd>
        {t.priority_reason && (<><dt>Why</dt><dd>{t.priority_reason}</dd></>)}
        <dt>Reports</dt>
        <dd>
          {t.report_count} · first {formatRelative(nowSec, t.first_reported_at)} · lang {t.lang}
        </dd>
      </dl>

      <h3>Hop trail</h3>
      <p className="muted small">
        Max {t.max_hops} hop{t.max_hops === 1 ? '' : 's'} · via gateway
        {t.gateways.length === 1 ? '' : 's'} {t.gateways.map(short).join(', ') || '—'}
      </p>
      {t.packets && t.packets.length > 0 && (
        <ul className="packets">
          {t.packets.map((p) => (
            <li key={p.msg_id}>
              <code>{short(p.origin_node)}</code> → <code>{short(p.gateway_node)}</code> · {p.hops} hop
              {p.hops === 1 ? '' : 's'} · {formatRelative(nowSec, p.received_at)}
              {p.summary && <span className="muted"> — {p.summary}</span>}
            </li>
          ))}
        </ul>
      )}

      <h3>Dispatch draft</h3>
      {t.dispatch_draft?.en || t.dispatch_draft?.local ? (
        <div className="draft">
          {t.dispatch_draft.en && (<><span className="lang">EN</span><p>{t.dispatch_draft.en}</p></>)}
          {t.dispatch_draft.local && (<><span className="lang">{t.lang.toUpperCase()}</span><p>{t.dispatch_draft.local}</p></>)}
        </div>
      ) : (
        <p className="muted small">No draft available.</p>
      )}

      <button type="button" className="approve" disabled={dispatched || busy} onClick={approve}>
        {dispatched ? 'Dispatched ✓' : busy ? 'Dispatching…' : 'Approve & dispatch'}
      </button>
      {error && <p className="err" role="alert">Could not dispatch: {error}</p>}
    </section>
  );
}
