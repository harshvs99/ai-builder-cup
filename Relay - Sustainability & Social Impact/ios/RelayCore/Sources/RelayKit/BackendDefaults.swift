import Foundation

/// Pure resolution of the gateway backend URL: emulator detection, build-time default, and the migration of the
/// old hard-coded emulator default on real phones. No platform types, so it runs in plain unit tests.
public enum BackendDefaults {
    /// The deployed backend (Cloud Run behind this domain). Matches `relay.backendUrl` in `android/gradle.properties`.
    public static let productionUrl = "https://relay.harshvs.com"

    /// The host machine's loopback as seen from the Android emulator (kept for file/behaviour parity with Android).
    public static let emulatorUrl = "http://10.0.2.2:8080"

    /// Robust emulator check over the `android.os.Build` strings (any of them may be nil or empty). Not used on iOS
    /// unless the app wants to treat the Simulator specially; it is ported so the Android tests carry over.
    public static func isEmulator(_ fingerprint: String?, _ hardware: String?, _ product: String?) -> Bool {
        let fp = (fingerprint ?? "").lowercased()
        let hw = (hardware ?? "").lowercased()
        let pr = (product ?? "").lowercased()
        return fp.hasPrefix("generic") || fp.contains("emulator") || fp.contains("sdk_gphone")
            || hw == "ranchu" || hw == "goldfish"
            || pr.contains("sdk")
    }

    /// The default backend for this device: the emulator host alias on an emulator, else the build-time URL.
    public static func defaultFor(emulator: Bool, buildDefault: String) -> String {
        if emulator { return emulatorUrl }
        let t = buildDefault.trimmed
        return t.isEmpty ? emulatorUrl : t
    }

    /// The backend to use given what is stored. Nothing stored (or blank) means "use the default". On a real
    /// phone the legacy emulator default `emulatorUrl` is treated as unset, so installs that saved it before
    /// the build-time default existed pick up the new one. Anything else the user chose is kept verbatim.
    public static func resolve(stored: String?, emulator: Bool, buildDefault: String) -> String {
        let def = defaultFor(emulator: emulator, buildDefault: buildDefault)
        let s = (stored ?? "").trimmed
        if s.isEmpty { return def }
        if !emulator && s.trimmingTrailing("/") == emulatorUrl { return def }
        return s
    }

    /// `host[:port]` of [url] for display, or the trimmed input when it does not parse.
    public static func hostOf(_ url: String) -> String {
        let t = url.trimmed
        let rest = t.substringAfter("://", missing: t)
        let authority = rest.substringBefore("/").substringBefore("?").substringAfterLast("@")
        return authority.isEmpty ? t : authority
    }
}

/// Build-time connection defaults. Android bakes these in with `buildConfigField` from Gradle properties
/// (`relay.backendUrl`, `relay.deviceKey` from `local.properties` / environment). On iOS the same values come from
/// the app's Info.plist (`RelayBackendURL`, `RelayDeviceKey`, `RelayLockConnection`, `RelayHelpLocateDefault`), which an untracked `.xcconfig`
/// fills in so the shared gateway key is never committed.
public struct BuildConfig: Equatable, Sendable {
    /// Local-development gateway key, used when none was configured.
    public static let fallbackDeviceKey = "dev-relay-key"

    /// Backend used when the user has not set one (Android `DEFAULT_BACKEND`).
    public var defaultBackend: String
    /// Shared gateway key sent as `X-Relay-Key` (Android `DEFAULT_KEY`).
    public var defaultKey: String
    /// Release builds pin backend and key to these values and ignore anything stored (Android `LOCK_CONNECTION`).
    public var lockConnection: Bool
    /// Default of the opt-in "Help locate neighbours" setting (Android `HELP_LOCATE_DEFAULT`, Gradle property
    /// `relay.helpLocateDefault`): on in demo builds, off in village builds.
    public var helpLocateDefault: Bool

    public init(defaultBackend: String = BackendDefaults.productionUrl, defaultKey: String = BuildConfig.fallbackDeviceKey,
                lockConnection: Bool = false, helpLocateDefault: Bool = true) {
        self.defaultBackend = defaultBackend
        self.defaultKey = defaultKey
        self.lockConnection = lockConnection
        self.helpLocateDefault = helpLocateDefault
    }

    /// Debug-style defaults: production backend, dev key, connection editable.
    public static let standard = BuildConfig()

    /// Reads the three Info.plist keys; missing or blank values fall back to `standard`.
    public init(infoDictionary info: [String: Any]?) {
        let backend = (info?["RelayBackendURL"] as? String)?.trimmed ?? ""
        let key = (info?["RelayDeviceKey"] as? String)?.trimmed ?? ""
        let lock = (info?["RelayLockConnection"] as? Bool) ?? ((info?["RelayLockConnection"] as? String).map { $0.lowercased() == "yes" || $0.lowercased() == "true" } ?? false)
        // Anything but an explicit "false"/"no"/"0" (or a boolean false) keeps the demo default of on.
        let helpRaw = info?["RelayHelpLocateDefault"]
        let help: Bool
        if let b = helpRaw as? Bool { help = b }
        else if let t = (helpRaw as? String)?.trimmed.lowercased() { help = !(t == "false" || t == "no" || t == "0") }
        else { help = true }
        self.init(
            defaultBackend: backend.isEmpty ? BackendDefaults.productionUrl : backend,
            defaultKey: key.isEmpty ? BuildConfig.fallbackDeviceKey : key,
            lockConnection: lock,
            helpLocateDefault: help
        )
    }
}
