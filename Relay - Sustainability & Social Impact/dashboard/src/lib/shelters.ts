import type { ShelterEntry } from '../types';

export const ENTRY_KINDS: [string, string][] = [
  ['cyclone_shelter', 'Cyclone shelter'], ['school', 'School'], ['relief_camp', 'Relief camp'], ['hospital', 'Hospital'], ['other', 'Other'],
];

/** Document for the Google Search Suggestions iframe: Google's own markup and styles, links open in a new tab. */
export function suggestionsDoc(html: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><base target="_blank"><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0">${html}</body></html>`;
}

export function emptyEntry(id: string): ShelterEntry {
  return {
    id, name: '', name_local: null, kind: 'cyclone_shelter', address: '', block: null, capacity: null, lat: null, lon: null,
    coords_from: 'none', source_url: null, verified: false, issues: [],
  };
}

/** Next free `eN` id for a hand-added row (the server keeps ids of the form eN). */
export function nextEntryId(entries: ShelterEntry[]): string {
  const max = entries.reduce((m, e) => Math.max(m, Number(/^e(\d+)$/.exec(e.id)?.[1] ?? 0)), 0);
  return `e${max + 1}`;
}

export function parseCoord(v: string): number | null {
  const t = v.trim();
  return t !== '' && Number.isFinite(Number(t)) ? Number(t) : null;
}

/** Entries that publishing would include: verified and with coordinates (the server also checks the district box). */
export function publishableCount(entries: ShelterEntry[]): number {
  return entries.filter((e) => e.verified && e.lat != null && e.lon != null).length;
}
