import { describe, expect, it } from 'vitest';
import { enabledCodes, LANGUAGES, nameList, parseLanguages } from './languages';
import { PLAN_LANGS } from './ready';

describe('languages', () => {
  it('ships English and Hindi only by default (mirror of backend DEFAULT_ENABLED)', () => {
    expect(enabledCodes()).toEqual(['en', 'hi']);
    expect(LANGUAGES.map((l) => l.code)).toEqual(['en', 'hi', 'or', 'tl', 'war', 'ceb']);
    expect(nameList()).toBe('English and Hindi');
  });

  it('plan languages follow the enabled set', () => {
    expect(PLAN_LANGS.map(([code]) => code)).toEqual(['en', 'hi']);
  });

  it('honours the backend list and keeps English', () => {
    const l = parseLanguages([
      { code: 'en', english_name: 'English', native_name: 'English', enabled: true },
      { code: 'or', english_name: 'Odia', native_name: 'ଓଡ଼ିଆ', enabled: true },
      { code: 'hi', english_name: 'Hindi', native_name: 'हिन्दी', enabled: false },
    ]);
    expect(l && enabledCodes(l)).toEqual(['en', 'or']);
    expect(l && nameList(l)).toBe('English and Odia');
    expect(enabledCodes([{ code: 'hi', english_name: 'Hindi', native_name: 'x', enabled: true }])).toEqual(['en', 'hi']);
  });

  it('rejects a body that is not a language list', () => {
    expect(parseLanguages({ nope: 1 })).toBeNull();
    expect(parseLanguages([])).toBeNull();
    expect(parseLanguages([{ code: 1 }])).toBeNull();
  });
});
