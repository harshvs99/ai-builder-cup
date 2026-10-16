import type { Ticket } from './types';

export function mkTicket(over: Partial<Ticket> = {}): Ticket {
  return {
    ticket_no: 1,
    category: 'MEDICAL',
    item: 'insulin',
    landmark: 'school',
    summary: 'needs insulin',
    lat: 20.29,
    lon: 85.82,
    urgency: 3,
    people: 1,
    vuln_flags: [],
    lang: 'en',
    report_count: 1,
    priority_score: 5,
    status: 'OPEN',
    first_reported_at: 1000,
    max_hops: 1,
    gateways: [],
    msg_ids: [],
    ...over,
  };
}
