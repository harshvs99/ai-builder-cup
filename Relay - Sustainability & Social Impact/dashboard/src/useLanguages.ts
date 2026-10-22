import { useEffect, useState } from 'react';
import { api } from './api';
import { LANGUAGES, type Language } from './lib/languages';

let cached: Language[] | null = null;

/** The language registry: the static mirror at first, then the backend's (which honours RELAY_LANGS). */
export function useLanguages(): readonly Language[] {
  const [langs, setLangs] = useState<readonly Language[]>(cached ?? LANGUAGES);
  useEffect(() => {
    if (cached) return;
    let stopped = false;
    api.listLanguages().then((l) => {
      if (l) cached = l;
      if (l && !stopped) setLangs(l);
    }).catch(() => { /* keep the static defaults */ });
    return () => { stopped = true; };
  }, []);
  return langs;
}
