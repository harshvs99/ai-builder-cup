import Foundation
import RelayCore

/// Picks the text for [lang]. Cebuano has no UI text yet and shows English.
public func tr(_ lang: Lang, _ en: String, _ hi: String, _ or: String, _ tl: String, _ war: String) -> String {
    switch lang {
    case .hi: return hi
    case .or: return or
    case .tl: return tl
    case .war: return war
    case .en, .ceb: return en
    }
}

/// Text for a string added after the language freeze: English and Hindi only. Hindi is a draft for native review; Odia,
/// Filipino and Waray (hidden, pending review) show the English text rather than an invented translation.
public func trEnHi(_ lang: Lang, _ en: String, _ hi: String) -> String { lang == .hi ? hi : en }

/// Plural rule of the app languages, for "1 person" / "N people". Pure.
public enum Plural {
    /// True when [n] takes the singular form. Hindi treats 0 as singular too; the rest are n == 1.
    public static func isOne(_ lang: Lang, _ n: Int) -> Bool {
        switch lang {
        case .hi: return n == 0 || n == 1
        default: return n == 1
        }
    }

    public static func pick(_ lang: Lang, _ n: Int, one: String, other: String) -> String { isOne(lang, n) ? one : other }
}

/// How long ago something happened, as a language-neutral bucket. Pure; `CommonStrings.ago` words it.
public enum RelativeTime {
    public enum Bucket: Equatable, Hashable, Sendable {
        case justNow
        case minutes(Int)
        case hours(Int)
        case yesterday
        case days(Int)
    }

    /// Under a minute is `justNow`, under an hour `minutes`, the same calendar day `hours`, the previous calendar day
    /// `yesterday`, then `days`. A [thenMs] in the future counts as `justNow` (clock skew). Calendar days are counted in
    /// [zone], so tests pin it instead of relying on the device's time zone.
    public static func bucket(_ thenMs: Int64, _ nowMs: Int64, zone: TimeZone = .autoupdatingCurrent) -> Bucket {
        let diff = nowMs - thenMs
        if diff < 60_000 { return .justNow }
        if diff < 3_600_000 { return .minutes(Int(diff / 60_000)) }
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = zone
        let thenDay = cal.startOfDay(for: Date(timeIntervalSince1970: Double(thenMs) / 1000))
        let nowDay = cal.startOfDay(for: Date(timeIntervalSince1970: Double(nowMs) / 1000))
        let days = cal.dateComponents([.day], from: thenDay, to: nowDay).day ?? 0
        if days <= 0 { return .hours(Int(diff / 3_600_000)) }
        if days == 1 { return .yesterday }
        return .days(days)
    }
}

/// Short clock times ("2:02 PM") in an app language's locale. `DateFormatter` is costly to build, so one is kept per
/// (language, zone) and used under a lock.
enum ClockFormat {
    private nonisolated(unsafe) static var cache: [String: DateFormatter] = [:]
    private static let lock = NSLock()

    /// The short time of [ms] for [lang] in [zone].
    static func short(_ ms: Int64, lang: Lang, zone: TimeZone) -> String {
        lock.lock(); defer { lock.unlock() }
        let key = "\(lang.tag)|\(zone.identifier)"
        let f: DateFormatter
        if let cached = cache[key] {
            f = cached
        } else {
            f = DateFormatter()
            f.locale = Locale(identifier: lang.tag)
            f.timeZone = zone
            f.dateStyle = .none
            f.timeStyle = .short
            cache[key] = f
        }
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }
}
