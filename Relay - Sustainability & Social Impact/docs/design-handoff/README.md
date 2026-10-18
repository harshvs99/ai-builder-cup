# Relay redesign handoff: screenshot index and brief

Captured 2026-10-16/17 from the current build (Android debug APK 0.1.0, dashboard `dist/` build of 2026-10-13) against an isolated screenshot backend with seeded data. App shots: Android 16 emulators (1080x2400, 420 dpi, status bar normalised via demo mode: 10:00, full battery) and one real phone (Samsung SM-S721B, 1080x2340, system dark mode, so its shots are dark only). Dashboard shots: headless Chrome at 2x pixel ratio. All PNGs were opened and checked (no blank, black or lock-screen frames).

Data caveats: seeded tickets are synthetic. Odia and Hindi text was entered through `DEBUG_SEND` broadcasts because `adb input text` cannot type non-Latin text; the typing screenshot (app/09) therefore shows romanised Odia. A few "My needs" cards in app/22, 23, 33, 34, 40, 41 are junk entries from my first debug sends (they were truncated to one word, shown as "?" category). That is an artefact of my harness, not a user flow, but it does show how the card looks when classification fails.

## Screenshot index

### Android app (`app/`)

| File | Screen | State | User is trying to |
|---|---|---|---|
| 01-first-run-location-permission.png | Home + system dialog | Fresh install, 1st of 4 stacked system permission dialogs (location), "Grant permissions" card behind it | Start the app for the first time |
| 02-first-run-permission-2.png | System dialog | Microphone | Same |
| 03-first-run-permission-3.png | System dialog | Nearby devices (Bluetooth) | Same |
| 04-first-run-permission-4.png | System dialog | Notifications | Same |
| 05-home-empty-origin.png | Home | Origin phone (pretend offline), 1 BLE peer, no needs yet, English | Report a need |
| 06-home-gateway-ok.png | Home | Gateway phone, "Gateway ✓ synced N s ago" | Check phone is working as gateway |
| 07-language-hindi-selected.png | Home | Language chip row, Hindi selected | Choose language |
| 08-language-odia-selected.png | Home | Odia selected | Choose language |
| 09-typing-need-odia-selected.png | Home | Keyboard open, text typed (romanised), Review enabled | Describe the need |
| 10-confirm-card-with-gps-fix.png | Home | Confirm card, top (category chips, item field) | Verify the extracted need |
| 11-confirm-card-scrolled-location-send.png | Home | Confirm card scrolled: urgency, flags, summary, "±5 m" fix, Send/Cancel | Send the need |
| 12-system-no-location-dialog.png | System dialog | Android "No location access" dialog over the confirm card (appears when Location is switched off) | Understand why there is no fix |
| 13-confirm-card-location-off.png | Home | Confirm card, "Location is off" + "Turn on" button + landmark fallback note | Send without GPS |
| 14-confirm-card-getting-location.png | Home | Confirm card, spinner "Getting location…" | Wait for GPS |
| 15-voice-listening-panel.png | Home | Voice panel "Listening (Odia)… Speak now", Stop/Cancel | Dictate a need |
| 16-voice-state-after-3s.png | Home | Same panel a few seconds later (emulator has no mic input, nothing recognised) | Dictate |
| 17-voice-unavailable-fallback.png | Home | Voice error: offline pack missing; Voice settings / Try again / Type instead (last button clipped); "Bluetooth off" pill because airplane mode was on | Recover from failed voice |
| 20-toast-sent-without-location.png | Home | Toast "Sent without location — rescuers will rely on the landmark"; card already says Received with Odia confirmation | Confirm send |
| 21-toast-sent-with-location.png | Home | Toast "Sent with location (±5 m)" | Confirm send |
| 22-my-needs-queued-no-links.png | Home | "Peers 0 / no gateway yet"; need status "Queued (no links yet)"; also two Received cards with Odia text | Know whether help was heard |
| 23-my-needs-sent-to-mesh.png | Home | Status "Sent to mesh" (gateway cannot upload) | Same |
| 24-gateway-cant-reach-backend.png | Home | Gateway card red: "Gateway can't reach backend (10.0.2.2:9) — check Settings / Tailscale", "failing for 1 min"; need "Waiting for upload — backend unreachable" | Diagnose gateway |
| 26-settings-top-test-connection-failed.png | Settings | Backend URL, red health line, Test connection ✗, device key, allowlist, Save/Defaults, Pretend-offline switch | Fix connectivity |
| 27-settings-scrolled-middle.png | Settings | Scrolled | Same |
| 28-settings-scrolled-bottom.png | Settings | Scrolled to bottom | Same |
| 29-relay-tab-top-peer.png | Relay tab | Mesh service, BLE links, gateway hops, peer card with node id, stats | Inspect mesh (developer view) |
| 30-relay-tab-scrolled-log.png | Relay tab | Monospace log lines | Same |
| 31-relay-tab-gateway-sync-error.png | Relay tab | Gateway view with pending uploads and sync error | Same |
| 32-settings-clear-store-dialog.png | Settings | "Clear store?" destructive dialog | Wipe local packets |
| 33-my-needs-mixed-statuses-top.png | Home | My needs with Sent to mesh x2 and Received (Hindi text) | Track requests |
| 34-my-needs-mixed-statuses-scrolled.png | Home | Received (Odia), Help dispatched (Odia, two stacked texts), Received | Same |
| 35-settings-test-connection-ok.png | Settings | Green "Gateway ✓ synced 0 s ago", Test connection ✓ 3 ms | Verify setup |
| 36-settings-top-pretend-offline-device.png | Settings | Top of settings on an offline origin phone | Configure |
| 37-settings-scrolled-bottom.png | Settings | Pretend offline ON, node id, permissions card, Data buttons, POC note | Configure |
| 40-dark-home-my-needs.png | Home | Dark mode, my needs | Track requests |
| 41-dark-confirm-card.png | Home | Dark mode confirm card scrolled | Send |
| 42-dark-relay-tab.png | Relay tab | Dark | Inspect mesh |
| 43-dark-gateway-failing-home.png | Home | Dark, gateway failing (red text on grey) | Diagnose |
| 44-dark-settings-top.png | Settings | Dark | Configure |
| 60-phone-home-gateway-ok-dark.png | Home | Real phone, dark, status bar clutter, real earlier needs | Use the app |
| 61-phone-confirm-card.png | Home | Real phone, confirm card (Hindi selected, fix ±6 m). Not sent. | Verify need |
| 62-phone-confirm-card-scrolled.png | Home | Same, scrolled to Send/Cancel | Send |
| 63-phone-my-needs-scrolled.png | Home | Real phone, my needs with Help dispatched (Hindi) | Track requests |

Not captured: Odia/Hindi typed in the text field; the actual spoken-recognition result; "No permission" location state; the "Saved" toast; Bluetooth-off enable dialog; light-mode phone shots (phone is in system dark mode and I did not change its settings); 12/17 vs 16 are near duplicates.

### Control-room dashboard (`web/`)

| File | Viewport | State | Operator is trying to |
|---|---|---|---|
| 01-empty-desktop-light.png | 1440x900 | Empty backend, map has no tiles at world zoom, "Queue (0)" message | Wait for reports |
| 02-empty-desktop-dark.png | 1440x900 dark | Same | Same |
| 03-empty-tablet-light.png | 768x1024 | Same | Same |
| 04-empty-mobile-light.png | 375x812 | Same | Same |
| 05-overview-desktop-light.png | 1440x900 | 15 tickets, Fit-all view spans Odisha + Leyte (zoomed to half of Asia), clustered markers overlap, header stats, queue | Triage |
| 06-overview-desktop-dark.png | 1440x900 dark | Same | Same |
| 07-overview-tablet-light.png | 768x1024 | Same | Same |
| 08-overview-mobile-light.png | 375x812 | Same, viewport only | Same |
| 08b-overview-mobile-light-full-page.png | 375 wide, full page | Same, whole scroll length | Same |
| 09-ticket-cluster-open-desktop-light.png | 1440x900 | Ticket #101 selected: OPEN, x4 reports, location+accuracy, hop trail, Approve button below the fold | Decide on a P1 medical ticket |
| 10-ticket-open-desktop-light.png | 1440x900 | Leyte rescue ticket, map flown to Tacloban | Same |
| 11-ticket-no-location-desktop-light.png | 1440x900 | Ticket with no GPS (orange "No location — near ..."), not on map | Handle unlocatable report |
| 12-ticket-dispatched-desktop-light.png | 1440x900 | DISPATCHED ticket, button reads "Dispatched ✓" | Review a closed ticket |
| 13-ticket-hindi-desktop-light.png | 1440x900 | Devanagari title and landmark (landmark truncated mid-phrase) | Read non-Latin report |
| 14-ticket-open-desktop-dark.png | 1440x900 dark | Selected ticket; detail panel capped, Approve button hidden under the queue | Approve |
| 15-ticket-cluster-tablet-light.png | 768x1024 | Selected ticket | Same |
| 16-ticket-cluster-mobile-light.png | 375x812 | Selected ticket under the map | Same |
| 17-ticket-approve-button-scrolled-desktop-light.png | 1440x900 | Side panel scrolled so "Approve & dispatch" and the draft are visible | Approve |
| 18-header-stats-crop-light.png | 1440 wide, 40 px crop | Header: category counts, "1 without location", "Gateways online: 1", Live | Read status at a glance |
| 19-ticket-approve-button-scrolled-mobile-light.png | 375x812 | Approve button reached by scrolling | Approve on a phone |
| 20-ticket-mesh-origin-odia-desktop-light.png | 1440x900 | Ticket that really travelled over BLE from an emulator (Odia request) | Same |
| 21-backend-unreachable-desktop-light.png | 1440x900 | Backend stopped; header shows red "Backend unreachable — retrying", header wraps | Notice outage |
| 22-approve-error-desktop-light.png | 1440x900 | Approve pressed with backend down: "Could not dispatch: Backend unreachable (Failed to fetch)" | Recover from failed approve |
| 23-backend-unreachable-mobile-light.png | 375x812 | Same outage on mobile | Same |

## Product context

Relay is an offline disaster-relief messaging system for cyclone and flood zones (Odisha, India and Leyte, Philippines), where cell towers fail first. A villager types or speaks a need in their own language; the phone classifies it on-device into a roughly 100-byte packet (category, urgency 0-3, people count, vulnerability flags, item, landmark, summary, GPS fix with accuracy bucket). The packet hops phone to phone over Bluetooth LE. The first phone with any internet (the gateway) uploads it to the district backend.

The control-room dashboard clusters near-duplicate reports into tickets, ranks them P1 to P4 (score plus a plain-language reason), plots them on a map, and drafts a dispatch message. An operator approves; a short confirmation in the requester's language (Odia, Hindi, Tagalog, Waray, Cebuano, English) travels back through the mesh and appears on the requester's phone under "My needs". The app is both a request tool and, silently, a relay node for strangers' packets.

## Users and conditions

- Villagers and survivors: low-end Android phones with small batteries, direct sunlight, stress, wet hands, often low literacy, six languages including Devanagari and Odia scripts (Odia glyphs are wide and tall). No signal; Bluetooth only. One-handed, thumb reach. Many will not read English labels such as "Gateway", "hops" or "Peers"; those are developer concepts that currently sit on the primary screen.
- Gateway holders (anyone whose phone gets signal): need a trustworthy "your phone is helping others / is not" indicator, not a debug panel.
- Operators (district control room): laptops, long shifts, many simultaneous tickets, need triage speed, map trust, and zero-ambiguity approval. Dark mode and tablet or phone fallback matter.

## Hard constraints

- Stack: Android Compose Material 3 (single Activity, three-tab bottom bar: Home, Relay, Settings); dashboard is React with MapLibre GL and OSM raster tiles. Redesign must be implementable in these.
- Need card fields and enums are fixed by the wire format (protocol/SPEC.md): category in {MEDICAL, RESCUE, WATER, FOOD, SHELTER, MISSING_PERSON, OTHER}; urgency 0-3; people 1-255; vulnerability flags {ELDERLY, CHILD, PREGNANT, INJURED, TRAPPED}; item max 16 UTF-8 bytes; landmark max 24 bytes; summary max 40 bytes; location accuracy bucket 0 (no fix) to 5. In Devanagari (3 bytes per character) and Odia the item allows about 5 characters and the landmark about 8, so fields must truncate gracefully (today it cuts mid-phrase, see web/13).
- Confirmation text from the control room is at most 112 UTF-8 bytes, about 37 Odia characters; the UI must not assume it fits one line.
- The app must work fully offline: no web fonts, remote images, or icon fonts that need the network. Emoji are used as icons today; they depend on the device's emoji font and look different on old Androids.
- Light and dark themes, large touch targets, one-handed reach for Send and Cancel.
- Mesh and gateway status must stay honest: "Sent to mesh" is not "received". The statuses Queued, Sent to mesh, Waiting for upload, Uploaded, Received, Dispatched, Resolved are real and distinct.
- Dashboard: ticket list must stay the source of truth even when the map has no tile access; tickets with no GPS never appear on the map.

## Current UX problems observed while capturing

Android (`android/app/src/main/kotlin/org/relay/app/ui/`):
1. First run is four stacked system dialogs with no explanation (app/01-04; trigger in `RelayRoot.kt`, `requestAll()` on first composition). The only context is a small card behind the dialog (`HomeScreen.kt`, "Relay needs Bluetooth, location, microphone and notification permissions").
2. Developer vocabulary on the primary screen: "Node 801ed0…", "Peers 1", "gateway 1 hops away", "no gateway yet" (`HomeScreen.kt` MeshStatusBar). A villager cannot interpret these (app/05, 22). The Relay tab (RelayScreen.kt) is a raw debug console with a monospace log and sits in primary navigation.
3. The confirm card is very long: Send/Cancel are below the fold on a 2400 px phone (app/10 vs 11), and the typed text field and Review button stay visible above it, creating two competing primary buttons. Send is not pinned.
4. Classification failures are silent. Non-Latin input lands as category "?" (OTHER) with the first word as "item" and "people ×2" (app/22, 33). The confirm card has no cue to check it, and no way to pick language-specific wording.
5. Labels are English-only even when Odia or Hindi is selected: field labels, "Check before sending", "Send request", status pills and toasts (HomeScreen.kt). Only the control-room confirmation is localised. For low-literacy users icons and colour carry more weight than text, but status is mostly text.
6. Landmark is not extracted from free text and is not prominent; the field is optional-looking, but it is what rescuers use when there is no GPS (app/11, 13). The item field says "max 16 bytes", a unit users do not understand.
7. Location states are small (app/13, 14): one small line plus a "Turn on" tonal button; "Getting location…" blocks nothing but the Send button text changes to "Waiting for GPS…". The system "No location access" dialog (app/12) appears automatically and competes with the card.
8. Status wording and hierarchy in My needs: "Sent to mesh", "Waiting for upload", "Received by control room ✓" and "Help dispatched" are text pills in four colours. Received cards repeat the same sentence twice (a stored "Request #N reached the control room" plus the dispatch text, app/34, 63), and there is no timestamp, ordering by status, or plain "what to do now". Failure states are only shown on the gateway phone (app/24), not on the origin phone that actually needs to know.
9. Voice: the mic is an emoji in a small tonal button at the end of the field (app/15). The error panel's last button is clipped (app/17, horizontally scrolled row). The listening panel does not show a level meter or timer.
10. Dark mode contrast: red error text on the dark grey gateway card is nearly unreadable (app/43); green "Gateway ✓" on dark is weak (app/60); outlined chips and Cancel lose their borders on dark surfaces (app/41).
11. Settings mixes demo-only controls (Chain allowlist, device key, Pretend offline, "Clear store") with essentials, all in one scroll, with no separation (app/26-28, 37). Destructive buttons are the same style as safe ones.
12. Bottom bar uses emoji as icons; the language row scrolls off-screen (Waray partially cut, Cebuano hidden, app/05) so two of six languages are not discoverable.

Dashboard (`dashboard/src/`):
1. Detail and queue share one scroll column; on desktop the Approve button sits below the fold (web/09 vs 17) and in dark mode the draft is clipped at the panel edge with the button hidden behind the queue (web/14). Approve is the one action that matters.
2. Fit-all across two regions zooms to half of Asia and stacks markers into one blob (web/05, 08). There is no region filter or "Odisha / Leyte" jump.
3. Map markers show only category colour and size; priority tier (P1-P4) and OPEN versus DISPATCHED are not encoded on the map, only in the queue. Dispatched tickets stay on the map at full prominence.
4. Many P1s: five of fifteen tickets are P1, so the red tier does not discriminate. Queue rows show "0 hops" for everything (web/05), even mesh-origin tickets (web/20), which reads as a bug.
5. Dispatch draft "local" text is the English text prefixed with "[EN]" (no translation without Gemini credentials) and is labelled with the language code ("OR", "TL") (web/09, 21). The operator cannot tell it is untranslated.
6. Header gets crowded: seven category pills, "N without location", gateways, Live; at 1440 px the unreachable banner pushes it to two lines (web/21); at 375 px it eats a quarter of the screen before the map (web/04, 08).
7. Backend-unreachable is a small red label in the header; nothing on the map or queue marks data as stale (web/21). Approve error is plain red text under the button (web/22).
8. Raw coordinates and 8-character node IDs ("00000000" gateway for injected data) dominate the detail panel; the OSM tile labels switch language by region (Chinese labels on the Asia-wide view, web/05).
9. Mobile: the map takes about 40% of the screen and the detail panel opens below the queue's header with no scroll anchoring (web/16, 19).

## Open questions for the designer

1. Should the villager flow be a single guided card (language, speak, confirm) that carries no mesh or node vocabulary, with the Relay tab moved into a hidden "helper mode"?
2. How much of the app UI should be localised and icon-led for each of the six languages, and who supplies the translations and approves wording for life-safety terms?
3. What is the right way to show "your phone is carrying other people's messages" and gateway health to someone who is not technical, without draining attention?
4. One primary status per request: which of the seven real states can be merged for the requester, and what does each tell them to do?
5. Dashboard: do operators work region by region (district switch), or is one map for both countries the real model? What replaces Fit-all?
6. Should dispatched or old tickets leave the map? Do operators want batch approve, or a deliberate one-by-one approve?
7. Acceptable maximum density on the control room screen (tickets visible without scrolling) and whether a second large display layout is in scope.
8. Brand: is there a name, palette or illustration direction beyond the current Material default red and blue? Emoji as icons: keep for offline simplicity, or ship a bundled icon set?
