import Foundation
import RelayCore

/// Pure text and decisions for Request detail (unit-tested; the screen only displays them).
public enum RequestLogic {
    /// "#<ticket>" once the control room has a ticket number, else "R-" + first 4 hex of the msg id, uppercase.
    public static func reference(_ need: MyNeed) -> String {
        need.ticketNo >= 0 ? "#\(need.ticketNo)" : "R-" + need.msgIdHex.take(4).uppercased()
    }

    /// "Drinking water · 6 people": the item (or category name), then the head count.
    public static func title(_ need: MyNeed, _ common: CommonStrings) -> String { requestTitle(need, common) }

    /// Plain-text summary for the share sheet, in the app language of [s].
    public static func shareText(_ need: MyNeed, _ s: Strings, area: String, now: Int64) -> String {
        let c = s.common
        let r = s.request
        var lines: [String] = []
        lines.append(r.shareHeader(reference(need)))
        lines.append(title(need, c) + " (" + c.urgencyWord(need.urgency) + ")")
        lines.append(r.referenceLine(reference(need), c.clock(need.createdAtMs), area))
        if !need.landmark.trimmed.isEmpty { lines.append(r.shareNear(need.landmark)) }
        lines.append(r.shareStatus(statusText(need, c, now: now)))
        if hasMessage(need) { lines.append(r.shareMessage(need.ackText)) }
        return lines.joined(separator: "\n")
    }

    /// The request's state in words for the share text: "Cancelled", or the latest step with "· Expected by 2:40 PM".
    public static func statusText(_ need: MyNeed, _ c: CommonStrings, now: Int64) -> String {
        if need.cancelled { return c.statusCancelled }
        let step = RequestTimeline.latest(need, now: now)
        return c.stepTitle(step.key) + (step.etaAtMs > 0 && !step.etaOverdue ? " · " + c.expectedBy(step.etaAtMs) : "")
    }

    /// Identity of the control-room message currently shown: changes when a newer ACK arrives. The text part is the
    /// JVM `String.hashCode`, so the key is the same on every run (Swift's own hashing is seeded per process) and
    /// matches Android's.
    public static func messageKey(_ need: MyNeed) -> String { "\(need.msgIdHex):\(need.ackStatus):\(javaHashCode(need.ackText))" }

    /// True when there is a message from the control room (the card is shown).
    public static func hasMessage(_ need: MyNeed) -> Bool { need.ackStatus >= AckStatus.received.code && !need.ackText.trimmed.isEmpty }

    /// Auto-read once per new message: only with "Read messages aloud" on, a message present, and not read before.
    public static func shouldAutoRead(readAloud: Bool, _ need: MyNeed, alreadyRead: Set<String>) -> Bool {
        readAloud && hasMessage(need) && !alreadyRead.contains(messageKey(need))
    }

    /// The language name on "Hear it in Hindi": English wording in an English UI, the native name otherwise
    /// ("Hindi" vs "हिन्दी"), for the language the message was written in.
    public static func hearLanguageName(_ messageLang: Lang, uiLang: Lang) -> String {
        let e = AppLanguages.of(messageLang)
        return uiLang == .en ? e.englishName : e.nativeName
    }

    /// The label of the "Hear it in ..." button for [need]'s message.
    public static func hearItLabel(_ need: MyNeed, _ s: Strings) -> String {
        s.request.hearItIn(hearLanguageName(need.lang, uiLang: s.lang))
    }

    /// Java `String.hashCode` (31-based over UTF-16 units), 32-bit wrapping.
    static func javaHashCode(_ s: String) -> Int32 {
        var h: Int32 = 0
        for u in s.utf16 { h = h &* 31 &+ Int32(u) }
        return h
    }
}

/// How a timeline row is drawn.
public enum TimelineRowState: Sendable, Hashable { case done, current, future }

/// One display row of the Request detail timeline. `tag` is a small chip beside the title (e.g. "LIVE").
public struct TimelineRow: Equatable, Sendable {
    public let title: String
    public let state: TimelineRowState
    public let subtitle: String?
    public let tag: String?
    public init(title: String, state: TimelineRowState, subtitle: String? = nil, tag: String? = nil) {
        self.title = title; self.state = state; self.subtitle = subtitle; self.tag = tag
    }
}

extension RequestTimeline {
    /// Maps the pure timeline steps to display rows in the app language.
    public static func rows(_ steps: [Step], _ common: CommonStrings) -> [TimelineRow] {
        steps.map { s in
            let sub = common.stepSubtitle(s)
            return TimelineRow(
                title: common.stepTitle(s.key), state: s.state == .current ? .current : .done,
                subtitle: sub.trimmed.isEmpty ? nil : sub, tag: s.live ? common.liveTag : nil
            )
        }
    }

    /// Timeline rows for Request detail; a cancelled request ends with a "Cancelled" row.
    public static func rows(for need: MyNeed, now: Int64, strings s: Strings) -> [TimelineRow] {
        let rows = rows(steps(need, now: now), s.common)
        if !need.cancelled { return rows }
        let at = need.cancelledAtMs > 0 ? s.common.clock(need.cancelledAtMs) : nil
        return rows + [TimelineRow(title: s.common.statusCancelled, state: .done, subtitle: at)]
    }
}

/// Everything Request detail shows for one request, in the app language. Built by `make`; the view lays it out.
public struct RequestDetailModel: Equatable, Sendable {
    /// Navigation title, "Request".
    public let screenTitle: String
    /// "Drinking water · 6 people".
    public let title: String
    /// The urgency word while open; "Closed" or "Cancelled" otherwise.
    public let badge: String
    public let isClosed: Bool
    /// Urgency 0..3 (drives the orange "Urgent" tag colour while open).
    public let urgency: Int
    /// "R-7F3A · sent 2:02 PM · Ward 4".
    public let referenceLine: String
    public let timeline: [TimelineRow]
    /// The "FROM THE CONTROL ROOM" card: nil when the control room has not written yet.
    public let message: Message?
    /// "+ Add" and "× Cancel" capsules show only while the request is open.
    public let showsActions: Bool

    public struct Message: Equatable, Sendable {
        public let heading: String
        public let text: String
        /// "Hear it in Hindi".
        public let hearLabel: String
        /// Shown under the card when no voice for the language is installed.
        public let noVoice: String
    }

    public static func make(_ need: MyNeed, now: Int64, area: String, strings s: Strings) -> RequestDetailModel {
        let c = s.common
        let msg: Message? = RequestLogic.hasMessage(need)
            ? Message(heading: s.request.fromControlRoom, text: need.ackText, hearLabel: RequestLogic.hearItLabel(need, s), noVoice: s.request.noVoice)
            : nil
        return RequestDetailModel(
            screenTitle: s.request.title,
            title: RequestLogic.title(need, c),
            badge: requestTrailing(need, c),
            isClosed: need.isClosed,
            urgency: need.urgency,
            referenceLine: s.request.referenceLine(RequestLogic.reference(need), c.clock(need.createdAtMs), area),
            timeline: RequestTimeline.rows(for: need, now: now, strings: s),
            message: msg,
            showsActions: !need.isClosed
        )
    }
}
