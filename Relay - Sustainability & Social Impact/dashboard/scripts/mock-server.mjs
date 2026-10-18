// Tiny in-memory stand-in for the Relay backend (SPEC §7 dashboard endpoints).
// Usage: npm run mock   (PORT env overrides 8080)
import { createHash } from 'node:crypto';
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
  // Mirrors the backend: the first report's packet lists the tag keys, the ticket carries the per-tag report counts.
  if (t.photo_tags) {
    t.packets[0].photo_tags = Object.keys(t.photo_tags);
    t.priority_reason += ` Photo shows: ${Object.keys(t.photo_tags).map((k) => k.replace(/_/g, ' ')).join(', ')}.`;
  }
  tickets.set(no, t);
  return t;
}

const min = (m) => now() - m * 60;

// What the backend stores for a landmark found with Gemini + Google Maps grounding (SPEC section 7, `geo`).
const MAPS_GEO = {
  lat: 20.1146, lon: 85.8309, source: 'gemini_maps', query: 'Pipili bus stand', name: 'Pipili Bus Stand',
  place_id: 'ChIJmockPipiliBusStand', maps_uri: 'https://www.google.com/maps/place/?q=place_id:ChIJmockPipiliBusStand', district: 'puri',
};
const FIXTURES = [
  { category: 'MEDICAL', item: 'insulin', landmark: 'school', summary: 'mother needs insulin, diabetic', lat: 20.29612, lon: 85.82451, urgency: 3, people: 1, vuln_flags: ['ELDERLY'], lang: 'or', report_count: 3, max_hops: 2, first_reported_at: min(14) },
  { category: 'RESCUE', item: 'boat', landmark: 'Puri beach road', summary: 'family trapped on roof, water rising', photo_tags: { floodwater: 4, people_stranded: 2, house_damaged: 1 }, lat: 19.8135, lon: 85.8312, urgency: 3, people: 5, vuln_flags: ['CHILD', 'TRAPPED'], lang: 'or', report_count: 5, max_hops: 4, first_reported_at: min(31) },
  { category: 'WATER', item: 'drinking water', landmark: 'temple', summary: 'no clean water for 2 days', lat: 20.2701, lon: 85.8402, urgency: 2, people: 20, lang: 'or', report_count: 2, max_hops: 3, first_reported_at: min(55) },
  { category: 'FOOD', item: 'rice', landmark: 'bus stand', summary: '12 people need food packets', lat: 20.3011, lon: 85.8157, urgency: 1, people: 12, lang: 'en', report_count: 1, max_hops: 1, first_reported_at: min(8) },
  { category: 'SHELTER', item: 'tent', landmark: 'cyclone shelter 4', summary: 'roof collapsed, need shelter', photo_tags: { roof_damaged: 1, house_collapsed: 1 }, lat: 20.2488, lon: 85.7992, urgency: 2, people: 6, vuln_flags: ['CHILD'], lang: 'or', report_count: 1, max_hops: 2, first_reported_at: min(22) },
  { category: 'MEDICAL', item: 'wound dressing', landmark: 'market', summary: 'man with deep leg wound, bleeding', photo_tags: { person_injured: 2 }, lat: 11.2438, lon: 125.0042, urgency: 3, people: 1, vuln_flags: ['INJURED'], lang: 'war', report_count: 2, max_hops: 3, first_reported_at: min(11) },
  { category: 'RESCUE', item: 'rope', landmark: 'Tacloban airport road', summary: 'elderly couple stuck on second floor', photo_tags: { floodwater: 3, people_stranded: 3 }, lat: 11.2271, lon: 125.0278, urgency: 3, people: 2, vuln_flags: ['ELDERLY', 'TRAPPED'], lang: 'tl', report_count: 4, max_hops: 5, first_reported_at: min(40) },
  { category: 'MISSING_PERSON', item: null, landmark: 'San Jose', summary: 'looking for 8-year-old boy, last seen near church', lat: 11.2354, lon: 125.0126, urgency: 2, people: 1, vuln_flags: ['CHILD'], lang: 'ceb', report_count: 1, max_hops: 2, first_reported_at: min(5) },
  // Phone with Location switched off: loc_acc 0, no coordinates (must stay off the map, in the queue)
  { category: 'MEDICAL', item: 'insulin', landmark: 'school', summary: 'mother needs insulin (location off)', lat: null, lon: null, loc_acc: 0, urgency: 3, people: 1, vuln_flags: ['ELDERLY'], lang: 'en', report_count: 1, max_hops: 1, first_reported_at: min(3) },
  // No fix, but two nearby relay phones sent location hints: the backend adds an approximate mesh estimate
  // (has_location stays false; the dashboard draws a dashed circle, never a GPS dot).
  { category: 'WATER', item: 'drinking water', landmark: null, summary: 'no clean water, family of 6 (location off, mesh estimate)', lat: null, lon: null, loc_acc: 0, geo: { lat: 20.2763, lon: 85.8309, source: 'mesh', radius_m: 130, hint_count: 2 }, urgency: 2, people: 6, vuln_flags: ['CHILD'], lang: 'or', report_count: 1, max_hops: 2, first_reported_at: min(6) },
  // No fix, landmark found on Google Maps by the backend (has_location stays false; shown in the detail panel with
  // the Google Maps credit, not plotted). "Locate with Maps" on the 'school' ticket above fills in a similar one.
  { category: 'FOOD', item: 'rice', landmark: 'Pipili bus stand', summary: '9 people need food (location off, found on Maps)', lat: null, lon: null, loc_acc: 0, geo: MAPS_GEO, urgency: 2, people: 9, vuln_flags: [], lang: 'or', report_count: 1, max_hops: 2, first_reported_at: min(9) },
];
FIXTURES.forEach(addTicket);
// Two closed requests, so Recover has households reached and a response time to show.
for (const [no, mins] of [[100, 12], [101, 20]]) {
  const t = tickets.get(no);
  t.status = 'RESOLVED';
  t.dispatch = { team_id: 'boat-puri', team_name: 'Puri Rescue Boat 1', eta_min: 25, approved_at: t.first_reported_at + mins * 60 };
}

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
  'Access-Control-Expose-Headers': 'Content-Disposition',
};
function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...CORS });
  res.end(JSON.stringify(body));
}


// ---- Recover: a simplified copy of backend/app/recover.py (areas, damage level, evidence pack, report) ----
const PLACES = [
  { name: 'Jagannath Temple', block: 'Puri Sadar', lat: 19.8048, lon: 85.818 },
  { name: 'Pipili Bus Stand', block: 'Pipili', lat: 20.1146, lon: 85.8309 },
  { name: 'Nimapara Market', block: 'Nimapara', lat: 20.0376, lon: 86.0097 },
];
const km = (la1, lo1, la2, lo2) => Math.hypot((la1 - la2) * 111.2, (lo1 - lo2) * 111.2 * Math.cos((la1 * Math.PI) / 180));
const slugOf = (x) => x.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
const districtOf = (lat, lon) => (km(lat, lon, 19.81, 85.83) <= km(lat, lon, 28.614, 77.209) ? 'puri' : 'delhi');
const where = (t) => (t.has_location ? [t.lat, t.lon] : t.geo ? [t.geo.lat, t.geo.lon] : null);
function areaOf(t) {
  const p = where(t);
  if (!p) return { id: 'unlocated', name: 'Unlocated', district: null };
  const near = PLACES.map((pl) => ({ pl, d: km(p[0], p[1], pl.lat, pl.lon) })).sort((a, b) => a.d - b.d)[0];
  if (near.d <= 3) return { id: `place:${slugOf(near.pl.name)}`, name: `${near.pl.name}, ${near.pl.block}`, district: 'puri' };
  const i = Math.floor(p[0] / 0.018), j = Math.floor(p[1] / (0.018 / Math.max(0.2, Math.cos((p[0] * Math.PI) / 180))));
  const d = districtOf(p[0], p[1]);
  return { id: `grid:${i}:${j}`, name: `${d === 'puri' ? 'Puri' : 'Delhi'}: area near ${p[0].toFixed(2)}, ${p[1].toFixed(2)}`, district: d };
}
const HOUSE = ['house_damaged', 'house_collapsed', 'roof_damaged'];
const HAZARD = ['floodwater', 'road_blocked', 'landslide', 'fire', 'power_line_down'];
const sum = (a, f) => a.reduce((n, x) => n + f(x), 0);
function metrics(ts) {
  const reports = sum(ts, (t) => t.report_count);
  const resolved = ts.filter((t) => t.status === 'RESOLVED');
  const tags = {};
  for (const t of ts) for (const [k, v] of Object.entries(t.photo_tags ?? {})) tags[k] = (tags[k] ?? 0) + v;
  const waits = ts.filter((t) => t.dispatch?.approved_at).map((t) => (t.dispatch.approved_at - t.first_reported_at) / 60).sort((a, b) => a - b);
  const houses = ts.filter((t) => HOUSE.some((k) => t.photo_tags?.[k]) || t.answers?.house_damaged === 'yes').length;
  const collapsed = ts.filter((t) => t.photo_tags?.house_collapsed).length;
  const located = ts.filter((t) => where(t)).length;
  const by = {};
  for (const t of ts) by[t.category] = (by[t.category] ?? 0) + 1;
  const mid = waits.length ? (waits.length % 2 ? waits[(waits.length - 1) / 2] : (waits[waits.length / 2 - 1] + waits[waits.length / 2]) / 2) : null;
  return {
    tickets: ts.length, reports, duplicates_merged: reports - ts.length,
    households: sum(ts, (t) => t.packets.length), people: sum(ts, (t) => t.people),
    households_reached: sum(resolved, (t) => t.packets.length),
    open: ts.filter((t) => t.status === 'OPEN').length,
    dispatched: ts.filter((t) => t.status === 'DISPATCHED' || t.status === 'ON_THE_WAY').length,
    resolved: resolved.length, needs_confirmed_closed: resolved.length, by_category: by, photo_tags: tags,
    houses_damaged: houses, houses_collapsed: collapsed, median_minutes_to_dispatch: mid == null ? null : Math.round(mid * 10) / 10,
    share_with_location: ts.length ? Math.round((located / ts.length) * 1000) / 1000 : 0,
    share_with_gps_fix: ts.length ? Math.round((ts.filter((t) => t.has_location).length / ts.length) * 1000) / 1000 : 0,
  };
}
function level(m) {
  if (!m.tickets) return ['none', 'No requests from this area.'];
  const hazards = HAZARD.filter((k) => m.photo_tags[k]);
  const lv = m.houses_collapsed || m.houses_damaged >= 3 || m.reports >= 8 ? 'severe' : m.houses_damaged || m.reports >= 4 || hazards.length ? 'moderate' : 'light';
  const facts = [];
  if (m.houses_collapsed) facts.push(`${m.houses_collapsed} request${m.houses_collapsed === 1 ? '' : 's'} report a collapsed house`);
  facts.push(m.houses_damaged ? `${m.houses_damaged} request${m.houses_damaged === 1 ? '' : 's'} report a damaged house` : 'no house damage reported');
  facts.push(`${m.reports} report${m.reports === 1 ? '' : 's'} in all`);
  if (hazards.length && lv !== 'light') facts.push(`photo tags show ${hazards.map((h) => h.replace(/_/g, ' ')).join(', ')}`);
  return [lv, `${lv[0].toUpperCase()}${lv.slice(1)}: ${facts.join('; ')}.`];
}
const RANK = { severe: 0, moderate: 1, light: 2, none: 3 };
function inScope(t, district) { const d = areaOf(t).district; return district === 'all' || d === null || d === district; }
function damageMap(district) {
  const groups = new Map();
  for (const t of tickets.values()) if (inScope(t, district)) { const a = areaOf(t); groups.set(a.id, [...(groups.get(a.id) ?? []), t]); }
  const areas = [...groups.values()].map((ts) => {
    const a = areaOf(ts[0]), m = metrics(ts), [lv, why] = level(m), pts = ts.map(where).filter(Boolean);
    return { area_id: a.id, name: a.name, district: a.district,
      lat: pts.length ? Math.round((sum(pts, (p) => p[0]) / pts.length) * 1e5) / 1e5 : null,
      lon: pts.length ? Math.round((sum(pts, (p) => p[1]) / pts.length) * 1e5) / 1e5 : null, ...m, damage_level: lv, damage_reason: why };
  }).sort((a, b) => RANK[a.damage_level] - RANK[b.damage_level] || b.reports - a.reports || a.name.localeCompare(b.name));
  return { generated_at: now(), district, areas, totals: metrics([...tickets.values()].filter((t) => inScope(t, district))) };
}
const pseudo = (node) => `R-${createHash('sha256').update(`relay-evidence-v1:${node}`).digest('hex').slice(0, 12)}`;
const canon = (v) => JSON.stringify(v, (_, x) => (x && typeof x === 'object' && !Array.isArray(x) ? Object.fromEntries(Object.entries(x).sort(([a], [b]) => (a < b ? -1 : 1))) : x));
const sha = (v) => createHash('sha256').update(canon(v)).digest('hex');
function evidence(t) {
  const a = areaOf(t), d = t.dispatch ?? {};
  const rec = {
    schema: 'relay.evidence.v1',
    ticket: { ticket_no: t.ticket_no, category: t.category, item: t.item, landmark: t.landmark, summary: t.summary, people: t.people, urgency: t.urgency, lang: t.lang, status: t.status, report_count: t.report_count, first_reported_at: t.first_reported_at },
    area: { area_id: a.id, name: a.name, district: a.district },
    location: t.has_location ? { basis: 'gps', lat: t.lat, lon: t.lon, accuracy_bucket: t.loc_acc, accuracy: t.loc_acc_m }
      : t.geo ? { basis: 'approximate', source: t.geo.source, lat: t.geo.lat, lon: t.geo.lon, note: 'estimate, not a GPS fix' } : { basis: 'none', note: 'no GPS fix and no approximate position' },
    reports: t.packets.map((p) => ({ msg_id: p.msg_id, reporter: pseudo(p.origin_node), hops: p.hops, gateway: p.gateway_node, received_at: p.received_at, reported_at: p.reported_at })),
    timeline: [{ at: t.first_reported_at, event: 'first_reported' }, { at: t.first_reported_at + 360, event: 'received_by_control_room' },
      ...(d.approved_at ? [{ at: d.approved_at, event: 'dispatched', team: d.team_name, eta_min: d.eta_min }] : [])],
    answers: t.answers ?? {},
    photo_tags: { tags: t.photo_tags ?? {}, note: "classified on the requester's phone; tags only, no image" },
    priority: { score: t.priority_score, tier: t.priority_tier, reason: t.priority_reason },
  };
  rec.integrity = { algorithm: 'sha256', digest: sha(rec) };
  return rec;
}
function sendFile(res, body, name) {
  res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Disposition': `attachment; filename="${name}"`, ...CORS });
  res.end(JSON.stringify(body, null, 2));
}
function report(district) {
  const dm = damageMap(district), t = dm.totals;
  const med = t.median_minutes_to_dispatch == null ? null : Math.round(t.median_minutes_to_dispatch);
  const rows = dm.areas.slice(0, 8).map((a) => `${a.name}: ${a.damage_level} damage, ${a.tickets} requests, ${a.houses_damaged} with reported house damage, ${a.households_reached} households reached`);
  const text_en = [
    `Relief and damage report, ${district === 'all' ? 'all districts' : district}. ${t.tickets} requests were received from ${t.households} households (${t.reports} reports, of which ${t.duplicates_merged} duplicates were merged before dispatch). ${t.resolved} confirmed closed, ${t.dispatched} in progress, ${t.open} still open.`,
    `Damage by village (${dm.areas.length} areas). ${rows.join('; ')}.`,
    `Households reached: ${t.households_reached} of ${t.households}.`,
    med == null ? 'Response time: no request has been dispatched yet.' : `Response time: the median time from request to dispatch was ${med} minutes.`,
    `Data provenance and limitations: ${Math.round(t.share_with_location * 100)}% of requests have a usable location. This is mock data. Photo tags are unverified observations made by the requester's phone, and no image was ever stored. Locations are approximate.`,
  ].join('\n\n');
  const hi = district === 'delhi';
  return { generated_at: now(), district, counts: { totals: t, areas: dm.areas }, text_en,
    text_local: hi ? `राहत एवं क्षति रिपोर्ट: ${t.households} परिवारों से ${t.tickets} अनुरोध।` : `ରିଲିଫ୍ ଓ କ୍ଷୟକ୍ଷତି ରିପୋର୍ଟ: ${t.households}ଟି ପରିବାରରୁ ${t.tickets}ଟି ଅନୁରୋଧ।`,
    local_lang: hi ? 'hi' : 'or', model: 'template',
    evidence_digests: [...tickets.values()].filter((x) => inScope(x, district)).sort((a, b) => a.ticket_no - b.ticket_no).map((x) => ({ ticket_no: x.ticket_no, digest: evidence(x).integrity.digest })) };
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
  if (req.method === 'GET' && path === '/v1/recover/damage') return send(res, 200, damageMap(url.searchParams.get('district') ?? 'all'));
  if (req.method === 'POST' && path === '/v1/recover/report') return send(res, 200, report(url.searchParams.get('district') ?? 'all'));
  if (req.method === 'GET' && path === '/v1/recover/evidence') {
    const district = url.searchParams.get('district') ?? 'all', id = url.searchParams.get('area');
    const ts = [...tickets.values()].filter((t) => inScope(t, district) && areaOf(t).id === id);
    if (!id || ts.length === 0) return send(res, 404, { detail: 'area not found' });
    const records = ts.map(evidence);
    const pack = { schema: 'relay.evidence.v1.area', generated_at: now(), district, area: records[0].area, tickets: records,
      integrity: { algorithm: 'sha256', ticket_count: records.length, digest: createHash('sha256').update(JSON.stringify(records.map((r) => r.integrity.digest))).digest('hex') } };
    return sendFile(res, pack, `relay-evidence-area-${slugOf(id)}.json`);
  }
  const ev = path.match(/^\/v1\/recover\/evidence\/(\d+)$/);
  if (req.method === 'GET' && ev) {
    const t = tickets.get(Number(ev[1]));
    return t ? sendFile(res, evidence(t), `relay-evidence-ticket-${t.ticket_no}.json`) : send(res, 404, { detail: 'ticket not found' });
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
  const m = path.match(/^\/v1\/tickets\/(\d+)(\/approve|\/on-the-way|\/resolve|\/locate)?$/);
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
    if (req.method === 'POST' && m[2] === '/locate') {
      // Same statuses as the backend: 409 fix or mesh estimate, 422 no landmark; otherwise a canned Maps answer.
      if (t.has_location) return send(res, 409, { detail: 'ticket has a GPS fix' });
      if (t.geo?.source === 'mesh') return send(res, 409, { detail: 'ticket already has a mesh location estimate' });
      if (!t.landmark?.trim()) return send(res, 422, { detail: 'ticket has no landmark to locate' });
      t.geo = { ...MAPS_GEO, query: t.landmark.trim() };
      return send(res, 200, t);
    }
    if (req.method === 'POST' && m[2] === '/on-the-way') { t.status = 'ON_THE_WAY'; return send(res, 200, t); }
    if (req.method === 'POST' && m[2] === '/resolve') { t.status = 'RESOLVED'; return send(res, 200, t); }
  }
  send(res, 404, { detail: 'not found' });
});

server.listen(PORT, () => console.log(`[mock] Relay mock backend on http://localhost:${PORT} (${tickets.size} tickets)`));
