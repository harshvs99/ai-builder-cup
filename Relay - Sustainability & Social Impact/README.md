# Relay: relief messaging when the towers go down

After a cyclone hits the Odisha coast or Leyte, the cell towers fail first, and people's needs go unheard for days. Relay turns every villager's phone into a mesh node:

- **On the device:** a voice note becomes a classified, compressed need of about 100 bytes. This uses Gemma 3 270M or Gemma 4 E2B and works offline. The need hops from phone to phone over Bluetooth Low Energy using Relay's own mesh protocol (a bitchat-v1-compatible frame on Relay's own GATT service; see [protocol/SPEC.md](protocol/SPEC.md)).
- **At the first phone with signal:** any phone that finds a working network (patchy cell, emergency cell site, satellite Wi-Fi) becomes a gateway. A Gemini agent builds the needs map, prioritises it, drafts dispatches for the district control room, translates them, and sends confirmations back through the mesh.

**No GPS on the requester's phone?** Neighbouring relay phones that do have a fix can vouch for where they heard the request (a `LOC_HINT`, [SPEC §5.5](protocol/SPEC.md)); the control room shows the intersection of their ranges as a dashed "about N m" circle. Opt-in (Settings → Help locate neighbours: on in demo builds, off in village builds).

Layout: `protocol/` (wire spec and golden vectors), `android/` (core-protocol, simulator, app), `backend/` (FastAPI; SQLite locally, Firestore in the cloud), `dashboard/` (Vite + React + MapLibre), `infra/` (Terraform: one Cloud Run service for API + dashboard, Firestore, Pub/Sub, Gemini API key in Secret Manager; `deploy.sh` / `destroy.sh`), `ml/` (future).

**Run in Android Studio:** open the `android/` folder, start the AVDs `relayA` / `relayB`, then use the shared run configurations in the toolbar:
"Backend + dashboard: start", then "Relay app" (both emulators), then open http://localhost:8080/. The same flows are Gradle tasks in the `relay` group
(`relayBackendStart`, `relayBleDemo`, `relayAllTests`). Full steps, real-phone setup and troubleshooting: [docs/demo-runbook.md](docs/demo-runbook.md).

Status: the POC is verified locally; the cloud deploy is one command (`infra/deploy.sh <PROJECT_ID>`), pending a GCP project and credentials.

See [PLAN.md](PLAN.md) for the decision log, architecture, uplink options, phased delivery plan and demo runbook.
