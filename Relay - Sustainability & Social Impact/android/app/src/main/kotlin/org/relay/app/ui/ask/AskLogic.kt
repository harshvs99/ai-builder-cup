package org.relay.app.ui.ask

import org.relay.app.ui.i18n.CommonStrings
import org.relay.core.intake.IntakeDraft
import org.relay.core.intake.IntakeField
import org.relay.core.intake.QuestionBank
import org.relay.core.nlu.NeedDraft
import org.relay.core.payload.Category
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.Utf8
import org.relay.core.payload.VulnFlag
import org.relay.core.triage.TriageResult

/**
 * The request being composed across New request and Check and send. Pure data: no Android types.
 *
 * [category] is null until something (extraction or a tap) says what it is. [edited] holds the fields the person
 * set by hand; extraction never overwrites those (a user choice beats a model or rule guess).
 */
data class AskDraft(
    val category: Category? = null,
    /**
     * Further categories the person also needs (water *and* food). Each goes out as its own NEED, sharing the people,
     * flags, landmark, note and urgency; [category] stays the one the item and follow-up answers belong to.
     */
    val extraCategories: List<Category> = emptyList(),
    /** Also needs a vehicle out of the area. Its own RESCUE ticket (item "evacuation"), apart from a plain rescue one. */
    val transportOut: Boolean = false,
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
    /** Every chosen category, the primary first. */
    val categories: List<Category> get() = listOfNotNull(category) + extraCategories

    /** Follow-up questions for the current category, from the bank (never written by a model). */
    val questionIds: List<Int> get() = QuestionBank.select(category ?: Category.OTHER, vulnFlags)
}

object AskLogic {
    const val MAX_ITEM = NeedCodec.MAX_ITEM
    const val MAX_LANDMARK = NeedCodec.MAX_LANDMARK
    const val MAX_SUMMARY = NeedCodec.MAX_SUMMARY
    const val MAX_PEOPLE = 255

    /** Category chips of step 1, in display order; Missing person and Other sit behind "Something else". */
    // Water and food share one chip (WATER stands for the group): the same supply team answers both, so they are one
    // ticket. A chip is a group of wire categories, and at most one per group is chosen.
    val mainCategories = listOf(Category.WATER, Category.MEDICAL, Category.RESCUE, Category.SHELTER)
    val moreCategories = listOf(Category.MISSING_PERSON, Category.OTHER)

    /** The chips of the Need editor on Check and send: every category on its own. */
    val editorCategories = listOf(
        Category.WATER, Category.FOOD, Category.MEDICAL, Category.RESCUE, Category.SHELTER, Category.MISSING_PERSON, Category.OTHER,
    )

    /** The wire categories one chip stands for. */
    fun group(c: Category): List<Category> = if (c == Category.WATER || c == Category.FOOD) listOf(Category.WATER, Category.FOOD) else listOf(c)

    /** Chip text: "Water / Food" for the supply group, else the category's name. */
    fun chipLabel(common: CommonStrings, c: Category): String =
        group(c).let { g -> if (g.size > 1) g.joinToString(" / ") { common.categoryName(it) } else common.categoryName(c) }

    /** True when the draft has a category of the chip's group. */
    fun isChosen(d: AskDraft, chip: Category): Boolean = d.categories.any { it in group(chip) }

    /** Display order of vulnerability flags (children first, as in the design). */
    val flagOrder = listOf(VulnFlag.CHILD, VulnFlag.ELDERLY, VulnFlag.PREGNANT, VulnFlag.INJURED, VulnFlag.TRAPPED)

    /**
     * The "who is with you" flags worth offering on New request for the chosen categories (context-aware: no "pregnant"
     * prompt under a water request). Check and send still offers all of them.
     */
    fun relevantFlags(categories: List<Category>): List<VulnFlag> {
        val want = mutableSetOf(VulnFlag.CHILD, VulnFlag.ELDERLY)
        if (Category.MEDICAL in categories) { want += VulnFlag.PREGNANT; want += VulnFlag.INJURED }
        if (Category.RESCUE in categories) { want += VulnFlag.TRAPPED; want += VulnFlag.INJURED; want += VulnFlag.PREGNANT }
        if (Category.SHELTER in categories || Category.MISSING_PERSON in categories) want += VulnFlag.PREGNANT
        return flagOrder.filter { it in want }
    }

    /** True once the person has said or picked something, so follow-up inputs (how many people) make sense. */
    fun showDetails(d: AskDraft, transcript: String): Boolean = d.categories.isNotEmpty() || d.transportOut || transcript.isNotBlank()

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
            extraCategories = if (free(IntakeField.CATEGORY)) extrasFor(n) else draft.extraCategories,
            // An item extracted for another category than the person chose (rice, for a chosen "water") is not theirs.
            item = if (free(IntakeField.ITEM)) {
                if (free(IntakeField.CATEGORY) || draft.category == null || n.category == draft.category) fit(n.item.trim(), MAX_ITEM) else ""
            } else draft.item,
            people = if (free(IntakeField.PEOPLE)) n.people.coerceIn(1, MAX_PEOPLE) else draft.people,
            vulnFlags = if (free(IntakeField.VULN)) n.vulnFlags else draft.vulnFlags,
            landmark = if (free(IntakeField.LANDMARK)) fit(n.landmark.trim(), MAX_LANDMARK) else draft.landmark,
            summary = if (free(IntakeField.SUMMARY)) fit(n.summary.trim(), MAX_SUMMARY) else draft.summary,
            urgency = if (free(IntakeField.URGENCY)) n.urgency.coerceIn(0, 3) else draft.urgency,
        ).pruned()
    }

    /** The extra categories extraction heard, one per chip group and none in the primary's group ("water" + "food" is one). */
    private fun extrasFor(n: NeedDraft): List<Category> {
        if (n.category == Category.OTHER) return emptyList()
        val taken = group(n.category).toMutableSet()
        return n.extraCategories.filter { c -> c !in taken && taken.addAll(group(c)) }
    }

    /**
     * Fold a finished triage conversation into [draft]. Triage knows more than the first sentence did, so its categories
     * lead (medical before rescue, each its own ticket), urgency only ever goes up, danger flags are added, the answers it
     * collected go on the wire, and its control-room summary replaces the extractor's. Fields it sets count as edited, so
     * a later extraction pass cannot undo them.
     */
    fun applyTriage(d: AskDraft, r: TriageResult): AskDraft {
        val triaged = r.categories.filter { it != Category.OTHER }
        if (triaged.isEmpty()) return d
        val others = d.categories.filter { c -> triaged.none { c in group(it) } }
        val ordered = (triaged + others).fold(emptyList<Category>()) { acc, c -> if (acc.any { group(it).contains(c) }) acc else acc + c }
        return d.copy(
            category = ordered.first(),
            extraCategories = ordered.drop(1),
            urgency = maxOf(d.urgency, r.urgency).coerceIn(0, 3),
            vulnFlags = d.vulnFlags + r.vuln,
            people = if (IntakeField.PEOPLE !in d.edited && r.people != null) r.people!!.coerceIn(1, MAX_PEOPLE) else d.people,
            landmark = if (IntakeField.LANDMARK !in d.edited && d.landmark.isBlank() && !r.landmark.isNullOrBlank()) fit(r.landmark!!.trim(), MAX_LANDMARK) else d.landmark,
            summary = if (IntakeField.SUMMARY !in d.edited && r.summary.isNotBlank()) fit(r.summary, MAX_SUMMARY) else d.summary,
            answers = d.answers + r.answers.associate { it.questionId to it.value },
            edited = d.edited + setOf(IntakeField.CATEGORY, IntakeField.URGENCY, IntakeField.VULN) +
                (if (r.summary.isNotBlank()) setOf(IntakeField.SUMMARY) else emptySet()),
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
        if (c == null) d.copy(category = null, extraCategories = emptyList(), edited = d.edited - IntakeField.CATEGORY).pruned()
        else d.copy(category = c, extraCategories = emptyList(), edited = d.edited + IntakeField.CATEGORY).pruned()
            .let { retargeted(d, it) }

    /**
     * Tap on a merged chip (New request): adds its group to the chosen set, or removes it when already chosen. Tapping
     * a category that extraction guessed only confirms it (so it stays); removing the primary promotes the next one;
     * removing the last hands the decision back to extraction (category null, not edited).
     */
    fun toggleCategory(d: AskDraft, c: Category): AskDraft = change(d, c, exact = false)

    /**
     * Tap on an exact category (Check and send, where water and food are separate chips): like [toggleCategory], but
     * a sibling in the same group is swapped for [c], so "food" can be corrected to "water".
     */
    fun pickCategory(d: AskDraft, c: Category): AskDraft = change(d, c, exact = true)

    private fun change(d: AskDraft, c: Category, exact: Boolean): AskDraft {
        val chosen = d.categories.firstOrNull { it in group(c) }
        val n = when {
            chosen == null ->
                (if (d.category == null) d.copy(category = c) else d.copy(extraCategories = d.extraCategories + c))
                    .copy(edited = d.edited + IntakeField.CATEGORY)
            exact && chosen != c -> (
                if (d.category == chosen) d.copy(category = c)
                else d.copy(extraCategories = d.extraCategories.map { if (it == chosen) c else it })
                ).copy(edited = d.edited + IntakeField.CATEGORY)
            chosen in d.extraCategories -> d.copy(extraCategories = d.extraCategories - chosen)
            IntakeField.CATEGORY !in d.edited -> d.copy(edited = d.edited + IntakeField.CATEGORY)
            d.extraCategories.isEmpty() -> d.copy(category = null, edited = d.edited - IntakeField.CATEGORY)
            else -> d.copy(category = d.extraCategories.first(), extraCategories = d.extraCategories.drop(1))
        }
        return retargeted(d, n).pruned()
    }

    /**
     * An item the rules or a model picked belongs to the category it was picked for ("rice" for food); when the
     * person moves the primary category and never edited the item, it is dropped so the new category's word shows.
     */
    private fun retargeted(old: AskDraft, new: AskDraft): AskDraft =
        if (new.category != old.category && IntakeField.ITEM !in new.edited) new.copy(item = "") else new

    /**
     * One draft per chosen category, in order: the primary keeps the item and answers; the others get the category's
     * own word as item and the shared fields. No category or one gives a single draft.
     */
    fun perCategory(d: AskDraft): List<AskDraft> {
        val cats = d.categories
        val out = cats.mapIndexed { i, c ->
            val n = d.copy(category = c, extraCategories = emptyList(), transportOut = false)
            (if (i == 0) n else n.copy(item = "", answers = emptyMap())).pruned()
        }.toMutableList()
        if (d.transportOut) {
            // Alone, it takes what the person typed; next to other tickets the item stays with the first one.
            val item = if (cats.isEmpty() && d.item.isNotBlank()) d.item else EVACUATION_WORD
            out += d.copy(category = Category.RESCUE, extraCategories = emptyList(), transportOut = false, item = item, answers = emptyMap()).pruned()
        }
        return out.ifEmpty { listOf(d.pruned()) }
    }

    const val EVACUATION_WORD = "evacuation"

    fun toggleTransport(d: AskDraft): AskDraft = d.copy(transportOut = !d.transportOut)

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
        d.category != null || d.transportOut || transcript.isNotBlank() || d.item.isNotBlank()

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
        d.extraCategories.forEach { add(chipLabel(common, it)) }
        if (d.transportOut) add(common.transportOut)
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
        fit(d.item.trim().ifEmpty { if (d.category == Category.WATER) "food and water" else englishWord.getValue(d.category ?: Category.OTHER) }, MAX_ITEM)

    /** Answers in asking order, at most [QuestionBank.MAX_ANSWERS]; with any, the NEED encodes as v2. */
    fun wireAnswers(d: AskDraft): List<NeedAnswer> =
        d.questionIds.mapNotNull { id -> d.answers[id]?.let { NeedAnswer(id, it) } }
            .filter { QuestionBank.isValid(it.questionId, it.value) }
            .take(QuestionBank.MAX_ANSWERS)
}
