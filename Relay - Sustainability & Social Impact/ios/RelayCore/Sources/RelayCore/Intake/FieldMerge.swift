import Foundation

/// Fields a model proposed; nil = the model gave nothing usable for that field. Values are validated and clamped by
/// `FieldMerge.merge`, never trusted.
public struct ModelFields: Equatable, Sendable {
    public var category: Category?
    public var item: String?
    public var landmark: String?
    public var urgency: Int?
    public var people: Int?
    public var vulnFlags: Set<VulnFlag>?
    public var summary: String?

    public init(
        category: Category? = nil, item: String? = nil, landmark: String? = nil, urgency: Int? = nil,
        people: Int? = nil, vulnFlags: Set<VulnFlag>? = nil, summary: String? = nil
    ) {
        self.category = category; self.item = item; self.landmark = landmark; self.urgency = urgency
        self.people = people; self.vulnFlags = vulnFlags; self.summary = summary
    }
}

/// Per-field choice between the rule parser and a model. [prefer] names the winner for each field; if the preferred
/// source has nothing for a field (blank text, category OTHER, no model value) the other source fills it. Fields not
/// listed default to rules.
///
/// `perEval` is the policy the voice-spike eval supports: Gemma is better at category and item (and writes a
/// better summary), the rules are better at landmark, urgency, people and vulnerability flags.
public struct FieldMerge: Sendable {
    private let prefer: [IntakeField: FieldSource]

    public init(_ prefer: [IntakeField: FieldSource]) { self.prefer = prefer }

    public func sourceFor(_ field: IntakeField) -> FieldSource { prefer[field] ?? .rules }

    /// Merged fields and the source each one came from. [model] nil = rules only.
    public func merge(_ rules: NeedDraft, _ model: ModelFields?) -> (NeedDraft, [IntakeField: FieldSource]) {
        var sources: [IntakeField: FieldSource] = [:]

        func pick<T>(_ field: IntakeField, _ fromRules: T?, _ fromModel: T?, _ fallback: T) -> T {
            let order: [FieldSource] = sourceFor(field) == .model ? [.model, .rules] : [.rules, .model]
            for src in order {
                let v = src == .model ? fromModel : fromRules
                if let v { sources[field] = src; return v }
            }
            sources[field] = .rules
            return fallback
        }

        func text(_ s: String?, _ max: Int) -> String? {
            guard let t = s?.trimmingCharacters(in: .whitespacesAndNewlines), !t.isEmpty else { return nil }
            return Utf8.truncate(t, max)
        }

        var merged = rules
        merged.category = pick(.category, rules.category != .other ? rules.category : nil, model?.category, .other)
        merged.item = pick(.item, text(rules.item, 16), text(model?.item, 16), "")
        merged.landmark = pick(.landmark, text(rules.landmark, 24), text(model?.landmark, 24), "")
        merged.urgency = pick(.urgency, rules.urgency, model?.urgency.flatMap { (0...3).contains($0) ? $0 : nil }, rules.urgency)
        merged.people = pick(.people, rules.people, model?.people.flatMap { (1...255).contains($0) ? $0 : nil }, rules.people)
        merged.vulnFlags = pick(.vuln, rules.vulnFlags, model?.vulnFlags, Set<VulnFlag>())
        merged.summary = pick(.summary, text(rules.summary, 40), text(model?.summary, 40), "")
        return (merged, sources)
    }

    /// Merge, then choose the questions from the merged category (the category may have changed).
    public func toIntake(_ rules: NeedDraft, _ model: ModelFields?) -> IntakeDraft {
        let (need, sources) = merge(rules, model)
        return IntakeDraft(need: need, questionIds: QuestionBank.select(need.category, need.vulnFlags), sources: sources)
    }

    public static let rulesOnly = FieldMerge([:])

    public static let perEval = FieldMerge([
        .category: .model,
        .item: .model,
        .summary: .model,
        .landmark: .rules,
        .urgency: .rules,
        .people: .rules,
        .vuln: .rules,
    ])
}
