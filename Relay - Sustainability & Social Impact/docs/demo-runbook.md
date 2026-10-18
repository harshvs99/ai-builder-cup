# Relay demo runbook

## Run it in Android Studio

1. **Open the `android/` folder** (not the Relay root) in Android Studio 2026.1.2 and let Gradle sync. Only if sync complains about the JDK: Settings -> Build, Execution, Deployment -> Build Tools -> Gradle -> Gradle JDK = the bundled JBR 21.
2. **Devices.** Device Manager has two AVDs, `relayA` and `relayB` (Android 16, arm64). Start both. They share the emulator's netsim virtual Bluetooth, so they can mesh over real BLE.
3. **Run configurations** (toolbar dropdown, loaded from `android/.run/`):

| Step | Run configuration | What it does |
|---|---|---|
| 1 | **Backend + dashboard: start** | Builds the dashboard and starts the backend in the background on `0.0.0.0:8080` (prints URLs; keeps the DB) |
| 2 | **Relay app** | Installs and launches the app. Use *Select Multiple Devices* to hit both emulators at once |
| 3 | open <http://localhost:8080/> | Dashboard with the live needs map |
| - | Backend: stop | Stops the backend |
| - | Simulator: all scenarios / Simulator: e2e vs local backend | JVM mesh simulator, no devices needed (e2e needs the backend running) |
| - | BLE demo: two emulators | Automated two-emulator demo (see below) |
| - | All unit tests | `relayAllTests`: core-protocol, simulator, app |

The same tasks are in the Gradle tool window under **relay** (`relayBackendStart`, `relayBackendStop`, `relayBleDemo`, `relayAllTests`).

**How the app behaves**
- An emulator has internet, so it **auto-becomes a gateway** (uploads to the backend at `http://10.0.2.2:8080`).
- For a two-device mesh, turn on **Pretend offline (never act as gateway)** in Settings on one device (the origin). The other stays the gateway.
- On the origin: type or speak a need -> confirm card -> **Send**. Watch the **Relay** tab (hops, ACKs) and the dashboard (new ticket).
- On the dashboard, **approve** the ticket -> a DISPATCHED confirmation arrives on the origin phone.
- Logcat filter: `tag:RelayMesh`.

## Laptop-only demo (no devices)

```bash
scripts/run-local-poc.sh          # build dashboard, start backend, run the A->B->C->D simulator e2e, leave backend up
scripts/stop-local-poc.sh
```
Flags: `--no-e2e` (backend only), `--keep-db`, `--no-build`. Dashboard: <http://localhost:8080/>.

## Mesh-assisted location (requester with no GPS)

Settings → **Help locate neighbours** (default **on** in demo builds, **off** in village builds: build with
`-Prelay.helpLocateDefault=false`). A phone with it on and a GPS fix of 100 m or better, which hears a no-fix NEED
*directly* from the phone that sent it, tells the control room where it is; the dashboard then shows a dashed
"about N m · from K nearby phones" circle for that ticket instead of only the landmark. It never overrides a real fix,
and the header still counts the ticket as "without location".

- Laptop-only check (backend running, e.g. after `scripts/run-local-poc.sh`):
  `cd android && ./gradlew -q :simulator:run --args="locate --backend http://localhost:8080"` runs A (no fix) with two relays
  that have one and a third phone with a fix that is *not* next to A. Passes iff exactly the two neighbours hint and the
  backend ticket gets a `mesh` estimate within its own radius of the truth. `--no-help` turns the switch off and passes
  iff no hint is sent.
- Two emulators/phones: turn Help locate neighbours on, give the *relay* phone a location (Home open, Location on; the
  emulator's extended controls can set one) and send a NEED from the other with Location off. A background relay has
  no fix: the mesh service is not a `location` foreground service, so keep the relay's app open.

## Automated two-emulator BLE demo

Needs both emulators (`emulator-5554`, `emulator-5556`) running at the same time. Run the **BLE demo: two emulators** configuration, or from a terminal:

```bash
scripts/emulator-ble-demo.sh [--clean] [--no-install] [--no-build] [--skip-offline]
```
The Gradle task builds the debug APK first and passes `--no-build`; the script never calls Gradle itself. Backend is started if `/healthz` does not answer. Logs: `.run/ble-demo-logs/`.

PASS = steps a to e each print `PASS` with evidence, the script stops at the first `FAIL`:
a link up + HELLO + gateway/hop count; b NEED -> ticket in backend; c RECEIVED ACK (Odia) back at origin; d approve -> DISPATCHED at origin; e store-and-forward after the gateway's Bluetooth returns.

Measured on the reference run:

| Event | Time |
|---|---|
| BLE link up | 1.7 s after service start |
| NEED -> ticket in backend | 1.5 s |
| NEED -> RECEIVED ACK at origin | 1.5 s |
| Dashboard approve -> DISPATCHED at origin | 6.9 s (gateway polls every 10 s) |
| Store-and-forward | delivered 1.1 s after the gateway's Bluetooth came back |

## Real phones

1. Enable Developer options and USB debugging; plug in; pick the phone in the device dropdown and run **Relay app**.
2. **Default backend.** Real phones use the URL baked in at build time from `relay.backendUrl` in `android/gradle.properties` (currently the developer Mac on Tailscale, `http://100.116.13.37:8080`; it becomes the Cloud Run URL later). Override per build with `-Prelay.backendUrl=...`, or per phone in Settings -> Backend URL (alternatives: the LAN URL that `relayBackendStart` prints, or `adb reverse tcp:8080 tcp:8080` + `http://localhost:8080`). Emulators ignore it and use `http://10.0.2.2:8080`. A phone that saved the old emulator default `http://10.0.2.2:8080` is migrated to the new default automatically.
3. **Only gateway phones need Tailscale** (or whatever route reaches the backend). Mesh-only phones talk Bluetooth only.
4. **Any phone with internet becomes a gateway.** Phones that should be mesh-only must be in **airplane mode with Bluetooth turned back on**, or have **Settings -> Pretend offline** on. Otherwise they sync to the backend directly and the multi-hop demo is not real.
5. **Check gateway health** on Home: a gateway phone shows "Gateway ✓ synced N s ago" (green) or a red "Gateway can't reach backend (host:port) — check Settings / Tailscale". Settings has a **Test connection** button (GET `/healthz`, 5 s). A need on a gateway shows "Waiting for upload" until a sync goes through, then "Uploaded — waiting for confirmation" until the control room's RECEIVED ack.
6. **Turn on Location before sending**, and grant location "While using the app" (not "Only this time", which Android revokes when the app is backgrounded). The confirm card shows the state: "±8 m", "Getting location…", "Location is off" (Turn on button) or "No permission" (Allow button). Open Home a few seconds before sending so GPS can warm up outdoors; sending with no fix falls back to the landmark text.
7. **Voice input** uses the on-device recogniser API with our own listening panel. It asks for offline recognition only when the phone has no internet. Offline needs the language voice pack (Google app -> Settings -> Voice -> Offline speech recognition), so download packs while still online; otherwise the panel offers "Voice settings" and the user types.
8. Four-phone chain: set the **Chain allowlist** (node-id hex prefixes) on each phone so only A-B, B-C, C-D link; this makes the hops real.
9. To demonstrate offline: put the origin phones in airplane mode, then turn **Bluetooth back on**. **Keep data on for the gateway phone** (the last hop).

## Troubleshooting

- **Bluetooth permission denied / foreground-service start refused:** open the app once in the foreground and grant the permissions (Settings -> Grant), then retry.
- **Stale lint results:** delete `android/app/build/intermediates/lint*`.
- **Emulator BLE does nothing:** both AVDs must be running at the same time (shared netsim radio); cold-boot one if it was started alone.
- **Port 8080 busy:** `scripts/stop-local-poc.sh`, or set `PORT=<other>` for the script.
- **Emulator cannot reach the backend:** it must use `http://10.0.2.2:8080`, not `localhost` (this is the automatic default on emulators).
- **Phone shows "Gateway can't reach backend":** is Tailscale connected on that phone, is the backend running on the Mac, does Settings -> Test connection pass? Mesh-only phones should be in airplane mode or Pretend offline.

## Cloud deploy

One command, pending a GCP project and credentials: `infra/deploy.sh <PROJECT_ID>`. Prerequisites, phases and teardown: [`infra/README.md`](../infra/README.md).
