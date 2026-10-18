/**
 * Languages the control room can write in. The team only reviews English and Hindi, so only those
 * are enabled by default; the rest stay registered for when community reviewers switch them on.
 * The backend decides (`GET /v1/languages`, `RELAY_LANGS`); `LANGUAGES` below is the static mirror
 * used before that call returns or when it fails, and must equal the backend defaults
 * (`backend/app/languages.py`; keep the two in sync).
 */
export interface Language {
  code: string;
  english_name: string;
  native_name: string;
  enabled: boolean;
}

export const LANGUAGES: readonly Language[] = [
  { code: 'en', english_name: 'English', native_name: 'English', enabled: true },
  { code: 'hi', english_name: 'Hindi', native_name: 'हिन्दी', enabled: true },
  { code: 'or', english_name: 'Odia', native_name: 'ଓଡ଼ିଆ', enabled: false },
  { code: 'tl', english_name: 'Filipino', native_name: 'Filipino', enabled: false },
  { code: 'war', english_name: 'Waray', native_name: 'Winaray', enabled: false },
  { code: 'ceb', english_name: 'Cebuano', native_name: 'Cebuano', enabled: false },
];

export const enabledLanguages = (list: readonly Language[] = LANGUAGES): Language[] => list.filter((l) => l.enabled);

/** Codes of the enabled languages, always including English. */
export function enabledCodes(list: readonly Language[] = LANGUAGES): string[] {
  const codes = enabledLanguages(list).map((l) => l.code);
  return codes.includes('en') ? codes : ['en', ...codes];
}

/** "English, Hindi and Odia" for prose. */
export function nameList(list: readonly Language[] = LANGUAGES): string {
  const names = enabledLanguages(list).map((l) => l.english_name);
  return names.length < 2 ? names.join('') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/** Defensive parse of the `GET /v1/languages` body; null when it does not look right. */
export function parseLanguages(body: unknown): Language[] | null {
  if (!Array.isArray(body)) return null;
  const out: Language[] = [];
  for (const x of body) {
    const o = x as Partial<Language> | null;
    if (!o || typeof o.code !== 'string' || typeof o.english_name !== 'string') return null;
    out.push({ code: o.code, english_name: o.english_name, native_name: o.native_name ?? o.english_name, enabled: o.enabled === true });
  }
  return out.length ? out : null;
}
