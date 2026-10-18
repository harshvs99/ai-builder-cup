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
    RelayApp.swift      @main and the placeholder RootView
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
