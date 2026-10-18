import type { Announcement, AnnouncementLang, AnnouncementLangStatus, AnnouncementTranslation } from '../types';

export const ANNOUNCEMENT_LANGS: readonly AnnouncementLang[] = ['en', 'hi', 'or', 'tl', 'war'];
export const ANNOUNCEMENT_LANG_NAMES: Record<AnnouncementLang, string> = {
  en: 'English', hi: 'Hindi', or: 'Odia', tl: 'Filipino', war: 'Waray',
};
export const STATUS_LABEL: Record<AnnouncementLangStatus, string> = { ok: 'ok', needs_review: 'needs review', missing: 'missing' };

/** One editable row of the review table. */
export interface DraftRow {
  lang: AnnouncementLang;
  /** Current (possibly edited) text. */
  text: string;
  include: boolean;
}

/** Only languages the checks passed start ticked; needs-review and missing ones are the human's call. */
export function defaultInclude(t: Pick<AnnouncementTranslation, 'status' | 'text'>): boolean {
  return t.status === 'ok' && t.text.trim() !== '';
}

/** Rows for the languages the draft holds, limited to the enabled ones when given. */
export function draftRows(b: Announcement, enabled?: readonly string[]): DraftRow[] {
  return ANNOUNCEMENT_LANGS.filter((lang) => b.translations[lang] && (!enabled || enabled.includes(lang))).map((lang) => {
    const t = b.translations[lang];
    return { lang, text: t.text, include: defaultInclude(t) };
  });
}

/** Approve body: ticked languages, plus an edit for every ticked row whose text was changed
 *  (the backend re-checks edits and rejects the ones that fail). */
export function approveBody(b: Announcement, rows: readonly DraftRow[]): { langs: string[]; edits: Record<string, string> } {
  const on = rows.filter((r) => r.include && r.text.trim() !== '');
  const edits: Record<string, string> = {};
  for (const r of on) {
    if (r.lang !== b.source_lang && r.text.trim() !== b.translations[r.lang].text.trim()) edits[r.lang] = r.text.trim();
  }
  return { langs: on.map((r) => r.lang), edits };
}
