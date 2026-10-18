import type { WarningSeverity } from '../types';

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

/** Languages the phone app ships (the plan is written in the one the household picks). */
export const PLAN_LANGS = [
  ['en', 'English'], ['hi', 'Hindi'], ['or', 'Odia'], ['tl', 'Filipino'], ['war', 'Waray'],
] as const;

export function whenLabel(sec: number | null | undefined): string {
  if (sec == null) return '–';
  return new Date(sec * 1000).toLocaleString([], { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
}
