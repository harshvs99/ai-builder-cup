# Relay infrastructure (Google Cloud)

One Cloud Run service (`relay`) serves the FastAPI API and the built dashboard from the same origin. Firestore holds state, Pub/Sub buffers gateway uploads for Gemini triage (Vertex AI, no API key). Terraform with local state; a project is deployed and destroyed with one script each.

```
phones ──HTTPS──► Cloud Run "relay" ──► Firestore (named DB relay-xxxxxx)
dashboard ──────►  /v1/*  /healthz  /  (static)      │
                      │ publish                       │ Vertex AI Gemini
                      ▼                               ▼
              Pub/Sub relay-packets ──push (OIDC)──► /internal/triage
                      └─ 5 failed tries ─► relay-packets-dlq (+ 7-day hold subscription)
```

## Prerequisites
- `gcloud` logged in as a project Owner (or equivalent): `gcloud auth login` **and** `gcloud auth application-default login` (Terraform uses ADC).
- `terraform` >= 1.6 on PATH. No Docker needed locally; images build in Cloud Build.
- A project with billing enabled. If an org policy blocks `allUsers` (domain-restricted sharing), the public invoker binding will fail; that needs an org exception, not a code change.

## Deploy
```bash
./infra/deploy.sh <PROJECT_ID> [REGION]      # REGION defaults to asia-south1
RELAY_AUTO_APPROVE=1 ./infra/deploy.sh <PROJECT_ID>   # no prompts
```
Phases: (1) enable APIs, create Artifact Registry, build service account and staging bucket; (2) Cloud Build the image `infra/Dockerfile` (context = Relay root); (3) full `terraform apply` with that image; (4) `/healthz` check, fails loudly. First run on a fresh project takes roughly 6 to 10 minutes **[estimate]**; re-runs are mostly the image build.

State is local, one Terraform workspace per project, under `infra/terraform/terraform.tfstate.d/` (git-ignored). Losing it orphans the resources; there is no remote backend by design (single operator, hackathon scope).

## Point a gateway or simulator at it
```bash
terraform -chdir=infra/terraform workspace select <PROJECT_ID>
terraform -chdir=infra/terraform output -raw service_url     # base URL, also the dashboard
terraform -chdir=infra/terraform output -raw device_key      # value for header X-Relay-Key
```
Gateways `POST <service_url>/v1/sync` with header `X-Relay-Key: <device_key>`. Open `<service_url>/` for the control room. Rotating the key: `terraform apply -replace=random_password.device_key` (new secret version, new Cloud Run revision).

## Tear down
```bash
./infra/destroy.sh <PROJECT_ID>
```
Removes everything Terraform created, including the Firestore database (data is deleted), secret, topics, images and staging bucket. Intentionally left: enabled APIs, Cloud Build history and logs, the Pub/Sub service agent. Firestore database ids are random per deploy, so a rebuild never collides with a deleted database id.

## Cost (estimates, not quotes)
| Item | Expectation |
|---|---|
| Cloud Run | Scale to zero, 1 vCPU / 512Mi, request-billed; idle cost 0. Demo traffic is inside the free tier |
| Firestore | Free tier (1 GiB, 50k reads / 20k writes per day) covers a demo |
| Pub/Sub | Negligible (first 10 GiB/month free) |
| Vertex AI Gemini | Per token, the only cost that scales with use; a demo is cents |
| Artifact Registry | Cleanup keeps 5 images, well under a dollar a month |
| Cloud Build | Default machine, free minutes cover a handful of builds |

## Design notes
- **Pub/Sub auth without a URL cycle.** The push subscription needs the service URL; the service would need that URL as the token audience. Instead the audience is the fixed string `relay-triage` and the app verifies the token. One `terraform apply`, no second pass. The Cloud Run service is public, so the OIDC check in the app is the *only* guard on `/internal/triage`; it must not be skipped. (`relay-push` also holds `run.invoker`, which is redundant while the service is public, but keeps the config valid if you later make it private.)
- **Least privilege.** `relay-run`: `datastore.user`, `aiplatform.user`, `logging.logWriter` at project level (no practical resource-level alternative); `pubsub.publisher` on the one topic; `secretAccessor` on the one secret. `relay-push`: `run.invoker` on the service only. `relay-build`: AR writer on the repo, objectViewer on the staging bucket, `logging.logWriter`. Builds use this account because new projects no longer grant Cloud Build's default account enough rights.
- **google-beta provider** is used for one resource only (`google_project_service_identity`, creates the Pub/Sub service agent so IAM grants never race it).

## Backend contract

The backend must implement exactly this. Terraform provides it; nothing here is optional.

### Environment variables the service receives
| Variable | Value | Notes |
|---|---|---|
| `PORT` | `8080` (Cloud Run) | uvicorn binds `0.0.0.0:$PORT` |
| `RELAY_ENV` | `prod` | disable `/v1/dev/inject` when `prod` |
| `RELAY_STORE` | `firestore` | selects the Firestore repository (SQLite remains the dev default) |
| `RELAY_FIRESTORE_DB` | `relay-<6 hex>` | `firestore.Client(project=GOOGLE_CLOUD_PROJECT, database=RELAY_FIRESTORE_DB)`; it is a named database, never `(default)` |
| `RELAY_STATIC_DIR` | `/app/static` | built dashboard; serve at `/` (same origin), mounted **after** all API routes, with `index.html` fallback |
| `RELAY_DEVICE_KEY` | 32-char secret (Secret Manager `relay-device-key`) | compare to `X-Relay-Key` on `/v1/sync` with constant-time compare |
| `RELAY_PUBSUB_TOPIC` | `projects/<project>/topics/relay-packets` | full path; `PublisherClient.publish(RELAY_PUBSUB_TOPIC, ...)` |
| `RELAY_PUBSUB_AUDIENCE` | `relay-triage` | literal string, not a URL |
| `RELAY_PUBSUB_INVOKER_SA` | `relay-push@<project>.iam.gserviceaccount.com` | expected `email` claim |
| `GOOGLE_GENAI_USE_VERTEXAI` | `true` | `google-genai` then uses Vertex + ADC; treat this (not `GEMINI_API_KEY`) as the enable switch for the Gemini agent in prod |
| `GOOGLE_CLOUD_PROJECT` | project id | |
| `GOOGLE_CLOUD_LOCATION` | `global` (default) | Vertex location |
| `GEMINI_MODEL` | `gemini-flash-latest` (default) | |

Credentials are Application Default Credentials from the `relay-run` service account (metadata server); no key files.

### `/v1/sync` publishes to Pub/Sub
After validating and deduping, publish one message per accepted batch to `RELAY_PUBSUB_TOPIC`:
- `data` = UTF-8 JSON bytes: `{"gateway_node": "<id as sent>", "frames": ["<base64 outer frame>", ...]}`. Frames are the same base64 strings the gateway uploaded (only frames not already stored are needed). Keep each message well under 10 MB.
- No attributes are required.

### `POST /internal/triage` (Pub/Sub push target)
- Request body is the push envelope: `{"message": {"data": "<base64 of the JSON above>", "messageId": "...", "publishTime": "...", "attributes": {}}, "subscription": "projects/<p>/subscriptions/relay-packets-triage", "deliveryAttempt": <int>}`. Base64-decode `message.data`, then JSON-parse to get `gateway_node` and `frames`.
- **Auth (mandatory, route is publicly reachable):** require `Authorization: Bearer <JWT>`; verify with `google.oauth2.id_token.verify_oauth2_token(token, google.auth.transport.requests.Request(), audience=RELAY_PUBSUB_AUDIENCE)`; additionally require `email_verified` is true and `email == RELAY_PUBSUB_INVOKER_SA`. Missing or invalid token: 401. Wrong email: 403. `/v1/*` device-key auth does not apply here, and this route must not accept `X-Relay-Key` as a substitute.
- **Response semantics:** any 2xx acks. Anything else is retried with 10 s to 300 s backoff; after 5 delivery attempts the message goes to `relay-packets-dlq` (held 7 days in `relay-packets-dlq-hold`). Return 2xx for permanently bad payloads you want dropped; return 4xx/5xx to push to the DLQ for inspection.
- **Idempotent:** delivery is at least once. Dedupe on `msg_id` (frames) as `/v1/sync` already does; running the same message twice must not create duplicate tickets.
- **Deadline:** ack deadline 60 s, Cloud Run request timeout 120 s. Do the triage (Gemini calls included) inside the request, within about 50 s. Cloud Run CPU is throttled outside requests, so do not rely on background work after returning a response.

### Other constraints
- `/healthz` returns HTTP 200 with `{"ok": true}` (deploy.sh checks for it) and must not touch Firestore.
- No Firestore composite indexes are provisioned. Use single-field queries/orders (or sort in memory), or ask for `google_firestore_index` resources to be added.
- Dependencies to add to `backend/pyproject.toml` and `uv.lock`: `google-cloud-firestore`, `google-cloud-pubsub`, `google-auth`, `requests` (for the token verify transport). The image installs with `uv sync --frozen --no-dev`, so the lock must be committed in sync.
- The dashboard is built with `VITE_API_BASE=""` and calls `/v1/*` on its own origin; no CORS needed in prod.
