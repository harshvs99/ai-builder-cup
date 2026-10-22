import { useMemo, useState } from 'react';
import { api } from '../api';
import { useDamage, useRecoverReport, type Theme } from '../hooks';
import { districtById, districtName } from '../lib/districts';
import { languageName } from '../lib/triage';
import {
  DAMAGE_COLOR, DAMAGE_LABEL, evidenceFileName, evidenceIndexFileName, evidenceIndexJson, minutesLabel, percentLabel,
  reportClipboardText, saveBlob, sortAreas, type AreaSort,
} from '../lib/recover';
import { Kpi } from './Views';
import { RecoverMap } from './RecoverMap';

const SORTS: ReadonlyArray<{ id: AreaSort; label: string }> = [
  { id: 'damage', label: 'Damage' },
  { id: 'reports', label: 'Reports' },
  { id: 'reached', label: 'Reached' },
];

export function RecoverView({ district, theme }: { district: string; theme: Theme }) {
  const { damage, error } = useDamage(district);
  const { report, busy, error: reportError, generate } = useRecoverReport(district);
  const [selected, setSelected] = useState<string | null>(null);
  const [sort, setSort] = useState<AreaSort>('damage');
  const [lang, setLang] = useState<'en' | 'local'>('en');
  const [copied, setCopied] = useState(false);
  const [packBusy, setPackBusy] = useState<string | null>(null);
  const [packError, setPackError] = useState<string | null>(null);

  const scope = districtName(district);
  const areas = useMemo(() => sortAreas(damage?.areas ?? [], sort), [damage, sort]);
  const t = damage?.totals;
  const chosen = areas.find((a) => a.area_id === selected) ?? null;
  const unlocated = damage?.areas.find((a) => a.area_id === 'unlocated');
  const text = report ? (lang === 'en' ? report.text_en : report.text_local) : '';

  const downloadPack = async (areaId: string) => {
    setPackBusy(areaId);
    setPackError(null);
    try {
      saveBlob(await api.areaEvidence(areaId, district), evidenceFileName('area', areaId));
    } catch (e) {
      setPackError(e instanceof Error ? e.message : String(e));
    } finally {
      setPackBusy(null);
    }
  };

  const copy = async () => {
    if (!report) return;
    try {
      await navigator.clipboard.writeText(reportClipboardText(report, scope));
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard blocked: nothing to do */
    }
  };

  const downloadIndex = () => {
    if (!report) return;
    saveBlob(new Blob([evidenceIndexJson(report)], { type: 'application/json' }), evidenceIndexFileName(district));
  };

  return (
    <div className="page">
      <div className="page-head">
        <h1>Recover</h1>
        <p className="muted">
          {scope} · after the towers return, every request and dispatch becomes evidence: damage by village, households
          reached, and a log for the state and insurers
          {damage ? ` · computed ${new Date(damage.generated_at * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })} from ${t!.tickets} requests` : ''}
        </p>
        <div className="row-actions">
          <button type="button" className="btn primary" disabled={busy || !damage} onClick={generate}>
            {busy ? 'Generating…' : report ? 'Regenerate report' : 'Generate report'}
          </button>
        </div>
        {error && <p className="err" role="alert">{error}</p>}
      </div>

      <div className="kpi-row">
        <Kpi label="Households reached" value={t?.households_reached ?? '–'} note={t ? `of ${t.households} reporting` : undefined} />
        <Kpi label="Needs confirmed closed" value={t?.needs_confirmed_closed ?? '–'} note={t ? `of ${t.tickets} requests` : undefined} />
        <Kpi label="Duplicates merged" value={t?.duplicates_merged ?? '–'} note="before dispatch" />
        <Kpi label="Median to dispatch" value={minutesLabel(t?.median_minutes_to_dispatch)} note="ask to approval" />
        <Kpi label="With usable location" value={percentLabel(t?.share_with_location)} note={t ? `${percentLabel(t.share_with_gps_fix)} GPS fix` : undefined} />
        <Kpi label="Houses damaged" value={t?.houses_damaged ?? '–'} note="photo tags and answers, unverified" tone={t?.houses_damaged ? 'risk' : ''} />
      </div>

      <div className="card">
        <div className="card-head">
          <h2>Damage map</h2>
          <span className="muted small">
            {unlocated ? `${unlocated.tickets} request${unlocated.tickets === 1 ? '' : 's'} without a position are counted below, not drawn` : 'click a circle to select the area'}
          </span>
        </div>
        <RecoverMap areas={damage?.areas ?? []} district={districtById(district)} theme={theme} selected={selected} onSelect={setSelected} />
        {chosen && (
          <p className="recover-selected">
            <i className="dot" style={{ background: DAMAGE_COLOR[chosen.damage_level] }} /> <b>{chosen.name}</b>
            <span className="muted"> · {chosen.damage_reason}</span>
          </p>
        )}
      </div>

      <div className="card">
        <div className="card-head">
          <h2>Areas</h2>
          <div className="seg" role="group" aria-label="Sort areas by">
            {SORTS.map((s) => (
              <button key={s.id} type="button" className={sort === s.id ? 'on' : ''} onClick={() => setSort(s.id)}>{s.label}</button>
            ))}
          </div>
        </div>
        {!damage ? (
          <p className="muted">{error ? 'Could not load the damage map.' : 'Loading…'}</p>
        ) : areas.length === 0 ? (
          <p className="empty">No requests yet.</p>
        ) : (
          <table className="grid">
            <thead>
              <tr>
                <th>Area</th><th>Damage</th><th className="num">Requests</th><th className="num">Reports</th>
                <th className="num">Merged</th><th className="num">Houses</th><th className="num">Reached</th>
                <th className="num">Closed</th><th>Evidence</th>
              </tr>
            </thead>
            <tbody>
              {areas.map((a) => (
                <tr key={a.area_id} className={a.area_id === selected ? 'row-selected' : ''}>
                  <td>
                    <button type="button" className="link" onClick={() => setSelected(a.area_id)}>{a.name}</button>
                    <span className="muted small block">{a.damage_reason}</span>
                  </td>
                  <td>
                    <span className="status-text" style={{ color: a.damage_level === 'light' || a.damage_level === 'none' ? undefined : DAMAGE_COLOR[a.damage_level] }}>
                      <i className="dot" style={{ background: DAMAGE_COLOR[a.damage_level] }} /> {DAMAGE_LABEL[a.damage_level]}
                    </span>
                  </td>
                  <td className="num mono">{a.tickets}</td>
                  <td className="num mono">{a.reports}</td>
                  <td className="num mono">{a.duplicates_merged}</td>
                  <td className="num mono">{a.houses_damaged}</td>
                  <td className="num mono">{a.households_reached} / {a.households}</td>
                  <td className="num mono">{a.needs_confirmed_closed}</td>
                  <td>
                    <button type="button" className="btn ghost sm" disabled={packBusy === a.area_id} onClick={() => downloadPack(a.area_id)}>
                      {packBusy === a.area_id ? 'Preparing…' : 'Evidence pack'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {packError && <p className="err" role="alert">{packError}</p>}
      </div>

      <div className="card summary-card">
        <div className="card-head">
          <h2>Relief and insurer report</h2>
          {report && (
            <div className="seg" role="group" aria-label="Language">
              <button type="button" className={lang === 'en' ? 'on' : ''} onClick={() => setLang('en')}>English</button>
              <button type="button" className={lang === 'local' ? 'on' : ''} onClick={() => setLang('local')}>{languageName(report.local_lang)}</button>
            </div>
          )}
        </div>
        {reportError && <p className="err" role="alert">{reportError}</p>}
        {report ? (
          <>
            {text.split(/\n{2,}/).map((para, i) => <p key={i} lang={lang === 'local' ? report.local_lang : 'en'}>{para}</p>)}
            <div className="row-actions">
              <button type="button" className="btn soft sm" onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>
              <button type="button" className="btn ghost sm" onClick={downloadIndex}>Download evidence index</button>
            </div>
            <p className="muted small">
              {report.model === 'gemini' ? 'Drafted by Gemini; every figure in the text is checked against the store.' : 'Template text from the store figures.'}{' '}
              Generated {new Date(report.generated_at * 1000).toLocaleString()} for {scope} and not stored.
              The index lists {report.evidence_digests.length} SHA-256 digests, one per request: they show an export was not edited
              afterwards, not who made it (nothing is signed yet). Photo tags are unverified observations from requesters' phones
              (no image is kept), locations may be approximate, and a household is one reporting phone.
            </p>
          </>
        ) : (
          <p className="muted">
            Generate a report for the state relief commissioner or an insurer from the current figures, in English and {languageName(district === 'delhi' ? 'hi' : 'or')}.
            It is produced on request and not stored.
          </p>
        )}
      </div>
    </div>
  );
}
