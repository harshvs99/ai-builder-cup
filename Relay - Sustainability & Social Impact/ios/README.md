# Relay iOS

Native iPhone client for the Relay offline BLE mesh. It must interoperate byte-for-byte with Android
(`protocol/SPEC.md`). Spec: `docs/design/ios-build-plan.md` (iOS deltas) and `docs/design/android-redesign.md`
(screens, copy, states).

## Layout

```
ios/
  RelayCore/          Swift package (builds and tests on Linux and macOS, no UIKit)
    Sources/RelayCore   wire protocol, gossip, intake
    Sources/RelayKit    platform-free app logic (stores, gateway sync, i18n, screen logic)
  Relay.xcodeproj     app project, scheme "Relay" (also runs the RelayCore and RelayKit package tests)
  RelayApp/           the app target; a synchronized folder, so just add .swift files here, no project edits
    RelayApp.swift      @main
    Screens/            SwiftUI screens (see below)
    DesignSystem/       pure SwiftUI components (theme tokens, glass styles, chips, timeline, rows, ...)
    Assets.xcassets     AccentColor, AppIcon, named colour tokens with dark variants
    Info.plist          (excluded from the synchronized group; referenced by INFOPLIST_FILE)
    <lang>.lproj/InfoPlist.strings   permission strings: en, hi, or, tl, war
```

Bundle id `org.relay.ios`, Swift 6, iPhone only, deployment target iOS 17, built with the iOS 26 SDK.
Liquid Glass APIs are used only through `DesignSystem/View+Glass.swift`, which falls back to materials before
iOS 26. Do not write `#available(iOS 26, *)` in screens.

Asset colours are read as `Theme.Palette.*` (not `Color.xyz`) so they never collide with Xcode's generated
asset symbols.

## Build and test

```
open ios/Relay.xcodeproj
xcodebuild test -project ios/Relay.xcodeproj -scheme Relay -destination 'platform=iOS Simulator,name=iPhone 17'
cd ios/RelayCore && swift test
```

Set a development team in Signing & Capabilities to run on a device (automatic signing, team left empty).

## Background Bluetooth: be honest in the UI

iOS limits BLE in the background: a backgrounded peripheral drops its local name and moves service UUIDs to the
overflow area, and scanning only finds peers that advertise the service UUID explicitly, at a reduced rate. The
app declares `bluetooth-central` and `bluetooth-peripheral` and will use State Preservation and Restoration on a
best-effort basis. Full mesh behaviour is foreground only, and the UI must say plainly that an iPhone carries
messages best with Relay open.

Non-English strings (including the Info.plist usage strings) are drafts marked `needs native review`.
Odia has no on-device speech model, so voice input falls back to typing.

## Screens

`RelayApp/Screens/` is layout and wiring only; every rule and string comes from RelayKit (`UILogic` display models,
`Strings`) and `AppModel`.

- `RootView` (first-run `LanguageScreen`, then four tabs), `Navigation` (one `NavigationStack` path per tab via
  `TabNavigator`, the ask flow in `AskFlowController`, `RouteDestination`), `StringsEnvironment` (`\.strings`,
  `NowClock`, larger-text modifier), `DesignMapping` (RelayKit values to DesignSystem parameters).
- Tabs: `HomeScreen`, `RequestsScreen`, `NetworkScreen`, `SettingsScreen`. Pushed: `NewRequestScreen` (1 of 3),
  `CheckScreen` + `CheckEditors` (2 of 3), `RequestDetailScreen`, `DeveloperScreen` (Settings last row and the Network
  "< >" button; English-only on purpose).
- Language comes from `Settings.language` into `EnvironmentValues.strings`; Appearance maps to `preferredColorScheme`;
  Larger text adds two Dynamic Type steps on top of the system size.

## Status

- Verified on Linux: RelayCore and RelayKit tests (including every display model the screens use), and `swiftc -parse`
  of every screen file. The screens themselves have not been type-checked or run: there is no iOS SDK here.
- Needs Xcode: first compile of `Screens/`, light/dark and Dynamic Type passes, VoiceOver pass, the Liquid Glass look on
  iOS 26 and the material fallback on iOS 17 to 25.

## Android changes mirrored since the first port

- Mesh-assisted location: `LOC_HINT` (0x74) in RelayCore (`LocHint`, `LocHintResponder`, gossip store/flood/expiry), hint upload in
  `GatewaySyncer`, the "Help locate neighbours" setting (`RelayHelpLocateDefault` in Info.plist / xcconfig, default on),
  scan RSSI per central link. A hint needs a fix, and the app only has When-In-Use location, so an iPhone hints while Relay is open.
- BLE zombie-link recovery: `didModifyServices` reconnect, one discovery retry, 90 s liveness watchdog on the 500 ms tick.
  Needs a device test with a restarted Android peer.
- No-location prompt before Send (`NoLocationLogic`): an alert from `CheckScreen`; with no landmark, "Send without location" asks again.

## Contract v2 (languages, Ready mode, shelters, announcements)

Built from `docs/design/app-contract-v2.md`, mirroring `android/` with the same test names.

- Languages: `LanguageCatalog` (RelayKit/I18n), build list `RelayEnabledLangs` (Info.plist / `RELAY_ENABLED_LANGS`), `GET /v1/languages` via
  `LanguageClient`. Only en and hi are offered by default; or, tl, war keep their strings, marked `pendingReview`.
- `ANNOUNCEMENT` (0x76): `Payload/Announcement.swift`, gossip store/relay in `GossipNode`, golden vectors in `VectorsTests`,
  show/hide rules in `AnnouncementBoard`, sync fields in `SyncClient` / `GatewaySyncer`, Home card in `HomeScreen`.
- Ready: `RelayKit/Ready` (models, atomic `ReadyStore`, `ReadyRefresher`, `HttpReadyApi`, `ReadyView`, `Shelters`), app side in
  `Services/Ready` (`ReadyCenter`, `AnnouncementCenter`) and `Screens/Ready` (Ready, household, shelters). Background refresh is a
  `BGAppRefreshTask` (`org.relay.ios.ready-refresh`, Info.plist `BGTaskSchedulerPermittedIdentifiers` + `fetch`). Demo shelter lists show only
  in Debug builds or with `RELAY_DEMO_SHELTERS = YES`.
- **Not compiled or run in the container that wrote it** (no Swift toolchain, no iOS SDK): run `swift test` in `ios/RelayCore`, then build the
  Xcode project, before trusting any of the above. The Android twin of every test passes on the JVM.

## Device-test checklist

1. iPhone locked and Android: Android may not discover a locked iPhone because iOS moves the service UUID to the
   overflow area. Test iPhone-locked with Android first; it decides how the demo is staged.
2. iOS ATT MTU is at least 185 with Android: confirm the negotiated MTU and that a full NEED frame goes through.
3. CCCD / HELLO ordering when the iPhone is the peripheral: HELLO must not be sent before the central has subscribed.
4. Speech permission and error mapping: deny microphone, deny speech recognition, Odia (typed path), airplane mode with
   no on-device model, "didn't catch that".
5. A non-default backend: attach `ios/Config/Relay.xcconfig` (copy `Relay.xcconfig.example`) to the project.
