import Foundation
import RelayCore

/// Pure list logic for Home and the Requests tab.
public enum RequestLists {
    /// Newest first (a stable sort: requests created in the same millisecond keep their order).
    public static func newestFirst(_ needs: [MyNeed]) -> [MyNeed] {
        needs.enumerated().sorted { a, b in
            a.element.createdAtMs != b.element.createdAtMs ? a.element.createdAtMs > b.element.createdAtMs : a.offset < b.offset
        }.map { $0.element }
    }

    /// The most recent [n] requests, open or closed (Home).
    public static func recent(_ needs: [MyNeed], _ n: Int = 2) -> [MyNeed] { Array(newestFirst(needs).prefix(n)) }

    /// Active (not resolved or cancelled) and Closed, each newest first.
    public static func split(_ needs: [MyNeed]) -> (active: [MyNeed], closed: [MyNeed]) {
        let sorted = newestFirst(needs)
        return (sorted.filter { !$0.isClosed }, sorted.filter { $0.isClosed })
    }
}

/// Item words that merely restate their category, so the localized category name can stand in for them.
private let restatements: [NeedCategory: Set<String>] = [
    .water: ["water", "drinking water", "needs water"],
    .food: ["food", "needs food", "rice", "rice and dal"],
    .medical: ["medicine", "medicines", "needs medicine", "medical", "medical help", "needs medical help", "doctor", "needs doctor"],
    .rescue: ["rescue", "needs rescue", "help"],
    .shelter: ["shelter", "needs shelter"],
    .missingPerson: ["missing person", "missing"],
]

/// The name of what is needed, for titles and share text. English UI: the item (capitalised), else the category.
/// Other languages: the wire item is English, so show the localized category; when the item says more than the
/// category does (e.g. "insulin" under medical) show "<category>: <item>".
public func requestLabel(_ category: NeedCategory, _ item: String, english: Bool, categoryName: (NeedCategory) -> String) -> String {
    let shown = item.trimmed
    if english {
        let base = shown.isEmpty ? categoryName(category) : shown
        return base.prefix(1).uppercased() + base.dropFirst()
    }
    let restates = shown.isEmpty || (restatements[category]?.contains(shown.lowercased()) ?? false)
    return restates ? categoryName(category) : "\(categoryName(category)): \(shown)"
}

/// "Drinking water · 6 people" (English) or "पानी · 6 लोग": the label from `requestLabel`, then the head count.
public func requestTitle(_ need: MyNeed, _ c: CommonStrings) -> String {
    "\(requestLabel(need.category, need.item, english: c.isEnglish, categoryName: c.categoryName)) · \(c.people(need.people))"
}

/// The trailing label: the urgency word while open, "Closed" when resolved, "Cancelled" when cancelled.
public func requestTrailing(_ need: MyNeed, _ c: CommonStrings) -> String {
    if need.cancelled { return c.statusCancelled }
    if need.isClosed { return c.statusClosed }
    return c.urgencyWord(need.urgency)
}

/// One request in a list (Home and Requests tab): everything the row shows, in the app language. Built by `make`.
public struct RequestRowModel: Equatable, Sendable, Identifiable {
    public var id: String { msgIdHex }
    public let msgIdHex: String
    /// Category circle (a green check when `isClosed`).
    public let category: NeedCategory
    /// "Drinking water · 6 people".
    public let title: String
    /// The latest timeline step with relative time: "Reached a gateway phone · 2 min ago".
    public let statusLine: String
    /// The trailing label: urgency word, "Closed" or "Cancelled".
    public let trailing: String
    /// iPhone third line: "Urgent · R-7F3A · near the primary school" (the landmark part is left out when blank).
    public let metaLine: String
    public let isClosed: Bool
    public let isCancelled: Bool
    /// Open and urgent or worse: the trailing label is drawn in the warning colour.
    public let isLoud: Bool
    /// "title. statusLine. trailing", one VoiceOver element.
    public let accessibilityLabel: String

    public static func make(_ need: MyNeed, now: Int64, strings: Strings) -> RequestRowModel {
        let c = strings.common
        let title = requestTitle(need, c)
        let line = c.rowStatusLine(RequestTimeline.latest(need, now: now), now)
        let trailing = requestTrailing(need, c)
        return RequestRowModel(
            msgIdHex: need.msgIdHex, category: need.category, title: title, statusLine: line, trailing: trailing,
            metaLine: metaLine(need, strings), isClosed: need.isClosed, isCancelled: need.cancelled,
            isLoud: !need.isClosed && need.urgency >= 2, accessibilityLabel: "\(title). \(line). \(trailing)"
        )
    }

    /// "Urgent · R-7F3A · near the primary school": urgency word, reference, and the landmark in lower case.
    public static func metaLine(_ need: MyNeed, _ strings: Strings) -> String {
        var parts = [strings.common.urgencyWord(need.urgency), RequestLogic.reference(need)]
        let lm = need.landmark.trimmed
        if !lm.isEmpty {
            let near = strings.ask.nearLandmark(lm)
            parts.append(near.prefix(1).lowercased() + near.dropFirst())
        }
        return parts.joined(separator: " · ")
    }
}

/// The Requests tab: Active and Closed sections, newest first, or the empty message.
public struct RequestsTabModel: Equatable, Sendable {
    public struct Section: Equatable, Sendable {
        public let title: String
        public let rows: [RequestRowModel]
    }

    public let title: String
    /// Shown when there are no requests at all; nil otherwise.
    public let emptyMessage: String?
    /// Active first, then Closed; a section with no rows is left out.
    public let sections: [Section]

    public static func make(needs: [MyNeed], now: Int64, strings: Strings) -> RequestsTabModel {
        let (active, closed) = RequestLists.split(needs)
        var sections: [Section] = []
        if !active.isEmpty {
            sections.append(Section(title: strings.common.statusActive, rows: active.map { RequestRowModel.make($0, now: now, strings: strings) }))
        }
        if !closed.isEmpty {
            sections.append(Section(title: strings.common.statusClosed, rows: closed.map { RequestRowModel.make($0, now: now, strings: strings) }))
        }
        return RequestsTabModel(
            title: strings.home.requestsTitle, emptyMessage: needs.isEmpty ? strings.home.emptyRequests : nil, sections: sections
        )
    }
}
