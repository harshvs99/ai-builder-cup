# Relay iOS — build plan and spec

Status: not started. Android is built (see [android-redesign.md](android-redesign.md) for the screen-by-screen spec,
copy and states — **iOS uses the same screens, copy, data rules and five languages**; this file only lists what differs
and how to build it). Source mockups: "Relay Mobile Mockups", iOS light and dark (iOS 26, Liquid Glass, system blue tint).

## Objective
A native iPhone app that interoperates byte-for-byte with the Android mesh: an iPhone can originate a NEED, relay
frames for Android phones, act as a gateway, and show the same five-step request timeline. Done = an iPhone and an
Android phone exchange a NEED and its ACK over BLE, and an iPhone gateway uploads to relay.harshvs.com.

## Binding constraints (decide around these)
- **Wire compatibility is non-negotiable.** Implement `protocol/SPEC.md` exactly (outer bitchat-v1 frame, NEED 0x70
  v1 and v2 with answer bytes, ACK 0x71 incl. ON_THE_WAY/RESOLVED, HELLO 0x72, INVENTORY 0x73, GATT service/characteristic
  UUIDs and MTU behaviour in §2). Pass every vector in `protocol/vectors.json` in unit tests before any UI work.
- **iOS background BLE is the hard limit.** In background, iOS peripherals drop the local name and move service UUIDs
  to the overflow area; scanning only finds peers advertising the service UUID explicitly, at reduced rate. Plan: full
  mesh while foregrounded; `bluetooth-central` + `bluetooth-peripheral` background modes with State Preservation and
  Restoration as best effort; the UI must say plainly that an iPhone carries messages best with Relay open. Test
  iPhone-locked ↔ Android early (week 1), because it decides how the demo is staged.
- **No Gemma on iOS for the hackathon.** Use the rule-based intake (port `core-protocol/nlu` + `intake/` to Swift,
  same tests/fixtures incl. `ml/voice-spike/eval/guided_prompts.json`). Speech: `SFSpeechRecognizer` with
  `requiresOnDeviceRecognition = true`; Odia has no on-device model → the typed path.

## Architecture (Swift 6, SwiftUI, iOS 26 target, iOS 17 minimum if Liquid Glass falls back cleanly)
Mirror the Android module split so behaviour can be compared file by file:
- `RelayCore` (Swift package, no UIKit): frame codec, payloads, gossip node (store, seen-set LRU 10k, inventory diff,
  PushPlanner ordering/cap, TTL), intake rules + QuestionBank, RequestTimeline, VillagerStatus. 100% unit-tested,
  vectors shared with Android.
- `RelayBLE`: CoreBluetooth central + peripheral on Relay's GATT service; op queue, MTU, backoff (2 s after setup
  timeout), recent peers, duty cycle.
- `RelayGateway`: URLSession `POST /v1/sync` with `X-Relay-Key`, ACK cursor, pending uploads — same contract as
  `android/app/.../gateway/SyncClient.kt`.
- `RelayApp`: SwiftUI screens, `@Observable` stores, file-backed persistence (JSON, same fields as `MyNeed`).

## Screens — iOS-specific differences from the Android spec
Same flow and copy: Language (first run) → Home → New request (1 of 3) → Check and send (2 of 3) → Request detail,
plus Requests, Network, Settings tabs.
- Tab bar: floating Liquid Glass tab bar (Home, Requests, Network, Settings); system blue tint; SF Symbols
  (house, list.bullet, point.3.connected.trianglepath.dotted, gearshape).
- Navigation: large titles on tab roots ("Relay", "Network", "Settings"); flow screens use glass capsule buttons —
  "Cancel" (New request), "‹ Back" (Check and send) — with title centred and "1 of 3"/"2 of 3" trailing.
- Home: "Ward 4, Puri district · ● REACHABLE" (status word in small caps, green); glass status card with chevron;
  the 160 pt yellow mic circle (same `#FBBC04`), "Type instead" as a tinted glass capsule; request rows show a third
  line "Urgent · R-7F3A · near the primary school".
- New request: "LISTENING" in small caps; chips are tinted capsules (not outlined); stop is a circular red glass
  button; "Done, check it" a full-width filled capsule.
- Check and send: inset grouped list with "Change" trailing buttons; urgency is a glass segmented capsule with the
  selected segment filled orange (`systemOrange`); Send filled blue capsule; "Change something" a grey glass capsule.
- Request detail: back chevron + share (square.and.arrow.up); Urgent as orange text tag; the same timeline (green
  check circles, blue ring for current, "LIVE" small caps); "FROM THE CONTROL ROOM" card with "Hear it in Hindi";
  actions as two half-width capsules "+ Add" (blue tint) and "× Cancel" (red tint).
- Network: same card, chain (You — 1 — Gateway · 2 hops), 2×2 tiles; iOS switches (green). Background toggle copy:
  "Bluetooth only. Slower while the app is closed." (honest about iOS background limits).
- Settings: inset grouped; Language and voice (Language ›, Read messages aloud, Voice speed Slow | Normal);
  Screen and battery (Appearance Light | Dark | Auto, Larger text, the dark-at-night note); Network and permissions
  (Gateway mode Automatic ›, Bluetooth Allowed ›, Location While using ›, Microphone "Ask each time" in orange ›);
  Before a disaster (Household plan, Set up → coming soon); Developer row last.
- Voice out: `AVSpeechSynthesizer`; Larger text = Dynamic Type (respect the system size too).
- Permissions staged exactly as Android (Bluetooth + notifications first, location at first Send, mic at first tap);
  Info.plist usage strings in all five languages.

## Build sequence and gates
1. RelayCore + vectors green (gate: every `protocol/vectors.json` case, intake fixtures within the Android
   `RuleScoreTest` floors).
2. BLE spike: iPhone ↔ Android HELLO + one NEED both ways, foreground; then iPhone locked (gate: documented result).
3. Gateway sync against relay.harshvs.com (gate: NEED from Android via iPhone gateway appears in the dashboard;
   approve sends the ACK back).
4. Screens in the order above, light/dark, all five languages (non-English marked for native review, reuse the
   Android strings files `android/app/.../ui/i18n/*Strings.kt` as the source).
5. TestFlight build.

## Repo layout
`Relay - Sustainability & Social Impact/ios/` with `RelayCore` as a local Swift package and an Xcode project for the
app. CI-free for now; `xcodebuild test` must pass locally.
