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
| `RELAY_ENV` | `dev` | `prod` removes `POST /v1/dev/inject` (404) |
| `RELAY_CORS_ORIGINS` | empty | extra comma-separated origins; `http://localhost:5173` and `http://127.0.0.1:5173` are always allowed |
| `RELAY_STATIC_DIR` | unset | if the directory exists, the built dashboard is served at `/` (after all API routes, `index.html` fallback) |
| `RELAY_PUBSUB_TOPIC` | unset | full topic path; set = `/v1/sync` publishes and triage runs in `POST /internal/triage`; unset = in-process triage |
| `RELAY_PUBSUB_AUDIENCE`, `RELAY_PUBSUB_INVOKER_SA` | unset | expected `aud` and `email` of the push OIDC token; unset = `/internal/triage` answers 503 (fails closed) |
| `RELAY_TRIAGE_BUDGET_S` | `50` | wall-clock cap for one push delivery (phase 1 + Gemini); over budget returns 503 and Pub/Sub redelivers |
| `GOOGLE_GENAI_USE_VERTEXAI` | unset | `true` enables Gemini on Vertex AI via ADC (`GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, default `global`) |
| `GEMINI_API_KEY` | unset | enables Gemini with an API key (Vertex wins if both are set); neither = deterministic agent |
| `GEMINI_MODEL` | `gemini-flash-latest` | model for the Gemini agent |
| `RELAY_AGENT_BUDGET_S` | `20` | wall-clock cap on Gemini enrichment per batch |
| `PORT` | `8080` | Docker/Cloud Run listen port |

## Layout

```
app/codec.py            wire frame + NEED/ACK/HELLO/INVENTORY codec (strict; raises CodecError)
app/store/base.py       Store interface (ingest, approve_ticket, acks, ...) and shared types
app/store/sqlite.py     SqliteStore (local dev, tests)
app/store/firestore.py  FirestoreStore (packets, tickets, acks, gateways, counters; transactions)
app/priority.py         deterministic score, tier bands, reason text
app/cluster.py          near-duplicate matching + merge rules
app/templates.py        ACK texts (6 languages; non-English pending native-speaker review)
app/agent.py            triage pipeline; DeterministicAgent and GeminiAgent (API key or Vertex)
app/pubsub.py           publish side of /v1/sync, push envelope parsing, OIDC verifier
app/main.py             FastAPI app (SPEC section 7), /internal/triage, static dashboard
scripts/test-firestore.sh   emulator-backed test run
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
