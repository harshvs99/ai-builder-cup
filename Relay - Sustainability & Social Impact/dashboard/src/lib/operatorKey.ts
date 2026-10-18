/** Operator key for the control-room routes (backend RELAY_OPERATOR_KEY). Optional: with no key
 *  available no header is sent, which is what an open backend expects. */
export const OPERATOR_KEY_STORAGE = 'relay.operatorKey';

type KeyStorage = Pick<Storage, 'getItem' | 'setItem'>;

export interface OperatorKey {
  key: string | null;
  /** True when the key came from the `?key=` URL param (the caller should strip it from the URL). */
  fromUrl: boolean;
}

/** Precedence: `?key=` URL param (persisted to storage), then storage, then the build-time env key.
 *  Storage may be missing or throw (private windows), so every access is guarded. */
export function resolveOperatorKey(
  envKey: string | undefined,
  search: string,
  storage?: KeyStorage | null,
): OperatorKey {
  const fromUrl = new URLSearchParams(search).get('key')?.trim();
  if (fromUrl) {
    try {
      storage?.setItem(OPERATOR_KEY_STORAGE, fromUrl);
    } catch {
      /* storage unavailable: the key still works for this page load */
    }
    return { key: fromUrl, fromUrl: true };
  }
  let stored: string | null = null;
  try {
    stored = storage?.getItem(OPERATOR_KEY_STORAGE)?.trim() || null;
  } catch {
    /* ignore */
  }
  return { key: stored || envKey?.trim() || null, fromUrl: false };
}

/** Browser wiring: resolve against the real URL and storage, then drop `?key=` from the address bar. */
export function loadOperatorKey(): string | null {
  if (typeof window === 'undefined') return null;
  let storage: KeyStorage | null = null;
  try {
    storage = window.localStorage;
  } catch {
    /* blocked */
  }
  const { key, fromUrl } = resolveOperatorKey(import.meta.env.VITE_OPERATOR_KEY, window.location.search, storage);
  if (fromUrl) {
    const url = new URL(window.location.href);
    url.searchParams.delete('key');
    window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash);
  }
  return key;
}
