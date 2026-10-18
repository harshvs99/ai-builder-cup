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
| `RELAY_OPERATOR_KEY` | unset | opt-in control-room auth. Unset = routes open, as before. Set = `GET /v1/tickets[/{no}]`, `POST /v1/tickets/{no}/approve\|on-the-way\|resolve`, `GET /v1/teams`, `POST /v1/dispatch/propose`, `POST /v1/sitreps/run`, `GET /v1/sitreps/latest` and `GET /v1/stats` need `X-Operator-Key` (constant-time compare, 401 otherwise). `/v1/sync` keeps the device key; `/healthz`, `/v1/health` and `/internal/triage` are unchanged. A caller that is not the dashboard (e.g. the hourly sitrep Cloud Scheduler job) must send the header too. The dashboard reads it from `VITE_OPERATOR_KEY` or a `?key=` URL param |
| `RELAY_ENV` | `dev` | `prod` removes `POST /v1/dev/inject` (404) |
| `RELAY_CORS_ORIGINS` | empty | extra comma-separated origins; `http://localhost:5173` and `http://127.0.0.1:5173` are always allowed |
| `RELAY_STATIC_DIR` | unset | if the directory exists, the built dashboard is served at `/` (after all API routes, `index.html` fallback) |
| `RELAY_PUBSUB_TOPIC` | unset | full topic path; set = `/v1/sync` publishes and triage runs in `POST /internal/triage`; unset = in-process triage |
| `RELAY_PUBSUB_AUDIENCE`, `RELAY_PUBSUB_INVOKER_SA` | unset | expected `aud` and `email` of the push OIDC token; unset = `/internal/triage` answers 503 (fails closed) |
| `RELAY_TRIAGE_BUDGET_S` | `50` | wall-clock cap for one push delivery (phase 1 + Gemini); over budget returns 503 and Pub/Sub redelivers |
| `GOOGLE_GENAI_USE_VERTEXAI` | unset | `true` enables Gemini on Vertex AI via ADC (`GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, default `global`) |
| `GEMINI_API_KEY` | unset | enables Gemini with an API key (Vertex wins if both are set); neither = deterministic agent |
| `GEMINI_MODEL` | `gemini-flash-latest` | model for the Gemini agent |
| `SITREP_GEMINI_MODEL` | `GEMINI_MODEL` | model that writes the sitrep prose (set a Pro-tier name here) |
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
app/templates.py        ACK texts (6 languages; non-English pending native-speaker review)
app/agent.py            triage pipeline; DeterministicAgent and GeminiAgent (API key or Vertex)
app/pubsub.py           publish side of /v1/sync, push envelope parsing, OIDC verifier
app/teams.py            dispatch teams: seed file (data/teams_puri.json), derived load and status
app/dispatch.py         allocator (priority, kind match, capacity, ETA), order drafting, ETA-bearing ACK
app/sitrep.py           counts from store queries, prose around them (template or Gemini, number-validated)
app/textgen.py          structured-JSON Gemini client for dispatch orders and sitreps
app/data/               teams_puri.json, gazetteer_puri.json (landmarks with Hindi/Odia aliases)
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
  rounded up to 5. Gemini may write `order_en` / `order_local` (Hindi or Odia by ticket language);
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
- `POST /v1/sitreps/run`, `GET /v1/sitreps/latest`: counts come from store queries; Gemini writes
  English and Odia prose around them, and the text is used only if every number in it is one it was
  handed. Hourly via the Cloud Scheduler job in `infra/terraform/sitrep.tf` (not applied).
- `uv run python scripts/seed_district.py --url http://localhost:8080` loads the demo night.

## Deploy

Google Cloud (Cloud Run + Firestore + Pub/Sub + Vertex AI): `../infra/README.md`; the image is built
from the Relay root with `infra/Dockerfile` (backend plus the built dashboard in `/app/static`).

Standalone SQLite on Cloud Run (demo only), using this folder's own Dockerfile:

```bash
gcloud run deploy relay-backend --source . --region <region> --max-instances 1 \
  --set-env-vars RELAY_DEVICE_KEY=<secret>,RELAY_CORS_ORIGINS=https://<dashboard-host>
```

SQLite sits on the instance's ephemeral disk, so a restart wipes tickets and ACKs and ACK ids
restart at 1; gateways holding a higher cursor are re-synced automatically (cursor reset rule above).
Pin `--max-instances 1`.
