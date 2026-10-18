import type { DamageArea, DamageLevel, RecoverReport } from '../types';

/** Damage colours: the urgency palette (styles.css --life, --urgent, --soon, --wait), one step per level. */
export const DAMAGE_COLOR: Record<DamageLevel, string> = {
  severe: '#e5484d',
  moderate: '#f08c2e',
  light: '#e9c23b',
  none: '#9aa3ad',
};

export const DAMAGE_LABEL: Record<DamageLevel, string> = {
  severe: 'Severe',
  moderate: 'Moderate',
  light: 'Light',
  none: 'None',
};

/** Legend entries in severity order. */
export const DAMAGE_LEGEND: ReadonlyArray<{ level: DamageLevel; label: string; color: string }> = (
  ['severe', 'moderate', 'light', 'none'] as const
).map((level) => ({ level, label: DAMAGE_LABEL[level], color: DAMAGE_COLOR[level] }));

const RANK: Record<DamageLevel, number> = { severe: 0, moderate: 1, light: 2, none: 3 };

export type AreaSort = 'damage' | 'reports' | 'reached' | 'name';

/** Areas ordered for the table: worst damage first by default; ties break on reports, then name. */
export function sortAreas(areas: readonly DamageArea[], by: AreaSort = 'damage'): DamageArea[] {
  const cmp = (a: DamageArea, b: DamageArea): number => {
    if (by === 'name') return a.name.localeCompare(b.name);
    if (by === 'reports' && a.reports !== b.reports) return b.reports - a.reports;
    if (by === 'reached' && a.households_reached !== b.households_reached) return b.households_reached - a.households_reached;
    if (by === 'damage' && RANK[a.damage_level] !== RANK[b.damage_level]) return RANK[a.damage_level] - RANK[b.damage_level];
    return b.reports - a.reports || a.name.localeCompare(b.name);
  };
  return [...areas].sort(cmp);
}

/** Circle radius in px for an area, growing with the square root of its reports (area ~ reports), clamped. */
export function areaRadius(reports: number): number {
  return Math.round(Math.min(34, Math.max(9, 7 + 5 * Math.sqrt(Math.max(0, reports)))));
}

/** Areas with a position as GeoJSON points for the map; Unlocated (no centroid) is left out. */
export function areaFeatures(areas: readonly DamageArea[], selected: string | null): GeoJSON.FeatureCollection {
  return {
    type: 'FeatureCollection',
    features: areas
      .filter((a) => a.lat != null && a.lon != null)
      .map((a) => ({
        type: 'Feature' as const,
        geometry: { type: 'Point' as const, coordinates: [a.lon!, a.lat!] },
        properties: { id: a.area_id, name: a.name, level: a.damage_level, r: areaRadius(a.reports), selected: a.area_id === selected ? 1 : 0 },
      })),
  };
}

/** Same slug as the backend's download names, so the file is named the same whichever side names it. */
export function slug(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

/** `relay-evidence-ticket-101.json`, `relay-evidence-area-place-jagannath-temple.json`. */
export function evidenceFileName(kind: 'ticket' | 'area', ident: string | number): string {
  return `relay-evidence-${kind}-${slug(String(ident))}.json`;
}

export function evidenceIndexFileName(district: string): string {
  return `relay-evidence-index-${slug(district)}.json`;
}

/** The report's list of ticket digests as a JSON document: what an auditor checks the packs against. */
export function evidenceIndexJson(report: RecoverReport): string {
  return JSON.stringify(
    {
      schema: 'relay.evidence.index.v1',
      generated_at: report.generated_at,
      district: report.district,
      algorithm: 'sha256',
      evidence_digests: report.evidence_digests,
    },
    null,
    2,
  );
}

/** Text for the clipboard: both languages under a heading line. */
export function reportClipboardText(report: RecoverReport, scope: string): string {
  const when = new Date(report.generated_at * 1000).toLocaleString();
  return `Relay relief and damage report, ${scope}, ${when}\n\n${report.text_en}\n\n${report.text_local}`;
}

/** "3 min" / "1 h 5 min" for a minutes figure; "–" when there is none. */
export function minutesLabel(min: number | null | undefined): string {
  if (min == null || !Number.isFinite(min)) return '–';
  const m = Math.round(min);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h${m % 60 ? ` ${m % 60} min` : ''}`;
}

export function percentLabel(share: number | null | undefined): string {
  return share == null || !Number.isFinite(share) ? '–' : `${Math.round(share * 100)}%`;
}

/** Hand a blob to the browser as a download. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
