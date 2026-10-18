import Foundation

/// Structured result of reading a free-text help request.
public struct NeedDraft: Equatable, Hashable, Sendable {
    public var category: Category
    /// 0..3; 3 = life-threatening
    public var urgency: Int
    /// 1..255 (1 when the text gives no count; 2 for plural-without-count)
    public var people: Int
    public var vulnFlags: Set<VulnFlag>
    /// short English label, <= 16 bytes ("insulin", "water", "rescue"...)
    public var item: String
    /// short English-ish place token, <= 24 bytes ("school"); empty if none found
    public var landmark: String
    /// English summary, <= 40 bytes
    public var summary: String
    public var lang: Lang

    public init(
        category: Category, urgency: Int, people: Int, vulnFlags: Set<VulnFlag>, item: String, landmark: String,
        summary: String, lang: Lang
    ) {
        self.category = category; self.urgency = urgency; self.people = people; self.vulnFlags = vulnFlags
        self.item = item; self.landmark = landmark; self.summary = summary; self.lang = lang
    }

    public var vulnMask: Int { VulnFlag.toMask(vulnFlags) }

    /// Build the wire-level `Need` from this draft plus the device-supplied fields.
    public func toNeed(
        msgId: [UInt8], originNode: [UInt8], createdAtSec: Int64, latE5: Int, lonE5: Int, locAcc: Int,
        lang: Lang? = nil, answers: [NeedAnswer] = []
    ) -> Need {
        Need(msgId: msgId, originNode: originNode, createdAt: createdAtSec, category: category, urgency: urgency,
             people: people, vulnFlags: vulnMask, latE5: latE5, lonE5: lonE5, locAcc: locAcc, lang: lang ?? self.lang,
             item: item, landmark: landmark, summary: summary, answers: answers)
    }
}

/// Offline rule-based extractor for English, Hindi (Devanagari + romanised), Odia (script +
/// romanised), Tagalog, Waray and Cebuano. Pure function, never fails. Matching is
/// language-agnostic on purpose (disaster messages are code-mixed); the language tag only comes from
/// `LangDetect`.
public enum RuleExtractor {
    private static let summaryMax = 40

    public static func extract(_ text: String, langHint: Lang? = nil) -> NeedDraft {
        extractUnsafe(text, langHint)
    }

    private static let whitespaceRun = Rx("\\s+")

    private static func rawSummary(_ text: String) -> String {
        Utf8.truncate(whitespaceRun.replace(text.trimmingCharacters(in: .whitespacesAndNewlines), with: " "), summaryMax)
    }

    private static func extractUnsafe(_ text: String, _ langHint: Lang?) -> NeedDraft {
        let raw = text.utf16Length > 1200 ? text.substr(0, 1200) : text
        let norm = normalizeText(raw)
        let lang = LangDetect.detect(raw, norm, langHint)

        // The landmark phrase is blanked out first so "near the hospital" does not become a medical need.
        let (landmark, withoutLandmark) = Landmarks.extract(norm)

        // Ages ("80 years old") feed the vulnerability flags and are removed before counting people.
        var flags = Set<VulnFlag>()
        let (ageStripped, ages) = Lexicons.stripAges(Lexicons.stripAvailable(withoutLandmark))
        for a in ages {
            if a >= 60 { flags.insert(.elderly) }
            if (0...12).contains(a) { flags.insert(.child) }
        }

        // Concepts
        let matched = Lexicons.concepts.enumerated().filter { $0.element.lex.matches(ageStripped) }
        let primary = matched.filter { !$0.element.context }.sorted { a, b in
            if a.element.urgency != b.element.urgency { return a.element.urgency > b.element.urgency }
            let ra = Lexicons.categoryRank(a.element.category), rb = Lexicons.categoryRank(b.element.category)
            if ra != rb { return ra < rb }
            return a.offset < b.offset
        }
        let contextOnly = matched.filter { $0.element.context }
        let ordered = (primary + contextOnly).map { $0.element }

        for c in matched { flags.formUnion(c.element.flags) }
        for (flag, lex) in Lexicons.flagLexicons where lex.matches(ageStripped) { flags.insert(flag) }

        let people = People.count(ageStripped)
        let subject = Lexicons.findSubject(ageStripped)
        if let f = subject?.1 { flags.insert(f) }

        guard let top = ordered.first else {
            return NeedDraft(category: .other, urgency: 1, people: people, vulnFlags: flags, item: "",
                             landmark: landmark, summary: rawSummary(text), lang: lang)
        }
        let category = top.category
        let urgency = top.urgency
        // "send a doctor" beats "unconscious": the item is what is asked for when something specific was asked
        let item = ordered.first { $0.request && !$0.context && $0.category == category }?.item
            ?? ordered.first { $0.item != nil && $0.category == category }?.item
            ?? top.item ?? Lexicons.defaultItem(category)

        let summary = buildSummary(ordered, subject?.0, people, flags)
        return NeedDraft(category: category, urgency: urgency, people: people, vulnFlags: flags,
                         item: Utf8.truncate(item, 16), landmark: Utf8.truncate(landmark, 24),
                         summary: summary, lang: lang)
    }

    private static func buildSummary(_ ordered: [Concept], _ subject: String?, _ people: Int, _ flags: Set<VulnFlag>) -> String {
        var phrases: [String] = []
        for c in ordered where !phrases.contains(c.phrase) { phrases.append(c.phrase) }
        phrases = Array(phrases.prefix(3))
        let prefix = subject ?? (people > 1 ? "\(people) people" : "")
        var parts: [String] = []
        parts.append(prefix.isEmpty ? phrases[0] : "\(prefix) \(phrases[0])")
        parts.append(contentsOf: phrases.dropFirst())
        for (flag, word) in [(VulnFlag.elderly, "elderly"), (.child, "child"), (.pregnant, "pregnant")] {
            if flags.contains(flag) && !parts.contains(where: { $0.contains(word) || $0.contains("labour") }) {
                parts.append(word)
            }
        }
        var sb = ""
        for p in parts {
            let candidate = sb.isEmpty ? p : "\(sb), \(p)"
            if Utf8.truncateToBytes(candidate, summaryMax + 1).count > summaryMax {
                if sb.isEmpty { sb = Utf8.truncate(p, summaryMax) }
                break
            }
            sb = candidate
        }
        return sb
    }
}
