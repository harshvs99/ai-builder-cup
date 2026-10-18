# Android redesign — foundation APIs (for screen work)

Spec: [android-redesign.md](android-redesign.md). Paths are under `android/app/src/main/kotlin/org/relay/app/`.

## Strings (`ui/i18n/`)
- In a composable: `val s = LocalStrings.current`, then `s.common.send`, `s.home.title`, etc.
- Each screen area has its own class — `HomeStrings`, `AskStrings`, `RequestStrings`, `NetworkSettingsStrings`. Add
  `val x get() = tr(lang, en, hi, or, tl, war)` (or a `fun` for parameterised text). Mark non-English text
  `// needs native review`.
- `CommonStrings`: nav labels and common actions; `people`/`phones`/`hops`/`ago`; `urgencyWord`/`urgencyHelp`;
  `categoryName`/`vulnFlag`; `stepTitle`/`stepSubtitle`/`rowStatusLine`; `villagerTitle`/`villagerLine`/`villagerWord`.
  `Plural` and `RelativeTime` in `TextFormat.kt`. `AppLanguages.all` = native + English names.

## Theme (`ui/theme/`)
`RelayTheme(mode, largerText) { }`; extra tokens via `RelayTheme.colors` (success, successContainer, warning,
onWarning, warningText, micYellow). `ThemeMode`, `VoiceSpeed`, `GatewayMode` live in `AppSettings.kt`.

## Settings
Read `MeshRepository.settings.flow.collectAsStateWithLifecycle()`; write `MeshRepository.settings.update { it.copy(...) }`.
Fields: themeMode, largerText, readAloud, voiceSpeed, languageConfirmed, areaLabel, gatewayMode (NEVER = old
pretend-offline), carryInBackground (stored, not yet wired to the service).

## Components (`ui/components/`)
`PillButton(text, onClick, style = PillStyle.Filled|Tonal|Outlined, icon, enabled)`, `SectionCard(title?, onClick?) {}`,
`EditableRow(label, value, onChange, secondary?, secondaryAction?)`,
`SegmentedChoice(options, selected, onSelect, label, warningSelected)`, `StatusDot(Tone)`, `StatusLabel(Tone, text)`,
`VillagerStatus.Dot.tone()`, `CheckChip(text)`, `CategoryIcon(category, closed)`, `UrgencyBadge(urgency)`,
`Timeline(List<TimelineStep(title, state, subtitle?, tag?)>)` + `List<RequestTimeline.Step>.toTimelineSteps(common)`,
`StatTile(label, value, valueColor)`, `SettingSwitchRow`, `SettingNavRow` (SettingRows.kt),
`StepTopAppBar(title, onBack, stepText?, actions)`, `SpeakButton(label, onClick)`, `SpeakIconButton`,
`RelayPreview` (for previews), `PlaceholderScreen` (delete when a real screen replaces it).

## Request model (`store/`)
`MyNeed`: vulnFlags, answers (question id → value), cancelled/cancelledAtMs, per-step timestamps (savedAtMs,
sentToMeshAtMs, uploadedAtMs, receivedAtMs, dispatchedAtMs, onTheWayAtMs, resolvedAtMs), etaAtMs, isClosed.
`Stage`/`NeedStatus` include ON_THE_WAY and RESOLVED. `RequestTimeline.steps(need, now)`;
`VillagerStatus.from(meshStatus, now)` → kind, dot, peers, hopsToGateway, syncedAgoMs.
`MeshRepository.cancel(msgId)` marks cancelled and drops the NEED from this phone's store/outbox.

## Navigation (`ui/Routes.kt`)
Screens take `nav: RelayNav`: back(), toHome(), toRequests(), toNetwork(), toSettings(), openRequest(id),
startAsk(mode, prefillFrom), toCheck(), finishAskTo(msgId), openConsole(), openDeveloper(), languageDone(),
askFlowOwner(). `ask` and `check` share a nested `askflow` graph: use `viewModel(viewModelStoreOwner = flowOwner)`.
The Scaffold applies no insets: tab screens call `statusBarsPadding()`; flow screens use `StepTopAppBar` +
`navigationBarsPadding()` + `imePadding()`.
Placeholder owners: `ui/home/HomeScreens.kt` (Language, Home, Requests), `ui/ask/AskScreens.kt` (Ask, Check),
`ui/request/RequestDetailScreen.kt`, `ui/network/NetworkScreens.kt` (Network, Settings; console = old Relay tab,
developer = old Settings as `DeveloperSettingsScreen`). Old home is `LegacyHomeScreen` at route `legacy-home`.

## Voice and permissions
`LocalSpeaker.current?.speak(text, lang)` → Boolean; `isVoiceAvailable(lang)`. `val p = rememberPermissions()`;
`p.ensureBluetooth { ok -> }`, `ensureLocation`, `ensureMicrophone`, `enableBluetooth`, `openAppSettings`; read `p.tick`
to recompose on permission changes.

## Known gaps
No "online X min ago" for a remote gateway (MeshStatus has no timestamp for it); GatewayMode.ALWAYS == AUTOMATIC;
all non-English strings are drafts for native review.
