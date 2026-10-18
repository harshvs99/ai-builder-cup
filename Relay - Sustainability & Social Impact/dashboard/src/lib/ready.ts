import type { WarningSeverity } from '../types';
import { enabledLanguages } from './languages';

/** Value of an `<input type="datetime-local">` (local time) as unix seconds; undefined when empty or invalid. */
export function localInputToUnix(v: string): number | undefined {
  if (!v) return undefined;
  const ms = new Date(v).getTime();
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : undefined;
}

export const SEVERITY_LABEL: Record<WarningSeverity, string> = { watch: 'Watch', warning: 'Warning', alert: 'Alert' };

export const KIND_LABEL: Record<string, string> = {
  cyclone_shelter: 'Cyclone shelter', school: 'School', relief_camp: 'Relief camp', hospital: 'Hospital',
};

/** Enabled languages (lib/languages.ts) the plan can be written in; the household picks one. */
export const PLAN_LANGS: readonly (readonly [string, string])[] =
  enabledLanguages().filter((l) => l.code !== 'ceb').map((l) => [l.code, l.english_name] as const);

export function whenLabel(sec: number | null | undefined): string {
  if (sec == null) return '–';
  return new Date(sec * 1000).toLocaleString([], { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
}
