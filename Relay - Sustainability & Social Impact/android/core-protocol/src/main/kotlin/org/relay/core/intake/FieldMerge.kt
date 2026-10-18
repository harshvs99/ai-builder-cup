package org.relay.core.intake

import org.relay.core.nlu.NeedDraft
import org.relay.core.payload.Category
import org.relay.core.payload.Utf8
import org.relay.core.payload.VulnFlag

/**
 * Fields a model proposed; null = the model gave nothing usable for that field. Values are validated and clamped by
 * [FieldMerge.merge], never trusted.
 */
data class ModelFields(
    val category: Category? = null,
    val item: String? = null,
    val landmark: String? = null,
    val urgency: Int? = null,
    val people: Int? = null,
    val vulnFlags: Set<VulnFlag>? = null,
    val summary: String? = null,
)

/**
 * Per-field choice between the rule parser and a model. [prefer] names the winner for each field; if the preferred
 * source has nothing for a field (blank text, category OTHER, no model value) the other source fills it. Fields not
 * listed default to rules.
 *
 * [PER_EVAL] is the policy the voice-spike eval supports: Gemma is better at category and item (and writes a
 * better summary), the rules are better at landmark, urgency, people and vulnerability flags.
 */
class FieldMerge(private val prefer: Map<IntakeField, FieldSource>) {

    fun sourceFor(field: IntakeField): FieldSource = prefer[field] ?: FieldSource.RULES

    /** Merged fields and the source each one came from. [model] null = rules only. */
    fun merge(rules: NeedDraft, model: ModelFields?): Pair<NeedDraft, Map<IntakeField, FieldSource>> {
        val sources = HashMap<IntakeField, FieldSource>()

        fun <T> pick(field: IntakeField, fromRules: T?, fromModel: T?, default: T): T {
            val order = if (sourceFor(field) == FieldSource.MODEL) listOf(FieldSource.MODEL, FieldSource.RULES)
                else listOf(FieldSource.RULES, FieldSource.MODEL)
            for (src in order) {
                val v = if (src == FieldSource.MODEL) fromModel else fromRules
                if (v != null) { sources[field] = src; return v }
            }
            sources[field] = FieldSource.RULES
            return default
        }

        fun text(s: String?, max: Int): String? = s?.trim()?.takeIf { it.isNotEmpty() }?.let { Utf8.truncate(it, max) }

        val merged = rules.copy(
            category = pick(IntakeField.CATEGORY, rules.category.takeIf { it != Category.OTHER }, model?.category, Category.OTHER),
            item = pick(IntakeField.ITEM, text(rules.item, 16), text(model?.item, 16), ""),
            landmark = pick(IntakeField.LANDMARK, text(rules.landmark, 24), text(model?.landmark, 24), ""),
            urgency = pick(IntakeField.URGENCY, rules.urgency, model?.urgency?.takeIf { it in 0..3 }, rules.urgency),
            people = pick(IntakeField.PEOPLE, rules.people, model?.people?.takeIf { it in 1..255 }, rules.people),
            vulnFlags = pick(IntakeField.VULN, rules.vulnFlags, model?.vulnFlags, emptySet()),
            summary = pick(IntakeField.SUMMARY, text(rules.summary, 40), text(model?.summary, 40), ""),
        )
        return merged to sources
    }

    /** Merge, then choose the questions from the merged category (the category may have changed). */
    fun toIntake(rules: NeedDraft, model: ModelFields?): IntakeDraft {
        val (need, sources) = merge(rules, model)
        return IntakeDraft(need, QuestionBank.select(need.category, need.vulnFlags), sources = sources)
    }

    companion object {
        val RULES_ONLY = FieldMerge(emptyMap())

        val PER_EVAL = FieldMerge(mapOf(
            IntakeField.CATEGORY to FieldSource.MODEL,
            IntakeField.ITEM to FieldSource.MODEL,
            IntakeField.SUMMARY to FieldSource.MODEL,
            IntakeField.LANDMARK to FieldSource.RULES,
            IntakeField.URGENCY to FieldSource.RULES,
            IntakeField.PEOPLE to FieldSource.RULES,
            IntakeField.VULN to FieldSource.RULES,
        ))
    }
}
