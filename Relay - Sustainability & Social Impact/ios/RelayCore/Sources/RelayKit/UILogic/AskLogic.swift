import Foundation
import RelayCore

/// The request being composed across New request and Check and send. Pure data: no UI types.
///
/// `category` is nil until something (extraction or a tap) says what it is. `edited` holds the fields the person set
/// by hand; extraction never overwrites those (a user choice beats a model or rule guess).
public struct AskDraft: Equatable, Sendable {
    public var category: NeedCategory?
    /// Further categories the person also needs (water *and* food). Each goes out as its own NEED, sharing the
    /// people, flags, landmark, note and urgency; `category` stays the one the item and follow-up answers belong to.
    public var extraCategories: [NeedCategory]
    /// Also needs a vehicle out of the area. Its own RESCUE ticket (item "evacuation"), apart from a plain rescue one.
    public var transportOut: Bool
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
        category: NeedCategory? = nil, extraCategories: [NeedCategory] = [], transportOut: Bool = false, item: String = "", people: Int = 1, vulnFlags: Set<VulnFlag> = [],
        landmark: String = "", summary: String = "", urgency: Int = 1, answers: [Int: Int] = [:],
        edited: Set<IntakeField> = []
    ) {
        self.category = category; self.extraCategories = extraCategories; self.transportOut = transportOut; self.item = item; self.people = people; self.vulnFlags = vulnFlags
        self.landmark = landmark; self.summary = summary; self.urgency = urgency; self.answers = answers
        self.edited = edited
    }

    /// Every chosen category, the primary first.
    public var categories: [NeedCategory] { (category.map { [$0] } ?? []) + extraCategories }

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
    /// Water and food share one chip (`.water` stands for the group): the same supply team answers both, so they
    /// are one ticket. A chip is a group of wire categories, and at most one per group is chosen.
    public static let mainCategories: [NeedCategory] = [.water, .medical, .rescue, .shelter]
    public static let moreCategories: [NeedCategory] = [.missingPerson, .other]

    /// The wire categories one chip stands for.
    public static func group(_ c: NeedCategory) -> [NeedCategory] { c == .water || c == .food ? [.water, .food] : [c] }

    /// Chip text: "Water / Food" for the supply group, else the category's name.
    public static func chipLabel(_ common: CommonStrings, _ c: NeedCategory) -> String {
        group(c).count > 1 ? group(c).map { common.categoryName($0) }.joined(separator: " / ") : common.categoryName(c)
    }

    /// True when the draft has a category of the chip's group.
    public static func isChosen(_ d: AskDraft, chip c: NeedCategory) -> Bool { d.categories.contains { group(c).contains($0) } }

    /// The chips of the Need editor on Check and send: every category on its own.
    public static let editorCategories: [NeedCategory] = [.water, .food, .medical, .rescue, .shelter, .missingPerson, .other]

    /// Display order of vulnerability flags (children first, as in the design).
    public static let flagOrder: [VulnFlag] = [.child, .elderly, .pregnant, .injured, .trapped]

    /// The "who is with you" flags worth offering on New request for the chosen categories (context-aware: no "pregnant"
    /// prompt under a water request). Check and send still offers all of them.
    public static func relevantFlags(_ categories: [NeedCategory]) -> [VulnFlag] {
        var want: Set<VulnFlag> = [.child, .elderly]
        if categories.contains(.medical) { want.formUnion([.pregnant, .injured]) }
        if categories.contains(.rescue) { want.formUnion([.trapped, .injured, .pregnant]) }
        if categories.contains(.shelter) || categories.contains(.missingPerson) { want.insert(.pregnant) }
        return flagOrder.filter { want.contains($0) }
    }

    /// True once the person has said or picked something, so follow-up inputs (how many people) make sense.
    public static func showDetails(_ d: AskDraft, _ transcript: String) -> Bool {
        !d.categories.isEmpty || d.transportOut || !transcript.trimmed.isEmpty
    }

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
        if free(.category) {
            d.category = n.category == .other ? nil : n.category
            d.extraCategories = extras(of: n)
        }
        // An item extracted for another category than the person chose (rice, for a chosen "water") is not theirs.
        let itemFits = free(.category) || d.category == nil || n.category == d.category
        if free(.item) { d.item = itemFits ? fit(n.item.trimmed, maxItem) : "" }
        if free(.people) { d.people = clamp(n.people, 1, maxPeople) }
        if free(.vuln) { d.vulnFlags = n.vulnFlags }
        if free(.landmark) { d.landmark = fit(n.landmark.trimmed, maxLandmark) }
        if free(.summary) { d.summary = fit(n.summary.trimmed, maxSummary) }
        if free(.urgency) { d.urgency = clamp(n.urgency, 0, 3) }
        return d.pruned()
    }

    /// The extra categories extraction heard, one per chip group and none in the primary's group ("water" + "food" is one).
    private static func extras(of n: NeedDraft) -> [NeedCategory] {
        if n.category == .other { return [] }
        var taken = Set(group(n.category))
        var out: [NeedCategory] = []
        for c in n.extraCategories where !taken.contains(c) {
            taken.formUnion(group(c))
            out.append(c)
        }
        return out
    }

    private static func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { min(max(v, lo), hi) }

    /// Fold a finished triage conversation into `draft`. Triage knows more than the first sentence did, so its categories
    /// lead (medical before rescue, each its own ticket), urgency only ever goes up, danger flags are added, the answers it
    /// collected go on the wire, and its control-room summary replaces the extractor's. Fields it sets count as edited, so
    /// a later extraction pass cannot undo them. Port of Android `AskLogic.applyTriage`.
    public static func applyTriage(_ d: AskDraft, _ r: TriageResult) -> AskDraft {
        let triaged = r.categories.filter { $0 != .other }
        if triaged.isEmpty { return d }
        let others = d.categories.filter { c in !triaged.contains { group($0).contains(c) } }
        var ordered: [NeedCategory] = []
        for c in triaged + others where !ordered.contains(where: { group($0).contains(c) }) { ordered.append(c) }
        var n = d
        n.category = ordered.first
        n.extraCategories = Array(ordered.dropFirst())
        n.urgency = clamp(max(d.urgency, r.urgency), 0, 3)
        n.vulnFlags = d.vulnFlags.union(r.vuln)
        if !d.edited.contains(.people), let p = r.people { n.people = clamp(p, 1, maxPeople) }
        if !d.edited.contains(.landmark), d.landmark.isEmpty, let l = r.landmark, !l.trimmed.isEmpty { n.landmark = fit(l.trimmed, maxLandmark) }
        if !d.edited.contains(.summary), !r.summary.isEmpty { n.summary = fit(r.summary, maxSummary) }
        for a in r.answers { n.answers[a.questionId] = a.value }
        n.edited.formUnion([.category, .urgency, .vuln])
        if !r.summary.isEmpty { n.edited.insert(.summary) }
        return n.pruned()
    }

    /// Set the category by hand; nil clears the choice so extraction decides again.
    public static func setCategory(_ d: AskDraft, _ c: NeedCategory?) -> AskDraft {
        var n = d
        n.extraCategories = []
        if let c {
            n.category = c
            n.edited.insert(.category)
        } else {
            n.category = nil
            n.edited.remove(.category)
        }
        return retargeted(from: d, n).pruned()
    }

    /// Tap on a merged chip (New request): adds its group to the chosen set, or removes it when already chosen.
    /// Tapping a category that extraction guessed only confirms it (so it stays); removing the primary promotes the
    /// next one; removing the last hands the decision back to extraction (`category` nil, not edited).
    public static func toggleCategory(_ d: AskDraft, _ c: NeedCategory) -> AskDraft {
        change(d, c, exact: false)
    }

    /// Tap on an exact category (Check and send, where water and food are separate chips): like `toggleCategory`,
    /// but a sibling in the same group is swapped for [c], so "food" can be corrected to "water".
    public static func pickCategory(_ d: AskDraft, _ c: NeedCategory) -> AskDraft {
        change(d, c, exact: true)
    }

    private static func change(_ d: AskDraft, _ c: NeedCategory, exact: Bool) -> AskDraft {
        var n = d
        if let chosen = n.categories.first(where: { group(c).contains($0) }) {
            if exact, chosen != c {
                if n.category == chosen { n.category = c } else if let i = n.extraCategories.firstIndex(of: chosen) { n.extraCategories[i] = c }
                n.edited.insert(.category)
            } else if let i = n.extraCategories.firstIndex(of: chosen) {
                n.extraCategories.remove(at: i)
            } else if !n.edited.contains(.category) {
                n.edited.insert(.category)
            } else if n.extraCategories.isEmpty {
                n.category = nil
                n.edited.remove(.category)
            } else {
                n.category = n.extraCategories.removeFirst()
            }
        } else {
            if n.category == nil { n.category = c } else { n.extraCategories.append(c) }
            n.edited.insert(.category)
        }
        return retargeted(from: d, n).pruned()
    }

    /// An item the rules or a model picked belongs to the category it was picked for ("rice" for food); when the
    /// person moves the primary category and never edited the item, it is dropped so the new category's word shows.
    private static func retargeted(from old: AskDraft, _ new: AskDraft) -> AskDraft {
        guard new.category != old.category, !new.edited.contains(.item) else { return new }
        var n = new
        n.item = ""
        return n
    }

    /// One draft per chosen category, in order: the primary keeps the item and answers; the others get the
    /// category's own word as item and the shared fields. A draft with no category gives a single `.other` one.
    public static func perCategory(_ d: AskDraft) -> [AskDraft] {
        let cats = d.categories
        var out: [AskDraft] = cats.enumerated().map { i, c in
            var n = d
            n.category = c
            n.extraCategories = []
            n.transportOut = false
            if i > 0 { n.item = ""; n.answers = [:] }
            return n.pruned()
        }
        if d.transportOut {
            var t = d
            t.category = .rescue
            t.extraCategories = []
            t.transportOut = false
            // Alone, it takes what the person typed; next to other tickets the item stays with the first one.
            t.item = cats.isEmpty && !d.item.trimmed.isEmpty ? d.item : evacuationWord
            t.answers = [:]
            out.append(t.pruned())
        }
        return out.isEmpty ? [d.pruned()] : out
    }

    public static let evacuationWord = "evacuation"

    public static func toggleTransport(_ d: AskDraft) -> AskDraft {
        var n = d; n.transportOut.toggle(); return n
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
        d.category != nil || d.transportOut || !transcript.trimmed.isEmpty || !d.item.trimmed.isEmpty
    }

    private static func isGeneric(_ category: NeedCategory?, _ item: String) -> Bool {
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
        chips.append(contentsOf: d.extraCategories.map { chipLabel(common, $0) })
        if d.transportOut { chips.append(common.transportOut) }
        if d.people > 1 || !d.vulnFlags.isEmpty {
            chips.append(d.vulnFlags.isEmpty ? common.people(d.people) : peopleLine(common, d.people, d.vulnFlags))
        }
        if !d.landmark.trimmed.isEmpty { chips.append(near(d.landmark)) }
        if !d.summary.trimmed.isEmpty { chips.append(d.summary) }
        return chips
    }

    /// The "Something else" row shows when the person opened it, or when a category behind it is chosen.
    public static func showsMoreCategories(_ d: AskDraft, opened: Bool) -> Bool {
        opened || d.categories.contains { moreCategories.contains($0) }
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

    private static let englishWord: [NeedCategory: String] = [
        .water: "water", .food: "food", .medical: "medicine", .rescue: "rescue", .shelter: "shelter",
        .missingPerson: "missing person", .other: "help",
    ]

    /// Item text for the wire: the draft's, or the category's English word so the control room never sees blank.
    public static func wireItem(_ d: AskDraft) -> String {
        let item = d.item.trimmed
        if item.isEmpty, d.category == .water { return "food and water" }
        return fit(item.isEmpty ? englishWord[d.category ?? .other]! : item, maxItem)
    }

    /// Answers in asking order, at most `QuestionBank.maxAnswers`; with any, the NEED encodes as v2.
    public static func wireAnswers(_ d: AskDraft) -> [NeedAnswer] {
        let all = d.questionIds.compactMap { id in d.answers[id].map { NeedAnswer(id, $0) } }
        return Array(all.filter { QuestionBank.isValid($0.questionId, $0.value) }.prefix(QuestionBank.maxAnswers))
    }
}
