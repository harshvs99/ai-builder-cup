import type { Ticket } from '../types';
import { formatRelative } from '../lib/time';
import { hasMeshEstimate, isMappable, meshEstimateShort } from '../lib/location';
import { hasPhotoTags, photoTagSummary } from '../lib/photoTags';
import {
  QUEUE_FILTERS,
  SORT_LABEL,
  headline,
  matchesFilter,
  rowState,
  ticketRef,
  urgencyOf,
  type SortKey,
} from '../lib/triage';

interface Props {
  /** All tickets matching the search, in display order (filters are applied here). */
  tickets: Ticket[];
  filterId: string;
  onFilter: (id: string) => void;
  sort: SortKey;
  onSort: (s: SortKey) => void;
  loaded: boolean;
  nowSec: number;
  fresh: ReadonlyMap<number, number>;
  seen: ReadonlySet<number>;
  selected: number | null;
  onSelect: (no: number) => void;
}

function where(t: Ticket): string {
  const lm = t.landmark?.trim() ? `near ${t.landmark.trim()}` : null;
  if (hasMeshEstimate(t)) return [lm, `no GPS · approx. ${meshEstimateShort(t.geo)}`].filter(Boolean).join(' · ');
  if (!isMappable(t)) return [lm, 'no location'].filter(Boolean).join(' · ');
  return [lm, t.loc_acc_m ? `GPS ${t.loc_acc_m.replace('<', '±')}` : 'GPS'].filter(Boolean).join(' · ');
}

export function Queue(p: Props) {
  const active = QUEUE_FILTERS.find((f) => f.id === p.filterId) ?? QUEUE_FILTERS[0]!;
  const shown = p.tickets.filter((t) => matchesFilter(t, active.filter));
  const openCount = p.tickets.filter((t) => t.status === 'OPEN').length;

  return (
    <section className="queue panel" aria-label="Request queue">
      <div className="queue-head">
        <h2>Queue · {openCount} open</h2>
        <label className="sort">
          Sort:
          <select value={p.sort} onChange={(e) => p.onSort(e.target.value as SortKey)}>
            {(Object.keys(SORT_LABEL) as SortKey[]).map((k) => (
              <option key={k} value={k}>{SORT_LABEL[k]}</option>
            ))}
          </select>
        </label>
      </div>
      <div className="chips" role="toolbar" aria-label="Filter the queue">
        {QUEUE_FILTERS.map((f) => {
          const n = p.tickets.filter((t) => t.status === 'OPEN' && matchesFilter(t, f.filter)).length;
          return (
            <button
              key={f.id}
              type="button"
              className={f.id === active.id ? 'chip on' : 'chip'}
              aria-pressed={f.id === active.id}
              onClick={() => p.onFilter(f.id)}
            >
              {f.label}{f.id === 'all' || f.filter.kind === 'urgency' || f.id === 'noloc' ? ` ${n}` : ''}
            </button>
          );
        })}
      </div>
      {shown.length === 0 ? (
        <p className="empty">
          {!p.loaded
            ? 'Loading…'
            : p.tickets.length === 0
              ? 'No requests yet. They appear here as soon as a gateway phone uploads.'
              : 'Nothing matches this filter.'}
        </p>
      ) : (
        <ul className="rows">
          {shown.map((t) => {
            const u = urgencyOf(t);
            const state = rowState(t, p.seen);
            const cls = [
              'row',
              t.ticket_no === p.selected ? 'selected' : '',
              t.status === 'RESOLVED' ? 'closed' : '',
              p.fresh.has(t.ticket_no) ? 'fresh' : '',
            ].join(' ');
            return (
              <li key={t.ticket_no}>
                <button type="button" className={cls} onClick={() => p.onSelect(t.ticket_no)} data-ticket={t.ticket_no}>
                  <span className={`score u-${u.key}`} title={`Priority score ${t.priority_score.toFixed(1)}`}>
                    {Math.round(t.priority_score)}
                  </span>
                  <span className="body">
                    <span className="title">
                      {headline(t)}
                      {hasPhotoTags(t) && (
                        <span className="photo-ind" role="img" aria-label="Has photo tags" title={photoTagSummary(t.photo_tags)}>📷</span>
                      )}
                    </span>
                    <span className="line">{where(t)}</span>
                    <span className="line muted">
                      {u.label} · {formatRelative(p.nowSec, t.first_reported_at)} · {t.max_hops} hop{t.max_hops === 1 ? '' : 's'} · {ticketRef(t.ticket_no)}
                      {t.report_count > 1 ? ` · ${t.report_count} reports` : ''}
                    </span>
                  </span>
                  <span className={`state s-${state.replace(/ /g, '-').toLowerCase()}`}>{state}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
