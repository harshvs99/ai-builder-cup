# iOS port: offline map advice, map-pack download, switching a disaster off

For the iOS session. Android, backend and dashboard are done on branch `claude/optimistic-wozniak-t6rrwk`
(commits `fc36773`, `8564f54`, `9b44e8b`). The wire format (BROADCAST, SPEC 5.6) is **unchanged**: nothing to redo in the
codec or vectors. Port the behaviour below; the Kotlin files are the reference, and their tests are the checklist.

## 1. Switching a disaster off, order-independent (do this first: it is a bug fix)

Reference: `android/app/src/main/kotlin/org/relay/app/DisasterStore.kt`, test `android/app/src/test/.../DisasterStoreTest.kt`.

- Keep the latest `DISASTER_CLEARED` time per disaster (persisted, dropped 24 h + 10 min after it).
- A `DISASTER` issued at or before that time is ignored, whenever it arrives (gateway, neighbour, start-up replay).
  Before this, a declaration arriving after its all-clear switched the disaster back on.
- A `DISASTER` issued after it switches it on again. `clear()` (developer) forgets both lists.
- SPEC 5.6 point 4 has the rule. The backend now stops handing out superseded declarations; nothing to do for that.

## 2. Map context in triage (shared library)

Reference: `android/core-protocol/src/main/kotlin/org/relay/core/triage/Places.kt`, additions in `Triage.kt`
(`TriageState.map`, `TriageState.placeView`, `Interpretation.advice`, `TriageResult.places`, `TriageEngine.start(..., map)`,
`absorb` keeps `placeView`) and `TriageBank.kt` (`placeText`, `distanceText`, compass labels, 4 languages).
Test: `android/core-protocol/src/test/kotlin/org/relay/core/PlacesTest.kt` (port it as is; the expected strings are exact).

- `MapContext.around(places, hereE5, accuracyM)`: nearest shelter (≤ 15 km), hospital (≤ 50 km), water (≤ 2 km);
  null without a position or with accuracy worse than 1 km. Distances rounded for speech (50 m / 0.5 km / 1 km steps).
- `PlaceAdvisor.allowed(state)` is the safety rule (who must not be sent walking); `choose(allowed, modelPick)` applies
  the model's pick: only allowed keys, model order; no pick = everything allowed.
- Result screen: first aid as before, then a "Nearby, from the offline map" card with at most 2 place lines; the spoken
  advice appends the same 2 lines before the "sent" note.

## 3. Gemma prompt

Reference: `android/app/src/main/kotlin/org/relay/app/gemma/TriageBrain.kt` (`TriagePrompt`), test `MapAdviceTest.kt`.

- System prompt: third job "CHOOSE which place advice from the ADVICE MENU fits", output key `"advice":[...]`,
  planning rule 7 (MAP) and the advice rules. Example output ends with `"advice":[]`.
- Turn: `MAP: <MapContext.promptLine() or "no offline map or no position">` and
  `ADVICE MENU: <key: description | ...> or (empty)` before `MENU`.
- Parse: `advice` absent = null (keep last view); present (even `[]`) = the pick, lower-cased, blanks dropped.

## 4. Map pack: fetched in normal times, kept for a disaster

Reference: `android/app/src/main/kotlin/org/relay/app/location/MapPack.kt` (store, parse, `MapPackPolicy`) and
`MapPackUpdater.kt` (fetch). Endpoint: SPEC 7 `GET /v1/mappack?lat=&lon=&radius_km=30` with `X-Relay-Key`.

- On app start (foreground): if `MapPackPolicy.shouldFetch` — not in a declared disaster for this position, position
  known, and no pack / pack > 30 days / moved more than half the radius from the pack centre; attempts spaced 15 min
  without a pack, 6 h with one — fetch and store the body with `fetched_at_ms` added. Failures keep the old pack.
- Triage start: `map = MapPack.around(storedPack, lastFix)` (fix ≤ 30 min old) and pass the position to the disaster
  gate too (`active(here:)`), so an area-limited declaration is skipped when the phone knows it is outside it.
- No pack = triage exactly as before.
- Developer section: pack status, "Update map now" (refused during a declared disaster), "End <disaster>" buttons that
  originate a `DISASTER_CLEARED`.

## Open items (same as Android)

- Hindi, Odia and Tagalog text for the place advice is a draft pending native-speaker review.
- The map pack only has Puri-district places (from the backend gazetteer).
