import { useMemo, useRef, useState } from 'react';
import { Header } from './components/Header';
import { MapView, type MapHandle } from './components/MapView';
import { Queue } from './components/Queue';
import { Detail } from './components/Detail';
import { useLiveData, useNowSec, useTicketDetail } from './hooks';
import { sortTickets } from './lib/sort';

export function App() {
  const { tickets, stats, connected, loaded, fresh, refresh } = useLiveData();
  const nowSec = useNowSec();
  const [selected, setSelected] = useState<number | null>(null);
  const mapRef = useRef<MapHandle>(null);
  const sorted = useMemo(() => sortTickets(tickets), [tickets]);
  const [detail, setDetail] = useTicketDetail(selected);

  const listTicket = sorted.find((t) => t.ticket_no === selected) ?? null;
  // Prefer the freshest of list/detail for scalar fields; packets only come from detail.
  const shown = listTicket ? { ...(detail ?? {}), ...listTicket, packets: detail?.packets } : null;

  return (
    <div className="app">
      <Header tickets={tickets} stats={stats} connected={connected} />
      <main className="main">
        <section className="map-pane" aria-label="Map of reported needs">
          <MapView ref={mapRef} tickets={sorted} fresh={fresh} selected={selected} onSelect={setSelected} />
          <button className="fit-btn" type="button" onClick={() => mapRef.current?.fitAll()}>
            Fit all
          </button>
        </section>
        <aside className="side">
          {shown && (
            <Detail
              ticket={shown}
              nowSec={nowSec}
              onClose={() => setSelected(null)}
              onApproved={(t) => {
                setDetail((d) => ({ ...t, packets: t.packets ?? d?.packets }));
                refresh();
              }}
            />
          )}
          <Queue
            tickets={sorted}
            loaded={loaded}
            nowSec={nowSec}
            fresh={fresh}
            selected={selected}
            onSelect={setSelected}
          />
        </aside>
      </main>
    </div>
  );
}
