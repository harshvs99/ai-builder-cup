import { describe, expect, it } from 'vitest';
import { detectNewTickets } from './diff';
import { mkTicket } from '../test-utils';

describe('detectNewTickets', () => {
  const list = [mkTicket({ ticket_no: 1 }), mkTicket({ ticket_no: 2 })];

  it('reports nothing on the first poll', () => {
    expect(detectNewTickets(null, list)).toEqual([]);
  });
  it('reports tickets absent from the previous poll', () => {
    expect(detectNewTickets(new Set([1]), list)).toEqual([2]);
  });
  it('reports nothing when unchanged', () => {
    expect(detectNewTickets(new Set([1, 2]), list)).toEqual([]);
  });
  it('ignores tickets that disappeared', () => {
    expect(detectNewTickets(new Set([1, 2, 9]), list)).toEqual([]);
  });
  it('treats everything as new after an empty previous poll', () => {
    expect(detectNewTickets(new Set(), list)).toEqual([1, 2]);
  });
});
