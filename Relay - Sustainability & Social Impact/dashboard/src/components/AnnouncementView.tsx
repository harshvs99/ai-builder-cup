import { useCallback, useEffect, useState } from 'react';
import { api } from '../api';
import { enabledCodes, nameList } from '../lib/languages';
import { useLanguages } from '../useLanguages';
import { ALL_DISTRICTS, districtName } from '../lib/districts';
import {
  approveBody, ANNOUNCEMENT_LANG_NAMES, draftRows, STATUS_LABEL, type DraftRow,
} from '../lib/announcements';
import { formatRelative } from '../lib/time';
import type { Announcement, AnnouncementLangStatus } from '../types';

const BADGE: Record<AnnouncementLangStatus, string> = { ok: 'ok', needs_review: 'warn', missing: 'muted' };
const errText = (e: unknown) => (e instanceof Error ? e.message : String(e));

export function AnnouncementView({ district, nowSec }: { district: string; nowSec: number }) {
  const [text, setText] = useState('');
  const [draft, setDraft] = useState<Announcement | null>(null);
  const [rows, setRows] = useState<DraftRow[]>([]);
  const [recent, setRecent] = useState<Announcement[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const all = district === ALL_DISTRICTS;
  const languages = useLanguages();
  const enabled = enabledCodes(languages);

  const reload = useCallback(() => {
    api.listAnnouncements().then(setRecent).catch((e) => setError(errText(e)));
  }, []);
  useEffect(reload, [reload]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(errText(e));
    } finally {
      setBusy(false);
    }
  };

  const makeDraft = () =>
    run(async () => {
      const b = await api.createAnnouncement({ district, text: text.trim() });
      setDraft(b);
      setRows(draftRows(b, enabled));
      reload();
    });

  const publish = () =>
    run(async () => {
      if (!draft) return;
      await api.approveAnnouncement(draft.id, approveBody(draft, rows));
      setDraft(null);
      setRows([]);
      setText('');
      reload();
    });

  const withdraw = (id: number) =>
    run(async () => {
      await api.withdrawAnnouncement(id);
      reload();
    });

  const setRow = (lang: string, patch: Partial<DraftRow>) =>
    setRows((rs) => rs.map((r) => (r.lang === lang ? { ...r, ...patch } : r)));
  const chosen = rows.filter((r) => r.include && r.text.trim()).length;

  return (
    <div className="page">
      <div className="page-head">
        <h1>Announcement</h1>
        <p className="muted">
          One public message to everyone in a district, in {nameList(languages)}. A model translates it,
          a second model call checks each translation against your text, and nothing is published until you approve.
        </p>
      </div>

      <div className="card">
        <h2>New message{all ? '' : ` for ${districtName(district)} district`}</h2>
        <textarea
          className="bc-input"
          rows={3}
          maxLength={600}
          placeholder="Cyclone shelter at Puri Zilla School is open. Drinking water at the bus stand from 14:00."
          aria-label="Message in English"
          value={text}
          onChange={(e) => setText(e.target.value)}
          disabled={all}
        />
        {all && <p className="muted small">Pick one district in the header first: an announcement goes to a single district.</p>}
        <div className="row-actions">
          <button type="button" className="btn primary" disabled={busy || all || !text.trim()} onClick={makeDraft}>
            {busy && !draft ? 'Translating…' : 'Draft translations'}
          </button>
        </div>
        {error && <p className="err" role="alert">{error}</p>}
      </div>

      {draft && (
        <div className="card">
          <div className="card-head">
            <h2>Review translations</h2>
            <span className="muted small">Tick what to publish. Edited text is re-checked for numbers and times.</span>
          </div>
          <table className="grid bc-table">
            <thead>
              <tr><th>Send</th><th>Language</th><th>Text</th><th>Back-translation</th><th>Status</th></tr>
            </thead>
            <tbody>
              {rows.map((row) => {
                const lang = row.lang;
                const t = draft.translations[lang];
                const isSource = lang === draft.source_lang;
                return (
                  <tr key={lang}>
                    <td>
                      <input
                        type="checkbox"
                        checked={row.include}
                        aria-label={`Publish ${ANNOUNCEMENT_LANG_NAMES[lang]}`}
                        onChange={(e) => setRow(lang, { include: e.target.checked })}
                      />
                    </td>
                    <td>{ANNOUNCEMENT_LANG_NAMES[lang]}{isSource && <span className="muted small block">source</span>}</td>
                    <td>
                      <textarea
                        className="bc-input"
                        rows={3}
                        lang={lang}
                        value={row.text}
                        readOnly={isSource}
                        aria-label={`${ANNOUNCEMENT_LANG_NAMES[lang]} text`}
                        onChange={(e) => setRow(lang, { text: e.target.value })}
                      />
                    </td>
                    <td className="muted">{isSource ? '–' : t.back_translation || '–'}</td>
                    <td>
                      <span className={`status-text ${BADGE[t.status]}`}>{STATUS_LABEL[t.status]}</span>
                      {t.issues && <span className="small block warn">{t.issues}</span>}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="row-actions">
            <button type="button" className="btn primary" disabled={busy || chosen === 0} onClick={publish}>
              Approve and publish ({chosen})
            </button>
            <button type="button" className="btn ghost" disabled={busy} onClick={() => { setDraft(null); setRows([]); }}>
              Discard draft
            </button>
          </div>
        </div>
      )}

      <div className="card">
        <h2>Recent announcements</h2>
        {recent.length === 0 ? <p className="empty">Nothing announcement yet.</p> : (
          <table className="grid">
            <thead><tr><th>Message</th><th>District</th><th>Languages</th><th>Status</th><th /></tr></thead>
            <tbody>
              {recent.slice(0, 20).map((b) => (
                <tr key={b.id}>
                  <td>{b.source_text}<span className="muted small block">{formatRelative(nowSec, b.created_at)}</span></td>
                  <td>{districtName(b.district)}</td>
                  <td className="mono">{b.approved_langs.length ? b.approved_langs.join(', ') : '–'}</td>
                  <td><span className={`status-text ${b.status === 'approved' ? 'ok' : 'muted'}`}>{b.status}</span></td>
                  <td>
                    {b.status === 'approved' && (
                      <button type="button" className="btn sm ghost" disabled={busy} onClick={() => withdraw(b.id)}>Withdraw</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
