package org.relay.core.intake

import org.relay.core.nlu.NeedDraft
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Lang
import org.relay.core.payload.Need
import org.relay.core.payload.NeedAnswer

/** Where a field of an [IntakeDraft] came from. */
enum class FieldSource { RULES, MODEL }

/** The ticket fields an engine fills, for per-field source tracking and [FieldMerge]. */
enum class IntakeField { CATEGORY, ITEM, LANDMARK, URGENCY, PEOPLE, VULN, SUMMARY }

/**
 * What intake produces from one free-text request: the ticket fields, up to [QuestionBank.MAX_ANSWERS] follow-up
 * question ids (from the final category's bank, in asking order) and the answers collected so far.
 */
data class IntakeDraft(
    val need: NeedDraft,
    val questionIds: List<Int>,
    val answers: List<NeedAnswer> = emptyList(),
    val sources: Map<IntakeField, FieldSource> = IntakeField.entries.associateWith { FieldSource.RULES },
) {
    /** Question ids still to ask, in order. */
    val pending: List<Int> get() = questionIds.filter { id -> answers.none { it.questionId == id } }

    /** Wire-level [Need] with the collected answers (v2 on the wire when there are any). */
    fun toNeed(
        msgId: ByteArray, originNode: ByteArray, createdAtSec: Long, latE5: Int, lonE5: Int, locAcc: Int,
        lang: Lang = need.lang,
    ): Need = need.toNeed(msgId, originNode, createdAtSec, latE5, lonE5, locAcc, lang, answers)
}

/**
 * Turns text into an [IntakeDraft] and records answers. Implemented by [RuleIntake] today; a model-backed engine
 * returns the same type, with per-field [IntakeDraft.sources] (see [FieldMerge]).
 */
interface IntakeEngine {
    fun extract(text: String, langHint: Lang? = null): IntakeDraft

    /**
     * Record the answer to [questionId]. A question the draft did not ask, or a value the question does not have, is
     * ignored (the draft is returned unchanged); answering twice replaces the earlier answer.
     */
    fun applyAnswer(draft: IntakeDraft, questionId: Int, answerValue: Int): IntakeDraft {
        if (questionId !in draft.questionIds || !QuestionBank.isValid(questionId, answerValue)) return draft
        val kept = draft.answers.filter { it.questionId != questionId } + NeedAnswer(questionId, answerValue)
        return draft.copy(answers = kept.sortedBy { draft.questionIds.indexOf(it.questionId) })
    }
}

/** Rule-based intake: [RuleExtractor] fields plus the category's questions chosen by [QuestionBank.select]. */
object RuleIntake : IntakeEngine {
    override fun extract(text: String, langHint: Lang?): IntakeDraft {
        val d = RuleExtractor.extract(text, langHint)
        return IntakeDraft(d, QuestionBank.select(d.category, d.vulnFlags))
    }
}
