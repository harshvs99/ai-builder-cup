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
function propose(only) {  // one ticket, as the real API
  const out = [];
  for (const t of tickets.values()) {
    if (t.status !== 'OPEN' || t.ticket_no !== only) continue;
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

// Announcements: fake translations ("[hi] ...") so the review table has something to show.
const announcements = [];
let annSeq = 0;
const nextSeq = () => (annSeq = Math.max(Date.now(), annSeq + 1));
const LANG_CODES = ['en', 'hi', 'or', 'tl', 'war', 'ceb'];
const DISTRICT_CODES = ['puri', 'delhi'];
const LANG_REGISTRY = [['en', 'English', 'English', true], ['hi', 'Hindi', 'हिन्दी', true], ['or', 'Odia', 'ଓଡ଼ିଆ', false], ['tl', 'Filipino (Tagalog)', 'Filipino', false],
  ['war', 'Waray', 'Winaray', false], ['ceb', 'Cebuano', 'Cebuano', false]];
const ENABLED = (process.env.RELAY_LANGS ?? 'en,hi').split(',').map((c) => c.trim());
const utf8 = (t) => Buffer.byteLength(t, 'utf8');
// at most 139 UTF-8 bytes, cut on a character boundary, ending in "…" when cut (mirrors backend announcements.mesh_text)
function meshText(text) {
  text = text.split(/\s+/).filter(Boolean).join(' ');
  if (utf8(text) <= 139) return text;
  let cut = '';
  for (const ch of text) { if (utf8(cut + ch) > 136) break; cut += ch; }
  const sp = cut.lastIndexOf(' ');
  if (sp >= (cut.length * 2) / 3) cut = cut.slice(0, sp);
  return cut.trimEnd() + '…';
}
// SPEC 5.7 ANNOUNCEMENT payload
function annPayload(row, lang, status) {
  const text = Buffer.from(row.mesh_texts[lang], 'utf8');
  const b = Buffer.alloc(20 + 1 + text.length);
  b.writeUInt8(1, 0); Buffer.from(row.announcement_id, 'hex').copy(b, 1);
  b.writeUInt8(LANG_CODES.indexOf(lang), 9); b.writeUInt8(DISTRICT_CODES.indexOf(row.district), 10); b.writeUInt8(status, 11);
  b.writeUInt32BE(row.approved_at, 12); b.writeUInt32BE(0, 16); b.writeUInt8(text.length, 20); text.copy(b, 21);
  return b.toString('base64');
}
// mirrors backend announcements.sync_payloads
function annSync(cursor, district) {
  const latest = Math.max(0, ...announcements.map((r) => Math.max(r.approved_seq ?? 0, r.withdrawn_seq ?? 0)));
  if (cursor > latest) cursor = 0;
  const events = [];
  for (const r of announcements) {
    if (!r.announcement_id || (district && r.district !== district) || now() - r.approved_at > 72 * 3600) continue;
    if ((r.approved_seq ?? 0) > cursor && r.status === 'approved') events.push([r.approved_seq, r, 0]);
    if ((r.withdrawn_seq ?? 0) > cursor) events.push([r.withdrawn_seq, r, 1]);
  }
  events.sort((a, b) => a[0] - b[0]);
  const out = []; let next = cursor;
  for (const [seq, r, status] of events) {
    const frames = r.approved_langs.filter((l) => ENABLED.includes(l)).map((l) => annPayload(r, l, status));
    if (out.length && out.length + frames.length > 50) break;
    out.push(...frames); next = seq;
  }
  return [out, next];
}
const BC_LANGS = ['en', 'hi', 'or', 'tl', 'war'].filter((l) => ENABLED.includes(l)); // like the backend: enabled languages only
const digits = (t) => (t.match(/\d+/g) ?? []).map(Number).sort((a, b) => a - b).join(',');
function bcDraft(text, sourceLang) {
  const tr = {};
  for (const l of BC_LANGS) {
    const t = l === sourceLang ? text : `[${l}] ${text}`;
    tr[l] = { text: t, back_translation: text, faithful: true, issues: '', checks_passed: true, status: 'ok' };
  }
  // one language is made to look suspicious so the needs-review path can be tried
  if (tr.war) tr.war = { ...tr.war, text: `[war] ${text} 99`, faithful: false, checks_passed: false, status: 'needs_review', issues: 'numbers differ from the source' };
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
  'Access-Control-Allow-Methods': 'GET, POST, PATCH, OPTIONS',
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
const nearestDistrict = (lat, lon) => {
  if (lat == null || lon == null || lat === '' || lon === '' || Number.isNaN(Number(lat)) || Number.isNaN(Number(lon))) return null;
  const C = { puri: [19.81, 85.83], delhi: [28.614, 77.209] };
  const dist = (id) => (Number(lat) - C[id][0]) ** 2 + ((Number(lon) - C[id][1]) * Math.cos((Number(lat) * Math.PI) / 180)) ** 2;
  return DISTRICT_IDS.reduce((a, b) => (dist(b) < dist(a) ? b : a));
};
const activeWarning = (d) => [...warnings.values()].filter((w) => w.district === d && warningView(w).active).sort((a, b) => b.id - a.id)[0] ?? null;
const CHECKLIST = ['ID papers, medicines and a torch in one bag', '3 days of drinking water and dry food', 'Phone and power bank charged',
  'Switch off electricity and gas before you leave', 'Know your shelter and the road to it'];
// The pack as GET /v1/ready/pack serves it. A published shelter list wins over the demo one (shelters_source "published").
function readyPack(d, lang) {
  const list = [...shelterLists.values()].find((l) => l.district === d && l.status === 'published');
  const shelters = list
    ? list.entries.filter((e) => e.verified && e.lat != null && e.lon != null).map((e) => ({ id: e.id, name: e.name_local || e.name, name_en: e.name, kind: e.kind,
        lat: e.lat, lon: e.lon, capacity: e.capacity, facilities: [], block: e.block, demo: false }))
    : SHELTERS[d].map(shelterView);
  const w = activeWarning(d);
  const content = { district: d, lang, warning: w ? { id: w.id, source: w.source, severity: w.severity, headline: w.headline, issued_at: w.issued_at,
      expected_landfall_at: w.expected_landfall_at, expires_at: w.expires_at } : null,
    shelters, shelters_source: list ? 'published' : 'demo', shelters_published_at: list?.published_at ?? null, checklist: CHECKLIST, checklist_lang: 'en' };
  // djb2 over the content: changes exactly when the warning, the shelters or the checklist do
  let h = 5381; for (const c of JSON.stringify(content)) h = ((h * 33) ^ c.charCodeAt(0)) >>> 0;
  return { ...content, generated_at: now(), version: h.toString(16).padStart(8, '0') + '00000000' };
}
// Shelter lists: discover returns 3 fake entries (one without coordinates); publish makes them the district's list.
/** @type {Map<number, any>} */
const shelterLists = new Map();
let nextShelterList = 1;
const SUGGESTIONS = '<div class="container"><a class="chip" href="https://www.google.com/search?q=cyclone+shelters" style="font:13px sans-serif;padding:6px 12px;border:1px solid #888;border-radius:16px;text-decoration:none">cyclone shelters list</a></div>';
const fakeEntry = (id, name, over = {}) => ({ id, name, name_local: null, kind: 'cyclone_shelter', address: 'Mock address', block: null, capacity: 500,
  lat: null, lon: null, coords_from: 'none', source_url: 'https://example.gov.in/shelters.pdf', verified: false, issues: [], ...over });
function discoverEntries(district) {
  const [lat, lon] = district === 'delhi' ? [28.62, 77.22] : [19.81, 85.83];
  return [
    fakeEntry('e1', 'Mock Multipurpose Cyclone Shelter', { lat, lon, coords_from: 'source' }),
    fakeEntry('e2', 'Mock Govt High School', { kind: 'school', lat: lat + 0.02, lon: lon + 0.02, coords_from: 'estimate', issues: ["coordinates are the model's estimate: check them on the map"] }),
    fakeEntry('e3', 'Mock Relief Camp (no coordinates)', { kind: 'relief_camp', capacity: null, issues: ['no coordinates: set them on the map before publishing'] }),
  ];
}
function shelterRoute(req, res, path, url) {
  if (req.method === 'GET' && path === '/v1/ready/shelter-lists') {
    const d = url.searchParams.get('district');
    return send(res, 200, { lists: [...shelterLists.values()].filter((l) => !d || l.district === d).sort((a, b) => b.id - a.id) });
  }
  if (req.method === 'POST' && (path === '/v1/ready/shelter-lists/discover' || path === '/v1/ready/shelter-lists')) {
    return readBody(req, (b) => {
      if (!b || !DISTRICT_IDS.includes(b.district)) return send(res, 404, { detail: 'unknown district' });
      const found = path.endsWith('/discover');
      const l = { id: nextShelterList++, district: b.district, status: 'draft', created_at: now(),
        entries: found ? discoverEntries(b.district) : (b.entries ?? []).map((e, i) => fakeEntry(`e${i + 1}`, e.name ?? '', e)),
        sources: found ? [{ uri: 'https://example.gov.in/shelters.pdf', title: 'example.gov.in' }] : [], search_suggestions_html: found ? SUGGESTIONS : '',
        model: found ? 'mock' : null, notes: [], origin: found ? 'gemini' : 'manual', published_at: null, published_by_note: null };
      shelterLists.set(l.id, l);
      setTimeout(() => send(res, 200, l), found ? 800 : 0);
    });
  }
  const sl = path.match(/^\/v1\/ready\/shelter-lists\/(\d+)(\/publish)?$/);
  if (sl) {
    const l = shelterLists.get(Number(sl[1]));
    if (!l) return send(res, 404, { detail: 'shelter list not found' });
    if (l.status !== 'draft') return send(res, 409, { detail: `list is ${l.status}; only a draft can change` });
    if (req.method === 'PATCH' && !sl[2]) {
      return readBody(req, (b) => {
        l.entries = (b?.entries ?? []).map((e, i) => fakeEntry(e.id ?? `e${i + 1}`, String(e.name ?? ''), { ...e, issues: e.lat == null ? ['no coordinates: set them on the map before publishing'] : [] }));
        send(res, 200, l);
      });
    }
    if (req.method === 'POST' && sl[2]) {
      return readBody(req, (b) => {
        const ok = l.entries.filter((e) => e.verified && e.lat != null && e.lon != null);
        if (!l.entries.some((e) => e.verified)) return send(res, 422, { detail: 'verify at least one entry before publishing' });
        if (ok.length === 0) return send(res, 422, { detail: 'no verified entry has coordinates inside the district; set them on the map first' });
        for (const o of shelterLists.values()) if (o.district === l.district && o.status === 'published') o.status = 'archived';
        l.status = 'published';
        l.published_at = now();
        l.published_by_note = b?.note || null;
        const skipped = l.entries.filter((e) => !ok.includes(e)).map((e) => ({ id: e.id, name: e.name, reason: e.verified ? 'no coordinates' : 'not verified' }));
        send(res, 200, { list: l, published: ok.length, skipped });
      });
    }
  }
  return false;
}
function readyRoute(req, res, path, url) {
  if (path.startsWith('/v1/ready/shelter-lists') && shelterRoute(req, res, path, url) !== false) return;
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
  if (req.method === 'GET' && path === '/v1/ready/pack') {
    const d = url.searchParams.get('district') ?? nearestDistrict(url.searchParams.get('lat'), url.searchParams.get('lon'));
    if (!d) return send(res, 422, { detail: 'give district, or lat and lon' });
    if (!SHELTERS[d]) return send(res, 404, { detail: `unknown district '${d}'` });
    return send(res, 200, readyPack(d, url.searchParams.get('lang') ?? 'en'));
  }
  if (req.method === 'POST' && (path === '/v1/ready/plan/preview' || path === '/v1/ready/plan')) {
    return readBody(req, (b) => {
      const district = b?.district ?? nearestDistrict(b?.lat, b?.lon) ?? 'puri';
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
      const w = activeWarning(district);
      send(res, 200, { plan_id: hex(16), lang: b.lang ?? 'en', steps_lang: 'en', district, steps, source: 'template', warning_id: w?.id ?? null,
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
  if (req.method === 'POST' && path === '/v1/dispatch/propose') {
    const n = Number(url.searchParams.get('ticket_no'));
    if (!url.searchParams.has('ticket_no') || !Number.isInteger(n)) return send(res, 422, { detail: 'ticket_no is required' });
    const t = tickets.get(n);
    if (!t) return send(res, 404, { detail: 'unknown ticket' });
    if (t.status !== 'OPEN') return send(res, 409, { detail: 'ticket is not open' });
    return send(res, 200, { proposals: propose(n) });
  }
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
  if (req.method === 'GET' && path === '/v1/languages') {
    return send(res, 200, LANG_REGISTRY.map(([code, english_name, native_name, reviewed]) => ({ code, english_name, native_name, enabled: ENABLED.includes(code), reviewed })));
  }
  if (req.method === 'GET' && path === '/v1/announcements/active') {
    const d = url.searchParams.get('district') ?? '';
    if (!DISTRICT_IDS.includes(d)) return send(res, 422, { detail: `district must be one of ${JSON.stringify(DISTRICT_IDS)}` });
    return send(res, 200, { announcements: announcements.filter((r) => r.district === d && r.status === 'approved').reverse().map((r) => ({
      id: r.id, announcement_id: r.announcement_id, district: r.district, source_lang: r.source_lang, created_at: r.created_at, approved_at: r.approved_at,
      texts: Object.fromEntries(r.approved_langs.filter((l) => ENABLED.includes(l)).map((l) => [l, r.translations[l].text])) })) });
  }
  if (req.method === 'GET' && path === '/v1/public/shelters') {
    const d = url.searchParams.get('district') ?? '';
    if (!DISTRICT_IDS.includes(d)) return send(res, 404, { detail: `unknown district '${d}'` });
    const list = [...shelterLists.values()].find((l) => l.district === d && l.status === 'published');
    const items = list ? list.entries.filter((e) => e.verified && e.lat != null && e.lon != null) : [];
    if (!items.length) return send(res, 404, { detail: `no published shelter list for ${d}` });
    return send(res, 200, { district: d, published_at: list.published_at,
      shelters: items.map((e) => ({ id: e.id, name: e.name, name_local: e.name_local, kind: e.kind, address: e.address, lat: e.lat, lon: e.lon, capacity: e.capacity, source_url: e.source_url })),
      source_note: 'Mock: verified by the control room; confirm with local officials before travelling.' });
  }
  if (req.method === 'POST' && path === '/v1/sync') {
    return readBody(req, (b) => {
      if (!b || !/^[0-9a-f]{16}$/i.test(b.gateway_node ?? '')) return send(res, 422, { detail: 'gateway_node must be 16 hex characters' });
      gatewaySeen.set(b.gateway_node.toLowerCase(), now());
      // The mock does not decode NEED frames: it only counts them. ACKs come from tickets in real backends.
      const out = { accepted: 0, duplicates: 0, rejected: (b.frames ?? []).length, acks: [], ack_cursor: b.ack_cursor ?? 0 };
      if (b.district !== undefined || b.announcement_cursor !== undefined) {
        const [payloads, cursor] = annSync(b.announcement_cursor ?? 0, DISTRICT_IDS.includes(b.district) ? b.district : undefined);
        out.announcements = payloads; out.announcement_cursor = cursor;
      }
      send(res, 200, out);
    });
  }
  if (path === '/v1/announcements' && req.method === 'GET') return send(res, 200, { announcements: [...announcements].reverse() });
  if (path === '/v1/announcements' && req.method === 'POST') {
    return readBody(req, (b) => {
      if (!b || !b.district || !b.text) return send(res, 422, { detail: 'district and text are required' });
      const lang = b.source_lang ?? 'en';
      const row = { id: announcements.length + 1, district: b.district, source_text: b.text.trim(), source_lang: lang, created_at: now(),
        translations: bcDraft(b.text.trim(), lang), status: 'draft', approved_at: null, approved_langs: [] };
      announcements.push(row);
      send(res, 200, row);
    });
  }
  const bm = path.match(/^\/v1\/announcements\/(\d+)\/(approve|withdraw)$/);
  if (bm && req.method === 'POST') {
    const row = announcements.find((x) => x.id === Number(bm[1]));
    if (!row) return send(res, 404, { detail: 'announcement not found' });
    if (bm[2] === 'withdraw') {
      if (row.status === 'approved') row.withdrawn_seq = nextSeq();
      row.status = 'withdrawn';
      return send(res, 200, row);
    }
    return readBody(req, (b) => {
      if (row.status !== 'draft') return send(res, 409, { detail: `announcement is ${row.status}, not a draft` });
      const edits = b?.edits ?? {};
      for (const [l, t] of Object.entries(edits)) {
        if (digits(t) !== digits(row.source_text)) return send(res, 422, { detail: `${l}: numbers differ from the source` });
        row.translations[l] = { text: t, back_translation: '', faithful: false, issues: 'edited by operator, not model-checked', checks_passed: true, status: 'ok', edited: true };
      }
      const langs = b?.langs ?? BC_LANGS.filter((l) => row.translations[l].status === 'ok');
      row.approved_langs = BC_LANGS.filter((l) => langs.includes(l) || l in edits);
      row.status = 'approved';
      row.approved_at = now();
      row.announcement_id = hex(16);
      row.mesh_texts = Object.fromEntries(row.approved_langs.map((l) => [l, meshText(row.translations[l].text)]));
      row.approved_seq = nextSeq();
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
