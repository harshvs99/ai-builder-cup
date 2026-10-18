import Foundation
import RelayCore

/// The request being composed across New request and Check and send. Pure data: no UI types.
///
/// `category` is nil until something (extraction or a tap) says what it is. `edited` holds the fields the person set
/// by hand; extraction never overwrites those (a user choice beats a model or rule guess).
public struct AskDraft: Equatable, Sendable {
    public var category: Category?
    public var item: String
    public var people: Int
    public var vulnFlags: Set<VulnFlag>
    public var landmark: String
    public var summary: String
    public var urgency: Int
    /// QuestionBank question id -> answer value. Only ids in `questionIds` survive.
    public var answers: [Int: Int]
    public var edited: Set<IntakeField>

    public init(
        category: Category? = nil, item: String = "", people: Int = 1, vulnFlags: Set<VulnFlag> = [],
        landmark: String = "", summary: String = "", urgency: Int = 1, answers: [Int: Int] = [:],
        edited: Set<IntakeField> = []
    ) {
        self.category = category; self.item = item; self.people = people; self.vulnFlags = vulnFlags
        self.landmark = landmark; self.summary = summary; self.urgency = urgency; self.answers = answers
        self.edited = edited
    }

    /// Follow-up questions for the current category, from the bank (never written by a model).
    public var questionIds: [Int] { QuestionBank.select(category ?? .other, vulnFlags) }

    /// Drops answers whose question is no longer asked (the category or the flags changed).
    public func pruned() -> AskDraft {
        let ids = Set(questionIds)
        let kept = answers.filter { ids.contains($0.key) }
        if kept.count == answers.count { return self }
        var d = self
        d.answers = kept
        return d
    }
}

/// Pure rules of New request and Check and send: byte limits, merging intake into the draft, what the screens say,
/// and the wire fields. Port of Android `AskLogic`.
public enum AskLogic {
    public static let maxItem = NeedCodec.maxItem
    public static let maxLandmark = NeedCodec.maxLandmark
    public static let maxSummary = NeedCodec.maxSummary
    public static let maxPeople = 255

    /// Category chips of step 1, in display order; Missing person and Other sit behind "Something else".
    public static let mainCategories: [Category] = [.water, .food, .medical, .rescue, .shelter]
    public static let moreCategories: [Category] = [.missingPerson, .other]

    /// Display order of vulnerability flags (children first, as in the design).
    public static let flagOrder: [VulnFlag] = [.child, .elderly, .pregnant, .injured, .trapped]

    // ---------------------------------------------------------------- byte limits

    public static func byteLength(_ s: String) -> Int { s.utf8.count }

    /// [s] cut on a code point boundary to at most [maxBytes] UTF-8 bytes (the wire limit).
    public static func fit(_ s: String, _ maxBytes: Int) -> String { Utf8.truncate(s, maxBytes) }

    /// How many more characters of the kind just typed still fit: bytes left divided by the width of the last
    /// character (1 for Latin, 3 for Devanagari or Odia), so the counter never promises room that is not there.
    public static func charsLeft(_ s: String, _ maxBytes: Int) -> Int {
        let left = maxBytes - byteLength(s)
        if left <= 0 { return 0 }
        let last = s.unicodeScalars.last.map { String($0).utf8.count } ?? 1
        return left / last
    }

    // ---------------------------------------------------------------- draft from intake, merge rules

    /// Fold an intake result into [draft]. Fields in `AskDraft.edited` keep the person's value; the rest follow the
    /// intake. Category `.other` from extraction means "not understood" (nil). Answers for questions that no longer
    /// apply (the category changed) are dropped.
    public static func applyIntake(_ draft: AskDraft, _ intake: IntakeDraft) -> AskDraft {
        let n = intake.need
        func free(_ f: IntakeField) -> Bool { !draft.edited.contains(f) }
        var d = draft
        if free(.category) { d.category = n.category == .other ? nil : n.category }
        if free(.item) { d.item = fit(n.item.trimmed, maxItem) }
        if free(.people) { d.people = clamp(n.people, 1, maxPeople) }
        if free(.vuln) { d.vulnFlags = n.vulnFlags }
        if free(.landmark) { d.landmark = fit(n.landmark.trimmed, maxLandmark) }
        if free(.summary) { d.summary = fit(n.summary.trimmed, maxSummary) }
        if free(.urgency) { d.urgency = clamp(n.urgency, 0, 3) }
        return d.pruned()
    }

    private static func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { min(max(v, lo), hi) }

    /// Set the category by hand; nil clears the choice so extraction decides again.
    public static func setCategory(_ d: AskDraft, _ c: Category?) -> AskDraft {
        var n = d
        if let c {
            n.category = c
            n.edited.insert(.category)
        } else {
            n.category = nil
            n.edited.remove(.category)
        }
        return n.pruned()
    }

    public static func setItem(_ d: AskDraft, _ text: String) -> AskDraft {
        var n = d; n.item = fit(text, maxItem); n.edited.insert(.item); return n
    }

    public static func setLandmark(_ d: AskDraft, _ text: String) -> AskDraft {
        var n = d; n.landmark = fit(text, maxLandmark); n.edited.insert(.landmark); return n
    }

    public static func setSummary(_ d: AskDraft, _ text: String) -> AskDraft {
        var n = d; n.summary = fit(text, maxSummary); n.edited.insert(.summary); return n
    }

    public static func setPeople(_ d: AskDraft, _ count: Int) -> AskDraft {
        var n = d; n.people = clamp(count, 1, maxPeople); n.edited.insert(.people); return n
    }

    public static func toggleFlag(_ d: AskDraft, _ f: VulnFlag) -> AskDraft {
        var n = d
        if n.vulnFlags.contains(f) { n.vulnFlags.remove(f) } else { n.vulnFlags.insert(f) }
        n.edited.insert(.vuln)
        return n.pruned()
    }

    public static func setUrgency(_ d: AskDraft, _ u: Int) -> AskDraft {
        var n = d; n.urgency = clamp(u, 0, 3); n.edited.insert(.urgency); return n
    }

    /// Choose [value] for [questionId]; choosing the chosen value again clears it. Unknown ids or values are ignored.
    public static func setAnswer(_ d: AskDraft, _ questionId: Int, _ value: Int) -> AskDraft {
        if !d.questionIds.contains(questionId) || !QuestionBank.isValid(questionId, value) { return d }
        var n = d
        if d.answers[questionId] == value { n.answers[questionId] = nil } else { n.answers[questionId] = value }
        return n
    }

    // ---------------------------------------------------------------- what the screens say

    /// True once there is enough to move on: a category (even "Other") or anything said or typed.
    public static func hasContent(_ d: AskDraft, _ transcript: String) -> Bool {
        d.category != nil || !transcript.trimmed.isEmpty || !d.item.trimmed.isEmpty
    }

    private static func isGeneric(_ category: Category?, _ item: String) -> Bool {
        let i = item.trimmed.lowercased()
        if i.isEmpty { return true }
        switch category {
        case .water?: return ["water", "drinking water"].contains(i)
        case .food?: return ["food", "rice"].contains(i)
        case .medical?: return ["medicine", "medical"].contains(i)
        case .rescue?: return i == "rescue"
        case .shelter?: return i == "shelter"
        case .missingPerson?: return ["missing person", "missing"].contains(i)
        default: return false
        }
    }

    /// The specific item when there is one, else the category in words ("Water"); empty when neither is known.
    public static func needWords(_ common: CommonStrings, _ d: AskDraft) -> String {
        if !isGeneric(d.category, d.item) { return d.item.trimmed }
        if let c = d.category { return common.categoryName(c) }
        return ""
    }

    /// "6" then " · " and the flags in words: "6 · children, elderly"; just the number when no flags.
    public static func peopleLine(_ common: CommonStrings, _ people: Int, _ flags: Set<VulnFlag>) -> String {
        let words = flagOrder.filter { flags.contains($0) }.map { common.vulnFlag($0) }.joined(separator: ", ")
        return words.isEmpty ? "\(people)" : "\(people) · \(words)"
    }

    /// Chips under "Understood so far": need, people, landmark, note. Empty list = nothing understood yet.
    public static func understood(_ common: CommonStrings, near: (String) -> String, _ d: AskDraft) -> [String] {
        var chips: [String] = []
        let need = needWords(common, d)
        if !need.isEmpty { chips.append(need) }
        if d.people > 1 || !d.vulnFlags.isEmpty {
            chips.append(d.vulnFlags.isEmpty ? common.people(d.people) : peopleLine(common, d.people, d.vulnFlags))
        }
        if !d.landmark.trimmed.isEmpty { chips.append(near(d.landmark)) }
        if !d.summary.trimmed.isEmpty { chips.append(d.summary) }
        return chips
    }

    /// The "Something else" row shows when the person opened it, or when a category behind it is chosen.
    public static func showsMoreCategories(_ d: AskDraft, opened: Bool) -> Bool {
        opened || (d.category.map { moreCategories.contains($0) } ?? false)
    }

    // ---------------------------------------------------------------- location line (Check and send)

    /// "GPS · about 40 m accuracy", or the reason there is no fix ("Location permission is off", "Location is turned
    /// off on this phone", "Still finding your location…", from `NoLocationLogic`).
    public static func locationText(_ ask: AskStrings, _ ui: LocUi, accuracyM: Double?) -> String {
        guard let reason = NoLocationLogic.reason(for: ui) else {
            guard let a = accuracyM, !a.isNaN, a >= 0 else { return "GPS" }
            return ask.locationAccuracy(max(1, Int(a.rounded(.toNearestOrAwayFromZero))))
        }
        return NoLocationLogic.reasonText(ask, reason)
    }

    /// The "Turn on" button beside the location line shows when location is off or not allowed.
    public static func offersTurnOnLocation(_ ui: LocUi) -> Bool { ui == .off || ui == .noPermission }

    // ---------------------------------------------------------------- the wire

    private static let englishWord: [Category: String] = [
        .water: "water", .food: "food", .medical: "medicine", .rescue: "rescue", .shelter: "shelter",
        .missingPerson: "missing person", .other: "help",
    ]

    /// Item text for the wire: the draft's, or the category's English word so the control room never sees blank.
    public static func wireItem(_ d: AskDraft) -> String {
        let item = d.item.trimmed
        return fit(item.isEmpty ? englishWord[d.category ?? .other]! : item, maxItem)
    }

    /// Answers in asking order, at most `QuestionBank.maxAnswers`; with any, the NEED encodes as v2.
    public static func wireAnswers(_ d: AskDraft) -> [NeedAnswer] {
        let all = d.questionIds.compactMap { id in d.answers[id].map { NeedAnswer(id, $0) } }
        return Array(all.filter { QuestionBank.isValid($0.questionId, $0.value) }.prefix(QuestionBank.maxAnswers))
    }
}
