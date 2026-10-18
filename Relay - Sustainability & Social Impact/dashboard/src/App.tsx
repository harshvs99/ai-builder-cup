import { useEffect, useMemo, useRef, useState } from 'react';
import { Header, type View } from './components/Header';
import { MapView, type MapHandle, type MapLayers } from './components/MapView';
import { Queue } from './components/Queue';
import { Detail, type DetailAction } from './components/Detail';
import { GatewaysView, SitrepView, TeamsView } from './components/Views';
import { useLiveData, useNowSec, useSeen, useTheme, useTicketDetail } from './hooks';
import { sortTickets } from './lib/sort';
import { isMappable } from './lib/location';
import { QUEUE_FILTERS, matchesFilter, matchesSearch, orderQueue, ticketRef, type SortKey } from './lib/triage';
import type { Ticket } from './types';

const LEGEND = [
  ['life', 'Life at risk'],
  ['urgent', 'Urgent'],
  ['soon', 'Soon'],
  ['wait', 'Can wait'],
] as const;

export function App() {
  const { tickets, stats, teams, gateways, connected, loaded, fresh, refresh } = useLiveData();
  const nowSec = useNowSec();
  const [theme, toggleTheme] = useTheme();
  const [seen, markSeen] = useSeen();
  const [view, setView] = useState<View>('board');
  const [selected, setSelected] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [filterId, setFilterId] = useState('all');
  const [sort, setSort] = useState<SortKey>('priority');
  const [layers, setLayers] = useState<MapLayers>({ requests: true, teams: false, heat: false });
  const mapRef = useRef<MapHandle>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const actionRef = useRef<((a: DetailAction) => void) | null>(null);

  const sorted = useMemo(() => sortTickets(tickets), [tickets]);
  const searched = useMemo(
    () => orderQueue(sorted.filter((t) => matchesSearch(t, search)), sort),
    [sorted, search, sort],
  );
  const filter = (QUEUE_FILTERS.find((f) => f.id === filterId) ?? QUEUE_FILTERS[0]!).filter;
  const visible = useMemo(() => searched.filter((t) => matchesFilter(t, filter)), [searched, filter]);
  const [detail, setDetail] = useTicketDetail(selected);

  const listTicket = sorted.find((t) => t.ticket_no === selected) ?? null;
  // Prefer the freshest of list/detail for scalar fields; packets only come from detail.
  const shown = listTicket ? { ...(detail ?? {}), ...listTicket, packets: detail?.packets } : null;
  const noLocation = visible.filter((t) => !isMappable(t) && t.status !== 'RESOLVED');

  const select = (no: number | null) => {
    setSelected(no);
    if (no !== null) markSeen(no);
  };
  const open = (no: number) => {
    setView('board');
    select(no);
  };
  const onChanged = (t: Ticket) => {
    setDetail((d) => ({ ...t, packets: t.packets ?? d?.packets }));
    refresh();
  };

  // Keyboard: J / K move through the queue, A approve, D delivered, Esc close, / search.
  const keyState = useRef({ visible, selected, view });
  keyState.current = { visible, selected, view };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement | null;
      if (el && (el.tagName === 'INPUT' || el.tagName === 'SELECT' || el.tagName === 'TEXTAREA' || el.isContentEditable)) {
        if (e.key === 'Escape') el.blur();
        return;
      }
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      const { visible: list, selected: sel, view: v } = keyState.current;
      if (e.key === '/') {
        e.preventDefault();
        searchRef.current?.focus();
        return;
      }
      if (v !== 'board') return;
      const k = e.key.toLowerCase();
      if (k === 'j' || k === 'k') {
        if (list.length === 0) return;
        const i = list.findIndex((t) => t.ticket_no === sel);
        const next = i < 0 ? 0 : Math.min(list.length - 1, Math.max(0, i + (k === 'j' ? 1 : -1)));
        const no = list[next]!.ticket_no;
        select(no);
        document.querySelector(`[data-ticket="${no}"]`)?.scrollIntoView({ block: 'nearest' });
      } else if (k === 'a') actionRef.current?.('approve');
      else if (k === 'd') actionRef.current?.('resolve');
      else if (k === 'escape') select(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggleLayer = (k: keyof MapLayers) => setLayers((l) => ({ ...l, [k]: !l[k] }));

  return (
    <div className="app">
      <Header
        view={view}
        onView={setView}
        tickets={tickets}
        stats={stats}
        gateways={gateways}
        connected={connected}
        nowSec={nowSec}
        search={search}
        onSearch={(q) => {
          setSearch(q);
          if (q && view !== 'board') setView('board');
        }}
        searchRef={searchRef}
        theme={theme}
        onToggleTheme={toggleTheme}
      />
      {!connected && <div className="banner" role="alert">Backend unreachable. Showing the last data received; retrying every 2 s.</div>}

      <main className={`board ${view === 'board' ? '' : 'hidden'} ${shown ? 'with-detail' : ''}`}>
        <Queue
          tickets={searched}
          filterId={filterId}
          onFilter={setFilterId}
          sort={sort}
          onSort={setSort}
          loaded={loaded}
          nowSec={nowSec}
          fresh={fresh}
          seen={seen}
          selected={selected}
          onSelect={select}
        />
        <section className="map-pane panel" aria-label="Map of reported needs">
          <MapView
            ref={mapRef}
            tickets={visible}
            teams={teams}
            layers={layers}
            theme={theme}
            fresh={fresh}
            selected={selected}
            onSelect={select}
          />
          <div className="map-toggles" role="group" aria-label="Map layers">
            {([['requests', 'Requests'], ['teams', 'Teams'], ['heat', 'Heat']] as const).map(([k, label]) => (
              <button key={k} type="button" className={layers[k] ? 'chip on' : 'chip'} aria-pressed={layers[k]} onClick={() => toggleLayer(k)}>
                {label}
              </button>
            ))}
          </div>
          <div className="map-zoom">
            <button type="button" aria-label="Zoom in" onClick={() => mapRef.current?.zoomBy(1)}>+</button>
            <button type="button" aria-label="Zoom out" onClick={() => mapRef.current?.zoomBy(-1)}>−</button>
            <button type="button" aria-label="Fit all requests" title="Fit all" onClick={() => mapRef.current?.fitAll()}>⤢</button>
          </div>
          <div className="map-bottom">
            <ul className="legend" aria-label="Legend">
              {LEGEND.map(([k, label]) => <li key={k}><i className={`dot u-${k}`} />{label}</li>)}
              {layers.teams && <li><i className="dot team" />Team base</li>}
            </ul>
            {noLocation.length > 0 && (
              <div className="noloc-strip">
                <span className="muted">No location:</span>
                {noLocation.slice(0, 2).map((t) => (
                  <button key={t.ticket_no} type="button" className="chip" onClick={() => select(t.ticket_no)}>
                    {ticketRef(t.ticket_no)}{t.landmark ? ` · ${t.landmark}` : ''}
                  </button>
                ))}
                {noLocation.length > 2 && (
                  <button type="button" className="chip" onClick={() => setFilterId('noloc')}>+{noLocation.length - 2}</button>
                )}
              </div>
            )}
          </div>
        </section>
        {shown && (
          <Detail
            ticket={shown}
            teams={teams}
            seen={seen}
            nowSec={nowSec}
            onClose={() => select(null)}
            onCentre={() => mapRef.current?.centreOn(shown)}
            onChanged={onChanged}
            actionRef={actionRef}
          />
        )}
      </main>

      {view === 'teams' && <TeamsView teams={teams} tickets={tickets} nowSec={nowSec} onOpen={open} />}
      {view === 'sitrep' && (
        <SitrepView tickets={tickets} teams={teams} stats={stats} gateways={gateways} nowSec={nowSec} onOpen={open} />
      )}
      {view === 'gateways' && <GatewaysView gateways={gateways} tickets={tickets} nowSec={nowSec} />}
    </div>
  );
}
