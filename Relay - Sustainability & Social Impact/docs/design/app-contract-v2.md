# Relay apps, contract v2: languages, Ready mode, shelters, announcements

Status: **ready to build**. Audience: the engineer (or agent) who implements this in `android/` and `ios/`.
The backend side is done on branch `claude/inspiring-johnson-ljk8c1` unless a section says otherwise.
The existing specs still hold: [android-redesign.md](android-redesign.md) (screens, copy, visual system),
[ios-build-plan.md](ios-build-plan.md) (iOS differences), [protocol/SPEC.md](../../protocol/SPEC.md) (wire format).
This file only adds to them; where it contradicts them, this file wins.

## 0. Ground rules

- **Both platforms, same behaviour.** Every feature lands in Android (Kotlin/Compose) and iOS (Swift/SwiftUI). Logic
  that is not UI goes in the shared-by-design core modules (`android/core-protocol`, `ios/RelayCore`) with mirrored
  unit tests, as today. A feature is done only when both platforms pass the same tests.
- **Offline first.** Everything a person needs during the outage is stored on the phone before it is needed. Network
  calls only refresh the stored copy; the UI always renders from storage.
- **Never claim help is coming unless it is.** Unchanged from the current apps: statuses change only on a backend ACK.
- **Wire compatibility.** New frame types follow SPEC §4 forward-compatibility: an old build relays them opaquely.
  Golden vectors in `protocol/vectors.json` must pass on both platforms before any UI work on that feature.
- **Privacy.** No names, phone numbers or photos leave the phone. The household profile (§3.2) stays on the phone
  except as the anonymous flags sent to `/v1/ready/plan`.
- **Auth.** Phone endpoints use the existing device key header `X-Relay-Key` (same as `/v1/sync`), except
  `/v1/languages` and `/v1/public/shelters`, which need no key.

## 1. Languages: English and Hindi only, more later

The team can review only English and Hindi, so v2 ships **en and hi enabled**; Odia, Filipino and Waray stay in the
string tables but are **hidden** until a community reviewer signs them off.

- `GET /v1/languages` (no auth) → `[{ "code": "en", "english_name": "English", "native_name": "English", "enabled": true }, …]`.
- The language picker (first run and Settings) shows only languages that are `enabled` in **both** the app's build
  config and the latest `/v1/languages` response. Cache the response; with no response yet, use the build default
  (`en`, `hi`).
- Keep the existing `or`, `tl`, `war` strings in the string tables; mark them `pending review` (Android: a
  `translatable` comment / separate file; iOS: a separate `.xcstrings` table) so enabling one is a config change.
- If a stored user language becomes disabled, fall back to English and say so once ("Odia is not available yet;
  showing English.").
- Backend text the phone shows (ACK texts, plan steps, announcements, checklist) arrives already in an enabled language;
  the phone never translates.
- Speech: on-device recognition and TTS for `en-IN` and `hi-IN`.

## 2. Districts

The phone does not hard-code districts. It learns its district from the Ready pack (`district` field), which the
backend resolves from `lat`/`lon`. Store the last known district id; use it for announcements (§5) and the shelter list
(§4). With no location ever, ask once on the Ready screen: "Which district are you in?" with the ids the backend
knows (today `puri`, `delhi`; get the list from the pack's 404 message only as a last resort, or add a static list
behind a build flag).

## 3. Ready mode (before landfall)

### 3.1 Data (backend, done)

`GET /v1/ready/pack?lat=&lon=&lang=` (or `?district=&lang=`), device key →

```json
{ "district": "puri", "lang": "hi", "generated_at": 1792336961, "version": "a1b2c3d4e5f60718",
  "warning": { "id": 4, "source": "IMD", "severity": "warning", "headline": "…",
               "issued_at": 1792330000, "expected_landfall_at": 1792400000, "expires_at": null } | null,
  "shelters": [ { "id": "…", "name": "…", "name_en": "…", "kind": "cyclone_shelter|school|relief_camp|hospital|other",
                  "lat": 19.81, "lon": 85.83, "capacity": 500, "facilities": ["water", …], "block": "…", "demo": false } ],
  "shelters_source": "published" | "demo", "shelters_published_at": 1792300000 | null,
  "checklist": ["…"], "checklist_lang": "hi" }
```

`POST /v1/ready/plan`, device key, body
`{ "lang": "hi", "lat": 19.8, "lon": 85.8, "household": { "people": 4, "elderly": true, "children": false,
"infants": false, "pregnant": false, "disability": false, "medical_needs": "insulin", "livestock": false } }` →

```json
{ "plan_id": "…", "lang": "hi", "steps_lang": "hi", "district": "puri", "steps": ["…"], "source": "gemini|template",
  "warning_id": 4, "nearest_shelters": [ { "id": "…", "name": "…", "distance_km": 1.2 } ], "audio_url": "/v1/ready/plan/…/audio" | null }
```

`GET {audio_url}`, device key → `audio/wav` (PCM16 mono 24 kHz). 404 = no server audio; 503 = synthesis failed. Both
mean "use on-device TTS".

### 3.2 Household profile (new screen, Settings → "My household")

One screen, asked once, editable later: number of people (stepper 1–20), and toggles for elderly, children,
infants, pregnant, disability, livestock, plus "Medicine or care needs" (free text, max 100 chars, hint
"e.g. insulin, oxygen"). Stored locally. Copy is short and speakable; every control has an accessibility label.

### 3.3 Refresh logic (core module, unit-tested)

- `ReadyRefresher`: on app open, on network regained, and at most every 3 h while the app runs, if there is
  connectivity: fetch the pack (with the last known location, else the stored district).
- If `warning != null`: also fetch the plan (only when `version` changed, the household changed, the language
  changed, or there is no stored plan), then the audio if `audio_url` is set.
- Store pack, plan and WAV atomically in app files (reuse `AtomicFiles` / the iOS equivalent), keyed by `version` and
  `plan_id`. Keep the previous copy until the new one is fully written.
- Background: Android WorkManager periodic job (6 h, network constraint); iOS `BGAppRefreshTask`. Best effort only.
- When a new warning arrives (warning id differs from the stored one), post one local notification:
  "Cyclone warning for {district}. Your plan is ready." Tapping opens the Ready screen.

### 3.4 Ready screen (new; Home shows a banner when a warning is active)

- **Home banner** (only when the stored pack has an active warning): severity colour (watch = surfaceContainerHigh,
  warning = warning colour, alert = error colour), headline, "Landfall in about N h" when `expected_landfall_at` is
  set, button "Get ready". Hidden when no warning.
- **Ready screen**, rendered from storage, works in airplane mode:
  1. Warning card: source ("IMD"), severity, headline, issued time, landfall time.
  2. "Your plan": numbered steps; a large speaker button plays the stored WAV, else on-device TTS in `steps_lang`.
     If `steps_lang` differs from the UI language, show "Shown in English" once.
  3. "Nearest shelters": the plan's `nearest_shelters` (name + distance), each opening the shelter detail (§4).
  4. Checklist: tickable items (ticks stored locally, reset when `version` changes).
  5. Footer: "Updated {time}" from `generated_at`; "Shelter list verified by the district on {date}" when
     `shelters_source == "published"`, else (demo builds only) "Demo shelter list, not verified".
- With no stored pack: explain that Relay downloads a plan when there is a warning and the phone has signal.

## 4. Shelters (view and find)

- Source on the phone: the pack's `shelters`. Phones with signal and no pack may also call
  `GET /v1/public/shelters?district=` (no key) → `{ district, published_at, shelters: [{id, name, name_local, kind,
  address, lat, lon, capacity, source_url}], source_note }`; 404 = nothing published yet.
- **Production builds show only `shelters_source == "published"` lists.** Demo lists (`demo: true`) show only in
  demo builds, with a "Demo" badge.
- **Shelters list** (from the Ready screen and from a new "Shelters" row on Home): sorted by distance from the last
  known location, showing name, kind icon, distance, capacity.
- **Shelter detail**: name (local and English), kind, address, capacity, facilities, coordinates, a small offline map.
  - Map tiles: do not depend on network. Use a static, pre-rendered district map image bundled per district
    (or cached tiles downloaded with the pack, if you add an optional `tiles_url` later) with the shelter pins drawn
    on top; when the phone has network, the platform map view may be used instead.
  - "Directions" opens the system maps app (Android `geo:lat,lon?q=lat,lon(name)` intent; iOS
    `MKMapItem.openInMaps`) — works when the maps app has offline data.
  - "Copy coordinates" and "Speak" (reads name and distance aloud).

## 5. Announcements (control room → everyone in a district, in words)

Not to be confused with the **`BROADCAST` frame (`0x75`, SPEC §5.6)** already on `main`: that one is a 25-byte
*disaster declaration* (flood / cyclone / earthquake / landslide, declared or cleared) which switches the triage
questions on, and the apps already implement it. An *announcement* is free text a duty officer wrote, translated and
approved per language. Both travel down from the backend on `/v1/sync`; they are separate frames and separate fields.

### 5.1 Over HTTP (backend, done)

`GET /v1/announcements/active?district=`, device key →
`{ "announcements": [{ "id", "district", "source_lang", "created_at", "approved_at", "texts": { "en": "…", "hi": "…" } }] }`.
Only human-approved languages are present. Phones with signal poll this with the Ready refresh (§3.3).

### 5.2 Over the mesh: `ANNOUNCEMENT` frame, type `0x76` (backend part pending, see §8)

Payload ≤ 160 bytes, so the frame stays within the 182-byte single-write limit of SPEC §2 (22-byte header + payload):

```
size field
1    payload_version   = 0x01
8    announcement_id   random 64-bit, assigned by the backend at approval (same for every language)
1    lang              index in LANG_CODES (en 0, hi 1, or 2, tl 3, war 4, ceb 5)
1    district          index in the backend district table (puri 0, delhi 1)
1    status            0 ACTIVE, 1 WITHDRAWN
4    approved_at       u32 unix seconds
4    expires_at        u32 unix seconds, 0 = none (phones drop it after 72 h anyway)
1+n  text              ≤ 139 bytes UTF-8, already in `lang` (about 46 Devanagari characters; the backend
                       shortens longer texts with "…" and the full text stays on the HTTP route)
```

- Frame: no recipient (a broadcast frame in the SPEC §3 sense), `sender_id` = injecting gateway, TTL 7, stored, inventoried, flooded and expired
  like `ACK` (SPEC §6). Dedupe key: first 8 bytes of SHA-256 over `announcement_id ‖ lang ‖ status`, so a WITHDRAWN
  frame is a new frame that supersedes the ACTIVE one.
- **Gateway:** the `/v1/sync` request gains an optional `"district": "puri"` (from the gateway's location / last pack)
  and `"announcement_cursor": n`; the response gains `"announcements": ["<base64 ANNOUNCEMENT payload>"]` and
  `"announcement_cursor": n` (next to the existing `"broadcasts"` disaster declarations). The gateway wraps each payload in a frame and originates it, like ACKs. Old gateways send
  neither field and get neither.
- **Every phone** relays every ANNOUNCEMENT regardless of district or language. It **shows** one only when its
  `district` matches the phone's stored district (or the phone has none) and its `lang` is the UI language — or,
  when no frame in the UI language exists for that `announcement_id` after 10 minutes, the `en` one.
- **Display:** a "Message from the control room" card at the top of Home (newest first, max 3, dismissible), a
  notification on first receipt, a speaker button (on-device TTS). WITHDRAWN removes the card and shows nothing.
- `protocol/SPEC.md` gets a §5.6 for this frame and `protocol/vectors.json` gets vectors (backend task, §8).

## 6. What stays the same

Request flow, intake rules, NEED/ACK/HELLO/INVENTORY/LOC_HINT, gateway sync of NEEDs and ACKs, Network and Settings
screens. Gemma is not part of this contract (rule-based intake stays).

## 7. Tests and acceptance

- Core modules: unit tests for `ReadyRefresher` decisions (when to fetch pack / plan / audio), storage atomicity,
  pack/plan JSON parsing with unknown fields ignored, ANNOUNCEMENT codec against the golden vectors, show/hide rules
  (district, language fallback, WITHDRAWN), language picker filtering. Same test names on both platforms.
- Simulator (`android/simulator`): a scenario where a gateway injects an ANNOUNCEMENT and 5 nodes over 3 hops show it, and
  a WITHDRAWN supersedes it.
- Acceptance on devices (run against the mock backend `dashboard/scripts/mock-server.mjs` or a local backend):
  1. Issue a warning in the dashboard → phone with Wi-Fi shows the Home banner and a notification within one refresh.
  2. Airplane mode → Ready screen still shows warning, plan, shelters, checklist; speaker reads the plan.
  3. Publish a shelter list in the dashboard → pack `version` changes → phone list updates; detail opens Directions.
  4. Approve an en+hi announcement in the dashboard (Announce page) → phone A (gateway, signal) and phone B (airplane mode, BLE only) both show it in
     their UI language; withdraw → both remove it.
  5. Switch the app language to Hindi → every new screen and backend text is in Hindi; Odia is not offered.
- Accessibility: TalkBack / VoiceOver labels on every new control, 48 dp / 44 pt targets, both themes.

## 8. Backend work this contract still needs

1. ANNOUNCEMENT mesh delivery: `announcement_id` and `mesh_text` (≤ 139 bytes) per approved language at approval, the
   `/v1/sync` `district` / `announcement_cursor` / `announcements` fields, SPEC §5.7, codec and golden vectors.
2. `GET /v1/languages`, and English + Hindi as the only enabled languages (in progress).
3. Published shelter lists, `shelters_source` in the pack and `GET /v1/public/shelters` (in progress).

Build the app features against the mock server first; items 2 and 3 land before the apps need them.

## 9. Handoff checklist

- Branch from `claude/inspiring-johnson-ljk8c1` (or `main` once merged) to `apps/contract-v2`.
- Order: languages (§1) → ANNOUNCEMENT codec + vectors (§5.2) → Ready refresh + storage (§3.3) → Ready UI (§3.4) →
  shelters (§4) → announcement UI (§5.2) → simulator scenario → device acceptance (§7).
- Android must build with `./gradlew relayAllTests assembleDebug`; iOS with the `RelayCore` package tests and the
  Xcode project. A cloud container can run the Android/JVM core tests and the simulator; the device steps in §7 are
  for the owner's local machine.
