# Relay backend

FastAPI service for the Relay offline BLE mesh. Gateway phones upload NEED frames; the backend
clusters them into tickets, scores and drafts dispatches, and queues confirmation ACKs that the
next gateway sync carries back into the mesh. API and wire format: `../protocol/SPEC.md`.

## Run

```bash
cd backend
uv sync
uv run uvicorn app.main:app --port 8080          # http://localhost:8080/docs
```

## Test

```bash
uv run pytest -q                  # offline: SQLite; the Firestore variants are skipped
scripts/test-firestore.sh         # starts the Firestore emulator (Docker, or gcloud + JDK), runs the
                                  # firestore-marked variants of the same API suite, stops it
```

Every API-level test runs against both stores. The Firestore variants only run when
`FIRESTORE_EMULATOR_HOST` points at a reachable emulator; they never contact Google Cloud.

## Try it

```bash
curl -s localhost:8080/healthz
curl -s -X POST localhost:8080/v1/dev/inject -H 'content-type: application/json' -d '{"needs":[
  {"category":"MEDICAL","urgency":3,"people":1,"vuln_flags":["ELDERLY"],"lat":20.29612,"lon":85.82451,
   "lang":"or","item":"insulin","landmark":"school","summary":"mother needs insulin"}]}'
curl -s localhost:8080/v1/tickets
curl -s -X POST localhost:8080/v1/tickets/101/approve
```

## Configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `RELAY_STORE` | `sqlite` | `sqlite` or `firestore` |
| `RELAY_DB` | `relay.db` | SQLite file path |
| `GOOGLE_CLOUD_PROJECT`, `RELAY_FIRESTORE_DB` | unset | Firestore: `firestore.Client(project=..., database=...)`, ADC credentials |
| `RELAY_DEVICE_KEY` | `dev-relay-key` | required in `X-Relay-Key` on `/v1/sync` (constant-time compare; **change in prod**) |
| `RELAY_OPERATOR_KEY` | unset | opt-in control-room auth. Unset = routes open, as before. Set = `GET /v1/tickets[/{no}]`, `POST /v1/tickets/{no}/approve\|on-the-way\|resolve`, `GET /v1/teams`, `POST /v1/dispatch/propose`, `POST /v1/sitreps/run`, `GET /v1/sitreps/latest`, `GET /v1/stats`, `POST /v1/broadcasts`, `GET /v1/broadcasts`, `POST /v1/broadcasts/{id}/approve\|withdraw`, `GET\|POST /v1/ready/warnings*`, `GET /v1/ready/shelters` and `POST /v1/ready/plan/preview` need `X-Operator-Key` (constant-time compare, 401 otherwise). `/v1/sync` keeps the device key; `/healthz`, `/v1/health` and `/internal/triage` are unchanged. A caller that is not the dashboard (e.g. the hourly sitrep Cloud Scheduler job) must send the header too. The dashboard reads it from `VITE_OPERATOR_KEY` or a `?key=` URL param |
| `RELAY_LANGS` | `en,hi` | comma list of enabled languages (must include `en`; codes from `en,hi,or,tl,war,ceb`). Only enabled languages get templates, model output and broadcast translations; anything else falls back to English. A typo fails at start. See [Languages](#languages) |
| `RELAY_ENV` | `dev` | `prod` removes `POST /v1/dev/inject` (404) |
| `RELAY_CORS_ORIGINS` | empty | extra comma-separated origins; `http://localhost:5173` and `http://127.0.0.1:5173` are always allowed |
| `RELAY_STATIC_DIR` | unset | if the directory exists, the built dashboard is served at `/` (after all API routes, `index.html` fallback) |
| `RELAY_PUBSUB_TOPIC` | unset | full topic path; set = `/v1/sync` publishes and triage runs in `POST /internal/triage`; unset = in-process triage |
| `RELAY_PUBSUB_AUDIENCE`, `RELAY_PUBSUB_INVOKER_SA` | unset | expected `aud` and `email` of the push OIDC token; unset = `/internal/triage` answers 503 (fails closed) |
| `RELAY_TRIAGE_BUDGET_S` | `50` | wall-clock cap for one push delivery (phase 1 + Gemini); over budget returns 503 and Pub/Sub redelivers |
| `GEMINI_API_KEY` | unset | enables Gemini with a Google AI Studio key; this is the production path (Cloud Run gets it from Secret Manager). Use a **paid-tier** key: free-tier prompts may be used to improve Google products, not acceptable for disaster victims' data. Unset (and no Vertex) = deterministic agent |
| `GOOGLE_GENAI_USE_VERTEXAI` | unset | optional alternative: `true` uses Gemini on Vertex AI via ADC (`GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, default `global`); wins over `GEMINI_API_KEY` if both are set. Not used by the Terraform deploy |
| `RELAY_GEOCODER` | unset | `gemini_maps` opts in to Maps-grounded landmark geocoding (works with `GEMINI_API_KEY` or Vertex; `GEMINI_GEOCODE_MODEL` overrides the model). **Do not enable for real use:** Google's Maps-grounding docs bar "high risk activities including emergency response services", on the Gemini API and Vertex AI alike. The built-in gazetteer stays the default |
| `GEMINI_MODEL` | `gemini-3.8-flash` | model for the Gemini agent |
| `SITREP_GEMINI_MODEL` | `gemini-pro-latest` | model that writes the sitrep prose; when its call fails the sitrep retries on `GEMINI_MODEL`, then falls back to templates |
| `BROADCAST_GEMINI_MODEL` | `GEMINI_MODEL` | model that translates broadcasts into the five app languages and, in a separate call, checks each translation against the source |
| `SHELTER_GEMINI_MODEL` | `gemini-3.8-flash` | model for the Google Search-grounded shelter list discovery (`POST /v1/ready/shelter-lists/discover`, 60 s timeout); no Gemini credentials = 503, enter the list by hand |
| `READY_GEMINI_MODEL` | `GEMINI_MODEL` | model that writes the Ready-mode household plan (text validated, else template); no Gemini credentials = templates |
| `RELAY_TTS_MODEL` | unset | enables server-side speech for Ready plans (e.g. `gemini-2.5-flash-preview-tts`, API key or Vertex). Unset = `audio_url` is null and phones use on-device TTS. `RELAY_TTS_VOICE` (default `Kore`) picks the prebuilt voice |
| `RELAY_AGENT_BUDGET_S` | `20` | wall-clock cap on Gemini enrichment per batch |
| `RELAY_EMBED_MERGE` | on | `off` disables embedding-based duplicate merging (rules only). Also off when no Gemini credentials. Embeddings replace only the exact item-text test; category, 200 m, 6 h and no-fix gates still apply |
| `RELAY_EMBED_MODEL` | `gemini-embedding-001` | embedding model (256 dims, `SEMANTIC_SIMILARITY`); uses `GEMINI_API_KEY` or Vertex like the agent |
| `RELAY_EMBED_THRESHOLD` | `0.80` | cosine similarity at which two items count as the same need. A starting guess: tune on multilingual pairs before the demo |
| `PORT` | `8080` | Docker/Cloud Run listen port |

## Layout

```
app/codec.py            wire frame + NEED/ACK/HELLO/INVENTORY/LOC_HINT codec (strict; raises CodecError)
app/meshgeo.py          approximate position from LOC_HINTs: intersection of discs, RSSI as a weak weight (pure)
app/store/base.py       Store interface (ingest, approve_ticket, acks, ...) and shared types
app/store/sqlite.py     SqliteStore (local dev, tests)
app/store/firestore.py  FirestoreStore (packets, tickets, acks, gateways, counters; transactions)
app/priority.py         deterministic score, tier bands, reason text
app/cluster.py          near-duplicate matching + merge rules
app/languages.py        language registry: which languages are enabled (RELAY_LANGS), names, GET /v1/languages
app/templates.py        ACK texts (en, hi reviewed; other languages in CONTRIB_TEMPLATES, used only when enabled)
app/agent.py            triage pipeline; DeterministicAgent and GeminiAgent (API key, or Vertex)
app/pubsub.py           publish side of /v1/sync, push envelope parsing, OIDC verifier
app/teams.py            dispatch teams: seed file (data/teams_puri.json), derived load and status
app/dispatch.py         allocator (priority, kind match, capacity, ETA), order drafting, ETA-bearing ACK
app/sitrep.py           counts from store queries, prose around them (template or Gemini, number-validated)
app/broadcast.py        broadcast in the enabled languages: translate, deterministic + model check, human approval
app/textgen.py          structured-JSON Gemini client for dispatch orders and sitreps
app/ready.py            Ready mode: shelters, warnings + CAP parser, offline pack, validated household plan
app/tts.py              optional Gemini text-to-speech (PCM to WAV), off unless RELAY_TTS_MODEL is set
app/data/               teams_puri.json, gazetteer_puri.json (landmarks with Hindi/Odia aliases), shelters_*.json (DEMO placeholders)
app/main.py             FastAPI app (SPEC section 7), /internal/triage, static dashboard
scripts/test-firestore.sh   emulator-backed test run
scripts/seed_district.py    40 NEEDs over a simulated cyclone night in Puri, posted through /v1/sync
tests/                  pytest (golden vectors, fuzz, API on both stores, Pub/Sub, static, agent)
```

## Modes

- **Local**: `RELAY_STORE=sqlite`, no topic. `/v1/sync` triages in-process and returns the ACKs.
- **Cloud**: `RELAY_STORE=firestore` + `RELAY_PUBSUB_TOPIC`. `/v1/sync` validates, records packets
  (create-if-absent on `msg_id`), publishes `{"gateway_node", "frames"}` and returns ACKs queued so far.
  Pub/Sub pushes to `/internal/triage`, which verifies the OIDC token, then triages inside the request
  (phase 1 per packet in one Firestore transaction each, then Gemini within the budget).
  A frame recorded but never triaged (publish failed, dead-lettered) is re-published by the next
  sync that carries it, so a gateway retry heals it.
- **Firestore details**: `tickets/{ticket_no}` (numbers from 101, `counters/ticket_no`),
  `acks/{seq}` (`counters/ack_seq`, the sync cursor), `ack_keys/{ref_msg_id}_{status}` (one ACK per
  NEED and status), `packets/{msg_id}`, `gateways/{node}`. No composite indexes: the merge lookup is
  one equality (`open_category`) with window/item filtered in memory; the ticket list is sorted in memory.
  Every ticket and ACK number is allocated in the same transaction that writes it.
- **ACK cursor reset**: a gateway cursor above the newest ACK sequence (store was wiped) counts as 0,
  so pending ACKs are re-sent; the mesh dedupes on `ack_id`.

## Agent behaviour

Per batch, phase 1 is fully deterministic and atomic per packet: cluster, score,
reason, dispatch draft, and a RECEIVED ACK per reporter. Phase 2 (only when Gemini is enabled)
asks Gemini, one structured-JSON call per touched ticket (8 s hard timeout), for a better reason,
a tier adjustment clamped to one tier, and a polished + translated dispatch draft. The score is
never model-written; ACK text is never model-written; any error or invalid field falls back to
the deterministic value and is logged.

## Mesh-assisted location (LOC_HINT)

A NEED with `loc_acc = 0` (no GPS fix) gets an approximate position when nearby phones that *do* have a fix
heard it directly from its requester and said so (`protocol/SPEC.md` §5.5). Gateways upload those hint frames in
the same `POST /v1/sync` as the NEEDs; the response then also carries `"hints": {"accepted", "duplicates", "rejected"}`
(absent when the upload held none). Hints are stored per NEED (`loc_hints` table / collection, one per relay and NEED,
first wins) and resolved to the ticket, whichever arrives first; a hint for a NEED not yet triaged is kept.

The estimate (`app/meshgeo.py`) is the **intersection of discs** centred on the relays with radius = relay accuracy
bound + 100 m, taken over the largest group of hints that agree (a wrong or forged hint is left out); RSSI is only
a weak weight (at most 2x) for where inside the intersection to put the point. It is served as the ticket's `geo`:
`{"lat","lon","source":"mesh","radius_m":130,"hint_count":2}`. Rules:

- never on a ticket with a GPS fix (the store refuses, in both SQLite and Firestore);
- replaces a gazetteer / Maps landmark estimate, and a later landmark lookup can no longer replace it;
- `has_location`, `loc_acc`, `lat`/`lon` are unchanged, so the ticket still counts as "without location";
- the dispatch ETA and order text use it ("approx. ..., within about 130 m, estimated from 2 nearby phone(s)");
- hints with `relay_node != sender`, a position of exactly 0,0, or an `observed_at` older than 72 h / more than 24 h
  ahead are rejected; hints are accepted from any holder of the device key, the same trust as NEEDs.

`POST /v1/dev/inject` takes `mesh_hints: [{lat, lon, accuracy_m (<= 100), rssi_dbm?}]` on a `loc_acc: 0` need to seed
phones around it (`GET /v1/tickets/{n}` then shows the `mesh` geo).

## Control room: teams, dispatch, sitrep

- `GET /v1/teams`: six Puri-district teams (2 boat, 2 medical, 2 supply), seeded from
  `app/data/teams_puri.json` on first use. `load` = approved, unreleased dispatches; `status` is
  `off` (operator), `busy` (at capacity) or `free`.
- `POST /v1/dispatch/propose`: walks OPEN tickets by score, then age; matches team kind to category
  (RESCUE/MISSING_PERSON boat, MEDICAL medical, the rest supply; any free team as fallback); never
  exceeds capacity (existing load plus this run). ETA = straight-line km from the team base to the
  ticket (GPS fix, else `geo`, else a flat 45 min) at 15 km/h (boat) or 25 km/h, plus 10 min prep,
  rounded up to 5. Gemini may write `order_en` / `order_local` (the district's local language, Hindi unless Odia is enabled);
  text that lacks the ticket number or the team name is replaced by a template. Proposals are stored
  on the tickets as `proposal` and nothing is sent to the mesh.
- `POST /v1/tickets/{no}/approve` takes an optional `{team_id?, eta_min?, order_en?}`; missing parts
  come from the pending proposal. It records `dispatch`, counts toward the team's load and puts the
  ETA into the DISPATCHED ACK (templated text). A full team is 409; no body and no proposal is the
  plain 45 min approval.
- `POST /v1/tickets/{no}/on-the-way` (optional `{eta_min?}`) and `POST /v1/tickets/{no}/resolve`:
  DISPATCHED -> ON_THE_WAY -> RESOLVED, or DISPATCHED -> RESOLVED; any other transition is 409, an
  unknown ticket 404, and repeating an action the ticket already took is a 200 no-op. Each queues one
  ACK per reporting origin (status 2 / 3 of SPEC §5.2, templated text in the reporter's language; the
  on-the-way ACK carries `eta_min` when given, else the ETA is unknown and the text has none).
  `dispatch.on_the_way_at` / `dispatch.resolved_at` record the times. Resolve releases the team's
  load in the same transaction (`Store.advance_ticket`); on-the-way keeps it. Resolved tickets are
  not open in `/v1/stats` and the sitrep, and the sitrep `resolved` count is real (`dispatched` counts
  DISPATCHED + ON_THE_WAY). SQLite databases made before these statuses are migrated on first open
  (the `tickets` table is rebuilt to widen its status CHECK).
- `POST /v1/sitreps/run?district=puri|delhi`, `GET /v1/sitreps/latest?district=…`: one report per
  district (`app/districts.py`: English + the district's `local_lang`: its native language if enabled, else Hindi, so by default Puri and Delhi both write English + Hindi; `RELAY_LANGS=en,hi,or` brings Odia back for Puri). Counts come from
  that district's tickets (an unlocated ticket counts in every district); Gemini Pro writes prose
  around them, and the text is used only if every number in it is one it was handed. Without
  `district`, `run` writes every district's report and returns `{"sitreps": [...]}`. Hourly via the
  Cloud Scheduler job in `infra/terraform/sitrep.tf` (not applied).
- Dispatch orders (`POST /v1/dispatch/propose`) are written in English plus the assigned team's
  district language, whatever language the requester used.
- Broadcasts (`app/broadcast.py`): `POST /v1/broadcasts {district, text, source_lang?}` drafts the message in
  en, hi, or, tl, war (one Gemini call translates, a separate one back-translates and judges each against
  the source; numbers and times must also match the source deterministically). `POST /v1/broadcasts/{id}/approve
  {langs?, edits?}` publishes the languages whose status is ok (or the listed/edited ones, re-checked; 422 if
  they fail); `POST .../withdraw` retracts; `GET /v1/broadcasts?district=&status=` lists. Phones with signal
  fetch `GET /v1/broadcasts/active?district=` with the **device** key: approved, not withdrawn, approved
  languages only. Mesh delivery is not built (proposal in the module docstring).
- `uv run python scripts/seed_district.py --url http://localhost:8080` loads the demo night.

## Ready mode (before landfall)

Phones still have signal before landfall, so this is a plain HTTPS fetch that phones cache for offline use
(`app/ready.py` has the design and the phone-side contract; the apps do not implement it yet).

- `POST /v1/ready/warnings` (operator) `{district, source: IMD|PAGASA|NDMA|manual, severity: watch|warning|alert, headline, expected_landfall_at?, expires_at?}`;
  `GET /v1/ready/warnings?district=&active=` and `GET /v1/ready/shelters?district=&lang=` (operator); `POST /v1/ready/warnings/{id}/cancel` (operator, idempotent);
  `POST /v1/ready/warnings/import-cap` (operator) `{district, cap_xml}`: paste a CAP 1.2 alert (IMD / NDMA SACHET / PAGASA);
  its actual, unexpired alerts become warnings (CAP Extreme = alert, Severe = warning, otherwise watch). Parsing only, no polling.
- `GET /v1/ready/pack?district=|lat=&lon=&lang=` (device key): `{district, generated_at, warning|null, shelters, checklist, version}`;
  `version` hashes the content, not the time. Shelter `name` is the local-script name when `lang` is the district language.
- `POST /v1/ready/plan` (device key) `{lang, district?|lat,lon, household{people, elderly, children, infants, pregnant, disability, medical_needs<=100, livestock}}`
  returns `{plan_id, steps, nearest_shelters[{id,name,distance_km}], source: gemini|template, steps_lang, audio_url}`.
  Nearest two non-hospital shelters by haversine. The model may name a shelter only through `[S1]`/`[S2]` tokens that the
  server replaces; steps are rejected (template used) on a wrong count (3 to 10), a step over 200 characters, an unknown
  token, a number other than 2 or 3, or a literal place name. Languages without a template (tl, war) get English steps
  (`steps_lang: "en"`). Plans are cached in memory by (district, lang, household bucket, shelters, warning).
  `POST /v1/ready/plan/preview` is the same with the operator key, for the dashboard.
- `GET /v1/ready/plan/{plan_id}/audio` (device key): `audio/wav` (16-bit mono 24 kHz) when `RELAY_TTS_MODEL` is set; otherwise 404
  and phones read the plan with on-device TextToSpeech. Odia and Filipino are preview languages in Gemini TTS and Waray is likely
  unsupported (no `audio_url`, device TTS).
- **Shelter lists (Discover, verify, publish)** (`app/shelters.py`), all operator unless noted:
  `POST /v1/ready/shelter-lists/discover` `{district}` asks Gemini with the Google Search tool for the district disaster management
  authority's shelter list and stores a **draft** (502 if the model call fails, 503 without Gemini credentials);
  `POST /v1/ready/shelter-lists` `{district, entries}` makes a draft by hand; `GET /v1/ready/shelter-lists?district=`;
  `PATCH /v1/ready/shelter-lists/{id}` `{entries}` replaces a draft's entries (re-validated; officers set lat/lon, kind, capacity, `verified`);
  `POST /v1/ready/shelter-lists/{id}/publish` (422 unless an entry is verified and has in-district coordinates; only those are published,
  the rest are returned in `skipped`; the district's previous published list is archived).
  `GET /v1/public/shelters?district=` (**no auth**) is the published, verified list `{district, published_at, shelters[], source_note}` (404 before any publish).
  Once a list is published, `ready.shelters_for` (pack, plans, `GET /v1/ready/shelters`) uses it instead of the demo files; the pack carries
  `shelters_source` ("published" | "demo") and `shelters_published_at`.
  Model output is untrusted: coordinates outside the district box are dropped, links not among the grounding sources are flagged
  `source_unconfirmed`, duplicates dropped, every entry starts `verified: false`. **Search Suggestions** (`search_suggestions_html`) must be
  displayed wherever grounded results are shown (the dashboard renders them in a sandboxed iframe). Search-grounded results must be reviewed
  against the authority's own document before publishing; the officer who ticks Verified is accountable. Check the Gemini API terms on
  storing and redistributing grounded results: published entries are meant to be the district's own verified data. Google Maps grounding is not used.
- **The shelter lists in `app/data` are placeholders.** `app/data/shelters_puri.json` and `shelters_delhi.json` are invented, every entry has
  `"demo": true`. Replace them with the official lists (OSDMA multipurpose cyclone shelter list for Puri, DDMA for Delhi) before a pilot.
  The Odia plan and checklist texts are pending community review (`CONTRIB_T`, `CONTRIB_CHECKLIST`) and are served only when Odia is enabled; a request in a language that is not enabled gets English steps with `steps_lang: "en"`.

## Languages

The team can read and review English and Hindi only, so only those two are enabled by default
(`RELAY_LANGS=en,hi`). The other wire languages (Odia `or`, Filipino `tl`, Waray `war`, Cebuano `ceb`)
stay registered in `app/languages.py` so they can be switched on as community support grows, but they
are off, and everything that writes text goes through `languages.effective(code)`: a language that is
not enabled gets English.

| Area | Enabled language | Not enabled |
|---|---|---|
| ACK text (`templates.py`) | reviewed template (`TEMPLATES`) or, if enabled, a draft in `CONTRIB_TEMPLATES` | English text (always within the 112-byte limit) |
| Dispatch order and sitrep (`dispatch.py`, `sitrep.py`) | English + the district's `local_lang` | `local_lang` is Hindi when the district's native language (Puri: Odia) is off |
| Triage dispatch draft (`agent.py`) | model translation into the requester's language | English, no model translation |
| Broadcast (`broadcast.py`) | translated, checked and approved per enabled language | not translated; `source_lang` must be enabled |
| Ready plan and pack (`ready.py`) | template or model steps in the language | English, `steps_lang: "en"` |
| Server speech (`tts.py`) | `TTS_LANGS` intersected with enabled | no `audio_url` |

`GET /v1/languages` (no auth) lists `{code, english_name, native_name, enabled, reviewed}` for every
registered language; the dashboard and apps show only the enabled ones.

Strings for languages that are not reviewed live in clearly marked "pending community review"
structures (`CONTRIB_TEMPLATES`, `CONTRIB_ORDER_LOCAL`, `CONTRIB_TEMPLATES` in `sitrep.py`,
`CONTRIB_T` and `CONTRIB_CHECKLIST` in `ready.py`) and are consulted only when the language is enabled.

**Enabling a language (community reviewer).** 1) Have a native speaker review every string in the
`CONTRIB_*` structures for that language and fix them; move them into the main (reviewed) tables.
2) Set `reviewed` to true in `app/languages.py` and add the code to `DEFAULT_ENABLED` (or, to try
it first, set `RELAY_LANGS=en,hi,<code>`). 3) Add tests: update the "default" expectations in
`tests/test_templates_priority.py`, `test_dispatch.py`, `test_sitrep.py`, `test_ready.py` and
`test_broadcast.py`, and keep a test that the language is off when it is not listed. 4) Mirror the
change in `dashboard/src/lib/languages.ts`.

## Deploy

Google Cloud (Cloud Run + Firestore + Pub/Sub + Gemini API key in Secret Manager): `../infra/README.md`; the image is built
from the Relay root with `infra/Dockerfile` (backend plus the built dashboard in `/app/static`).

Standalone SQLite on Cloud Run (demo only), using this folder's own Dockerfile:

```bash
gcloud run deploy relay-backend --source . --region <region> --max-instances 1 \
  --set-env-vars RELAY_DEVICE_KEY=<secret>,RELAY_CORS_ORIGINS=https://<dashboard-host>
```

SQLite sits on the instance's ephemeral disk, so a restart wipes tickets and ACKs and ACK ids
restart at 1; gateways holding a higher cursor are re-synced automatically (cursor reset rule above).
Pin `--max-instances 1`.
