import Foundation
import RelayCore

/// All UI text for one app language, chosen in-app (Settings language), independent of the phone's locale.
///
/// Each screen area owns one file with one struct, so agents do not collide:
/// `CommonStrings` (shared), `HomeStrings`, `AskStrings`, `RequestStrings`, `NetworkSettingsStrings`.
///
/// To add a string: open your area's file and add a computed `var` (or a `func` for text with parameters) that calls
/// `tr(lang, en, hi, or, tl, war)` with all five languages side by side, and mark non-English text
/// `// needs native review`. In SwiftUI the app puts a `Strings` into the environment from the Settings language;
/// views read `strings.home.title` and so on.
public struct Strings: Equatable, Sendable {
    public let lang: Lang
    public let common: CommonStrings
    public let home: HomeStrings
    public let ask: AskStrings
    public let request: RequestStrings
    public let network: NetworkSettingsStrings

    /// [timeZone] only affects clock times and "yesterday" wording; tests pin it, the app follows the device.
    public init(_ lang: Lang, timeZone: TimeZone = .autoupdatingCurrent) {
        self.lang = lang
        common = CommonStrings(lang, timeZone: timeZone)
        home = HomeStrings(lang)
        ask = AskStrings(lang)
        request = RequestStrings(lang)
        network = NetworkSettingsStrings(lang, timeZone: timeZone)
    }

    /// English, for environment defaults, previews and tests.
    public static let english = Strings(.en)
}

/// Strings for [lang] (a value type, so there is nothing to cache).
public func stringsFor(_ lang: Lang, timeZone: TimeZone = .autoupdatingCurrent) -> Strings { Strings(lang, timeZone: timeZone) }

/// The five app languages with their native and English names (names are not translated).
public enum AppLanguages {
    public struct Entry: Equatable, Hashable, Sendable {
        public let lang: Lang
        public let nativeName: String
        public let englishName: String
        public init(lang: Lang, nativeName: String, englishName: String) {
            self.lang = lang; self.nativeName = nativeName; self.englishName = englishName
        }
    }

    public static let all: [Entry] = [
        Entry(lang: .en, nativeName: "English", englishName: "English"),
        Entry(lang: .hi, nativeName: "हिन्दी", englishName: "Hindi"),
        Entry(lang: .or, nativeName: "ଓଡ଼ିଆ", englishName: "Odia"),
        Entry(lang: .tl, nativeName: "Tagalog", englishName: "Filipino"),
        Entry(lang: .war, nativeName: "Waray", englishName: "Waray-Waray"),
    ]

    /// The entry for [lang]; Cebuano (no UI text) and anything unknown give English.
    public static func of(_ lang: Lang) -> Entry { all.first { $0.lang == lang } ?? all[0] }
}
