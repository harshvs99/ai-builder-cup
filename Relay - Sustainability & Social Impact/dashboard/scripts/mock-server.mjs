// Tiny in-memory stand-in for the Relay backend (SPEC §7 dashboard endpoints).
// Usage: npm run mock   (PORT env overrides 8080)
import http from 'node:http';

const PORT = Number(process.env.PORT ?? 8080);
const now = () => Math.floor(Date.now() / 1000);
const tierOf = (s) => (s >= 8 ? 'P1' : s >= 5 ? 'P2' : s >= 3 ? 'P3' : 'P4');
const hex = (n) => Array.from({ length: n }, () => Math.floor(Math.random() * 16).toString(16)).join('');

const GATEWAYS = ['a1b2c3d4e5f60718', '0f1e2d3c4b5a6978', 'cafe0123babe4567'];

/** @type {Map<number, any>} */
const tickets = new Map();
let nextNo = 100;

function addTicket(f) {
  const no = nextNo++;
  const gw = f.gateways ?? [GATEWAYS[no % GATEWAYS.length]];
  const reported = f.first_reported_at ?? now();
  const count = f.report_count ?? 1;
  const t = {
    ticket_no: no,
    category: 'OTHER', item: null, landmark: null, summary: '', urgency: 1, people: 1,
    vuln_flags: [], lang: 'en', priority_reason: '', status: 'OPEN',
    max_hops: 1, msg_ids: [hex(16)], ...f,
    report_count: count, gateways: gw, first_reported_at: reported,
  };
  // Location quality (mirrors the backend): lat/lon null and has_location false when there is no fix.
  t.loc_acc ??= t.lat == null ? 0 : 1;
  t.has_location = t.loc_acc !== 0;
  t.loc_acc_m = [null, '<10 m', '<50 m', '<200 m', '<1 km', '≥1 km'][t.loc_acc];
  if (!t.has_location) { t.lat = null; t.lon = null; }
  t.priority_score ??= Math.round((t.urgency * 2 + Math.min(t.people, 6) * 0.4 + count * 0.5 + t.vuln_flags.length * 0.6) * 10) / 10;
  t.priority_tier = tierOf(t.priority_score);
  t.priority_reason ||= `urgency ${t.urgency}/3, ${t.people} people${t.vuln_flags.length ? ', ' + t.vuln_flags.join('+').toLowerCase() : ''}, ${count} report(s)`;
  t.dispatch_draft ??= {
    en: `Dispatch ${t.category.toLowerCase().replace('_', ' ')} support (${t.item ?? 'assistance'}) to ${t.landmark ?? 'reported location'} at ${t.has_location ? `${t.lat.toFixed(4)}, ${t.lon.toFixed(4)}` : 'unknown location (no GPS fix)'}.`,
    local: `[${t.lang}] ${t.summary}`,
  };
  t.packets = t.msg_ids.map((id, i) => ({
    msg_id: id, origin_node: hex(16), gateway_node: gw[i % gw.length],
    hops: Math.max(1, t.max_hops - i), loc_acc: t.loc_acc, received_at: reported + i * 20, summary: t.summary,
  }));
  tickets.set(no, t);
  return t;
}

const min = (m) => now() - m * 60;
const FIXTURES = [
  { category: 'MEDICAL', item: 'insulin', landmark: 'school', summary: 'mother needs insulin, diabetic', lat: 20.29612, lon: 85.82451, urgency: 3, people: 1, vuln_flags: ['ELDERLY'], lang: 'or', report_count: 3, max_hops: 2, first_reported_at: min(14) },
  { category: 'RESCUE', item: 'boat', landmark: 'Puri beach road', summary: 'family trapped on roof, water rising', lat: 19.8135, lon: 85.8312, urgency: 3, people: 5, vuln_flags: ['CHILD', 'TRAPPED'], lang: 'or', report_count: 5, max_hops: 4, first_reported_at: min(31) },
  { category: 'WATER', item: 'drinking water', landmark: 'temple', summary: 'no clean water for 2 days', lat: 20.2701, lon: 85.8402, urgency: 2, people: 20, lang: 'or', report_count: 2, max_hops: 3, first_reported_at: min(55) },
  { category: 'FOOD', item: 'rice', landmark: 'bus stand', summary: '12 people need food packets', lat: 20.3011, lon: 85.8157, urgency: 1, people: 12, lang: 'en', report_count: 1, max_hops: 1, first_reported_at: min(8) },
  { category: 'SHELTER', item: 'tent', landmark: 'cyclone shelter 4', summary: 'roof collapsed, need shelter', lat: 20.2488, lon: 85.7992, urgency: 2, people: 6, vuln_flags: ['CHILD'], lang: 'or', report_count: 1, max_hops: 2, first_reported_at: min(22) },
  { category: 'MEDICAL', item: 'wound dressing', landmark: 'market', summary: 'man with deep leg wound, bleeding', lat: 11.2438, lon: 125.0042, urgency: 3, people: 1, vuln_flags: ['INJURED'], lang: 'war', report_count: 2, max_hops: 3, first_reported_at: min(11) },
  { category: 'RESCUE', item: 'rope', landmark: 'Tacloban airport road', summary: 'elderly couple stuck on second floor', lat: 11.2271, lon: 125.0278, urgency: 3, people: 2, vuln_flags: ['ELDERLY', 'TRAPPED'], lang: 'tl', report_count: 4, max_hops: 5, first_reported_at: min(40) },
  { category: 'MISSING_PERSON', item: null, landmark: 'San Jose', summary: 'looking for 8-year-old boy, last seen near church', lat: 11.2354, lon: 125.0126, urgency: 2, people: 1, vuln_flags: ['CHILD'], lang: 'ceb', report_count: 1, max_hops: 2, first_reported_at: min(5) },
  // Phone with Location switched off: loc_acc 0, no coordinates (must stay off the map, in the queue)
  { category: 'MEDICAL', item: 'insulin', landmark: 'school', summary: 'mother needs insulin (location off)', lat: null, lon: null, loc_acc: 0, urgency: 3, people: 1, vuln_flags: ['ELDERLY'], lang: 'en', report_count: 1, max_hops: 1, first_reported_at: min(3) },
];
FIXTURES.forEach(addTicket);

// Live generator: a new ticket every 10 s.
const SEEDS = [
  { c: [20.29, 85.82], lang: 'or' },
  { c: [11.24, 125.0], lang: 'tl' },
];
const TEMPLATES = [
  ['MEDICAL', 'medicine', 'fever and vomiting, needs ORS and doctor', 2],
  ['WATER', 'drinking water', 'tank empty, queue of 30 people', 2],
  ['FOOD', 'food packets', 'no food since yesterday', 1],
  ['RESCUE', 'boat', 'stranded on rooftop, flood rising', 3],
  ['SHELTER', 'tarpaulin', 'house flooded, 4 people outdoors', 2],
  ['OTHER', 'phone charging', 'need power bank to contact family', 0],
];
const LANDMARKS = ['school', 'temple', 'bridge', 'market', 'clinic', 'river bank', 'church'];
const pick = (a) => a[Math.floor(Math.random() * a.length)];
setInterval(() => {
  const seed = pick(SEEDS);
  const [cat, item, summary, urgency] = pick(TEMPLATES);
  const t = addTicket({
    category: cat, item, summary, urgency, landmark: pick(LANDMARKS), lang: seed.lang,
    lat: seed.c[0] + (Math.random() - 0.5) * 0.12, lon: seed.c[1] + (Math.random() - 0.5) * 0.12,
    people: 1 + Math.floor(Math.random() * 8), max_hops: 1 + Math.floor(Math.random() * 4),
    vuln_flags: Math.random() < 0.3 ? [pick(['ELDERLY', 'CHILD', 'PREGNANT', 'INJURED'])] : [],
  });
  console.log(`[mock] new ticket #${t.ticket_no} ${t.category}`);
}, 10_000);

const summarize = ({ packets: _p, ...rest }) => rest;
const sorted = () => [...tickets.values()].sort((a, b) => b.priority_score - a.priority_score);

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, X-Relay-Key',
};
function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...CORS });
  res.end(JSON.stringify(body));
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');
  const path = url.pathname.replace(/\/+$/, '') || '/';
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }

  if (req.method === 'GET' && path === '/healthz') return send(res, 200, { ok: true });
  if (req.method === 'GET' && path === '/v1/tickets') return send(res, 200, { tickets: sorted().map(summarize) });
  if (req.method === 'GET' && path === '/v1/stats') {
    const open_by_category = {};
    for (const t of tickets.values()) if (t.status === 'OPEN') open_by_category[t.category] = (open_by_category[t.category] ?? 0) + 1;
    return send(res, 200, { open_by_category, gateways_online: GATEWAYS.length - 1, tickets: tickets.size });
  }
  const m = path.match(/^\/v1\/tickets\/(\d+)(\/approve)?$/);
  if (m) {
    const t = tickets.get(Number(m[1]));
    if (!t) return send(res, 404, { detail: 'ticket not found' });
    if (req.method === 'GET' && !m[2]) return send(res, 200, t);
    if (req.method === 'POST' && m[2]) {
      t.status = 'DISPATCHED';
      return send(res, 200, t);
    }
  }
  send(res, 404, { detail: 'not found' });
});

server.listen(PORT, () => console.log(`[mock] Relay mock backend on http://localhost:${PORT} (${tickets.size} tickets)`));
