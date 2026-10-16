import { CATEGORIES, type Stats, type Ticket } from '../types';
import { CATEGORY_META } from '../lib/categories';
import { countWithoutLocation } from '../lib/location';

interface Props {
  tickets: Ticket[];
  stats: Stats | null;
  connected: boolean;
}

export function Header({ tickets, stats, connected }: Props) {
  const counts = (c: (typeof CATEGORIES)[number]) =>
    stats?.open_by_category?.[c] ??
    tickets.filter((t) => t.category === c && t.status === 'OPEN').length;

  const noLoc = countWithoutLocation(tickets);

  return (
    <header className="header">
      <h1>Relay — District Control Room</h1>
      <ul className="cats" aria-label="Open needs by category">
        {CATEGORIES.map((c) => (
          <li key={c} title={CATEGORY_META[c].label} className={counts(c) ? '' : 'zero'}>
            <span aria-hidden>{CATEGORY_META[c].icon}</span>
            <span className="cat-name">{c.replace('_', ' ')}</span>
            <b>{counts(c)}</b>
          </li>
        ))}
      </ul>
      <div className="header-right">
        <span
          className={`noloc-count ${noLoc ? '' : 'zero'}`}
          title="Tickets with no GPS fix: not shown on the map, still in the queue"
        >
          📍 <b>{noLoc}</b> without location
        </span>
        <span className="gw" title="Gateways that synced in the last 60 s">
          Gateways online: <b>{stats?.gateways_online ?? '–'}</b>
        </span>
        <span className={`conn ${connected ? 'live' : 'down'}`} role="status">
          <i aria-hidden /> {connected ? 'Live' : 'Backend unreachable — retrying'}
        </span>
      </div>
    </header>
  );
}
