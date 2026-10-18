import Foundation
import RelayCore
@testable import RelayKit

/// Shared helpers of the i18n and screen-logic tests: a pinned time zone and 2026-10 timestamps.
enum UI {
    static let utc = TimeZone(identifier: "UTC")!
    static let appLangs: [Lang] = [.en, .hi, .or, .tl, .war]

    /// Strings with the time zone pinned, so "yesterday" and clock times do not depend on the machine.
    static func strings(_ lang: Lang) -> Strings { Strings(lang, timeZone: utc) }

    /// Epoch ms of 2026-10-[day] [h]:[m]:[s] UTC.
    static func at(_ day: Int, _ h: Int, _ m: Int = 0, _ s: Int = 0) -> Int64 {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = utc
        let date = cal.date(from: DateComponents(year: 2026, month: 10, day: day, hour: h, minute: m, second: s))!
        return Int64(date.timeIntervalSince1970 * 1000)
    }

    /// ICU 72+ writes "2:02 PM" with a narrow no-break space; tests compare with a plain one.
    static func plain(_ s: String) -> String { s.replacingOccurrences(of: "\u{202F}", with: " ").replacingOccurrences(of: "\u{00A0}", with: " ") }

    static func need(
        _ id: String, at: Int64 = 1_700_000_000_000, ack: Int = -1, cancelled: Bool = false, item: String = "",
        urgency: Int = 2, people: Int = 6, category: NeedCategory = .water, landmark: String = ""
    ) -> MyNeed {
        MyNeed(
            msgIdHex: id, createdAtMs: at, category: category, urgency: urgency, people: people, item: item,
            landmark: landmark, summary: "", lang: .en, needPayloadB64: "", ackStatus: ack, cancelled: cancelled
        )
    }
}
