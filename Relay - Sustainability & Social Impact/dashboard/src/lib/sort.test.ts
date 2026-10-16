import { describe, expect, it } from 'vitest';
import { sortTickets, ticketTitle, tierOf } from './sort';
import { mkTicket } from '../test-utils';

describe('sortTickets', () => {
  it('orders by priority_score desc without mutating input', () => {
    const input = [
      mkTicket({ ticket_no: 1, priority_score: 2 }),
      mkTicket({ ticket_no: 2, priority_score: 9 }),
      mkTicket({ ticket_no: 3, priority_score: 5 }),
    ];
    const out = sortTickets(input);
    expect(out.map((t) => t.ticket_no)).toEqual([2, 3, 1]);
    expect(input.map((t) => t.ticket_no)).toEqual([1, 2, 3]);
  });

  it('breaks ties by longest waiting, then ticket_no', () => {
    const out = sortTickets([
      mkTicket({ ticket_no: 5, priority_score: 4, first_reported_at: 200 }),
      mkTicket({ ticket_no: 4, priority_score: 4, first_reported_at: 100 }),
      mkTicket({ ticket_no: 3, priority_score: 4, first_reported_at: 100 }),
    ]);
    expect(out.map((t) => t.ticket_no)).toEqual([3, 4, 5]);
  });
});

describe('tierOf / ticketTitle', () => {
  it('prefers backend tier, falls back to derived', () => {
    expect(tierOf(mkTicket({ priority_tier: 'P3', priority_score: 9 }))).toBe('P3');
    expect(tierOf(mkTicket({ priority_score: 9 }))).toBe('P1');
    expect(tierOf(mkTicket({ priority_score: 1 }))).toBe('P4');
  });
  it('falls back to item + landmark when summary is empty', () => {
    expect(ticketTitle(mkTicket({ summary: '  ', item: 'water', landmark: 'bridge' }))).toBe('water near bridge');
    expect(ticketTitle(mkTicket({ summary: 'hi' }))).toBe('hi');
  });
});
