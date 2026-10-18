import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, createClient } from './api';
import { mkTicket } from './test-utils';

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

afterEach(() => vi.unstubAllGlobals());

describe('api client', () => {
  it('lists tickets from {tickets: [...]} and hits the right URL', async () => {
    const f = vi.fn().mockResolvedValue(json({ tickets: [mkTicket({ ticket_no: 7 })] }));
    vi.stubGlobal('fetch', f);
    const out = await createClient('http://x:1/').listTickets();
    expect(out[0]?.ticket_no).toBe(7);
    expect(f.mock.calls[0]?.[0]).toBe('http://x:1/v1/tickets');
  });

  it('reads the language registry and tolerates a bad body', async () => {
    const f = vi.fn().mockResolvedValueOnce(json([{ code: 'en', english_name: 'English', native_name: 'English', enabled: true }]))
      .mockResolvedValueOnce(json({ nope: 1 }));
    vi.stubGlobal('fetch', f);
    const c = createClient('http://x');
    expect((await c.listLanguages())?.[0]?.code).toBe('en');
    expect(f.mock.calls[0]?.[0]).toBe('http://x/v1/languages');
    expect(await c.listLanguages()).toBeNull();
  });

  it('POSTs approve', async () => {
    const f = vi.fn().mockResolvedValue(json(mkTicket({ status: 'DISPATCHED' })));
    vi.stubGlobal('fetch', f);
    const t = await createClient('http://x').approve(3);
    expect(t.status).toBe('DISPATCHED');
    expect(f.mock.calls[0]?.[0]).toBe('http://x/v1/tickets/3/approve');
    expect(f.mock.calls[0]?.[1]).toMatchObject({ method: 'POST' });
  });

  it('sends X-Operator-Key only when a key is configured', async () => {
    const f = vi.fn().mockImplementation(async () => json({ tickets: [] }));
    vi.stubGlobal('fetch', f);
    await createClient('http://x', 'k1').listTickets();
    await createClient('http://x', 'k1').approve(3);
    await createClient('http://x').listTickets();
    expect(f.mock.calls[0]?.[1]?.headers).toMatchObject({ 'X-Operator-Key': 'k1' });
    expect(f.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', headers: { 'X-Operator-Key': 'k1' } });
    expect(f.mock.calls[2]?.[1]?.headers).toBeUndefined();
  });

  it('talks to the Ready endpoints with the operator key', async () => {
    const f = vi.fn().mockImplementation(async () => json({ warnings: [], shelters: [], imported: [], skipped: [] }));
    vi.stubGlobal('fetch', f);
    const c = createClient('http://x', 'k1');
    expect(await c.listWarnings('puri')).toEqual([]);
    expect(await c.listShelters('delhi')).toEqual([]);
    await c.importCap('puri', '<alert/>');
    await c.cancelWarning(4);
    expect(f.mock.calls.map((x) => x[0])).toEqual([
      'http://x/v1/ready/warnings?district=puri',
      'http://x/v1/ready/shelters?district=delhi',
      'http://x/v1/ready/warnings/import-cap',
      'http://x/v1/ready/warnings/4/cancel',
    ]);
    expect(JSON.parse(f.mock.calls[2]?.[1]?.body as string)).toEqual({ district: 'puri', cap_xml: '<alert/>' });
    expect(f.mock.calls[3]?.[1]).toMatchObject({ method: 'POST', headers: { 'X-Operator-Key': 'k1' } });
  });

  it('talks to the shelter list endpoints', async () => {
    const f = vi.fn().mockImplementation(async () => json({ lists: [], id: 3, published: 1, skipped: [] }));
    vi.stubGlobal('fetch', f);
    const c = createClient('http://x', 'k1');
    expect(await c.listShelterLists('puri')).toEqual([]);
    await c.discoverShelters('puri');
    await c.saveShelterList(3, []);
    await c.publishShelterList(3, 'checked');
    expect(f.mock.calls.map((x) => [x[0], x[1]?.method])).toEqual([
      ['http://x/v1/ready/shelter-lists?district=puri', undefined],
      ['http://x/v1/ready/shelter-lists/discover', 'POST'],
      ['http://x/v1/ready/shelter-lists/3', 'PATCH'],
      ['http://x/v1/ready/shelter-lists/3/publish', 'POST'],
    ]);
    expect(f.mock.calls[2]?.[1]?.headers).toMatchObject({ 'X-Operator-Key': 'k1' });
  });

  it('wraps network failures as ApiError(status 0)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const err = await createClient('http://x').getStats().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(0);
    expect(err.message).toMatch(/unreachable/i);
  });

  it('surfaces HTTP errors with the server detail', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ detail: 'ticket not found' }, 404)));
    const err = await createClient('http://x').approve(99).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(404);
    expect(err.message).toContain('ticket not found');
  });

  it('handles non-JSON error bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('boom', { status: 500 })));
    const err = await createClient('http://x').getTicket(1).catch((e) => e);
    expect(err.status).toBe(500);
    expect(err.message).toContain('boom');
  });

  it('rejects invalid JSON and malformed list bodies', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>', { status: 200 })));
    await expect(createClient('http://x').getStats()).rejects.toThrow(/Invalid JSON/);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ nope: 1 })));
    await expect(createClient('http://x').listTickets()).rejects.toThrow(/Malformed/);
  });
});
