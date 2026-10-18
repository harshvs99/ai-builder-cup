import { describe, expect, it } from 'vitest';
import { PHOTO_TAGS } from '../types';
import {
  PHOTO_TAG_LABEL, TAG_SEVERITY, groupBySeverity, hasPhotoTags, isPhotoTag, photoTagChips, photoTagSummary,
} from './photoTags';
import { mkTicket } from '../test-utils';

describe('photo tag table', () => {
  it('has a label and a severity for every tag, in SPEC bit order', () => {
    expect(PHOTO_TAGS).toHaveLength(15);
    expect(PHOTO_TAGS[0]).toBe('floodwater');
    expect(PHOTO_TAGS[14]).toBe('landslide');
    for (const k of PHOTO_TAGS) {
      expect(PHOTO_TAG_LABEL[k]).toMatch(/^[A-Z]/);
      expect(TAG_SEVERITY[k]).toBeDefined();
    }
  });

  it('recognises only table keys', () => {
    expect(isPhotoTag('fire')).toBe(true);
    expect(isPhotoTag('selfie')).toBe(false);
  });
});

describe('photoTagChips', () => {
  it('is empty when the ticket has no tags', () => {
    expect(photoTagChips(undefined)).toEqual([]);
    expect(photoTagChips({})).toEqual([]);
    expect(hasPhotoTags(mkTicket())).toBe(false);
  });

  it('orders by severity, then count, then table order, and keeps the counts', () => {
    const chips = photoTagChips({ roof_damaged: 3, floodwater: 1, people_stranded: 1, fire: 2, tree_fallen: 1 });
    expect(chips.map((c) => c.tag)).toEqual(['fire', 'people_stranded', 'floodwater', 'tree_fallen', 'roof_damaged']);
    expect(chips.find((c) => c.tag === 'roof_damaged')).toMatchObject({ label: 'Roof damaged', count: 3 });
  });

  it('drops unknown keys and non-positive counts instead of rendering them', () => {
    const chips = photoTagChips({ floodwater: 2, selfie: 4, fire: 0 } as never);
    expect(chips.map((c) => c.tag)).toEqual(['floodwater']);
  });
});

describe('groupBySeverity / photoTagSummary', () => {
  it('groups in life, hazard, damage order and skips empty groups', () => {
    const groups = groupBySeverity(photoTagChips({ boat_damaged: 1, person_injured: 1 }));
    expect(groups.map((g) => g.severity)).toEqual(['life', 'damage']);
  });

  it('summarises for a tooltip', () => {
    expect(photoTagSummary({ floodwater: 2, people_stranded: 1 })).toBe('Photo tags: People stranded, Floodwater');
  });
});
