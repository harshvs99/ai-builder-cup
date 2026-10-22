import Foundation
import Observation
import SwiftUI
import os
@preconcurrency import CoreBluetooth
import RelayCore
import RelayKit

/// `Settings` also names a SwiftUI scene (macOS only); the alias keeps lookups unambiguous in files importing SwiftUI.
typealias RelaySettings = RelayKit.Settings

private let relayLogger = Logger(subsystem: "org.relay.ios", category: "mesh")

/// The single object screens talk to. It owns RelayKit's settings, repository and engine, wires the iOS platform
/// services into them (CoreBluetooth transport, speech, location, permissions, reachability, battery, scene phase) and
/// republishes everything SwiftUI needs as `@Observable` main-actor state.
///
/// Screens read the properties below and call the methods; they never touch RelayKit's engine, the transport or the
/// services directly. Create it once (see `RelayApp`), inject with `.environment(model)`, read with
/// `@Environment(AppModel.self)`.
///
/// Threading: RelayKit publishes from background queues through `AsyncStream`s; `start()` spawns tasks that consume
/// them on the main actor. Platform inputs (`setForeground`, `setBattery`, ...) are thread-safe engine calls.
@MainActor @Observable
final class AppModel {
    // ================================================================== published state

    /// Mesh status + my requests, republished about once a second when something changed.
    private(set) var snapshot: MeshSnapshot
    /// Persisted settings (language, theme, voice, gateway mode, ...). Change with `updateSettings(_:)`.
    private(set) var settings: RelaySettings
    /// The plain-language connectivity status for Home and Network. Recomputed on every snapshot and every 15 s.
    private(set) var villagerStatus: VillagerStatus
    /// Bluetooth radio state (on / off / unauthorized ...), from the transport.
    private(set) var bluetoothState: BluetoothRadioState = .unknown
    /// Internet path available (this phone can act as a gateway).
    private(set) var internetAvailable = false
    /// Battery 0...100, -1 unknown.
    private(set) var batteryPercent = -1
    private(set) var charging = false
    /// The app is on screen (scene phase is not `.background`).
    private(set) var isForeground = true

    /// My requests, newest first.
    var myNeeds: [MyNeed] { snapshot.myNeeds }
    var status: MeshStatus { snapshot.status }
    /// This phone's 16-hex-character node id.
    var nodeIdHex: String { repository.nodeIdHex }
    /// 8-byte node id, for building a `Need` (`originNode`).
    var nodeId: [UInt8] { repository.nodeId }

    // ---- permissions
    /// Permission states (`bluetooth`, `notifications`, `location`, `microphone`, `speechRecognition`, `radio`).
    let permissions: Permissions

    // ---- voice in
    /// What the voice panel shows: idle / listening(partial, level, note) / problem(text, ...).
    var speechState: VoiceState { speech.state }
    /// Live transcript: the partial text while listening, then the final text. Empty after `clearTranscript()`.
    private(set) var transcript = ""
    /// True once `transcript` is the final result of the last utterance.
    private(set) var transcriptIsFinal = false
    /// Called with the final transcript (also stored in `transcript`). Set by the New request screen.
    @ObservationIgnored var onTranscript: ((String) -> Void)?

    // ---- voice out
    var isSpeaking: Bool { speaker.isSpeaking }

    // ---- location
    /// Best recent position fix, nil until one arrives.
    var locationFix: Fix? { location.fix }
    /// What the confirm card shows about location (`.noPermission/.off/.acquiring/.ready`).
    var locationUi: LocUi { location.ui }
    var locationLabel: String { LocationState.label(location.ui, fix: location.fix) }

    /// Replace to localise or reword the local "control room answered" notification (default is English).
    @ObservationIgnored var ackNotificationText: AckNotifier.TextProvider = { AckNotifier.defaultText(need: $0, status: $1) }

    // ================================================================== owned objects

    private let repository: MeshRepository
    private let engine: MeshEngine
    private let transport: CoreBluetoothTransport
    private let speech: SpeechInput
    private let speaker: Speaker
    private let location: LocationProvider
    private let network = NetworkMonitor()
    private let battery = BatteryMonitor()
    private let skipFixWait = Broadcaster<Bool>(false)
    /// Ready mode (pack, plan, household, banner): see `ReadyCenter`.
    let ready: ReadyCenter
    /// The control room's announcements: see `AnnouncementCenter`.
    let announcements: AnnouncementCenter
    @ObservationIgnored private let readyDelegate = ReadyNotificationDelegate()
    /// Demo builds (every Debug build, or `RELAY_DEMO_SHELTERS = YES`) may show a demo shelter list, with a "Demo" badge; Release builds never do.
    let demoBuild: Bool
    private static var isDebug: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    @ObservationIgnored private var started = false
    @ObservationIgnored private var engineStarted = false
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []
    /// Last ack status/text seen per request, to notice new answers. nil until the first snapshot.
    @ObservationIgnored private var lastAck: [String: (status: Int, text: String)]?
    @ObservationIgnored private var transportPausedForBackground = false

    // ================================================================== lifecycle

    init() {
        let store = UserDefaultsKeyValueStore()
        let buildConfig = BuildConfig(infoDictionary: AppModel.cleanedInfoDictionary())
        let settings = AppSettings(store: store, buildConfig: buildConfig, onEmulator: false)
        let repository = MeshRepository(
            dataDirectory: AppModel.dataDirectory(), settings: settings,
            logSink: { relayLogger.info("\($0, privacy: .public)") })
        let readyCenter = ReadyCenter(directory: AppModel.dataDirectory().appendingPathComponent("ready", isDirectory: true))
        let announcementCenter = AnnouncementCenter(directory: AppModel.dataDirectory().appendingPathComponent("ready", isDirectory: true))
        let readyStore = readyCenter.store
        let transport = CoreBluetoothTransport()
        let log: @Sendable (String) -> Void = { [weak repository] in repository?.log($0) }
        // Mesh-assisted location (SPEC 5.5): the engine reads this phone's latest fix from its own queue, and asks the
        // main-actor location provider for a short listening window when it wants a hint and has no fix.
        let latestFix = LatestFix()
        let location = LocationProvider(latestFix: latestFix)
        let engine = MeshEngine(
            repository: repository, transport: transport, syncClient: SyncClient(),
            hintFix: { latestFix.relayFix() },
            requestFix: { Task { @MainActor in _ = location.start() } },
            district: { readyStore.load().district })

        self.repository = repository
        self.engine = engine
        self.transport = transport
        self.location = location
        self.speech = SpeechInput(log: log)
        self.speaker = Speaker()
        self.snapshot = repository.snapshot
        self.settings = settings.current
        self.villagerStatus = VillagerStatus.from(repository.snapshot.status, now: currentTimeMs())
        self.permissions = Permissions(location: location)
        self.demoBuild = buildConfig.demoShelters || AppModel.isDebug
        self.ready = readyCenter
        self.announcements = announcementCenter
        // The Bluetooth prompt needs the mesh started; Permissions calls back into the model for that.
        permissions.startBluetooth = { [weak self] in self?.startMesh() }
        speaker.speed = settings.current.voiceSpeed
        ready.context = { [weak self] in
            guard let self else { return (RefreshInput(connected: false, lat: nil, lon: nil, lang: "en", household: Household()), "", "") }
            let s = self.settings
            let fix = self.locationFix
            return (RefreshInput(connected: self.internetAvailable, lat: fix?.lat, lon: fix?.lon, lang: s.effectiveLanguage.tag, household: s.household),
                    s.backendUrl, s.deviceKey)
        }
        let packetStore = repository.packetStore
        announcementCenter.heldProvider = { AnnouncementBoard.heldFrom(packetStore) }
        announcementCenter.context = { [weak self] in
            guard let self else { return (nil, .en, "", "", false) }
            return (self.ready.snapshot.district, self.settings.effectiveLanguage, self.settings.backendUrl, self.settings.deviceKey, self.internetAvailable)
        }
        announcementCenter.reloadHeld()
        readyCenter.afterRefresh = { [weak announcementCenter] trigger in
            await announcementCenter?.fetch(trigger)
            await announcementCenter?.notifyNew()
        }
        engine.onAnnouncement = { [weak announcementCenter] _ in
            Task { @MainActor in
                announcementCenter?.reloadHeld()
                await announcementCenter?.notifyNew()
            }
        }
        readyDelegate.onOpenReady = { [weak self] in Task { @MainActor in self?.ready.openRequested = true } }
        UNUserNotificationCenter.current().delegate = readyDelegate
    }

    /// Starts everything. Idempotent; call it as early as possible (`RelayApp.init`) so a background relaunch by
    /// CoreBluetooth state restoration brings the mesh back without any UI.
    ///
    /// The mesh itself starts here only if Bluetooth access was already decided (granted before, or denied). If it was
    /// never asked, the prompt is deferred until Home calls `ensureBluetooth()`, as on Android.
    func start() {
        if started { return }
        started = true

        // Radio state -> engine (stops/starts the transport) and UI.
        transport.setStateObserver { [engine = self.engine, weak self] state in
            engine.setBluetoothOn(state.isOn)
            Task { @MainActor in
                self?.bluetoothState = state
                self?.permissions.radio = state
            }
        }
        network.start { [engine = self.engine, weak self] ok in
            engine.setInternetAvailable(ok)
            Task { @MainActor in
                let regained = ok && self?.internetAvailable == false
                self?.internetAvailable = ok
                if regained { await self?.ready.refresh(.networkRegained) }
            }
        }
        battery.start { [engine = self.engine, weak self] percent, charging in
            engine.setBattery(percent: percent, charging: charging)
            self?.batteryPercent = percent
            self?.charging = charging
        }
        engine.setForeground(isForeground)

        speech.onText = { [weak self] text in
            guard let self else { return }
            self.transcript = text
            self.transcriptIsFinal = true
            self.onTranscript?(text)
        }

        tasks.append(Task { [weak self, repository = self.repository] in
            for await s in repository.snapshots() { self?.apply(s) }
        })
        tasks.append(Task { [weak self, repository = self.repository] in
            for await s in repository.settings.updates() { self?.applySettings(s) }
        })
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                self?.refreshVillagerStatus()
            }
        })
        tasks.append(Task { [weak self] in await self?.permissions.refresh() })
        refreshLanguages()
        tasks.append(Task { [weak self] in
            await self?.ready.refresh(.appOpen)
            while !Task.isCancelled { // every 3 h while the app runs (the policy also enforces it)
                try? await Task.sleep(nanoseconds: UInt64(ReadyPolicy.periodicMs) * 1_000_000)
                await self?.ready.refresh(.periodic)
            }
        })

        if CBManager.authorization != .notDetermined { startMesh() }
    }

    /// Starts the engine (and with it the CoreBluetooth transport, which shows the Bluetooth prompt the first time).
    private func startMesh() {
        if engineStarted { return }
        engineStarted = true
        engine.start()
    }

    // ================================================================== permissions

    /// Home's first appearance: Bluetooth (starting the mesh) then notifications. Returns whether Bluetooth is allowed.
    @discardableResult
    func ensureBluetooth() async -> Bool { await permissions.ensureBluetooth() }

    /// At the first Send. Returns whether location is allowed.
    @discardableResult
    func ensureLocation() async -> Bool { await permissions.ensureLocation() }

    /// At the first mic tap. Returns whether voice input can run.
    @discardableResult
    func ensureMicrophone() async -> Bool { await permissions.ensureMicrophone() }

    func openAppSettings() { permissions.openAppSettings() }

    // ================================================================== languages

    /// Which languages the backend offers (`GET /v1/languages`, no key); best effort. The cached copy, or the build default
    /// while there is none, is used offline.
    func refreshLanguages() {
        let backend = settings.backendUrl
        Task.detached(priority: .utility) { [weak self] in
            guard let body = LanguageClient().fetch(backend) else { return }
            await MainActor.run { self?.updateSettings { var s = $0; s.serverLanguagesJson = body; return s } }
        }
    }

    // ================================================================== scene phase

    /// Forward SwiftUI's `scenePhase`. `.active`/`.inactive` count as foreground (continuous scanning, no flapping
    /// while Control Center is pulled down); `.background` does not. With "Carry messages in the background" off, the
    /// radio is stopped in the background and restarted on return (Android `CarryPolicy`).
    func setScenePhase(_ phase: ScenePhase) {
        let foreground = phase != .background
        isForeground = foreground
        engine.setForeground(foreground)
        if foreground {
            if transportPausedForBackground {
                transportPausedForBackground = false
                engine.ensureTransport()
            }
            location.refreshAuthorization()
            Task { await permissions.refresh() }
            refreshLanguages()
            Task { await ready.refresh(.appOpen) }
        } else {
            speech.cancel()
            speaker.stop()
            if !settings.carryInBackground && engineStarted {
                transportPausedForBackground = true
                transport.stop()
            }
        }
    }

    // ================================================================== requests

    /// Records [need] under "my requests" and pushes it into the mesh (now, or when the engine starts). Resets the
    /// kept location fix so the next request does not reuse it.
    func submit(_ need: Need) throws {
        try repository.submitNeed(need)
        location.reset()
    }

    /// The user stopped sharing request [msgIdHex]. Returns false for an unknown id.
    @discardableResult
    func cancel(_ msgIdHex: String) -> Bool { repository.cancel(msgIdHex) }

    /// Removes request [msgIdHex] from the list; a still-open one is cancelled first so it stops being relayed.
    func deleteMyNeed(_ msgIdHex: String) { repository.deleteMyNeed(msgIdHex) }

    /// Ask the mesh to exchange inventories again right now (and kick the gateway sync).
    func resyncNow() { engine.resyncNow() }

    // ================================================================== settings

    /// Applies [transform] to the settings, persists and publishes them.
    func updateSettings(_ transform: (RelaySettings) -> RelaySettings) { repository.settings.update(transform) }

    /// Diagnostics log lines (newest last, at most 100) and a stream of changes, for the Developer screen.
    var logLines: [String] { repository.logLines }
    func logUpdates() -> AsyncStream<[String]> { repository.logUpdates() }

    // ================================================================== voice in

    /// True when this phone can recognise [lang]: on the device, or (Odia) with the on-device model when it is installed.
    func isVoiceInputAvailable(for lang: Lang) -> Bool { SpeechInput.isOnDeviceAvailable(lang) || GemmaRunner.canRunHere() }

    /// Starts listening in the current language: asks for microphone + speech permission first (first mic tap), then
    /// records. Progress is in `speechState`/`transcript`; the final text arrives via `onTranscript`.
    /// `quietSec` ends the utterance after that much quiet once speech was heard (nil: the long default for a story).
    func startListening(quietSec: Double? = nil) async {
        transcript = ""
        transcriptIsFinal = false
        speaker.stop()
        let lang = settings.effectiveLanguage
        guard await permissions.ensureMicrophone() else {
            speech.fail(text: SpeechLogic.onError(SpeechLogic.errorInsufficientPermissions, lang: lang, offlineAttempt: true,
                                                  online: false, alreadyRetried: true).message ?? "",
                        offerAppSettings: true)
            return
        }
        if let quietSec { speech.start(lang: lang, quietSec: quietSec) } else { speech.start(lang: lang) }
    }

    /// Stop recording and deliver what was heard.
    func stopListening() { speech.stop() }

    /// Abort without a result.
    func cancelListening() { speech.cancel() }

    /// Dismiss a voice problem message.
    func dismissSpeechProblem() { speech.dismiss() }

    func clearTranscript() {
        transcript = ""
        transcriptIsFinal = false
    }

    // ================================================================== control-room declarations

    /// Disasters the control room declared that are still inside the 24 h window (SPEC 5.6); empty = triage asks nothing
    /// disaster-specific. The last known position lets an area-limited declaration be ruled out.
    func activeDisasters() -> DisasterGate.Active {
        let here = location.fix.map { (latE5: Geo.toE5($0.lat), lonE5: Geo.toE5($0.lon)) }
        return repository.disasters.active(here: here)
    }

    /// Hours since each live declaration was issued, by type key (for the model's prompt).
    func disasterAgesHours() -> [String: Int64] { repository.disasters.agesHours() }

    /// Declarations on this phone, for the developer lab.
    func declaredDisasters() -> [DisasterContext] { repository.disasters.all() }

    /// Developer/demo: speak as the control room. Goes through the mesh like a gateway's broadcast; with the mesh stopped it
    /// is set locally instead. Returns a one-line note for the lab.
    func declareDisaster(_ type: DisasterType) -> String {
        if engine.isRunning, engine.declareDisaster(type) { return "Broadcast \(type.key) sent from this phone" }
        repository.disasters.put(DisasterContext(type: type, issuedAtSec: Int64(Date().timeIntervalSince1970)))
        return "Mesh not running: \(type.key) set locally"
    }

    func clearDisastersHere() { repository.disasters.clear() }

    // ================================================================== voice out

    /// Speaks [text] aloud in [lang] (default: the app language) at the Settings voice speed, replacing any speech in
    /// progress. Returns false when this phone has no voice for the language.
    @discardableResult
    func speak(_ text: String, lang: Lang? = nil) -> Bool {
        speaker.speed = settings.voiceSpeed
        return speaker.speak(text, lang: lang ?? settings.effectiveLanguage)
    }

    /// Speaks `lines` in a row in `lang` and calls `onDone` after the last one. False when there is no voice (then `onDone`
    /// is never called and the caller carries on without speech).
    @discardableResult
    func speakAll(_ lines: [String], lang: Lang? = nil, onDone: (@MainActor () -> Void)? = nil) -> Bool {
        speaker.speed = settings.voiceSpeed
        return speaker.speakAll(lines, lang: lang ?? settings.language, onDone: onDone)
    }

    func stopSpeaking() { speaker.stop() }

    /// True when a voice exists for [lang] ("Hear it in Hindi" can work).
    func isVoiceOutputAvailable(for lang: Lang) -> Bool { speaker.isVoiceAvailable(lang) }

    // ================================================================== location

    /// Starts a location window (30 s, stops early once the fix is within 10 m). Asks for permission if never asked.
    /// Returns false when there is no permission or Location Services are off.
    @discardableResult
    func requestLocationFix() async -> Bool {
        guard await permissions.ensureLocation() else { return false }
        return location.start()
    }

    /// Waits for the first fix, at most 10 s, or until `skipFixWaiting()` ("Send now"). nil = send without coordinates.
    func awaitLocationFix(timeoutMs: Int64 = FixWait.defaultTimeoutMs) async -> Fix? {
        skipFixWait.send(false)
        return await FixWait.wait(fixes: location.fixUpdates(), skip: skipFixWait.updates(), timeoutMs: timeoutMs)
    }

    /// "Send now": ends `awaitLocationFix` immediately.
    func skipFixWaiting() { skipFixWait.send(true) }

    /// Forget the kept fix.
    func resetLocationFix() { location.reset() }

    // ================================================================== internals

    private func apply(_ s: MeshSnapshot) {
        snapshot = s
        refreshVillagerStatus()
        noticeAcks(s.myNeeds)
    }

    private func applySettings(_ s: RelaySettings) {
        let languageChanged = s.effectiveLanguage != settings.effectiveLanguage
        settings = s
        speaker.speed = s.voiceSpeed
        if languageChanged { speech.cancel() }
    }

    private func refreshVillagerStatus() {
        let next = VillagerStatus.from(snapshot.status, now: currentTimeMs())
        if next != villagerStatus { villagerStatus = next }
    }

    /// New control-room answer for one of my requests: local notification, and read aloud if that setting is on.
    private func noticeAcks(_ needs: [MyNeed]) {
        var current: [String: (status: Int, text: String)] = [:]
        for n in needs { current[n.msgIdHex] = (n.ackStatus, n.ackText) }
        defer { lastAck = current }
        guard let previous = lastAck else { return } // the first snapshot only seeds the baseline
        for n in needs where !n.cancelled && n.ackStatus >= 0 {
            let isNew: Bool
            if let before = previous[n.msgIdHex] {
                // A later stage, or the same stage with new text from the control room.
                isNew = n.ackStatus > before.status || (n.ackStatus == before.status && !n.ackText.isEmpty && n.ackText != before.text)
            } else {
                isNew = true
            }
            guard isNew, let ackStatus = AckStatus.fromCode(n.ackStatus) else { continue }
            if !isForeground {
                AckNotifier.post(need: n, status: ackStatus, text: ackNotificationText(n, ackStatus))
            }
            if settings.readAloud, isForeground, !n.ackText.isEmpty {
                speaker.speed = settings.voiceSpeed
                speaker.speak(n.ackText, lang: n.lang)
            }
        }
    }

    // ---------------------------------------------------------------- static helpers

    /// `Application Support/Relay`, excluded from iCloud/iTunes backups: a restored copy of the packet store and the
    /// node id on a second phone would put two phones on the mesh under one identity.
    private static func dataDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        var dir = base.appendingPathComponent("Relay", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
        return dir
    }

    /// Info.plist with unexpanded build-setting placeholders (`$(RELAY_BACKEND_URL)` when no xcconfig defines it)
    /// blanked, so `BuildConfig` falls back to its defaults instead of using the literal text.
    private static func cleanedInfoDictionary() -> [String: Any]? {
        guard var info = Bundle.main.infoDictionary else { return nil }
        for key in ["RelayBackendURL", "RelayDeviceKey", "RelayLockConnection", "RelayHelpLocateDefault", "RelayEnabledLangs", "RelayDemoShelters"] {
            if let v = info[key] as? String, v.contains("$(") { info[key] = "" }
        }
        return info
    }
}
