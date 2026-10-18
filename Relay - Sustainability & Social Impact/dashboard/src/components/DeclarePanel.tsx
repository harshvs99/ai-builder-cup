import { useEffect, useState } from 'react';
import { api } from '../api';
import type { District } from '../lib/districts';
import { DISASTERS, PHONE_EFFECT, disasterLabel, districtArea, inForce, lifeLabel } from '../lib/declare';
import type { Broadcast, BroadcastArea, DeclarationDraft, DisasterKey } from '../types';

interface Props {
  /** The district in view; null = all districts (no default area). */
  district: District | null;
  broadcasts: Broadcast[];
  nowSec: number;
  onClose: () => void;
  /** A broadcast was sent: refresh the list. */
  onChanged: () => void;
  /** The area being drafted, so the map can outline it. */
  onDraftArea: (a: BroadcastArea | null) => void;
}

interface Form {
  disaster: DisasterKey | '';
  wholeNetwork: boolean;
  lat: string;
  lon: string;
  radius: string;
  label: string;
}

const SOURCE_TEXT: Record<string, string> = {
  gazetteer: 'from the district gazetteer',
  model: "Gemini's estimate: check it on the map",
  district: 'the district in view',
  rules: '',
};

function formFrom(d: DeclarationDraft | null, district: District | null): Form {
  const area = d?.area ?? (district ? { ...districtArea(district) } : null);
  return {
    disaster: d?.disaster ?? '',
    wholeNetwork: !area,
    lat: area ? String(area.lat) : '',
    lon: area ? String(area.lon) : '',
    radius: area ? String(area.radius_km) : '',
    label: d?.label ?? '',
  };
}

/** The circle the form describes, or null (whole network, or not a valid circle yet). */
function areaOf(f: Form): BroadcastArea | null {
  if (f.wholeNetwork) return null;
  const lat = Number(f.lat), lon = Number(f.lon), radius_km = Math.round(Number(f.radius));
  if (!f.lat.trim() || !f.lon.trim() || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  if (!Number.isFinite(radius_km) || radius_km < 1 || radius_km > 500) return null;
  return { lat, lon, radius_km };
}

function whereText(b: Broadcast): string {
  return b.area ? `within ${b.area.radius_km} km of ${b.area.lat.toFixed(3)}, ${b.area.lon.toFixed(3)}` : 'every phone';
}

/**
 * Declare a disaster to every phone. The operator describes it in their own words; Gemini (or keyword rules) turns that
 * into the only thing the mesh can carry, a disaster code and a circle, which the operator checks and edits before
 * anything is sent. Also lists what is in force, with a way to end it.
 */
export function DeclarePanel({ district, broadcasts, nowSec, onClose, onChanged, onDraftArea }: Props) {
  const [text, setText] = useState('');
  const [draft, setDraft] = useState<DeclarationDraft | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [sent, setSent] = useState<string | null>(null);
  const active = inForce(broadcasts, nowSec);
  const area = form ? areaOf(form) : null;

  useEffect(() => onDraftArea(area), [area?.lat, area?.lon, area?.radius_km]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => () => onDraftArea(null), []); // eslint-disable-line react-hooks/exhaustive-deps

  const set = (patch: Partial<Form>) => {
    setForm((f) => (f ? { ...f, ...patch } : f));
    setConfirming(false);
  };

  const read = async () => {
    if (!text.trim()) return;
    setBusy(true);
    setError(null);
    setSent(null);
    try {
      const d = await api.interpretDeclaration(text.trim(), district ? districtArea(district) : undefined);
      setDraft(d);
      setForm(formFrom(d, district));
      setConfirming(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const fillIn = () => {
    setDraft(null);
    setForm(formFrom(null, district));
    setConfirming(false);
    setSent(null);
  };

  const send = async () => {
    if (!form || !form.disaster) return;
    if (!confirming) {
      setConfirming(true);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      if (draft?.kind === 'cleared') {
        await api.clearDisaster(form.disaster, form.label || undefined);
        setSent(`${disasterLabel(form.disaster)} ended. Phones stop asking its questions as the all-clear reaches them.`);
      } else {
        await api.declare({ disaster: form.disaster, area, label: form.label.trim() || undefined });
        setSent(`${disasterLabel(form.disaster)} declared. Every gateway picks it up on its next sync; phones carry it for 24 h.`);
      }
      setDraft(null);
      setForm(null);
      setText('');
      setConfirming(false);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const end = async (d: DisasterKey) => {
    const others = active.filter((b) => b.disaster === d).length;
    const msg = others > 1
      ? `End all ${others} ${disasterLabel(d).toLowerCase()} declarations? An all-clear ends every one of them, wherever it applied.`
      : `End the ${disasterLabel(d).toLowerCase()} declaration? Phones stop asking its questions as the all-clear reaches them.`;
    if (!window.confirm(msg)) return;
    setBusy(true);
    setError(null);
    try {
      await api.clearDisaster(d);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const invalidArea = form && !form.wholeNetwork && !area;
  const cleared = draft?.kind === 'cleared';
  const sendLabel = !form?.disaster
    ? 'Choose a disaster'
    : cleared
      ? (confirming ? `Confirm: end ${form.disaster} everywhere` : `End ${disasterLabel(form.disaster).toLowerCase()}`)
      : confirming
        ? `Confirm: send ${form.disaster} to ${area ? `phones within ${area.radius_km} km` : 'every phone'}`
        : 'Send to phones';

  return (
    <aside className="detail panel declare" aria-label="Declare a disaster">
      <div className="detail-head">
        <b>Disaster declaration</b>
        <button type="button" className="icon-btn" onClick={onClose} aria-label="Close">×</button>
      </div>
      <div className="detail-body">
        <h3>In force</h3>
        {active.length === 0 ? (
          <p className="muted small">Nothing declared. Phones ask only the questions that apply anywhere.</p>
        ) : (
          <ul className="declared-list">
            {active.map((b) => (
              <li key={b.id}>
                <div>
                  <b>{disasterLabel(b.disaster)}</b>{b.label ? <span> · {b.label}</span> : null}
                  <span className="block muted small">{whereText(b)} · {lifeLabel(b, nowSec)}</span>
                </div>
                <button type="button" className="btn sm ghost" disabled={busy} onClick={() => end(b.disaster)}>End</button>
              </li>
            ))}
          </ul>
        )}

        <h3>New declaration</h3>
        <label className="block small muted" htmlFor="declare-text">What is happening, in your own words</label>
        <textarea
          id="declare-text"
          className="declare-text"
          rows={3}
          maxLength={600}
          placeholder="e.g. Cyclone made landfall near Gop, storm surge along the coast, power lines down"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
        <div className="row-actions">
          <button type="button" className="btn sm primary" disabled={busy || !text.trim()} onClick={read}>
            {busy && !form ? 'Reading…' : 'Read with Gemini'}
          </button>
          <button type="button" className="btn sm ghost" disabled={busy} onClick={fillIn}>Fill in myself</button>
        </div>
        {sent && <p className="declare-ok small" role="status">{sent}</p>}
        {error && <p className="declare-err small" role="alert">{error}</p>}

        {form && (
          <div className="declare-draft">
            {draft && (
              <p className="small muted">
                {draft.source === 'model' ? 'Gemini read this as' : 'Gemini is not available; keyword rules read this as'}
                {cleared ? ' an all-clear.' : ' a declaration.'}
                {draft.reason ? ` ${draft.reason}` : ''}
              </p>
            )}
            <label className="block small" htmlFor="declare-disaster">Disaster code sent to phones</label>
            <select id="declare-disaster" value={form.disaster} onChange={(e) => set({ disaster: e.target.value as DisasterKey | '' })}>
              <option value="">Choose…</option>
              {DISASTERS.map((d) => <option key={d.key} value={d.key}>{d.label}</option>)}
            </select>
            {form.disaster && <p className="small muted">{PHONE_EFFECT[form.disaster]}</p>}

            {!cleared && (
              <fieldset className="declare-area">
                <legend className="small">Where</legend>
                <label className="small">
                  <input type="radio" checked={!form.wholeNetwork} onChange={() => set({ wholeNetwork: false })} /> Within a circle
                </label>
                <label className="small">
                  <input type="radio" checked={form.wholeNetwork} onChange={() => set({ wholeNetwork: true })} /> Every phone in the network
                </label>
                {!form.wholeNetwork && (
                  <div className="declare-coords">
                    <label className="small">Lat <input inputMode="decimal" value={form.lat} onChange={(e) => set({ lat: e.target.value })} /></label>
                    <label className="small">Lon <input inputMode="decimal" value={form.lon} onChange={(e) => set({ lon: e.target.value })} /></label>
                    <label className="small">Radius km <input inputMode="numeric" value={form.radius} onChange={(e) => set({ radius: e.target.value })} /></label>
                  </div>
                )}
                {!form.wholeNetwork && draft?.area && (
                  <p className="small muted">
                    Centre: {draft.area.name || 'unnamed'}{SOURCE_TEXT[draft.area.source] ? ` (${SOURCE_TEXT[draft.area.source]})` : ''}.
                  </p>
                )}
                {invalidArea && <p className="declare-err small">Enter a centre and a radius of 1 to 500 km.</p>}
                {form.wholeNetwork && (
                  <p className="small muted">Phones in every district will treat this disaster as declared.</p>
                )}
              </fieldset>
            )}

            <label className="block small" htmlFor="declare-label">Label (stays in the control room)</label>
            <input id="declare-label" className="declare-label" maxLength={80} value={form.label} onChange={(e) => set({ label: e.target.value })} />

            {draft?.notes.map((n) => <p key={n} className="declare-warn small">{n}</p>)}
            <p className="small muted">
              On the mesh: {draft?.wire_bytes ?? 25} bytes (disaster code, centre, radius). Lives 24 h from now; marked aging after 12 h.
            </p>
            <div className="row-actions">
              <button type="button" className={confirming ? 'btn primary danger' : 'btn primary'} disabled={busy || !form.disaster || !!invalidArea} onClick={send}>
                {sendLabel}
              </button>
              {confirming && <button type="button" className="btn ghost" onClick={() => setConfirming(false)}>Not yet</button>}
            </div>
          </div>
        )}
      </div>
    </aside>
  );
}
