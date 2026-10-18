import Foundation
import RelayCore

/// Light / Dark, or follow the system (default). Raw values are the Android names, so stored values carry over.
public enum ThemeMode: String, CaseIterable, Sendable, Hashable { case light = "LIGHT", dark = "DARK", auto = "AUTO" }

/// Text-to-speech rate for "Hear it" buttons and read-aloud.
public enum VoiceSpeed: String, CaseIterable, Sendable, Hashable { case slow = "SLOW", normal = "NORMAL" }

/// Whether this phone uploads for the mesh when it has internet. `never` is the old "Pretend offline" switch.
/// `automatic` and `always` behave the same today (a phone with internet is a gateway); `always` is reserved for
/// "stay a gateway even when the network looks constrained".
public enum GatewayMode: String, CaseIterable, Sendable, Hashable { case automatic = "AUTOMATIC", never = "NEVER", always = "ALWAYS" }

public struct Settings: Equatable, Sendable {
    public var backendUrl: String
    public var deviceKey: String
    /// Comma-separated node-id hex prefixes; empty = accept every peer.
    public var allowlist: String
    public var language: Lang
    /// Languages the user added beyond English and Hindi, in the order added (never contains en or hi).
    public var extraLanguages: [Lang]
    public var themeMode: ThemeMode
    /// Multiplies all text by 1.2 (the iOS UI also respects Dynamic Type).
    public var largerText: Bool
    /// Speak new messages from the control room aloud.
    public var readAloud: Bool
    public var voiceSpeed: VoiceSpeed
    /// The user has picked a language on the first-run screen (false until then).
    public var languageConfirmed: Bool
    /// Optional "Your area" text shown on Home and in request references; empty = omit.
    public var areaLabel: String
    public var gatewayMode: GatewayMode
    /// Keep the mesh running when the app is closed (best effort on iOS).
    public var carryInBackground: Bool
    /// Opt-in "Help locate neighbours" (mesh-assisted location, SPEC 5.5): when a neighbour's NEED has no position,
    /// this phone publishes its own position (about 100 m or better) to the mesh and the control room. Default on in
    /// demo builds, off in village builds (`BuildConfig.helpLocateDefault`).
    public var helpLocateNeighbours: Bool
    /// Last good `GET /v1/languages` body, cached so the picker is right offline; empty until one arrived.
    public var serverLanguagesJson: String
    /// The disabled language the "not available yet" line was already shown for (language tag), or empty.
    public var languageNoticeFor: String
    /// Languages this build can offer (`BuildConfig.enabledLanguages`). Not persisted: `AppSettings` fills it from the build.
    public var buildLanguages: [Lang]
    /// "My household" (contract section 3.2): stays on the phone; only its anonymous flags go to `/v1/ready/plan`.
    public var household: Household
    /// The household screen was saved at least once.
    public var householdSet: Bool

    public init(
        backendUrl: String = BackendDefaults.emulatorUrl, deviceKey: String = BuildConfig.fallbackDeviceKey,
        allowlist: String = "", language: Lang = .en, extraLanguages: [Lang] = [], themeMode: ThemeMode = .auto, largerText: Bool = false,
        readAloud: Bool = false, voiceSpeed: VoiceSpeed = .normal, languageConfirmed: Bool = false,
        areaLabel: String = "", gatewayMode: GatewayMode = .automatic, carryInBackground: Bool = true,
        helpLocateNeighbours: Bool = BuildConfig.standard.helpLocateDefault,
        serverLanguagesJson: String = "", languageNoticeFor: String = "", buildLanguages: [Lang] = LanguageCatalog.buildDefault,
        household: Household = Household(), householdSet: Bool = false
    ) {
        self.backendUrl = backendUrl; self.deviceKey = deviceKey; self.allowlist = allowlist; self.language = language
        self.extraLanguages = Settings.normalizedExtras(extraLanguages)
        self.themeMode = themeMode; self.largerText = largerText; self.readAloud = readAloud
        self.voiceSpeed = voiceSpeed; self.languageConfirmed = languageConfirmed; self.areaLabel = areaLabel
        self.gatewayMode = gatewayMode; self.carryInBackground = carryInBackground
        self.helpLocateNeighbours = helpLocateNeighbours
        self.serverLanguagesJson = serverLanguagesJson; self.languageNoticeFor = languageNoticeFor; self.buildLanguages = buildLanguages
        self.household = household; self.householdSet = householdSet
    }

    /// Languages the picker offers: enabled in this build AND in the cached server list (build default until one arrived).
    public var offeredLanguages: [Lang] {
        LanguageCatalog.offered(build: buildLanguages, server: LanguageCatalog.parseServer(serverLanguagesJson))
    }

    public var languageResolution: LanguageResolution { LanguageCatalog.resolve(stored: language, offered: offeredLanguages) }

    /// The language every string is shown in: `language`, or English when it is no longer offered.
    public var effectiveLanguage: Lang { languageResolution.effective }

    /// The old "Pretend offline" behaviour: never act as a gateway.
    public var pretendOffline: Bool { gatewayMode == .never }

    /// English and Hindi are always preferred.
    public static let alwaysPreferred: [Lang] = [.en, .hi]

    /// The languages speech input should expect: English, Hindi, then the user's added languages. The app language
    /// counts too, so installs from before this setting existed still include theirs. Only languages the picker offers
    /// are kept (speech is en-IN and hi-IN unless a reviewed language is switched on). Order is stable, no duplicates.
    public var preferredLanguages: [Lang] {
        let offered = offeredLanguages
        return (Settings.alwaysPreferred + Settings.normalizedExtras(extraLanguages + [language])).filter { offered.contains($0) }
    }

    /// Switches the app language; a language beyond English and Hindi is also remembered as a preferred one.
    public func withLanguage(_ lang: Lang) -> Settings {
        var s = self
        s.language = lang
        s.extraLanguages = Settings.normalizedExtras(extraLanguages + [lang])
        return s
    }

    /// Drops en, hi and repeats, keeping first-seen order.
    static func normalizedExtras(_ langs: [Lang]) -> [Lang] {
        var seen = Set(alwaysPreferred)
        return langs.filter { seen.insert($0).inserted }
    }
}

/// Persisted settings, observable through `current`, `updates()` and `addListener`.
///
/// Storage keys are the Android `SharedPreferences` keys (`backend`, `key`, `lang`, `theme`, ...). `onEmulator`
/// selects the emulator backend alias; leave it false on iOS (even in the Simulator, where the production default
/// or a user-set URL is what you want).
public final class AppSettings: @unchecked Sendable {
    public typealias Listener = @Sendable (Settings) -> Void

    private let store: any KeyValueStore
    private let lock = NSLock()
    private var listeners: [Int: Listener] = [:]
    private var nextToken = 0
    private let flow: Broadcaster<Settings>

    private let buildConfig: BuildConfig

    /// Backend used when the user has not set one: the emulator alias on an emulator, otherwise the build-time default.
    public let defaultBackend: String

    /// Release builds pin backend and key to the build-time values and ignore anything stored, so a stale or
    /// mistyped value from an older install cannot break sync. Debug builds keep both editable.
    public let connectionLocked: Bool

    public init(store: any KeyValueStore, buildConfig: BuildConfig = .standard, onEmulator: Bool = false) {
        let def = BackendDefaults.defaultFor(emulator: onEmulator, buildDefault: buildConfig.defaultBackend)
        self.store = store
        self.buildConfig = buildConfig
        self.defaultBackend = def
        self.connectionLocked = buildConfig.lockConnection
        self.flow = Broadcaster(AppSettings.read(store, buildConfig, onEmulator, defaultBackend: def, locked: buildConfig.lockConnection))
    }

    public var current: Settings { flow.value }

    /// Current settings now, then every change.
    public func updates() -> AsyncStream<Settings> { flow.updates() }

    private static func read(_ prefs: any KeyValueStore, _ cfg: BuildConfig, _ onEmulator: Bool, defaultBackend: String, locked: Bool) -> Settings {
        func enumOr<E: RawRepresentable & CaseIterable>(_ name: String?, _ def: E) -> E where E.RawValue == String {
            E.allCases.first { $0.rawValue == name } ?? def
        }
        let gateway: GatewayMode
        if let mode = prefs.stringValue(forKey: "gateway_mode") {
            gateway = enumOr(mode, .automatic)
        } else {
            // Older installs stored the "Pretend offline" switch as a boolean.
            gateway = (prefs.boolValue(forKey: "offline") ?? false) ? .never : .automatic
        }
        return Settings(
            backendUrl: locked ? defaultBackend : BackendDefaults.resolve(stored: prefs.stringValue(forKey: "backend"), emulator: onEmulator, buildDefault: cfg.defaultBackend),
            deviceKey: locked ? cfg.defaultKey : (prefs.stringValue(forKey: "key") ?? cfg.defaultKey),
            allowlist: prefs.stringValue(forKey: "allowlist") ?? "",
            language: Lang.fromTag(prefs.stringValue(forKey: "lang") ?? "en") ?? .en,
            extraLanguages: (prefs.stringValue(forKey: "extra_langs") ?? "").split(separator: ",").compactMap { Lang.fromTag(String($0)) },
            themeMode: enumOr(prefs.stringValue(forKey: "theme"), ThemeMode.auto),
            largerText: prefs.boolValue(forKey: "larger_text") ?? false,
            readAloud: prefs.boolValue(forKey: "read_aloud") ?? false,
            voiceSpeed: enumOr(prefs.stringValue(forKey: "voice_speed"), VoiceSpeed.normal),
            // Existing installs have no flag, so they see the language screen once.
            languageConfirmed: prefs.boolValue(forKey: "lang_confirmed") ?? false,
            areaLabel: prefs.stringValue(forKey: "area") ?? "",
            gatewayMode: gateway,
            carryInBackground: prefs.boolValue(forKey: "carry_bg") ?? true,
            helpLocateNeighbours: prefs.boolValue(forKey: "help_locate") ?? cfg.helpLocateDefault,
            serverLanguagesJson: prefs.stringValue(forKey: "server_langs") ?? "",
            languageNoticeFor: prefs.stringValue(forKey: "lang_notice_for") ?? "",
            buildLanguages: cfg.enabledLanguages,
            household: Household.fromJson(prefs.stringValue(forKey: "household")),
            householdSet: prefs.boolValue(forKey: "household_set") ?? false
        )
    }

    /// Applies [f], persists the result and notifies listeners. No-op when nothing changed.
    public func update(_ f: (Settings) -> Settings) {
        lock.lock()
        var next = f(flow.value)
        if connectionLocked {
            next.backendUrl = defaultBackend
            next.deviceKey = buildConfig.defaultKey
        }
        if next == flow.value { lock.unlock(); return }
        // The default is stored as "unset" so it keeps following the build-time value.
        if next.backendUrl == defaultBackend { store.removeValue(forKey: "backend") } else { store.setString(next.backendUrl, forKey: "backend") }
        if next.deviceKey == buildConfig.defaultKey { store.removeValue(forKey: "key") } else { store.setString(next.deviceKey, forKey: "key") }
        store.setString(next.allowlist, forKey: "allowlist")
        store.setBool(next.pretendOffline, forKey: "offline")
        store.setString(next.language.tag, forKey: "lang")
        store.setString(next.extraLanguages.map(\.tag).joined(separator: ","), forKey: "extra_langs")
        store.setString(next.themeMode.rawValue, forKey: "theme")
        store.setBool(next.largerText, forKey: "larger_text")
        store.setBool(next.readAloud, forKey: "read_aloud")
        store.setString(next.voiceSpeed.rawValue, forKey: "voice_speed")
        store.setBool(next.languageConfirmed, forKey: "lang_confirmed")
        store.setString(next.areaLabel, forKey: "area")
        store.setString(next.gatewayMode.rawValue, forKey: "gateway_mode")
        store.setBool(next.carryInBackground, forKey: "carry_bg")
        store.setBool(next.helpLocateNeighbours, forKey: "help_locate")
        store.setString(next.serverLanguagesJson, forKey: "server_langs")
        store.setString(next.languageNoticeFor, forKey: "lang_notice_for")
        store.setString(next.household.normalized().toJson(), forKey: "household")
        store.setBool(next.householdSet, forKey: "household_set")
        flow.send(next)
        let targets = Array(listeners.values)
        lock.unlock()
        for l in targets { l(next) }
    }

    /// The last peers with a successful exchange (see `RecentPeers`). Stored outside `Settings`: no listener fires.
    public var recentPeers: [RecentPeers.Peer] {
        get { RecentPeers.parse(store.stringValue(forKey: "recent_peers")) }
        set { store.setString(RecentPeers.serialize(newValue), forKey: "recent_peers") }
    }

    /// Registers [listener]; returns a token for `removeListener`. Called on whichever thread calls `update`.
    @discardableResult
    public func addListener(_ listener: @escaping Listener) -> Int {
        lock.lock(); defer { lock.unlock() }
        let t = nextToken
        nextToken += 1
        listeners[t] = listener
        return t
    }

    public func removeListener(_ token: Int) {
        lock.lock(); defer { lock.unlock() }
        listeners.removeValue(forKey: token)
    }
}
