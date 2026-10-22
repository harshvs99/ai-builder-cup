import Foundation
import RelayCore

/// One entry of `GET /v1/languages` (no auth). Unknown fields are ignored.
public struct ServerLanguage: Equatable, Sendable {
    public let code: String
    public let englishName: String
    public let nativeName: String
    public let enabled: Bool
    public init(code: String, englishName: String, nativeName: String, enabled: Bool) {
        self.code = code; self.englishName = englishName; self.nativeName = nativeName; self.enabled = enabled
    }
}

/// What the app shows for the stored language after the picker rules ran: `effective` is used for every string,
/// `unavailable` is the stored one that is off (nil when it is on).
public struct LanguageResolution: Equatable, Sendable {
    public let effective: Lang
    public let unavailable: Lang?
    public init(effective: Lang, unavailable: Lang?) { self.effective = effective; self.unavailable = unavailable }
}

/// Which languages the picker offers (contract v2 section 1; port of Kotlin `LanguageCatalog`). A language is offered
/// only when it is enabled in BOTH the app's build config and the latest `/v1/languages` response; with no response yet
/// the build default (English, Hindi) applies. English is always offered. Odia, Filipino and Waray keep their strings
/// but stay hidden until a community reviewer signs them off and the backend turns them on.
public enum LanguageCatalog {
    /// First-run default and the set used before any server response: English and Hindi.
    public static let buildDefault: [Lang] = [.en, .hi]

    /// Parses a build config value such as `"en,hi"`; unknown codes are dropped and English is always present.
    public static func parseBuildLanguages(_ csv: String?) -> [Lang] {
        let listed = (csv ?? "").split(separator: ",").compactMap { Lang.fromTag($0.trimmingCharacters(in: .whitespaces)) }
        let all = listed.isEmpty ? buildDefault : listed
        return Lang.allCases.filter { $0 == .en || all.contains($0) }
    }

    /// Parses the `/v1/languages` body; nil when it is not the expected JSON array (so a bad cache falls back to the build default).
    public static func parseServer(_ json: String?) -> [ServerLanguage]? {
        guard let json, !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = json.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [Any] else { return nil }
        return root.compactMap { item -> ServerLanguage? in
            guard let o = item as? [String: Any] else { return nil }
            let code = ((o["code"] as? String) ?? "").trimmingCharacters(in: .whitespaces).lowercased()
            if code.isEmpty { return nil }
            return ServerLanguage(
                code: code, englishName: (o["english_name"] as? String) ?? code, nativeName: (o["native_name"] as? String) ?? code,
                enabled: (o["enabled"] as? Bool) ?? false)
        }
    }

    /// The languages to offer, in wire order. `server` nil = no usable response yet.
    public static func offered(build: [Lang], server: [ServerLanguage]?) -> [Lang] {
        let inBuild = Lang.allCases.filter { $0 == .en || build.contains($0) }
        guard let server else { return inBuild.filter { buildDefault.contains($0) } }
        let on = Set(server.filter { $0.enabled }.compactMap { Lang.fromTag($0.code) })
        return inBuild.filter { $0 == .en || on.contains($0) }
    }

    /// Falls back to English when the stored language is no longer offered. The stored choice itself is kept, so it returns if re-enabled.
    public static func resolve(stored: Lang, offered: [Lang]) -> LanguageResolution {
        offered.contains(stored) ? LanguageResolution(effective: stored, unavailable: nil) : LanguageResolution(effective: .en, unavailable: stored)
    }

    /// True when the "X is not available yet; showing English." line should be shown now: once per unavailable language.
    public static func shouldAnnounce(_ resolution: LanguageResolution, alreadyAnnounced: Lang?) -> Bool {
        guard let gone = resolution.unavailable else { return false }
        return gone != alreadyAnnounced
    }
}
