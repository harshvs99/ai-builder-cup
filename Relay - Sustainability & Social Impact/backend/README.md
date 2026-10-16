# Relay backend

This is the control-room side of Relay. Gateway phones upload NEED packets
collected from the mesh. The backend turns them into prioritised tickets for the
dashboard and hands back confirmations (ACKs) for the mesh to carry home. The
wire format is in [`../protocol/SPEC.md`](../protocol/SPEC.md).

## Run it

```bash
cd "Relay - Sustainability & Social Impact/backend"
python3 -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
RELAY_DEV_MODE=1 uvicorn app.main:app --reload --port 8080
```

In a second terminal, play the demo scenario. A gateway uploads the insulin
request, the operator approves it, and the confirmation comes back in Odia:

```bash
python -m app.demo --url http://localhost:8080 --approve
```

Run the tests with `pytest`. Interactive API docs are at http://localhost:8080/docs.

Set `GEMINI_API_KEY` (from Google AI Studio) to switch on the Gemini steps. Without
a key, every step falls back to rules, so the demo never depends on the network
path to Gemini. See `.env.example` for all settings.

## How a packet becomes a ticket

1. **`POST /v1/sync`.** The gateway sends base64 NEED payloads with the hop count
   it read from the transport TTL, plus its ACK cursor. Uploads are idempotent on
   `msg_id`.
2. **Merge.** A report joins an existing unresolved ticket when it has the same
   category, is within 250 m (or comes from the same phone when there's no GPS
   fix), arrived within 6 h, and names a compatible item. If two items differ (for
   example "insulin" and "diabetes medicine"), Gemini decides whether they're the
   same need.
3. **Priority.** The score is computed in code (`app/priority.py`): category,
   urgency, vulnerabilities, people, corroborating reports and time waiting. That
   keeps it stable and explainable. Gemini may move the tier by one step and must
   give the operator a one-line reason.
4. **Dispatch draft.** A rule-based draft is written immediately. Gemini then
   replaces it with a polished one in English and the requester's language.
5. **Confirmations.** A RECEIVED ACK is queued straight away for each reporting
   phone, using fixed templates per language (`app/templates.py`). Operator
   actions (approve, on the way, resolve) queue further ACKs, and Gemini
   translates any operator note. Every gateway receives every ACK, because any
   gateway may be the one nearest the phone that asked.

Steps 1–3 and the RECEIVED ACK happen inside the sync request, so the ACK
travels back in the same response. Gemini enrichment runs afterwards as a
background task.

## API

| Method | Path | Who | Purpose |
|---|---|---|---|
| POST | `/v1/sync` | gateway (`X-Relay-Key`) | Upload NEEDs, download ACKs |
| GET | `/v1/tickets?status=active\|all\|OPEN\|DISPATCHED\|RESOLVED` | dashboard (`X-Operator-Key`) | Ticket queue, sorted by priority |
| GET | `/v1/tickets/{id}` | dashboard | Ticket detail with every report |
| POST | `/v1/tickets/{id}/approve` · `/on-the-way` · `/resolve` | dashboard | `{eta_min?, note?}`. Updates the ticket and queues ACKs |
| GET | `/v1/changes?since=REV` | dashboard | Tickets changed since a revision |
| GET | `/v1/stream?since=REV&key=…` | dashboard | Same as above, as server-sent events |
| POST | `/v1/dev/inject` | dev only | Inject a NEED from JSON, no phone needed |
| GET | `/healthz` | anyone | Liveness, LLM mode, counts |

## Not done yet

- **Storage is SQLite.** That's fine locally and on a single Cloud Run instance,
  but data is lost when the container restarts. A Firestore store can replace
  `app/store.py` once the team has a Google Cloud project.
- **Translations need checking.** The non-English confirmation templates need
  review by native speakers of Odia, Hindi, Tagalog, Waray and Cebuano.
- **Gemini is untested against the real API.** The prompts are covered by tests
  using a fake model, but haven't been run against real Gemini yet (no key in the
  build environment).
