import { useState, type FormEvent } from 'react';
import { api } from '../api';
import { useReady } from '../hooks';
import { KIND_LABEL, PLAN_LANGS, SEVERITY_LABEL, localInputToUnix, whenLabel } from '../lib/ready';
import type { CapImportResult, Household, NewWarning, ReadyPlan, ReadyWarning, WarningSeverity, WarningSource } from '../types';

const SOURCES: WarningSource[] = ['IMD', 'PAGASA', 'NDMA', 'manual'];
const SEVERITIES: WarningSeverity[] = ['watch', 'warning', 'alert'];
const FLAGS = ['elderly', 'children', 'infants', 'pregnant', 'disability', 'livestock'] as const;

const EMPTY_HOUSEHOLD: Household = {
  people: 4, elderly: false, children: false, infants: false, pregnant: false, disability: false, medical_needs: '', livestock: false,
};

function WarningRow({ w, busy, onCancel }: { w: ReadyWarning; busy: boolean; onCancel: (id: number) => void }) {
  const state = w.cancelled_at != null ? 'Cancelled' : w.active ? 'Active' : 'Expired';
  return (
    <tr>
      <td>
        <b>{w.headline}</b>
        <span className="muted small block">
          {w.source} · {SEVERITY_LABEL[w.severity]} · issued {whenLabel(w.issued_at)}
          {w.area ? ` · ${w.area}` : ''}
        </span>
      </td>
      <td>{whenLabel(w.expected_landfall_at)}</td>
      <td>{whenLabel(w.expires_at)}</td>
      <td><span className={`status-text ${w.active ? 'warn' : 'muted'}`}>{state}</span></td>
      <td>
        {w.active && (
          <button type="button" className="btn ghost sm" disabled={busy} onClick={() => onCancel(w.id)}>Cancel</button>
        )}
      </td>
    </tr>
  );
}

function IssueForm({ districtId, busy, onIssue }: {
  districtId: string;
  busy: boolean;
  onIssue: (w: NewWarning) => Promise<unknown>;
}) {
  const [source, setSource] = useState<WarningSource>('IMD');
  const [severity, setSeverity] = useState<WarningSeverity>('warning');
  const [headline, setHeadline] = useState('');
  const [landfall, setLandfall] = useState('');
  const [expires, setExpires] = useState('');
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const out = await onIssue({
      district: districtId, source, severity, headline: headline.trim(),
      expected_landfall_at: localInputToUnix(landfall), expires_at: localInputToUnix(expires),
    });
    if (out) {
      setHeadline('');
      setLandfall('');
      setExpires('');
    }
  };
  return (
    <form className="form-grid" onSubmit={submit} aria-label="Issue a warning">
      <label>Source
        <select value={source} onChange={(e) => setSource(e.target.value as WarningSource)}>
          {SOURCES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
      </label>
      <label>Severity
        <select value={severity} onChange={(e) => setSeverity(e.target.value as WarningSeverity)}>
          {SEVERITIES.map((s) => <option key={s} value={s}>{SEVERITY_LABEL[s]}</option>)}
        </select>
      </label>
      <label className="wide">Headline
        <input type="text" required maxLength={200} value={headline} placeholder="Cyclone warning for the Odisha coast" onChange={(e) => setHeadline(e.target.value)} />
      </label>
      <label>Expected landfall
        <input type="datetime-local" value={landfall} onChange={(e) => setLandfall(e.target.value)} />
      </label>
      <label>Expires
        <input type="datetime-local" value={expires} onChange={(e) => setExpires(e.target.value)} />
      </label>
      <div className="wide row-actions">
        <button type="submit" className="btn primary" disabled={busy || !headline.trim()}>Issue warning</button>
        <span className="muted small">Phones in this district start preloading on their next check.</span>
      </div>
    </form>
  );
}

function CapForm({ busy, onImport }: { busy: boolean; onImport: (xml: string) => Promise<CapImportResult | null> }) {
  const [xml, setXml] = useState('');
  const [result, setResult] = useState<CapImportResult | null>(null);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const out = await onImport(xml);
    setResult(out);
    if (out && out.imported.length > 0) setXml('');
  };
  return (
    <form onSubmit={submit} aria-label="Paste a CAP alert">
      <label className="block">Paste a CAP alert (IMD, NDMA SACHET or PAGASA, XML)
        <textarea rows={6} className="mono" value={xml} placeholder="<alert xmlns=&quot;urn:oasis:names:tc:emergency:cap:1.2&quot;>…" onChange={(e) => setXml(e.target.value)} />
      </label>
      <div className="row-actions">
        <button type="submit" className="btn soft" disabled={busy || !xml.trim()}>Import alert</button>
      </div>
      {result && (
        <p className="small" role="status">
          Imported {result.imported.length}.
          {result.skipped.map((s, i) => <span key={i} className="muted block">Skipped {s.identifier ?? 'alert'}: {s.reason}</span>)}
        </p>
      )}
    </form>
  );
}

function PlanPreview({ districtId }: { districtId: string }) {
  const [lang, setLang] = useState('en');
  const [h, setH] = useState<Household>(EMPTY_HOUSEHOLD);
  const [lat, setLat] = useState('');
  const [lon, setLon] = useState('');
  const [plan, setPlan] = useState<ReadyPlan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const num = (v: string) => (v.trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : undefined);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setPlan(await api.previewPlan({ lang, district: districtId, lat: num(lat), lon: num(lon), household: h }));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div>
      <form className="form-grid" onSubmit={submit} aria-label="Preview a household plan">
        <label>Language
          <select value={lang} onChange={(e) => setLang(e.target.value)}>
            {PLAN_LANGS.map(([code, name]) => <option key={code} value={code}>{name}</option>)}
          </select>
        </label>
        <label>People
          <input type="number" min={1} max={50} value={h.people} onChange={(e) => setH({ ...h, people: Math.max(1, Math.min(50, Number(e.target.value) || 1)) })} />
        </label>
        <label>Latitude (optional)
          <input type="text" inputMode="decimal" value={lat} onChange={(e) => setLat(e.target.value)} />
        </label>
        <label>Longitude (optional)
          <input type="text" inputMode="decimal" value={lon} onChange={(e) => setLon(e.target.value)} />
        </label>
        <div className="wide checks">
          {FLAGS.map((f) => (
            <label key={f} className="check">
              <input type="checkbox" checked={h[f]} onChange={(e) => setH({ ...h, [f]: e.target.checked })} /> {f}
            </label>
          ))}
        </div>
        <label className="wide">Medical needs (free text, up to 100 characters)
          <input type="text" maxLength={100} value={h.medical_needs} onChange={(e) => setH({ ...h, medical_needs: e.target.value })} />
        </label>
        <div className="wide row-actions">
          <button type="submit" className="btn primary" disabled={busy}>{busy ? 'Writing…' : 'Preview plan'}</button>
        </div>
      </form>
      {error && <p className="err" role="alert">{error}</p>}
      {plan && (
        <div className="plan" aria-live="polite">
          <p className="muted small">
            Written by {plan.source === 'gemini' ? 'Gemini (checked against the shelter list)' : 'the template'}
            {plan.steps_lang !== plan.lang ? ` · no ${plan.lang} template yet, showing ${plan.steps_lang}` : ''}
          </p>
          <ol lang={plan.steps_lang}>{plan.steps.map((s, i) => <li key={i}>{s}</li>)}</ol>
          <p className="small">
            Nearest:{' '}
            {plan.nearest_shelters.map((s) => `${s.name} (${s.distance_km} km)`).join(' · ') || 'none'}
          </p>
        </div>
      )}
    </div>
  );
}

export function ReadyView({ scope, districtId }: { scope: string; districtId: string | null }) {
  const { warnings, shelters, loaded, busy, error, issue, cancel, importCap } = useReady(districtId);
  const active = warnings.filter((w) => w.active);
  return (
    <div className="page">
      <div className="page-head">
        <h1>Ready</h1>
        <p className="muted">
          {scope} · before landfall, phones with signal preload the shelter list and a plan for each household. Phones start
          once a warning is active here. {active.length ? `${active.length} active.` : 'No active warning.'}
        </p>
        {error && <p className="err" role="alert">{error}</p>}
      </div>
      {!districtId ? (
        <p className="empty">Warnings are per district. Choose a district above.</p>
      ) : (
        <>
          <div className="card">
            <h2>Warnings</h2>
            {!loaded ? <p className="muted">Loading…</p> : warnings.length === 0 ? <p className="empty">No warnings issued for this district.</p> : (
              <table className="grid">
                <thead><tr><th>Warning</th><th>Landfall</th><th>Expires</th><th>Status</th><th /></tr></thead>
                <tbody>{warnings.map((w) => <WarningRow key={w.id} w={w} busy={busy} onCancel={(id) => void cancel(id)} />)}</tbody>
              </table>
            )}
          </div>
          <div className="report-grid">
            <div className="card">
              <h2>Issue a warning</h2>
              <IssueForm districtId={districtId} busy={busy} onIssue={issue} />
            </div>
            <div className="card">
              <h2>Import a CAP alert</h2>
              <CapForm busy={busy} onImport={importCap} />
            </div>
          </div>
          <div className="card">
            <div className="card-head">
              <h2>Shelters</h2>
              {shelters.some((s) => s.demo) && (
                <span className="approx-badge" title="Placeholder list: replace with the official district list before a pilot">Demo data</span>
              )}
            </div>
            {shelters.length === 0 ? <p className="empty">{loaded ? 'No shelters loaded.' : 'Loading…'}</p> : (
              <table className="grid">
                <thead><tr><th>Name</th><th>Type</th><th>Block</th><th className="num">Capacity</th><th>Facilities</th></tr></thead>
                <tbody>
                  {shelters.map((s) => (
                    <tr key={s.id}>
                      <td>{s.name_en}{s.name !== s.name_en && <span className="muted small block">{s.name}</span>}</td>
                      <td>{KIND_LABEL[s.kind] ?? s.kind}</td>
                      <td>{s.block}</td>
                      <td className="num mono">{s.capacity}</td>
                      <td className="muted">{s.facilities.join(', ') || '–'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          <div className="card">
            <h2>Preview a household plan</h2>
            <PlanPreview districtId={districtId} />
          </div>
        </>
      )}
    </div>
  );
}
