import { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api';
import { districtById } from '../lib/districts';
import { ENTRY_KINDS, emptyEntry, nextEntryId, parseCoord, publishableCount, suggestionsDoc } from '../lib/shelters';
import { whenLabel } from '../lib/ready';
import type { ShelterEntry, ShelterList, ShelterPublishResult } from '../types';
import { ShelterMap } from './ShelterMap';

const msg = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** Shelter list workflow: Discover (Gemini + Google Search) or add manually, verify each row, publish. */
export function ShelterListPanel({ districtId }: { districtId: string }) {
  const [lists, setLists] = useState<ShelterList[]>([]);
  const [entries, setEntries] = useState<ShelterEntry[]>([]);
  const [draftId, setDraftId] = useState<number | null>(null);
  const [dirty, setDirty] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<ShelterPublishResult | null>(null);

  const draft = useMemo(() => lists.find((l) => l.id === draftId) ?? null, [lists, draftId]);
  const published = useMemo(() => lists.find((l) => l.status === 'published') ?? null, [lists]);
  const district = districtById(districtId);

  const adopt = useCallback((ls: ShelterList[], prefer?: number) => {
    setLists(ls);
    const d = ls.find((l) => l.id === prefer && l.status === 'draft') ?? ls.find((l) => l.status === 'draft') ?? null;
    setDraftId(d?.id ?? null);
    setEntries(d?.entries ?? []);
    setDirty(false);
    setSelected(null);
  }, []);

  useEffect(() => {
    setConfirming(false);
    setResult(null);
    setError(null);
    adopt([]);
    const ctl = new AbortController();
    api.listShelterLists(districtId, ctl.signal).then((ls) => !ctl.signal.aborted && adopt(ls)).catch((e) => !ctl.signal.aborted && setError(msg(e)));
    return () => ctl.abort();
  }, [districtId, adopt]);

  const run = async (label: string, fn: () => Promise<void>) => {
    setBusy(label);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(msg(e));
    } finally {
      setBusy(null);
    }
  };

  const reload = async (prefer?: number) => adopt(await api.listShelterLists(districtId), prefer);
  const discover = () => run('discover', async () => {
    setResult(null);
    const rec = await api.discoverShelters(districtId);
    await reload(rec.id);
  });
  const addManually = () => run('create', async () => {
    setResult(null);
    const rec = await api.createShelterList(districtId, []);
    await reload(rec.id);
  });
  const save = () => run('save', async () => {
    if (draftId == null) return;
    const rec = await api.saveShelterList(draftId, entries);
    setLists((ls) => ls.map((l) => (l.id === rec.id ? rec : l)));
    setEntries(rec.entries);
    setDirty(false);
  });
  const publish = () => run('publish', async () => {
    if (draftId == null) return;
    if (dirty) await api.saveShelterList(draftId, entries);
    const out = await api.publishShelterList(draftId);
    setConfirming(false);
    setResult(out);
    await reload();
  });

  const edit = (id: string, patch: Partial<ShelterEntry>) => {
    setEntries((es) => es.map((e) => (e.id === id ? { ...e, ...patch } : e)));
    setDirty(true);
    setConfirming(false);
  };
  const setCoord = (id: string, key: 'lat' | 'lon', v: string) => {
    const e = entries.find((x) => x.id === id);
    const n = parseCoord(v);
    edit(id, { [key]: n, coords_from: n == null && (key === 'lat' ? e?.lon : e?.lat) == null ? 'none' : 'officer' });
  };
  const pick = (lat: number, lon: number) => {
    if (selected) edit(selected, { lat: Number(lat.toFixed(6)), lon: Number(lon.toFixed(6)), coords_from: 'officer' });
  };
  const addRow = () => {
    const id = nextEntryId(entries);
    setEntries((es) => [...es, emptyEntry(id)]);
    setSelected(id);
    setDirty(true);
  };
  const removeRow = (id: string) => {
    setEntries((es) => es.filter((e) => e.id !== id));
    setSelected((s) => (s === id ? null : s));
    setDirty(true);
    setConfirming(false);
  };

  const ready = publishableCount(entries);
  const sel = entries.find((e) => e.id === selected) ?? null;

  return (
    <div className="shelter-panel">
      {error && <p className="err" role="alert">{error}</p>}
      <p className="small">
        {published ? (
          <>
            <b>Published list:</b> {published.entries.filter((e) => e.verified && e.lat != null).length} verified shelters, published{' '}
            {whenLabel(published.published_at ?? undefined)}
            {published.published_by_note ? ` (${published.published_by_note})` : ''}. Phones and the public lookup use it.
          </>
        ) : (
          <span className="muted">No list published for this district: phones still get the demo placeholders.</span>
        )}
      </p>
      <div className="row-actions">
        <button type="button" className="btn primary" disabled={busy !== null} onClick={() => void discover()}>
          {busy === 'discover' ? 'Searching… (up to a minute)' : 'Discover with Gemini + Google Search'}
        </button>
        <button type="button" className="btn soft" disabled={busy !== null} onClick={() => void addManually()}>Add manually</button>
      </div>
      <p className="muted small">
        Gemini drafts the list from the district authority&apos;s own pages (OSDMA, DDMA, district sites). Nothing reaches phones
        until you check each row against the authority&apos;s document and tick Verified. You are accountable for what you publish.
      </p>

      {result && (
        <p className="small" role="status">
          Published {result.published} shelters.
          {result.skipped.map((s) => <span key={s.id} className="muted block">Not published: {s.name} ({s.reason})</span>)}
        </p>
      )}

      {draft && (
        <>
          <h3>Draft #{draft.id} <span className="muted small">{draft.origin === 'gemini' ? `Gemini (${draft.model ?? 'model'})` : 'entered by hand'} · {whenLabel(draft.created_at)}</span></h3>
          {draft.notes.length > 0 && <ul className="small muted">{draft.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>}

          {draft.search_suggestions_html && (
            <div className="suggestions">
              <span className="muted small">Google Search suggestions</span>
              <iframe
                title="Google Search suggestions"
                srcDoc={suggestionsDoc(draft.search_suggestions_html)}
                sandbox="allow-popups allow-popups-to-escape-sandbox"
                className="suggestions-frame"
              />
            </div>
          )}
          {draft.sources.length > 0 && (
            <details className="small">
              <summary>{draft.sources.length} search sources</summary>
              <ul>{draft.sources.map((s) => <li key={s.uri}><a href={s.uri} target="_blank" rel="noopener noreferrer">{s.title || s.uri}</a></li>)}</ul>
            </details>
          )}

          <div className="shelter-edit">
            <div className="shelter-table">
              <table className="grid">
                <thead>
                  <tr><th>Name</th><th>Local name</th><th>Type</th><th>Address</th><th className="num">Capacity</th><th>Lat</th><th>Lon</th><th>Source</th><th>Issues</th><th>Verified</th><th /></tr>
                </thead>
                <tbody>
                  {entries.map((e) => (
                    <tr key={e.id} className={e.id === selected ? 'row-selected' : undefined} onClick={() => setSelected(e.id)}>
                      <td><input type="text" aria-label="Name" value={e.name} maxLength={120} onChange={(x) => edit(e.id, { name: x.target.value })} /></td>
                      <td><input type="text" aria-label="Local name" value={e.name_local ?? ''} onChange={(x) => edit(e.id, { name_local: x.target.value || null })} /></td>
                      <td>
                        <select aria-label="Type" value={e.kind} onChange={(x) => edit(e.id, { kind: x.target.value as ShelterEntry['kind'] })}>
                          {ENTRY_KINDS.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
                        </select>
                      </td>
                      <td><input type="text" aria-label="Address" value={e.address} onChange={(x) => edit(e.id, { address: x.target.value })} /></td>
                      <td><input type="number" aria-label="Capacity" className="narrow" min={1} value={e.capacity ?? ''} onChange={(x) => edit(e.id, { capacity: x.target.value ? Number(x.target.value) : null })} /></td>
                      <td><input type="text" aria-label="Latitude" className="narrow mono" inputMode="decimal" value={e.lat ?? ''} onChange={(x) => setCoord(e.id, 'lat', x.target.value)} /></td>
                      <td>
                        <input type="text" aria-label="Longitude" className="narrow mono" inputMode="decimal" value={e.lon ?? ''} onChange={(x) => setCoord(e.id, 'lon', x.target.value)} />
                        {e.coords_from === 'estimate' && <span className="approx-badge" title="The model's estimate: check on the map">estimate</span>}
                      </td>
                      <td>{e.source_url ? <a href={e.source_url} target="_blank" rel="noopener noreferrer">open</a> : <span className="muted">none</span>}</td>
                      <td className="small">{e.issues.map((i, k) => <span key={k} className="block warn">{i}</span>)}</td>
                      <td><input type="checkbox" aria-label={`Verified: ${e.name}`} checked={e.verified} onChange={(x) => edit(e.id, { verified: x.target.checked })} /></td>
                      <td><button type="button" className="btn ghost sm" onClick={(ev) => { ev.stopPropagation(); removeRow(e.id); }}>Remove</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {entries.length === 0 && <p className="empty">No entries yet.</p>}
              <div className="row-actions">
                <button type="button" className="btn soft sm" onClick={addRow}>Add row</button>
              </div>
            </div>
            {district && (
              <div className="shelter-map-wrap">
                <ShelterMap district={district} entries={entries} selectedId={selected} onSelect={setSelected} onPick={pick} />
                <p className="muted small">
                  {sel ? `Click the map to place “${sel.name || 'new row'}”.` : 'Click a row, then click the map to set its coordinates.'} Green = verified.
                </p>
              </div>
            )}
          </div>

          <div className="row-actions">
            <button type="button" className="btn soft" disabled={busy !== null || !dirty} onClick={() => void save()}>{busy === 'save' ? 'Saving…' : 'Save draft'}</button>
            {!confirming ? (
              <button type="button" className="btn primary" disabled={busy !== null || ready === 0} onClick={() => setConfirming(true)}>Publish verified ({ready})</button>
            ) : (
              <>
                <span className="small">
                  Publish {ready} verified shelters{published ? ' and archive the current list' : ''}? Phones will use them.
                </span>
                <button type="button" className="btn primary" disabled={busy !== null} onClick={() => void publish()}>{busy === 'publish' ? 'Publishing…' : 'Yes, publish'}</button>
                <button type="button" className="btn ghost" disabled={busy !== null} onClick={() => setConfirming(false)}>Cancel</button>
              </>
            )}
          </div>
        </>
      )}
    </div>
  );
}
