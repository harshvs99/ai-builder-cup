import type { Category, Tier } from '../types';

export const CATEGORY_META: Record<Category, { icon: string; label: string; color: string }> = {
  MEDICAL: { icon: '🩺', label: 'Medical', color: '#e5484d' },
  RESCUE: { icon: '🆘', label: 'Rescue', color: '#f76b15' },
  WATER: { icon: '💧', label: 'Water', color: '#0090ff' },
  FOOD: { icon: '🍚', label: 'Food', color: '#ffb224' },
  SHELTER: { icon: '🏠', label: 'Shelter', color: '#8e4ec6' },
  MISSING_PERSON: { icon: '🔎', label: 'Missing person', color: '#12a594' },
  OTHER: { icon: '❓', label: 'Other', color: '#889096' },
};

export function categoryMeta(c: string) {
  return CATEGORY_META[c as Category] ?? CATEGORY_META.OTHER;
}

/**
 * Fallback only: the backend supplies priority_tier. Thresholds are an
 * assumption (score scale is backend-defined).
 */
export function deriveTier(score: number): Tier {
  if (score >= 8) return 'P1';
  if (score >= 5) return 'P2';
  if (score >= 3) return 'P3';
  return 'P4';
}
