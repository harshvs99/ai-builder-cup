import type { Ticket } from '../types';
import { categoryMeta } from '../lib/categories';
import { formatRelative } from '../lib/time';
import { tierOf, ticketTitle } from '../lib/sort';
import { noLocationBadge } from '../lib/location';

interface Props {
  tickets: Ticket[];
  loaded: boolean;
  nowSec: number;
  fresh: ReadonlyMap<number, number>;
  selected: number | null;
  onSelect: (no: number) => void;
}

export function Queue({ tickets, loaded, nowSec, fresh, selected, onSelect }: Props) {
  return (
    <section className="queue" aria-label="Priority queue">
      <h2>Queue <span className="muted">({tickets.length})</span></h2>
      {tickets.length === 0 ? (
        <p className="empty">
          {loaded
            ? 'No needs reported yet. Packets appear here as soon as a gateway phone syncs.'
            : 'Loading…'}
        </p>
      ) : (
        <ul>
          {tickets.map((t) => {
            const tier = tierOf(t);
            const noLoc = noLocationBadge(t);
            const cls = [
              'row',
              t.ticket_no === selected ? 'selected' : '',
              t.status === 'DISPATCHED' ? 'dispatched' : '',
              fresh.has(t.ticket_no) ? 'fresh' : '',
            ].join(' ');
            return (
              <li key={t.ticket_no}>
                <button type="button" className={cls} onClick={() => onSelect(t.ticket_no)}>
                  <span className={`tier ${tier}`}>{tier}</span>
                  <span className="ico" aria-label={t.category}>{categoryMeta(t.category).icon}</span>
                  <span className="body">
                    <span className="title">{ticketTitle(t)}</span>
                    <span className="meta">
                      <span>{formatRelative(nowSec, t.first_reported_at)}</span>
                      <span>{t.max_hops} hop{t.max_hops === 1 ? '' : 's'}</span>
                      <span className={`status ${t.status}`}>{t.status === 'DISPATCHED' ? 'Dispatched' : 'Open'}</span>
                    </span>
                    {noLoc && <span className="noloc">{noLoc}</span>}
                  </span>
                  {t.report_count > 1 && <span className="count" title="Reports merged">×{t.report_count}</span>}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
