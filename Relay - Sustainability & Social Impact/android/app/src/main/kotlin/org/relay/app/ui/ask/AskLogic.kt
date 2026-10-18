package org.relay.app.ui.ask

import org.relay.app.ui.i18n.CommonStrings
import org.relay.core.intake.IntakeDraft
import org.relay.core.intake.IntakeField
import org.relay.core.intake.QuestionBank
import org.relay.core.payload.Category
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.Utf8
import org.relay.core.payload.VulnFlag

/**
 * The request being composed across New request and Check and send. Pure data: no Android types.
 *
 * [category] is null until something (extraction or a tap) says what it is. [edited] holds the fields the person
 * set by hand; extraction never overwrites those (a user choice beats a model or rule guess).
 */
data class AskDraft(
    val category: Category? = null,
    val item: String = "",
    val people: Int = 1,
    val vulnFlags: Set<VulnFlag> = emptySet(),
    val landmark: String = "",
    val summary: String = "",
    val urgency: Int = 1,
    /** QuestionBank question id -> answer value. Only ids in [questionIds] survive. */
    val answers: Map<Int, Int> = emptyMap(),
    val edited: Set<IntakeField> = emptySet(),
) {
    /** Follow-up questions for the current category, from the bank (never written by a model). */
    val questionIds: List<Int> get() = QuestionBank.select(category ?: Category.OTHER, vulnFlags)
}

object AskLogic {
    const val MAX_ITEM = NeedCodec.MAX_ITEM
    const val MAX_LANDMARK = NeedCodec.MAX_LANDMARK
    const val MAX_SUMMARY = NeedCodec.MAX_SUMMARY
    const val MAX_PEOPLE = 255

    /** Category chips of step 1, in display order; Missing person and Other sit behind "Something else". */
    val mainCategories = listOf(Category.WATER, Category.FOOD, Category.MEDICAL, Category.RESCUE, Category.SHELTER)
    val moreCategories = listOf(Category.MISSING_PERSON, Category.OTHER)

    /** Display order of vulnerability flags (children first, as in the design). */
    val flagOrder = listOf(VulnFlag.CHILD, VulnFlag.ELDERLY, VulnFlag.PREGNANT, VulnFlag.INJURED, VulnFlag.TRAPPED)

    // ---------------------------------------------------------------- byte limits

    fun byteLength(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** [s] cut on a code point boundary to at most [maxBytes] UTF-8 bytes (the wire limit). */
    fun fit(s: String, maxBytes: Int): String = Utf8.truncate(s, maxBytes)

    /**
     * How many more characters of the kind just typed still fit: bytes left divided by the width of the last
     * character (1 for Latin, 3 for Devanagari or Odia), so the counter never promises room that is not there.
     */
    fun charsLeft(s: String, maxBytes: Int): Int {
        val left = maxBytes - byteLength(s)
        if (left <= 0) return 0
        val last = if (s.isEmpty()) 1 else byteLength(String(Character.toChars(s.codePointBefore(s.length))))
        return left / last
    }

    // ---------------------------------------------------------------- draft from intake, merge rules

    /**
     * Fold an intake result into [draft]. Fields in [AskDraft.edited] keep the person's value; the rest follow the
     * intake. Category OTHER from extraction means "not understood" (null). Answers for questions that no longer
     * apply (the category changed) are dropped.
     */
    fun applyIntake(draft: AskDraft, intake: IntakeDraft): AskDraft {
        val n = intake.need
        fun free(f: IntakeField) = f !in draft.edited
        return draft.copy(
            category = if (free(IntakeField.CATEGORY)) n.category.takeIf { it != Category.OTHER } else draft.category,
            item = if (free(IntakeField.ITEM)) fit(n.item.trim(), MAX_ITEM) else draft.item,
            people = if (free(IntakeField.PEOPLE)) n.people.coerceIn(1, MAX_PEOPLE) else draft.people,
            vulnFlags = if (free(IntakeField.VULN)) n.vulnFlags else draft.vulnFlags,
            landmark = if (free(IntakeField.LANDMARK)) fit(n.landmark.trim(), MAX_LANDMARK) else draft.landmark,
            summary = if (free(IntakeField.SUMMARY)) fit(n.summary.trim(), MAX_SUMMARY) else draft.summary,
            urgency = if (free(IntakeField.URGENCY)) n.urgency.coerceIn(0, 3) else draft.urgency,
        ).pruned()
    }

    /** Drop answers whose question is no longer asked. */
    fun AskDraft.pruned(): AskDraft {
        val ids = questionIds.toSet()
        val kept = answers.filterKeys { it in ids }
        return if (kept.size == answers.size) this else copy(answers = kept)
    }

    /** Set the category by hand; null clears the choice so extraction decides again. */
    fun setCategory(d: AskDraft, c: Category?): AskDraft =
        if (c == null) d.copy(category = null, edited = d.edited - IntakeField.CATEGORY).pruned()
        else d.copy(category = c, edited = d.edited + IntakeField.CATEGORY).pruned()

    fun setItem(d: AskDraft, text: String) = d.copy(item = fit(text, MAX_ITEM), edited = d.edited + IntakeField.ITEM)

    fun setLandmark(d: AskDraft, text: String) = d.copy(landmark = fit(text, MAX_LANDMARK), edited = d.edited + IntakeField.LANDMARK)

    fun setSummary(d: AskDraft, text: String) = d.copy(summary = fit(text, MAX_SUMMARY), edited = d.edited + IntakeField.SUMMARY)

    fun setPeople(d: AskDraft, n: Int) = d.copy(people = n.coerceIn(1, MAX_PEOPLE), edited = d.edited + IntakeField.PEOPLE)

    fun toggleFlag(d: AskDraft, f: VulnFlag): AskDraft {
        val flags = if (f in d.vulnFlags) d.vulnFlags - f else d.vulnFlags + f
        return d.copy(vulnFlags = flags, edited = d.edited + IntakeField.VULN).pruned()
    }

    fun setUrgency(d: AskDraft, u: Int) = d.copy(urgency = u.coerceIn(0, 3), edited = d.edited + IntakeField.URGENCY)

    /** Choose [value] for [questionId]; choosing the chosen value again clears it. Unknown ids or values are ignored. */
    fun setAnswer(d: AskDraft, questionId: Int, value: Int): AskDraft {
        if (questionId !in d.questionIds || !QuestionBank.isValid(questionId, value)) return d
        return if (d.answers[questionId] == value) d.copy(answers = d.answers - questionId)
        else d.copy(answers = d.answers + (questionId to value))
    }

    // ---------------------------------------------------------------- what the screens say

    /** True once there is enough to move on: a category (even "Other") or anything said or typed. */
    fun hasContent(d: AskDraft, transcript: String): Boolean =
        d.category != null || transcript.isNotBlank() || d.item.isNotBlank()

    private fun generic(category: Category?, item: String): Boolean {
        val i = item.trim().lowercase()
        if (i.isEmpty()) return true
        return when (category) {
            Category.WATER -> i in setOf("water", "drinking water")
            Category.FOOD -> i in setOf("food", "rice")
            Category.MEDICAL -> i in setOf("medicine", "medical")
            Category.RESCUE -> i == "rescue"
            Category.SHELTER -> i == "shelter"
            Category.MISSING_PERSON -> i in setOf("missing person", "missing")
            else -> false
        }
    }

    /** The specific item when there is one, else the category in words ("Water"); empty when neither is known. */
    fun needWords(common: CommonStrings, d: AskDraft): String {
        val c = d.category
        return when {
            !generic(c, d.item) -> d.item.trim()
            c != null -> common.categoryName(c)
            else -> ""
        }
    }

    /** "6" then " · " and the flags in words: "6 · children, elderly"; just the number when no flags. */
    fun peopleLine(common: CommonStrings, people: Int, flags: Set<VulnFlag>): String {
        val words = flagOrder.filter { it in flags }.joinToString(", ") { common.vulnFlag(it) }
        return if (words.isEmpty()) "$people" else "$people · $words"
    }

    /** Chips under "Understood so far": need, people, landmark, note. Empty list = nothing understood yet. */
    fun understood(common: CommonStrings, near: (String) -> String, d: AskDraft): List<String> = buildList {
        val need = needWords(common, d)
        if (need.isNotEmpty()) add(need)
        if (d.people > 1 || d.vulnFlags.isNotEmpty()) add(
            if (d.vulnFlags.isEmpty()) common.people(d.people) else peopleLine(common, d.people, d.vulnFlags),
        )
        if (d.landmark.isNotBlank()) add(near(d.landmark))
        if (d.summary.isNotBlank()) add(d.summary)
    }

    // ---------------------------------------------------------------- the wire

    private val englishWord = mapOf(
        Category.WATER to "water", Category.FOOD to "food", Category.MEDICAL to "medicine", Category.RESCUE to "rescue",
        Category.SHELTER to "shelter", Category.MISSING_PERSON to "missing person", Category.OTHER to "help",
    )

    /** Item text for the wire: the draft's, or the category's English word so the control room never sees blank. */
    fun wireItem(d: AskDraft): String =
        fit(d.item.trim().ifEmpty { englishWord.getValue(d.category ?: Category.OTHER) }, MAX_ITEM)

    /** Answers in asking order, at most [QuestionBank.MAX_ANSWERS]; with any, the NEED encodes as v2. */
    fun wireAnswers(d: AskDraft): List<NeedAnswer> =
        d.questionIds.mapNotNull { id -> d.answers[id]?.let { NeedAnswer(id, it) } }
            .filter { QuestionBank.isValid(it.questionId, it.value) }
            .take(QuestionBank.MAX_ANSWERS)
}
