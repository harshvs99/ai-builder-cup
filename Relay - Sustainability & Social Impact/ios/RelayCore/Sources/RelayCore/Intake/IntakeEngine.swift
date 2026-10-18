import Foundation

/// Where a field of an `IntakeDraft` came from.
public enum FieldSource: Sendable, Hashable { case rules, model }

/// The ticket fields an engine fills, for per-field source tracking and `FieldMerge`.
public enum IntakeField: CaseIterable, Sendable, Hashable { case category, item, landmark, urgency, people, vuln, summary }

/// What intake produces from one free-text request: the ticket fields, up to `QuestionBank.maxAnswers` follow-up
/// question ids (from the final category's bank, in asking order) and the answers collected so far.
public struct IntakeDraft: Equatable, Sendable {
    public var need: NeedDraft
    public var questionIds: [Int]
    public var answers: [NeedAnswer]
    public var sources: [IntakeField: FieldSource]

    public init(
        need: NeedDraft, questionIds: [Int], answers: [NeedAnswer] = [],
        sources: [IntakeField: FieldSource] = Dictionary(uniqueKeysWithValues: IntakeField.allCases.map { ($0, FieldSource.rules) })
    ) {
        self.need = need; self.questionIds = questionIds; self.answers = answers; self.sources = sources
    }

    /// Question ids still to ask, in order.
    public var pending: [Int] { questionIds.filter { id in !answers.contains { $0.questionId == id } } }

    /// Wire-level `Need` with the collected answers (v2 on the wire when there are any).
    public func toNeed(
        msgId: [UInt8], originNode: [UInt8], createdAtSec: Int64, latE5: Int, lonE5: Int, locAcc: Int, lang: Lang? = nil
    ) -> Need {
        need.toNeed(msgId: msgId, originNode: originNode, createdAtSec: createdAtSec, latE5: latE5, lonE5: lonE5,
                    locAcc: locAcc, lang: lang ?? need.lang, answers: answers)
    }
}

/// Turns text into an `IntakeDraft` and records answers. Implemented by `RuleIntake` today; a model-backed engine
/// returns the same type, with per-field `IntakeDraft.sources` (see `FieldMerge`).
public protocol IntakeEngine: Sendable {
    func extract(_ text: String, langHint: Lang?) -> IntakeDraft

    /// Record the answer to [questionId]. A question the draft did not ask, or a value the question does not have, is
    /// ignored (the draft is returned unchanged); answering twice replaces the earlier answer.
    func applyAnswer(_ draft: IntakeDraft, questionId: Int, answerValue: Int) -> IntakeDraft
}

extension IntakeEngine {
    public func extract(_ text: String) -> IntakeDraft { extract(text, langHint: nil) }

    public func applyAnswer(_ draft: IntakeDraft, questionId: Int, answerValue: Int) -> IntakeDraft {
        if !draft.questionIds.contains(questionId) || !QuestionBank.isValid(questionId, answerValue) { return draft }
        var kept = draft.answers.filter { $0.questionId != questionId }
        kept.append(NeedAnswer(questionId: questionId, value: answerValue))
        var d = draft
        // stable sort by position in the asking order
        d.answers = kept.enumerated().sorted { a, b in
            let ia = draft.questionIds.firstIndex(of: a.element.questionId) ?? -1
            let ib = draft.questionIds.firstIndex(of: b.element.questionId) ?? -1
            return ia != ib ? ia < ib : a.offset < b.offset
        }.map { $0.element }
        return d
    }
}

/// Rule-based intake: `RuleExtractor` fields plus the category's questions chosen by `QuestionBank.select`.
public struct RuleIntake: IntakeEngine {
    public static let shared = RuleIntake()
    public init() {}

    public func extract(_ text: String, langHint: Lang?) -> IntakeDraft {
        let d = RuleExtractor.extract(text, langHint: langHint)
        return IntakeDraft(need: d, questionIds: QuestionBank.select(d.category, d.vulnFlags))
    }
}
