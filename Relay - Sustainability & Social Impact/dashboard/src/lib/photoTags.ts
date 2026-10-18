import { PHOTO_TAGS, type PhotoTag, type Ticket } from '../types';

/** Operator-facing names for the photo tag table (SPEC §5.1). */
export const PHOTO_TAG_LABEL: Record<PhotoTag, string> = {
  floodwater: 'Floodwater',
  house_damaged: 'House damaged',
  house_collapsed: 'House collapsed',
  roof_damaged: 'Roof damaged',
  person_injured: 'Person injured',
  people_stranded: 'People stranded',
  road_blocked: 'Road blocked',
  tree_fallen: 'Tree fallen',
  power_line_down: 'Power line down',
  boat_damaged: 'Boat damaged',
  crops_damaged: 'Crops damaged',
  livestock_lost: 'Livestock lost',
  vehicle_damaged: 'Vehicle damaged',
  fire: 'Fire',
  landslide: 'Landslide',
};

/** How much a tag should draw the eye: life safety first, then access and structure, then property. */
export type TagSeverity = 'life' | 'hazard' | 'damage';

export const TAG_SEVERITY: Record<PhotoTag, TagSeverity> = {
  person_injured: 'life',
  people_stranded: 'life',
  house_collapsed: 'life',
  fire: 'life',
  landslide: 'hazard',
  floodwater: 'hazard',
  power_line_down: 'hazard',
  road_blocked: 'hazard',
  tree_fallen: 'hazard',
  house_damaged: 'damage',
  roof_damaged: 'damage',
  boat_damaged: 'damage',
  crops_damaged: 'damage',
  livestock_lost: 'damage',
  vehicle_damaged: 'damage',
};

const SEVERITY_ORDER: TagSeverity[] = ['life', 'hazard', 'damage'];

export interface TagChip {
  tag: PhotoTag;
  label: string;
  severity: TagSeverity;
  /** How many of the ticket's reports carried this tag. */
  count: number;
}

export function isPhotoTag(k: string): k is PhotoTag {
  return (PHOTO_TAGS as readonly string[]).includes(k);
}

/** The ticket's tags as chips: most severe group first, then most-reported, then table order. Unknown keys are dropped. */
export function photoTagChips(photoTags: Ticket['photo_tags']): TagChip[] {
  const chips: TagChip[] = [];
  for (const [k, n] of Object.entries(photoTags ?? {})) {
    if (!isPhotoTag(k) || !n || n < 1) continue;
    chips.push({ tag: k, label: PHOTO_TAG_LABEL[k], severity: TAG_SEVERITY[k], count: n });
  }
  return chips.sort(
    (a, b) =>
      SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity) ||
      b.count - a.count ||
      PHOTO_TAGS.indexOf(a.tag) - PHOTO_TAGS.indexOf(b.tag),
  );
}

export function hasPhotoTags(t: Pick<Ticket, 'photo_tags'>): boolean {
  return photoTagChips(t.photo_tags).length > 0;
}

/** Tags grouped by severity, in display order; empty groups are left out. */
export function groupBySeverity(chips: TagChip[]): { severity: TagSeverity; chips: TagChip[] }[] {
  return SEVERITY_ORDER.map((severity) => ({ severity, chips: chips.filter((c) => c.severity === severity) })).filter(
    (g) => g.chips.length > 0,
  );
}

/** Tooltip text for the queue indicator, e.g. "Photo tags: People stranded, Floodwater". */
export function photoTagSummary(photoTags: Ticket['photo_tags']): string {
  return `Photo tags: ${photoTagChips(photoTags).map((c) => c.label).join(', ')}`;
}
