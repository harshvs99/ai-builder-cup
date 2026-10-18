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
    hops: Math.max(1, t.max_hops - i), loc_acc: t.loc_acc, reported_at: reported, received_at: reported + 360 + i * 20, summary: t.summary,
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
  // No fix, but two nearby relay phones sent location hints: the backend adds an approximate mesh estimate
  // (has_location stays false; the dashboard draws a dashed circle, never a GPS dot).
  { category: 'WATER', item: 'drinking water', landmark: null, summary: 'no clean water, family of 6 (location off, mesh estimate)', lat: null, lon: null, loc_acc: 0, geo: { lat: 20.2763, lon: 85.8309, source: 'mesh', radius_m: 130, hint_count: 2 }, urgency: 2, people: 6, vuln_flags: ['CHILD'], lang: 'or', report_count: 1, max_hops: 2, first_reported_at: min(6) },
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

const TEAMS = [
  { id: 'boat-puri', name: 'Puri Rescue Boat 1', kind: 'boat', capacity: 3, base_lat: 19.8135, base_lon: 85.8312, status: 'free' },
  { id: 'med-pipili', name: 'Pipili Medical Team', kind: 'medical', capacity: 4, base_lat: 20.113, base_lon: 85.833, status: 'free' },
  { id: 'sup-nimapara', name: 'Nimapara Supply Team', kind: 'supply', capacity: 5, base_lat: 20.033, base_lon: 86.0, status: 'free' },
  { id: 'sup-gop', name: 'Gop Supply Team', kind: 'supply', capacity: 5, base_lat: 20.085, base_lon: 86.095, status: 'off' },
];
const KIND_FOR = { RESCUE: 'boat', MEDICAL: 'medical' };
const teamViews = () => TEAMS.map((t) => {
  const load = [...tickets.values()].filter((x) => x.dispatch?.team_id === t.id && x.status !== 'RESOLVED').length;
  return { ...t, load, status: t.status === 'off' ? 'off' : load >= t.capacity ? 'busy' : 'free' };
});
const gatewaySeen = new Map(GATEWAYS.map((g, i) => [g, i === GATEWAYS.length - 1 ? now() - 3 * 3600 : now()]));
setInterval(() => { for (const g of GATEWAYS.slice(0, -1)) gatewaySeen.set(g, now() - Math.floor(Math.random() * 20)); }, 5000);
const sitreps = new Map(); // district id -> latest sitrep
function propose() {
  const out = [];
  for (const t of tickets.values()) {
    if (t.status !== 'OPEN') continue;
    const team = teamViews().find((x) => x.status === 'free' && x.kind === (KIND_FOR[t.category] ?? 'supply'));
    if (!team) continue;
    t.proposal = {
      team_id: team.id, team_name: team.name, eta_min: 20 + (t.ticket_no % 5) * 10,
      order_en: `${team.name}: take ${t.item ?? 'assistance'} to ${t.landmark ?? 'the reported location'} for ${t.people} people.`,
      order_local: `[${t.lang}] ${t.summary}`, local_lang: t.lang, reason: `nearest free ${team.kind} team`, proposed_at: now(),
    };
    out.push({ ticket_no: t.ticket_no, ...t.proposal });
  }
  return out;
}

// Broadcasts: fake translations ("[hi] ...") so the review table has something to show.
const broadcasts = [];
const BC_LANGS = ['en', 'hi', 'or', 'tl', 'war'];
const digits = (t) => (t.match(/\d+/g) ?? []).map(Number).sort((a, b) => a - b).join(',');
function bcDraft(text, sourceLang) {
  const tr = {};
  for (const l of BC_LANGS) {
    const t = l === sourceLang ? text : `[${l}] ${text}`;
    tr[l] = { text: t, back_translation: text, faithful: true, issues: '', checks_passed: true, status: 'ok' };
  }
  // one language is made to look suspicious so the needs-review path can be tried
  tr.war = { ...tr.war, text: `[war] ${text} 99`, faithful: false, checks_passed: false, status: 'needs_review', issues: 'numbers differ from the source' };
  return tr;
}
function readBody(req, cb) {
  let body = '';
  req.on('data', (c) => (body += c));
  req.on('end', () => { try { cb(body ? JSON.parse(body) : {}); } catch { cb(null); } });
}

const summarize = ({ packets: _p, ...rest }) => rest;
const sorted = () => [...tickets.values()].sort((a, b) => b.priority_score - a.priority_score);

const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, X-Relay-Key, X-Operator-Key',
};
function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...CORS });
  res.end(JSON.stringify(body));
}

// Ready mode (before landfall): warnings in memory, a few placeholder shelters, a template plan.
const DISTRICT_IDS = ['puri', 'delhi'];
/** @type {Map<number, any>} */
const warnings = new Map();
let nextWarning = 1;
const warningView = (w) => ({ ...w, active: w.cancelled_at == null && (w.expires_at == null || w.expires_at > now()) });
const SHELTERS = {
  puri: [
    { id: 'puri-mcs-01', name: 'Puri Town Cyclone Shelter', kind: 'cyclone_shelter', lat: 19.812, lon: 85.828, capacity: 1500, facilities: ['water', 'toilets', 'generator', 'first_aid'], block: 'Puri Sadar' },
    { id: 'puri-sch-01', name: 'Zilla School, Puri', kind: 'school', lat: 19.807, lon: 85.835, capacity: 600, facilities: ['water', 'toilets'], block: 'Puri Sadar' },
    { id: 'puri-hos-01', name: 'District Headquarters Hospital, Puri', kind: 'hospital', lat: 19.818, lon: 85.824, capacity: 300, facilities: ['water', 'generator', 'medical'], block: 'Puri Sadar' },
  ],
  delhi: [
    { id: 'del-sch-01', name: 'Government Boys Senior Secondary School, Connaught Place', kind: 'school', lat: 28.632, lon: 77.219, capacity: 700, facilities: ['water', 'toilets'], block: 'New Delhi' },
    { id: 'del-camp-01', name: 'Yamuna Bank Relief Camp', kind: 'relief_camp', lat: 28.623, lon: 77.279, capacity: 1500, facilities: ['water', 'toilets', 'kitchen'], block: 'Shahdara' },
  ],
};
const shelterView = (s) => ({ ...s, name_en: s.name, demo: true });
function readyRoute(req, res, path, url) {
  if (req.method === 'GET' && path === '/v1/ready/warnings') {
    const d = url.searchParams.get('district');
    if (d && !DISTRICT_IDS.includes(d)) return send(res, 404, { detail: `unknown district '${d}'` });
    return send(res, 200, { warnings: [...warnings.values()].filter((w) => !d || w.district === d).sort((a, b) => b.id - a.id).map(warningView) });
  }
  if (req.method === 'GET' && path === '/v1/ready/shelters') {
    const d = url.searchParams.get('district') ?? '';
    return SHELTERS[d] ? send(res, 200, { district: d, shelters: SHELTERS[d].map(shelterView) }) : send(res, 404, { detail: `unknown district '${d}'` });
  }
  if (req.method === 'POST' && path === '/v1/ready/warnings') {
    return readBody(req, (b) => {
      if (!b || !DISTRICT_IDS.includes(b.district)) return send(res, 404, { detail: 'unknown district' });
      if (!b.headline || !String(b.headline).trim()) return send(res, 422, { detail: 'headline must not be blank' });
      const w = { id: nextWarning++, district: b.district, source: b.source ?? 'manual', severity: b.severity ?? 'watch', headline: String(b.headline).trim(),
        expected_landfall_at: b.expected_landfall_at ?? null, expires_at: b.expires_at ?? null, issued_at: now(), cancelled_at: null };
      warnings.set(w.id, w);
      send(res, 200, warningView(w));
    });
  }
  if (req.method === 'POST' && path === '/v1/ready/warnings/import-cap') {
    return readBody(req, (b) => {
      if (!b || !DISTRICT_IDS.includes(b.district)) return send(res, 404, { detail: 'unknown district' });
      // The real backend parses CAP 1.2; the mock only lifts the identifier and the headline.
      const xml = String(b.cap_xml ?? '');
      const pick = (tag) => xml.match(new RegExp(`<${tag}>([^<]*)</${tag}>`))?.[1]?.trim();
      const headline = pick('headline');
      if (!xml.includes('<alert') || !headline) return send(res, 422, { detail: 'no CAP <alert> with an <info> block found' });
      const w = { id: nextWarning++, district: b.district, source: 'NDMA', severity: 'warning', headline, expected_landfall_at: null,
        expires_at: null, issued_at: now(), cancelled_at: null, cap_identifier: pick('identifier') ?? null, area: pick('areaDesc') ?? '' };
      warnings.set(w.id, w);
      send(res, 200, { imported: [warningView(w)], skipped: [] });
    });
  }
  const cancel = path.match(/^\/v1\/ready\/warnings\/(\d+)\/cancel$/);
  if (req.method === 'POST' && cancel) {
    const w = warnings.get(Number(cancel[1]));
    if (!w) return send(res, 404, { detail: 'warning not found' });
    w.cancelled_at ??= now();
    return send(res, 200, warningView(w));
  }
  if (req.method === 'POST' && path === '/v1/ready/plan/preview') {
    return readBody(req, (b) => {
      const district = b?.district ?? 'puri';
      if (!SHELTERS[district]) return send(res, 404, { detail: 'unknown district' });
      const h = b.household ?? {};
      const near = SHELTERS[district].filter((s) => s.kind !== 'hospital').slice(0, 2);
      const steps = [
        `When the authorities tell you to leave, go to ${near[0].name} first.${near[1] ? ` If the road is blocked, go to ${near[1].name}.` : ''}`,
        'Pack one bag now: ID papers, medicines, torch, dry food and 3 days of drinking water.',
        ...(h.elderly || h.disability ? ['Arrange transport early for anyone who walks slowly or uses an aid.'] : []),
        ...(h.infants ? ['Pack milk or formula, clean water, nappies and a dry cloth for the baby.'] : []),
        ...(h.children ? ['Keep children with an adult at all times.'] : []),
        ...(h.livestock ? ['Untie livestock and leave them on high ground with food and water.'] : []),
        'Charge your phone and power bank.',
        'Before you leave, switch off electricity and gas.',
      ];
      send(res, 200, { plan_id: hex(16), lang: b.lang ?? 'en', steps_lang: 'en', district, steps, source: 'template', warning_id: null,
        nearest_shelters: near.map((s, i) => ({ id: s.id, name: s.name, distance_km: 0.6 + i * 0.9 })), audio_url: null });
    });
  }
  return false;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url ?? '/', 'http://localhost');
  const path = url.pathname.replace(/\/+$/, '') || '/';
  if (req.method === 'OPTIONS') { res.writeHead(204, CORS); return res.end(); }

  if (path.startsWith('/v1/ready/') && readyRoute(req, res, path, url) !== false) return;
  if (req.method === 'GET' && path === '/healthz') return send(res, 200, { ok: true });
  if (req.method === 'GET' && path === '/v1/tickets') return send(res, 200, { tickets: sorted().map(summarize) });
  if (req.method === 'GET' && path === '/v1/teams') return send(res, 200, teamViews());
  if (req.method === 'GET' && path === '/v1/gateways') {
    return send(res, 200, { gateways: [...gatewaySeen].map(([node, last_seen]) => ({ node, last_seen, online: last_seen >= now() - 60 }))
      .sort((a, b) => b.last_seen - a.last_seen), online_window_s: 60 });
  }
  if (req.method === 'POST' && path === '/v1/dispatch/propose') return send(res, 200, { proposals: propose() });
  if (req.method === 'GET' && path === '/v1/sitreps/latest') {
    const s = sitreps.get(url.searchParams.get('district') ?? 'puri');
    return s ? send(res, 200, s) : send(res, 404, { detail: 'no sitrep yet' });
  }
  if (req.method === 'POST' && path === '/v1/sitreps/run') {
    // the mock does not split tickets by district; it only mirrors each district's language
    const district = url.searchParams.get('district') ?? 'puri';
    const delhi = district === 'delhi';
    const open = [...tickets.values()].filter((t) => t.status === 'OPEN');
    const p1 = open.filter((t) => t.priority_tier === 'P1');
    const counts = { open: open.length, open_by_category: {}, p1_open: p1.length,
      dispatched: [...tickets.values()].filter((t) => t.status === 'DISPATCHED' || t.status === 'ON_THE_WAY').length,
      resolved: [...tickets.values()].filter((t) => t.status === 'RESOLVED').length, gateways_online: GATEWAYS.length - 1,
      oldest_open_ticket_no: open[0]?.ticket_no ?? null, oldest_open_age_s: 600 };
    const sitrep = { period_start: now() - 3600, period_end: now(), generated_at: now(), district, counts,
      local_lang: delhi ? 'hi' : 'or', ticket_nos: p1.map((t) => t.ticket_no),
      text_en: `Situation report, ${delhi ? 'Delhi' : 'Puri'}: ${counts.open} open requests, ${counts.p1_open} of them top priority. ${counts.dispatched} dispatched, ${counts.resolved} resolved.\n\n${counts.gateways_online} gateways online.`,
      text_local: delhi ? `स्थिति रिपोर्ट: ${counts.open} खुले अनुरोध।` : `ପରିସ୍ଥିତି ରିପୋର୍ଟ: ${counts.open}ଟି ଖୋଲା ଅନୁରୋଧ।` };
    sitreps.set(district, sitrep);
    return send(res, 200, sitrep);
  }
  if (req.method === 'GET' && path === '/v1/stats') {
    const open_by_category = {};
    for (const t of tickets.values()) if (t.status === 'OPEN') open_by_category[t.category] = (open_by_category[t.category] ?? 0) + 1;
    return send(res, 200, { open_by_category, gateways_online: GATEWAYS.length - 1, tickets: tickets.size });
  }
  if (path === '/v1/broadcasts' && req.method === 'GET') return send(res, 200, { broadcasts: [...broadcasts].reverse() });
  if (path === '/v1/broadcasts' && req.method === 'POST') {
    return readBody(req, (b) => {
      if (!b || !b.district || !b.text) return send(res, 422, { detail: 'district and text are required' });
      const lang = b.source_lang ?? 'en';
      const row = { id: broadcasts.length + 1, district: b.district, source_text: b.text.trim(), source_lang: lang, created_at: now(),
        translations: bcDraft(b.text.trim(), lang), status: 'draft', approved_at: null, approved_langs: [] };
      broadcasts.push(row);
      send(res, 200, row);
    });
  }
  const bm = path.match(/^\/v1\/broadcasts\/(\d+)\/(approve|withdraw)$/);
  if (bm && req.method === 'POST') {
    const row = broadcasts.find((x) => x.id === Number(bm[1]));
    if (!row) return send(res, 404, { detail: 'broadcast not found' });
    if (bm[2] === 'withdraw') { row.status = 'withdrawn'; return send(res, 200, row); }
    return readBody(req, (b) => {
      if (row.status !== 'draft') return send(res, 409, { detail: `broadcast is ${row.status}, not a draft` });
      const edits = b?.edits ?? {};
      for (const [l, t] of Object.entries(edits)) {
        if (digits(t) !== digits(row.source_text)) return send(res, 422, { detail: `${l}: numbers differ from the source` });
        row.translations[l] = { text: t, back_translation: '', faithful: false, issues: 'edited by operator, not model-checked', checks_passed: true, status: 'ok', edited: true };
      }
      const langs = b?.langs ?? BC_LANGS.filter((l) => row.translations[l].status === 'ok');
      row.approved_langs = BC_LANGS.filter((l) => langs.includes(l) || l in edits);
      row.status = 'approved';
      row.approved_at = now();
      send(res, 200, row);
    });
  }
  const m = path.match(/^\/v1\/tickets\/(\d+)(\/approve|\/on-the-way|\/resolve)?$/);
  if (m) {
    const t = tickets.get(Number(m[1]));
    if (!t) return send(res, 404, { detail: 'ticket not found' });
    if (req.method === 'GET' && !m[2]) return send(res, 200, t);
    if (req.method === 'POST' && m[2] === '/approve') {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        const teamId = (body && JSON.parse(body).team_id) || t.proposal?.team_id;
        const team = TEAMS.find((x) => x.id === teamId);
        t.status = 'DISPATCHED';
        if (team) t.dispatch = { team_id: team.id, team_name: team.name, eta_min: t.proposal?.eta_min ?? 35, approved_at: now() };
        delete t.proposal;
        send(res, 200, t);
      });
      return;
    }
    if (req.method === 'POST' && m[2] === '/on-the-way') { t.status = 'ON_THE_WAY'; return send(res, 200, t); }
    if (req.method === 'POST' && m[2] === '/resolve') { t.status = 'RESOLVED'; return send(res, 200, t); }
  }
  send(res, 404, { detail: 'not found' });
});

server.listen(PORT, () => console.log(`[mock] Relay mock backend on http://localhost:${PORT} (${tickets.size} tickets)`));
