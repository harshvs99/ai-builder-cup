import { describe, expect, it, vi } from 'vitest';
import { OPERATOR_KEY_STORAGE, resolveOperatorKey } from './operatorKey';

const memory = (init: Record<string, string> = {}) => {
  const m = new Map(Object.entries(init));
  return {
    getItem: vi.fn((k: string) => m.get(k) ?? null),
    setItem: vi.fn((k: string, v: string) => void m.set(k, v)),
  };
};

describe('resolveOperatorKey', () => {
  it('returns no key when nothing is configured', () => {
    expect(resolveOperatorKey(undefined, '', memory())).toEqual({ key: null, fromUrl: false });
    expect(resolveOperatorKey('  ', '?x=1', null)).toEqual({ key: null, fromUrl: false });
  });

  it('uses the build-time env key', () => {
    expect(resolveOperatorKey('envkey', '', memory())).toEqual({ key: 'envkey', fromUrl: false });
  });

  it('takes ?key= over everything and persists it', () => {
    const s = memory({ [OPERATOR_KEY_STORAGE]: 'old' });
    expect(resolveOperatorKey('envkey', '?a=1&key=urlkey', s)).toEqual({ key: 'urlkey', fromUrl: true });
    expect(s.setItem).toHaveBeenCalledWith(OPERATOR_KEY_STORAGE, 'urlkey');
  });

  it('falls back to the persisted key before the env key', () => {
    const s = memory({ [OPERATOR_KEY_STORAGE]: 'stored' });
    expect(resolveOperatorKey('envkey', '', s)).toEqual({ key: 'stored', fromUrl: false });
  });

  it('survives storage that throws', () => {
    const broken = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(resolveOperatorKey('envkey', '?key=k', broken)).toEqual({ key: 'k', fromUrl: true });
    expect(resolveOperatorKey('envkey', '', broken)).toEqual({ key: 'envkey', fromUrl: false });
  });

  it('ignores an empty ?key=', () => {
    expect(resolveOperatorKey('envkey', '?key=', memory())).toEqual({ key: 'envkey', fromUrl: false });
  });
});
