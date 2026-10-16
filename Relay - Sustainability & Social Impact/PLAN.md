# Relay: implementation plan

> Relief messaging for when the towers go down. Every villager's phone becomes a mesh node.
> Gemma on the device turns a spoken need into a packet of about 100 bytes. The packet hops
> phone to phone over Bluetooth or Wi-Fi Direct. At the first phone with signal, a Gemini
> agent turns the packets into a prioritised needs map for the district control room, and a
> confirmation travels back through the mesh.

Everything in this document is built inside `Relay - Sustainability & Social Impact/` (referred to below as the Relay folder).

---

## 1. Goals and non-goals

### Must have (the demo moment)
1. Four Android phones: three in airplane mode with only Bluetooth and Wi-Fi switched back on, and one online.
2. On phone A, someone records the voice note *"my mother needs insulin, near the school"*.
3. The phone converts speech to text and Gemma classifies it **offline** into a compact packet: category `MEDICAL`, high urgency, item "insulin", landmark "school", plus GPS.
4. The packet takes at least two hops (A → B → D, with C as an optional third relay) and reaches gateway phone D.
5. D uploads the packet. A Gemini agent clusters, prioritises and drafts a dispatch, and the ticket appears on the **control-room map**.
6. A confirmation, translated into the requester's language, travels back A ← B ← D and shows on phone A.

### Should have
- Store-and-forward: a phone that walks from one cluster to another carries messages across (delay-tolerant networking).
- Duplicate collapse: exact duplicates are dropped in the mesh, and near-duplicates (same need, same place) are merged before dispatch.
- Supported languages: English, Hindi and Odia (Odisha), and Tagalog, Waray and Cebuano (Leyte).
- A human operator approves dispatches on the dashboard.

### Non-goals for v1
- iOS. Nearby Connections mesh support is Android-first.
- End-to-end encryption and strong identity. The packet format is designed so signatures can be added later (§4.4).
- Sending voice or images over the mesh. Only text packets travel.
- True routing protocols such as AODV or BATMAN. We use flooding plus anti-entropy, which is enough at village scale.

---

## 2. System architecture

```
 ┌──────────── OFFLINE MESH (airplane mode, BT + Wi-Fi on) ────────────┐        ┌──────────── CLOUD ────────────┐
 │                                                                     │        │                                │
 │  Phone A (origin)        Phone B (relay)        Phone D (gateway)   │ HTTPS  │  Relay API (Cloud Run)         │
 │  ┌──────────────┐        ┌────────────┐         ┌──────────────┐    │ ─────► │   POST /v1/sync                │
 │  │ Voice → ASR  │  BT /  │ store &    │  BT /   │ store &      │    │        │      │                         │
 │  │ Gemma → JSON │ ─────► │ forward    │ ──────► │ forward      │    │        │      ▼                         │
 │  │ encode 100 B │  WiFi  │ dedupe     │  WiFi   │ + uplink     │    │        │  Gemini agent (tools)          │
 │  └──────────────┘ Direct └────────────┘  Direct └──────────────┘    │        │   cluster · prioritise ·       │
 │         ▲                      ▲                       │            │ ◄───── │   draft dispatch · translate · │
 │         └──── ACK packet ──────┴──── ACK packet ───────┘            │  ACKs  │   queue confirmation           │
 │                                                                     │        │      │                         │
 └─────────────────────────────────────────────────────────────────────┘        │      ▼                         │
                                                                                │  Firestore ──► Control-room    │
                                                                                │               dashboard (map)  │
                                                                                └────────────────────────────────┘
```

Every phone runs the **same APK**. A phone becomes a gateway automatically when
`ConnectivityManager` reports a network with `NET_CAPABILITY_VALIDATED`.

**Why the agent runs in the cloud and not on the gateway phone:** the control-room map
needs a shared backend anyway. Running the agent server-side also keeps API keys off
villagers' phones, and lets several gateways feed one picture. The gateway phone stays a
thin uplink: it uploads packets and downloads ACKs.

---

## 3. Repository layout

```
Relay - Sustainability & Social Impact/
├── PLAN.md                     ← this file
├── README.md
├── protocol/                   # Single source of truth for the wire format
│   ├── relay.proto             #   NeedPacket, AckPacket, Hello, Inventory
│   └── README.md               #   Byte budget and versioning rules
├── android/                    # Kotlin, Gradle multi-module
│   ├── app/                    #   Compose UI, foreground service, DI wiring
│   ├── core-protocol/          #   Pure Kotlin/JVM: codec, IDs, dedupe, merge logic (no Android deps)
│   ├── mesh/                   #   Nearby Connections transport, peer manager, gossip engine
│   ├── nlu/                    #   ASR + Gemma (LiteRT-LM) + rule-based fallback extractor
│   ├── storage/                #   Room DB: packets, seen-set, outbox, ACKs
│   └── gateway/                #   Connectivity watcher + sync client
├── simulator/                  # JVM CLI that reuses core-protocol to simulate N nodes and topologies
├── backend/                    # Python 3.12, FastAPI on Cloud Run
│   ├── app/api.py              #   /v1/sync, /v1/tickets/{id}/approve
│   ├── app/agent/              #   Gemini agent + tool implementations
│   ├── app/priority.py         #   Deterministic priority scoring
│   └── tests/
├── dashboard/                  # Vite + React + MapLibre GL (OSM tiles), Firestore live listeners
├── ml/                         # Gemma fine-tuning + conversion
│   ├── data/                   #   Synthetic multilingual distress dataset (generated by Gemini)
│   ├── finetune_270m.ipynb     #   LoRA fine-tune of Gemma 3 270M → JSON extraction
│   ├── convert_litert.py       #   Export to .litertlm / .task, int4/int8 quantisation
│   └── eval/                   #   Held-out set + accuracy and latency scripts
└── docs/
    ├── demo-runbook.md         # Exact phone setup and script for the video
    └── architecture.md
```

---

## 4. Wire protocol (`protocol/`)

> **Update:** the implemented format is a fixed binary layout rather than protobuf. It has no codegen, a deterministic size, and is easy to mirror in Kotlin. Hop count moved out of the payload. [`protocol/SPEC.md`](protocol/SPEC.md) is now the source of truth; the tables below are the original sketch.

### 4.1 Packet types
| Type | Purpose | Approx. size |
|---|---|---|
| `NEED` | A classified need from a villager | 70–110 B |
| `ACK` | Confirmation from the control room to the origin | 40–100 B |
| `HELLO` | Sent on connect: node ID, gateway flag, hop distance to the nearest gateway | ~12 B |
| `INVENTORY` | Message IDs this node holds, used for anti-entropy | 8 B × n |

### 4.2 `NEED` layout (protobuf, all fields bounded)
| Field | Type | Bytes | Notes |
|---|---|---|---|
| version / type | uint8 ×2 | 2 | |
| msg_id | fixed64 | 8 | Random 64-bit value. Mesh-level dedupe key |
| origin_node | fixed32 | 4 | Random per install; ACKs are addressed to it |
| created_at | uint32 | 4 | Unix seconds |
| hops | uint8 | 1 | Incremented on each forward. Dropped when > `MAX_HOPS` (8) |
| category | enum | 1 | `MEDICAL, RESCUE, WATER, FOOD, SHELTER, MISSING_PERSON, OTHER` |
| urgency | uint8 | 1 | 0–3, as judged by Gemma |
| people | uint8 | 1 | Number of people affected |
| flags | bitfield | 1 | elderly · child · pregnant · injured · trapped |
| lat/lon | sint32 ×2 | 8 | Degrees × 1e5 (about 1 m). GPS works offline |
| loc_acc | uint8 | 1 | Accuracy bucket. 0 means no fix |
| lang | uint8 | 1 | Requester language, so the ACK can be translated back |
| item | string ≤ 16 | ≤ 17 | For example `insulin` |
| landmark | string ≤ 24 | ≤ 25 | For example `school` (English, normalised by Gemma) |
| summary | string ≤ 40 | ≤ 41 | English. For example `mother needs insulin, diabetic` |

A unit test fails the build if any encoded `NEED` is larger than **128 bytes**.

### 4.3 `ACK` layout
`ref_msg_id` (8), `target_node` (4), `ticket_no` (4), `status` (1: `RECEIVED / DISPATCHED / ON_THE_WAY / RESOLVED`), `eta_min` (2), and `text` (≤ 80 B UTF-8, already in the requester's language).

### 4.4 Future-proofing
- A reserved field holds a 64-byte Ed25519 signature for anti-spoofing in v2.
- The version byte allows schema changes. Nodes still forward unknown versions as opaque bytes.

---

## 5. Android app

**Stack:** Kotlin, Jetpack Compose, Hilt, Room, Coroutines/Flow,
`play-services-nearby` (Nearby Connections), LiteRT-LM for Gemma, and
`play-services-location`. Recommended minimum is Android 10; Android 12+ is targeted.

### 5.1 Mesh transport (`mesh/`)
- **Strategy:** `Strategy.P2P_CLUSTER`, which supports M-to-N connections. Every node
  **advertises and discovers at the same time** under service ID `org.relay.mesh`.
- **Endpoint name** encodes `nodeId | isGateway | hopsToGateway`. Peers can then prefer
  gateways before they even connect.
- **Connection policy:** auto-accept, with at most about 4 concurrent connections. When
  there are too many candidates, prefer peers that are closer to a gateway or that hold
  inventory we lack.
- **Payloads:** only `Payload.fromBytes` (a 32 KB limit; ours are about 100 B). This works
  even over plain BLE, so no Wi-Fi upgrade is required.
- **Foreground service** of type `connectedDevice` with a persistent notification, so the
  mesh survives screen-off and Doze.
- **Permissions:** `BLUETOOTH_SCAN/ADVERTISE/CONNECT`, `NEARBY_WIFI_DEVICES`
  (`neverForLocation`, Android 13+), `ACCESS_FINE_LOCATION` (needed for GPS and for BLE
  on older versions), `ACCESS_WIFI_STATE/CHANGE_WIFI_STATE`, `RECORD_AUDIO`,
  `FOREGROUND_SERVICE_CONNECTED_DEVICE`, and `POST_NOTIFICATIONS`. A first-run wizard
  walks the user through them.

### 5.2 Gossip engine (`mesh/` + `core-protocol/`)
An epidemic store-and-forward protocol:
1. **On connect:** exchange `HELLO` and then `INVENTORY` (IDs from the last 24 h). Each
   side pushes the packets the other is missing. This is anti-entropy, and it delivers
   messages that arrived while two phones were apart.
2. **On new packet** (created locally or received): check the seen-set in Room. If it is
   new, persist it, increment `hops`, and push it to every connected peer except the
   sender.
3. **ACK routing:** ACKs flood the same way. Only the node whose ID matches `target_node`
   shows the ACK, and every other node relays it. Optimisation: an ACK is not forwarded
   further once its target has been seen to acknowledge it.
4. **Expiry:** packets older than 72 h, or with more than `MAX_HOPS` hops, are dropped.
   The seen-set is bounded with LRU eviction.
5. **Uplink bias (stretch):** `hopsToGateway` from `HELLO` builds a simple gradient. When
   connection slots are scarce, `NEED` packets go to peers with a lower value first.

All of this logic lives in `core-protocol`, which has no Android dependencies, so
`simulator/` and the unit tests run the exact same code.

### 5.3 Turning speech into a packet (`nlu/`)
```
voice note ─► ASR (offline) ─► transcript ─► Gemma (JSON-constrained) ─► validate ─► NeedPacket
                                         └─► rule-based extractor (fallback / cross-check)
```
- **ASR:** use Android `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE` and pre-downloaded
  language packs. If the chosen Gemma E2B build accepts audio input, the phone sends audio
  straight to the model and skips the separate ASR step. This is decided in Phase 3 after
  testing on our phones.
- **Model tiering,** chosen at runtime:
  | Device | Model | Why |
  |---|---|---|
  | ≥ 6 GB RAM | Gemma 4 E2B (int4) | Better multilingual understanding, can handle audio |
  | Lower-end | Gemma 3 270M, **fine-tuned** (int8) | About 300 MB, fast on any phone |
  | Model fails, times out (> 4 s) or gives invalid JSON | Keyword/regex extractor | The demo never dead-ends |
- **Prompt contract:** the model returns only the JSON shape of `NEED`, with enum values
  listed explicitly. The parser rejects or repairs bad output; for example, it clamps
  enums and truncates strings to their byte limits.
- **The user stays in control:** before sending, phone A shows the parsed card
  (🩺 Medical · Insulin · near School · 1 person, elderly). The user can tap to fix a
  field. This also makes the demo easy to read on video.
- **Model distribution:** there will be no internet after the cyclone, so the model ships
  with the app (Play Asset Delivery install-time pack, or sideloaded for the demo). Stretch
  goal: share the APK and model from phone to phone over Nearby.

### 5.4 Duplicate collapse
- **Exact:** the `msg_id` seen-set means a packet is never stored or forwarded twice.
- **Origin-side:** if the same user re-records a need with the same category and item
  within 30 minutes, the phone updates the existing packet instead of creating a new one.
- **Near-duplicate (relays + backend):** two `NEED`s with the same category, the same
  geohash-7 cell (about 150 m) and a matching item within 6 h are linked. Relays still
  forward both; this keeps mesh correctness simple. The backend merges them into **one
  ticket with a report count**, so the control room sees one corroborated need instead of
  five rows.
- **Stretch:** relays suppress near-duplicates and send a 16-byte `CORROBORATE{ref, +1}`
  instead.

### 5.5 Gateway (`gateway/`)
- `NetworkCallback` watches for a validated network. The app then flips `isGateway`,
  re-advertises, and starts a sync loop: every 10 s while online, plus immediately when a
  new packet arrives.
- `POST /v1/sync {gatewayNode, packets[] (base64 protobuf), ackCursor}` returns
  `{acks[] (protobuf), ackCursor}`. Uploads are idempotent on `msg_id`.
- The gateway injects the returned ACKs into its own mesh store, and they flood back to
  the origin.

### 5.6 Screens
1. **Home:** a big *Hold to speak* button, the mesh status (peers nearby, gateway reachable
   N hops away), and the user's sent needs with live status (Queued → Relayed → Received
   by control room ✓).
2. **Confirm need card:** the parsed fields, editable, with a Send button.
3. **Relay view (debug/demo):** peers, packets carried, and the hop trail per packet. This
   is useful in the video.
4. **Settings/debug:** a node ID allowlist that forces a **chain topology** (§9), a model
   switch (E2B / 270M / rules), a "pretend offline" toggle, and a button to clear the store.

---

## 6. Gemma fine-tuning (`ml/`)

Gemma 3 270M is meant to be fine-tuned for narrow tasks. Without fine-tuning it will not
reliably produce our JSON, especially for Odia or Waray input.

1. **Dataset:** Gemini generates about 5,000 synthetic distress utterances across six
   languages, including code-mixed speech (Hinglish, Taglish) and ASR-style noise (no
   punctuation, misheard words). Each utterance comes with its gold JSON. A human
   spot-checks 200 of them. 500 are held out for evaluation.
2. **Fine-tune:** LoRA via Hugging Face TRL/PEFT (or KerasNLP) on Colab or a single
   L4/A100. Then merge the adapters.
3. **Convert:** export to LiteRT (`.litertlm` / `.task`) with int8 weights, and check the
   result in the Google AI Edge Gallery app before integrating it.
4. **Evaluate (`ml/eval/`):** category accuracy, item and landmark exact match, JSON
   validity rate, and p50/p95 latency on a mid-range phone.
   **Targets:** ≥ 90 % category accuracy, ≥ 98 % valid JSON after repair, and p95 < 3 s
   for 270M.
5. **E2B:** use it with a few-shot prompt first and fine-tune only if the evaluation says
   it is needed.

---

## 7. Backend and Gemini agent (`backend/`)

**Stack:** Python 3.12, FastAPI, Google Gen AI SDK (or the Agent Development Kit),
Firestore, and Cloud Run. Model names live in config: a fast Flash-class Gemini model for
triage and translation, and a Pro-class model for dispatch drafting if quality needs it.

### 7.1 Data model (Firestore)
- `packets/{msg_id}`: the raw decoded packet, the gateway that received it, and when it
  was received.
- `tickets/{ticket_id}`: the canonical need, `report_count`, the linked `msg_id`s,
  `priority_score`, `priority_reason`, `status`, `dispatch_draft{en, or|tl}`, and
  `assigned_to`.
- `acks/{id}`: queued confirmations, with `target_node`, `ref_msg_id`, translated text and
  a delivered flag.

### 7.2 Agent loop (triggered by each `/v1/sync` batch)
Gemini runs with function calling. The tools are ordinary Python functions:
| Tool | What it does |
|---|---|
| `get_new_packets()` | Returns the packets that are not yet linked to a ticket |
| `find_similar_tickets(packet)` | Searches by geohash and category, with Gemini judging "same need?" |
| `upsert_ticket(...)` | Creates a ticket or merges a packet into one, and increments the report count |
| `score_priority(ticket)` | **Deterministic** score (below). Gemini only writes the human-readable reason |
| `draft_dispatch(ticket)` | A short dispatch in the district emergency operations centre's format: what, who, where (coordinates + landmark + nearest village), urgency, report count |
| `translate(text, lang)` | Gemini translation into Odia, Hindi, Tagalog, Waray or Cebuano |
| `queue_ack(ticket, status, text)` | Writes the ACK, which the next gateway sync picks up |

**Priority score** (in code, so it is explainable and stable):
`score = category_weight (RESCUE/MEDICAL highest) + urgency×w + vulnerability flags + log(people) + log(report_count) + hours_waiting×w`.
Gemini may adjust the score by at most ±1 tier, and it must give a reason. The dashboard
shows that reason.

**Confirmations:** an automatic `RECEIVED` ACK is sent as soon as a ticket is created
(*"Your request #142 for insulin has reached the control room"*, translated). A
`DISPATCHED` ACK follows when an operator approves.

### 7.3 API
- `POST /v1/sync`: gateway upload and ACK download. Protected by a device API key, which
  is enough for the hackathon.
- `POST /v1/tickets/{id}/approve`: called by the dashboard. It marks the ticket dispatched
  and queues a `DISPATCHED` ACK.
- `POST /v1/dev/inject`: for development only. It injects fake packets so the dashboard
  can be built without phones.

---

## 8. Control-room dashboard (`dashboard/`)

**Stack:** Vite + React + TypeScript, MapLibre GL with OSM tiles, and the Firestore web
SDK with live `onSnapshot` listeners. It is deployed to Firebase Hosting.

- **Map:** pins coloured by category and sized by priority. Clusters merge as you zoom
  out. A new ticket animates in, and this is the hero shot for the demo.
- **Ticket queue:** sorted by priority. Each row shows the category icon, a one-line
  summary, the report count, time waiting and hop count.
- **Ticket detail:** the original summary, how the request was understood, the priority
  reason, the hop trail (A → B → D), and the dispatch draft in English and the local
  language. An **Approve & dispatch** button sends the confirmation.
- **Header stats:** open needs by category, and gateways online.

---

## 9. Demo setup and the two-hop risk

The risk the brief names is mesh reliability beyond two hops. We address it in two ways.

**Engineering:**
- In one room, every phone sees every other phone, so the packet would go A → D in a
  single hop. The **debug allowlist** forces a chain (A↔B, B↔C, C↔D), so the hops in the
  video are real. Each phone only accepts connections from its allowed neighbours.
- The simulator tests flooding, ACK return and dedupe on 4-, 10- and 50-node topologies,
  including random link drops, before we rely on real phones.
- Expect about 10–30 s for discovery and connection per hop on BLE. Keep advertising and
  discovery running continuously, and pre-connect the chain before recording starts.

**Runbook (`docs/demo-runbook.md`):**
1. Four phones, all charged, with the APK and models installed and the language packs
   downloaded.
2. Phones A, B and C: turn on airplane mode, **then turn Bluetooth and Wi-Fi back on**,
   without joining any network. Location stays on.
3. Phone D: mobile data on. Confirm the gateway badge is showing.
4. Turn on the chain allowlist, use the video length of **two hops (A → B → D)** that the
   brief recommends, and keep C as a hot spare relay.
5. Open the dashboard on a laptop, start recording, then speak into A.
6. Have a pre-recorded backup take, plus a `/v1/dev/inject` fallback in case venue radio
   noise breaks the live run.

---

## 10. Phased delivery

Each phase ends with a "done when" check. The order is chosen so that a demo-able vertical
slice exists as early as possible.

| # | Phase | Done when |
|---|---|---|
| 0 | **Scaffold:** Gradle modules, `relay.proto`, backend and dashboard skeletons, CI (unit tests + lint) | `./gradlew test`, `pytest` and `npm run build` pass in CI |
| 1 | **Protocol core:** codec, IDs, seen-set, gossip state machine in `core-protocol`, plus the simulator | The simulator delivers 100 % of packets and ACKs on a 10-node chain, and the size test enforces ≤ 128 B |
| 2 | **Two-phone mesh:** Nearby transport, foreground service, permissions, typed text sent as a `NEED` | Typed text goes from A to B in airplane mode |
| 3 | **Multi-hop + store-and-forward:** gossip on device, anti-entropy, chain allowlist, relay debug view | A → B → C → D works, and a packet survives B going out of range and coming back |
| 4 | **On-device understanding:** rule-based extractor first, then Gemma (E2B few-shot) via LiteRT-LM, ASR, and the confirm card | The insulin voice note becomes a correct packet offline in < 5 s |
| 5 | **Gateway + backend:** connectivity watcher, `/v1/sync`, Firestore, an agent with stub tools | A packet from the mesh appears in Firestore |
| 6 | **Gemini agent:** clustering, priority, dispatch drafts, translation, `RECEIVED` ACKs | Five duplicate reports become one ticket with `report_count=5` and a sensible priority reason |
| 7 | **Dashboard:** map, queue, detail, approve | A live ticket appears on the map within about 2 s of the sync |
| 8 | **ACK return path:** ACK injection at the gateway, display on the target phone | A sees the confirmation in Odia or Tagalog after the round trip |
| 9 | **Fine-tuned 270M:** dataset, LoRA, conversion, evaluation, runtime tiering | Evaluation targets in §6 are met, and a low-RAM phone uses 270M automatically |
| 10 | **Hardening + video:** battery and Doze testing, venue rehearsal ×3, backup take, README | The full demo succeeds 3 times in a row from a cold start |

Phases 5–7 (cloud) can run **in parallel** with phases 2–4 (device), because
`/v1/dev/inject` decouples them. Phase 9 can start as soon as phase 0 is done.

---

## 11. Testing strategy
- **`core-protocol` unit tests:** codec round-trip, the size limit, dedupe, hop expiry,
  ACK targeting, and inventory diffing.
- **Simulator scenarios:** chain, star, partitioned clusters bridged by a "walking" node,
  and 20 % link loss. Measure delivery ratio, latency in rounds, and duplicate
  transmissions.
- **NLU evaluation:** `ml/eval` runs on the held-out set and on a small set of real
  recorded utterances from team members in each language.
- **Backend:** pytest for priority scoring and merging, plus recorded Gemini responses so
  the agent tests are deterministic in CI.
- **Field tests:** a corridor or outdoor test to find the real BLE and Wi-Fi Direct range
  between hops, and a 2-hour battery drain test with the mesh service running.

---

## 12. Risks and mitigations
| Risk | Mitigation |
|---|---|
| The mesh is flaky beyond two hops | Anti-entropy on every reconnect, a chain allowlist for the demo, a two-hop video, and a simulator for confidence at larger scale |
| Gemma 270M quality in Indic or Philippine languages | Fine-tune on synthetic multilingual data, use E2B on capable phones, keep the rule fallback, and let the user correct the card |
| Offline ASR is missing for Odia or Waray | Try E2B audio input; otherwise fall back to Hindi/Filipino/English ASR, or let the user type or pick an icon (🩺 💧 🆘 🍚 🏠) as a no-ASR path |
| Model size and RAM | Runtime tiering, int4/int8 quantisation, and lazy loading of the model only while recording |
| Battery drain from continuous advertising and discovery | Duty-cycle discovery when idle (for example 30 s on / 60 s off) and keep advertising on. Full duty cycle only while there are unsent packets |
| Spoofed or abusive messages | v1: rate-limit per origin node and require operator approval. v2: signatures (§4.4) |
| Venue RF congestion during judging | Pre-recorded backup video and the dev inject endpoint |
| Android background limits kill the service | Foreground service, a battery optimisation exemption prompt, and testing on each OEM in the demo set |

---

## 13. Open questions for the team
1. How much time is there until submission, and how many people work on Android, backend
   and ML? This decides whether Phase 9 (the fine-tuned 270M) is in scope or whether we
   ship E2B with rules only.
2. Which phones do we have? At least one needs ≥ 6 GB RAM for E2B, and one should be
   low-end to show tiering.
3. Should the primary demo be Odisha (Odia) or Leyte (Waray/Tagalog)? Pick one to polish,
   and keep the other working.
4. Do we have a GCP project and Gemini API quota (Cloud Run, Firestore, Firebase Hosting)?
