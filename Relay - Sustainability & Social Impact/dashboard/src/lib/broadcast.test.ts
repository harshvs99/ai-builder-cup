import { describe, expect, it } from 'vitest';
import type { Broadcast, BroadcastLang, BroadcastTranslation } from '../types';
import { approveBody, defaultInclude, draftRows } from './broadcast';

const tr = (status: BroadcastTranslation['status'], text: string): BroadcastTranslation => ({
  text, back_translation: '', faithful: status === 'ok', issues: '', checks_passed: status === 'ok', status,
});
const bc: Broadcast = {
  id: 1, district: 'puri', source_text: 'Water at 14:00', source_lang: 'en', created_at: 0, status: 'draft',
  approved_at: null, approved_langs: [],
  translations: {
    en: tr('ok', 'Water at 14:00'), hi: tr('ok', 'hi 14:00'), or: tr('needs_review', 'or 15:00'),
    tl: tr('missing', ''), war: tr('ok', 'war 14:00'),
  } as Record<BroadcastLang, BroadcastTranslation>,
};

describe('broadcast helpers', () => {
  it('ticks only languages whose status is ok', () => {
    expect(draftRows(bc).filter((r) => r.include).map((r) => r.lang)).toEqual(['en', 'hi', 'war']);
    expect(defaultInclude(tr('ok', ' '))).toBe(false);
  });

  it('shows only the enabled languages', () => {
    expect(draftRows(bc, ['en', 'hi']).map((r) => r.lang)).toEqual(['en', 'hi']);
    expect(draftRows({ ...bc, translations: { en: bc.translations.en, hi: bc.translations.hi } as Broadcast['translations'] }).map((r) => r.lang)).toEqual(['en', 'hi']);
  });

  it('sends langs plus edits only for changed, ticked rows', () => {
    const rows = draftRows(bc).map((r) => (r.lang === 'or' ? { ...r, text: 'or 14:00', include: true } : r));
    expect(approveBody(bc, rows)).toEqual({ langs: ['en', 'hi', 'or', 'war'], edits: { or: 'or 14:00' } });
  });

  it('never sends an empty or unticked row', () => {
    const rows = draftRows(bc).map((r) => (r.lang === 'tl' ? { ...r, include: true } : r.lang === 'hi' ? { ...r, include: false } : r));
    expect(approveBody(bc, rows).langs).toEqual(['en', 'war']);
  });
});
