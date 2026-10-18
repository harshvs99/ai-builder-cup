# Relay

**The tower fell at 21:40. The needs didn't.**

Relay carries a cry for help across phones with no signal, and brings the answer back.
Built by **Assembly Lines** (Harshvardhan Singh, Manas Choudhary) for the Google Cloud AI Builder Cup 2026, Sustainability & Social Impact track.

- **Live control room:** [relay.harshvs.com](https://relay.harshvs.com)
- **Demo video (3 min):** [Google Drive](https://drive.google.com/file/d/15octzsyQ2cygVJEjCYpAf6gC18axCi2F/view?usp=sharing)
- **Submission deck:** [Relay-AI-Builder-Cup-Submission-updated.pdf](Relay-AI-Builder-Cup-Submission-updated.pdf)
- **Code:** this repository. The project lives in [`Relay - Sustainability & Social Impact/`](Relay%20-%20Sustainability%20%26%20Social%20Impact/README.md)

## The problem

After landfall, help exists. It just can't find the people who need it.

- A requester in Puri, Odisha has no bars. Her mother needs insulin, somewhere near the school road.
- The duty officer in the district control room has a map, forty volunteers and six boats, and no idea which village to send them to first.

| Event | What failed |
|---|---|
| Cyclone Amphan, 2020 | ~7,000 of 14,167 cell tower sites in West Bengal went dark |
| Typhoon Odette, 2021 | 135 Philippine cities and towns lost telecoms |
| Cyclone Fani, 2019 | Puri's officials fell back on police VHF radio |

Sources: The Quint, citing TAIPA (May 2020); Philstar Interaksyon (Dec 2021); Hindustan Times (13 May 2019).

Every lifeline assumes the tower is standing. Calls and SMS need a tower; mesh chat needs a contact. Relay needs neither.

| Channel | Works with towers down | Reaches the control room | Arrives ready to act on | Confirms help is coming |
|---|---|---|---|---|
| Calls and SMS | No | Yes | No | Sometimes |
| Emergency helplines | No | Yes | No | No |
| Satellite phones | Officials only | Yes | No | No |
| Mesh chat apps | Yes | No: contacts only | No: free text | No |
| **Relay** | **Yes** | **Yes, via gateway** | **Yes: a ticket** | **Yes, hops back** |

## How it works

A structured need hops until one phone finds signal; the confirmation rides the same path home.

1. **Requester phone.** No signal. A voice note or a tap; Gemma asks up to 3 follow-up questions, offline.
2. **Relay phones.** BLE mesh, TTL 7, store and forward. Nobody opens an app.
3. **Gateway phone.** Any phone with one bar of signal uploads tickets and carries replies.
4. **Relay cloud.** Cloud Run and ADK agents triage, dispatch, write the sitrep and translate.
5. **Control room.** Live map and priority queue. The duty officer approves in one click.
6. **Back the same hops.** *"Boat leaving the school at 15:40. Keep her warm."*

Relay never says "help is coming" until a human has approved it: status changes only on a real ACK.

### Three modes

| Before landfall: **Ready** | First 48 hours: **Relay** | After the towers return: **Recover** |
|---|---|---|
| The app spreads phone to phone over Android Nearby Share, so it is already on the phone when the tower goes. Family plans are read aloud. | A voice note becomes a ticket on the phone, hops across the mesh, is triaged in the cloud, approved by a human and confirmed back. | Every ticket and dispatch becomes evidence: a damage map by village, households reached, and a log for the state and insurers. |

## Architecture

| On the phone, offline | Gateway | Relay cloud | Control room |
|---|---|---|---|
| Gemma 4 E2B on LiteRT-LM (Gemma 3 270M as fallback): voice note to a ~100-byte ticket, with the dispatcher's questions. Nearby Connections BLE mesh: store and forward, TTL 7, ACKs. | Any phone with one bar. Syncs tickets up and replies down over HTTPS. | ADK agents on Cloud Run (triage, dispatch, sitrep). Gemini Flash + Maps grounding (merge, geocode, explain). Gemini Pro (sitreps and the recovery report). Gemini TTS (Ready-mode plans). | Administrator node: live map, priority queue, one-click approve. |

Where AI helps, stage by stage:

| Stage | Tooling |
|---|---|
| Ready | Gemini Flash + TTS |
| Intake | Rules, Gemma 4 E2B |
| Triage | Rules, Gemini Flash + Maps, embeddings for duplicate merging |
| Dispatch | ADK agent allocates teams within capacity and travel time |
| Sitrep and Recover | Gemini Pro |
| Reclassify | Gemini Flash |
| Mesh and return path | **No AI, by design.** The radio is the constraint; the phone renders a template id. |

If the on-device model is late, a rule-based parser ships the same ticket. Agents draft; the approve button stays human.

## Prototype results

The numbers below are what we measured; where a figure is an estimate it says so.

| Metric | Result | How it was measured |
|---|---|---|
| Ticket size on the wire | ≈100 B | Wire format, [`protocol/SPEC.md`](Relay%20-%20Sustainability%20%26%20Social%20Impact/protocol/SPEC.md) |
| Typed request to control room | **≈2.5 s** median (1.7 to 2.8 s, 4 runs) | Galaxy A52 to relay.harshvs.com over Wi-Fi, tap Send to ticket visible, 0 hops. Excludes voice intake and BLE hops |
| Gemma intake turn | **≈5.7 s** p50 | Gemma 4 E2B on audio, 34 clips, Samsung S24 FE, GPU backend |
| Intake accuracy | **85%** category (29/34), English and Hindi | Same 34-clip set, gold labels in English and Hindi only |
| Duplicate reports merged | **40 → 35 tickets** | Seeded 40-report cyclone night (8 reports in 3 clusters), `backend/scripts/seed_district.py` |
| Cloud cost | **< ₹1,000 per event, < ₹2,500 per district per season** (estimate) | ~1,000 tickets over 48 h, hourly sitreps, ₹85/USD, assumed Gemini list prices; Cloud Run, Firestore and Pub/Sub inside free tiers |

Limits we know about: accuracy is measured in English and Hindi only (the Bengali and Bhojpuri prompts are unreviewed and the Odia, Filipino and Waray prompts are not written yet), item and landmark matching are weaker than category, and 85% is below our 90% target.

The demo runs on a synthetic, labelled forty-ticket cyclone night: 40 tickets in, one dispatch per team plus an hourly sitrep, one human click to approve each.

**Running today:** BLE mesh with store-and-forward and ACKs; gateway sync and localised confirmations; control room map, queue, reasons and approve.
**Added for the Cup:** Gemma intake conversation (offline); control-room agents (merge, dispatch, sitrep).
**Shown on recorded data, labelled:** photo tickets that travel as tags; Ready-mode household plans.

## Safety and trust

- **Confirm, never assume.** Status changes only on a backend ACK or a human approval.
- **Ask about the situation.** "Is she conscious?", never a diagnosis.
- **Location is opt-in.** The landmark is the fallback rescuers rely on.
- **Minimum data.** No names, no numbers, no pixels; photos travel as tags.
- **Humans dispatch.** Agents draft; the approve button stays human.
- **The district owns the data.** Demo data is synthetic and clearable.

Risks we have hit, and the fix: BLE links flap (a rehearsed two-hop path on the exact demo phones); latency on mid-tier phones (small on-device model, rule-based fallback); Hindi and Odia misparsed (on-phone intake, cloud re-classification, original kept for audit); duplicate or spoofed tickets (message ids and merging with report counts; per-device signing is next).

## Scale

| Now: pilot | Next: state | Then: JAPAC |
|---|---|---|
| One Odisha district: control room on Relay, Ready mode before the warning, baseline vs Relay time to help | Every coastal district: SMS and IVR for feature phones, per-device signing, multi-district control rooms | The cyclone belt: Bangladesh, Philippines, Vietnam; data-mule mode between islands; rumour checks before broadcast |

A district pilot measures minutes from ask to dispatch, share of tickets with a usable location, duplicate reports merged before dispatch, needs confirmed closed by village, and households reached in the recovery report.

Why it matters: 119M people are exposed to tropical cyclones each year on average, and 85%+ of expected annual cyclone deaths fall in Bangladesh and India. Source: PreventionWeb (UNDRR), "Asia, most at risk for natural hazards, most vulnerable to cyclones".

New hardware needed: none. Relay runs on phones people already own, and the cloud works only when there is work.

## Repository

All paths are under `Relay - Sustainability & Social Impact/`.

| Path | What it holds |
|---|---|
| `protocol/` | Wire spec ([SPEC.md](Relay%20-%20Sustainability%20%26%20Social%20Impact/protocol/SPEC.md)) and golden vectors |
| `android/` | core-protocol, mesh simulator, Android app |
| `ios/` | RelayCore and the iOS app |
| `backend/` | FastAPI service: clustering, scoring, dispatch, sitreps (SQLite locally, Firestore in the cloud) |
| `dashboard/` | Control room UI (Vite, React, MapLibre) |
| `infra/` | Terraform for Cloud Run, Firestore, Pub/Sub and Secret Manager; `deploy.sh` and `destroy.sh` |
| `ml/voice-spike/` | On-device Gemma intake spike and the evaluation scripts behind the accuracy and latency figures |
| `docs/` | Design notes and the demo runbook |

Run it locally: see the [project README](Relay%20-%20Sustainability%20%26%20Social%20Impact/README.md) and [`docs/demo-runbook.md`](Relay%20-%20Sustainability%20%26%20Social%20Impact/docs/demo-runbook.md). For the decision log and architecture detail, see [`PLAN.md`](Relay%20-%20Sustainability%20%26%20Social%20Impact/PLAN.md).

---

*The tower might fall again. But this time, the help will arrive.*
