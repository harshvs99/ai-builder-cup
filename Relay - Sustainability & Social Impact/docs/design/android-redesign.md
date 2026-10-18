# Relay Android redesign — build spec

Transcribed from the "Relay Mobile Mockups" design (Android light and dark, Material 3, Google blue scheme; an iOS
version exists and comes later). Screens below are in flow order. Copy in quotes is the English source text; every
string ships in all five app languages (en, hi, or, tl, war). Non-English text written by an engineer or model is a
draft for native review and is marked so in the string table.

Who it is for: a villager at night after a cyclone, maybe with wet hands, maybe not reading well in any script. One
primary action per screen, status always as a colour plus a sentence, everything reachable one-handed, everything
speakable (TalkBack labels, and a speaker button where the design shows one).

## Visual system

- Material 3, Compose. Light, Dark and Auto (follow system; Auto is the default). Dark surfaces are near-black.
- Colour tokens (light / dark):
  - primary `#0B57D0` / `#A8C7FA`; onPrimary `#FFFFFF` / `#062E6F`; primaryContainer `#D3E3FD` / `#0842A0`;
    onPrimaryContainer `#041E49` / `#D3E3FD`
  - surface `#F8F9FC` / `#131314`; surfaceContainer `#EEF0F5` / `#1E1F20`; surfaceContainerHigh `#E6E8EE` / `#282A2C`;
    onSurface `#1F1F1F` / `#E3E3E3`; onSurfaceVariant `#444746` / `#C4C7C5`; outline `#747775` / `#8E918F`
  - success `#146C2E` / `#6DD58C`, successContainer `#C4EED0` / `#0F5223` (check chips, "Reachable", done steps)
  - warning (urgent) `#F9AB00` fill with `#3F2E00` text in both themes (Urgent badge, selected urgency segment)
  - error `#B3261E` / `#F2B8B5`, errorContainer `#F9DEDC` / `#8C1D18` (stop button, Cancel)
  - mic button fill `#FBBC04` (Google yellow) with `#1F1F1F` icon, both themes
- Shapes: cards 16 dp radius on surfaceContainer, no elevation; primary actions are full-width pill buttons 56 dp tall;
  chips 8 dp radius; bottom navigation is the M3 NavigationBar with the pill indicator.
- Type: M3 default type scale. "Larger text" multiplies all text by 1.2. Screen titles are headlineMedium
  ("Relay", "Network", "Settings"); flow screens use a TopAppBar with back arrow, title and a step counter ("1 of 3").
- Contrast: all text >= 4.5:1 on its surface in both themes. Touch targets >= 48 dp.

## Navigation

Bottom bar, four destinations: Home, Requests, Network, Settings (icons: home, list, share/nodes, gear).
Flow screens (New request, Check and send, Request detail) are pushed over the tab with a back arrow; the bottom bar
stays visible on Request detail and is hidden during New request / Check and send.

First run only: Choose your language, then Home. Permissions are asked when first needed, never as one wall:
Bluetooth + notifications when Home first shows (one plain sentence: "So your phone can pass messages to neighbours"),
location at first Send, microphone at first mic tap.

## 1. Choose your language (first run)

- Small label "Relay" in primary; title "Choose your language"; subtitle "Tap the speaker to hear it. You can change
  this later in Settings."
- One card with five radio rows: native name over English name, speaker icon at the end (speaks the language's name
  and a short greeting in that language via TextToSpeech; if no voice is installed, a short message says so).
  Rows: "English / English", "हिन्दी / Hindi", "ଓଡ଼ିଆ / Odia", "Tagalog / Filipino", "Waray / Waray-Waray".
- Full-width primary pill "Continue" pinned at the bottom. The whole app switches language immediately.

## 2. Home

- Title "Relay". Subtitle: area label then a status dot and word, e.g. "Ward 4, Puri district · ● Reachable". The area
  label is an optional text the user can set in Settings ("Your area"); omit it when empty. The status word and dot
  colour follow the villager status below. Help icon (?) top right opens a short "How Relay works" sheet.
- Status card (surfaceContainer, chevron, opens Network): title + one line, from the villager status:
  | state | title | line | dot / word |
  |---|---|---|---|
  | a gateway is known and the last sync on that path is OK | "Your messages can reach help" | "Gateway N hops away · online X min ago" | green "Reachable" |
  | this phone is the gateway and syncing OK | "This phone is sending messages to help" | "Connected to the internet · synced X min ago" | green "Reachable" |
  | peers but no gateway, or the gateway cannot reach the backend | "Waiting for a phone with signal" | "Connected to N phones nearby. Your messages wait safely." | amber "Waiting" |
  | no peers | "Looking for nearby phones" | "Keep Relay open. Messages wait safely on this phone." | grey "Searching" |
  | Bluetooth off | "Bluetooth is off" | "Turn it on so phones can pass your message" + Turn on action | grey "Off" |
  No node ids, addresses, hosts or error strings on this screen.
- Centre: a 160 dp yellow circular mic button (Google yellow, dark mic icon). Tap starts the New request flow in voice
  mode. Under it "Press and say what you need" (titleLarge) and "Water · food · medicine · rescue · shelter"
  (bodyMedium, onSurfaceVariant). Then a tonal pill "Type instead" with a keyboard icon (New request in text mode).
- "Your requests" header with "See all" (goes to the Requests tab); up to two most recent request rows (see Request row).

### Request row (Home and Requests)
Leading 40 dp circle with a category icon (water drop, food, medicine, rescue, shelter, person, other), tinted by
category; a green check circle instead when the request is closed. Title "Drinking water · 6 people" (item or category
name, then "1 person" / "N people"). Line two: the latest timeline step in words plus relative time, e.g. "Reached a
gateway phone · 2 min ago", "Delivered yesterday". Trailing label: urgency word ("Urgent", "Life at risk", ...) while
open; "Closed" when resolved; "Cancelled" when cancelled.

## 3. New request — step 1 of 3 (voice)

- TopAppBar: back arrow, "New request", "1 of 3".
- Headline "What do you need most right now?". Small tonal chip "Hear it again" (speaker icon) speaks the headline.
- Listening card (surfaceContainer): waveform icon + "Listening" in primary while recording (static "Paused" when
  not), the recognition language at the end ("Hindi"); the live transcript in quotes; a divider; label "Understood so
  far"; green check chips for what the intake engine has extracted so far: item or category, people ("6 people"),
  landmark ("Near the primary school"), and the summary/note ("Pump broken since yesterday"). Update as the transcript
  updates (RuleIntake on the partial text; model path later behind the same interface).
- "Or tap what you need": outlined chips Water, Food, Medicine, Rescue, Shelter, Something else (Missing person is in
  "Something else" via a second row of chips: Missing person, Other). Tapping a chip sets the category.
- Bottom: a 56 dp square stop button (errorContainer, stop icon) that stops listening, next to a full-width primary
  pill "Done, check it" (enabled once there is a category or text). Text link "Type instead" below switches the card
  to a multi-line text field (same extraction on typed text).
- Speech uses the existing offline SpeechRecognizer path; when the language pack is missing, the card says so in plain
  words with "Type instead" — never a developer message.

## 4. Check and send — step 2 of 3

- TopAppBar: back arrow, "Check and send", "2 of 3". Headline "Is this right?".
- One card with four rows, each: small label, value, and a "Change" text button at the end that edits just that field
  in a bottom sheet:
  - Need: item or category in words ("Drinking water")
  - People: "6" then " · " and the vulnerability flags in words ("children, elderly", "trapped"). The wire format
    carries flags, not counts, so do not invent counts.
  - Where: "Near <landmark>" (or "No landmark yet"), and a second line with the location state: "GPS · about 40 m
    accuracy", "Getting location…", "Location is off — rescuers will rely on the landmark" with a Turn on action.
  - Note: the summary (40-byte limit, shown as a quiet characters-left counter while editing).
- "A few quick questions" card (only when the intake engine returned questions for the category): each question from
  the core-protocol QuestionBank in the chosen language with 2–4 large answer chips and a speaker button; answers go
  into the NEED v2 answer bytes. This card is not in the mockup; it is required by the intake design. Keep it compact.
- "How urgent is it?" segmented control: Can wait | Soon | Urgent | Life at risk (urgency 0..3). Selected segment
  filled with the warning colour. Helper line under it explains the selected one: "Can wait: today or tomorrow is
  fine." / "Soon: needed today." / "Urgent: needed in the next few hours." / "Life at risk: someone may die without
  help now."
- Primary pill "Send" (send icon), outlined pill "Change something" (back to step 1 keeping what was understood),
  footnote "Goes phone to phone until it reaches a gateway. No network needed."
- Send stores and broadcasts the NEED exactly as today (same MeshRepository path), then opens Request detail.
  Step "3 of 3" is that sent state.

## 5. Request detail

- TopAppBar: back arrow, "Request", share icon (shares a plain-text summary).
- Title "Drinking water · 6 people" with an urgency badge ("Urgent", warning colour). Line: reference, sent time and
  area, e.g. "R-7F3A · sent 2:02 PM · Ward 4". Reference = "#<ticket no>" once the control room has one, else "R-" +
  first 4 hex of the msg id, uppercase.
- Vertical timeline, done steps as green check circles joined by a line, the current step as an outlined primary
  circle, future steps hidden:
  1. "Saved on this phone" — time
  2. "Passed to a neighbour's phone" — time (when first sent over the mesh)
  3. "Reached a gateway phone" — time · "uploaded to the control room"
  4. "Seen by the control room" — time · "marked urgent" (urgency word of the ticket if known)
  5. "Help is coming" (DISPATCHED) — "Expected by 4:30 PM" from eta_min when known
  6. "Team on the way" with a "LIVE" tag (ON_THE_WAY) — "Expected by …"
  7. "Delivered" (RESOLVED) — time
  Record a timestamp the first time each step is reached (persisted with the request). Do not show counts the phone
  cannot know (the mockup's "2 phones carried it" is omitted).
- "From the control room" card: the ACK text (already in the villager's language) and a tonal "Hear it in <language>"
  button. Auto-read when "Read messages aloud" is on and the message is new.
- Actions: "Cancel request" (error colour text button) and tonal "+ Add to request".
  - Cancel: confirm dialog, honest copy: "Stop sharing this request? Phones nearby will stop passing it on from this
    phone. If the control room already has it, they may still come." Marks the request cancelled locally and stops
    this phone from relaying its NEED. (No cancel frame exists on the wire.)
  - Add to request: opens New request prefilled with this request's category and landmark; it is sent as a new NEED.

## 6. Requests tab

Title "Your requests". Sections "Active" and "Closed" (resolved or cancelled), request rows as above, tap opens detail.
Empty state: "No requests yet. Press the yellow button on Home to ask for help."

## 7. Network

- Title "Network", code icon "< >" top right opens the existing developer console (today's Relay tab content, every
  number kept). Subtitle "Bluetooth mesh · 3 phones · ● Reachable".
- Card with the villager status title and a sentence ("The nearest gateway phone is 2 hops away and was online 1 minute
  ago. Relay never shows who owns a phone.") and a chain diagram: "You" (filled primary circle, "This phone") — dashed
  line — intermediate hop (phone icon circle, "1 hop") — dashed — gateway (green circle with a sync icon,
  "Gateway · 2 hops"). Collapse to You — Gateway when this phone is 1 hop away; show only You (as gateway) when this
  phone is the gateway; show You — "?" when no gateway is known.
- 2×2 tiles: "Phones nearby" (peer count), "Carried for neighbours" (stored NEED/ACK frames not originated here),
  "Waiting to pass on" (frames not yet delivered/uploaded), battery: "Mesh hours left" ("about N h", shown green; "—" when
  unknown), or "Battery — Charging" while the phone is charging.
- Toggles card: "Act as a gateway when online — Uploads everyone's requests the moment this phone gets signal."
  (inverse of today's "Pretend offline"); "Carry messages in the background — Bluetooth only. Uses some battery."
  (runs the foreground service when the app is closed).

## 8. Settings

Grouped list:
- Language and voice: Language (current, chevron → the language list), Read messages aloud (switch), Voice speed
  (Slow | Normal).
- Screen and battery: Theme (Light | Dark | Auto), Larger text (switch), note "Dark appearance is used at night by
  default. It is easier on the eyes in a shelter and saves battery." Your area (optional text, shown on Home).
- Network and permissions: Gateway mode (Automatic / Never / Always, chevron), Bluetooth (Allowed — green, chevron to
  system settings), Location (While using), Microphone (Ask each time — amber when not granted). Values reflect the
  real permission state.
- Before a disaster: "Household plan — Shelter, checklist and a spoken plan, saved offline" with a tonal "Set up"
  button (opens a "Coming soon" sheet for now; Ready mode is a later module).
- Developer (last row, small): backend URL, Test connection, device key, chain allowlist, Clear store, Clear my
  needs, node id — everything today's Settings has.
