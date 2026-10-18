package org.relay.core.nlu

import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.Need
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.Utf8
import org.relay.core.payload.VulnFlag

/** Structured result of reading a free-text help request. */
data class NeedDraft(
    val category: Category,
    /** 0..3; 3 = life-threatening */
    val urgency: Int,
    /** 1..255 (1 when the text gives no count; 2 for plural-without-count) */
    val people: Int,
    val vulnFlags: Set<VulnFlag>,
    /** short English label, <= 16 bytes ("insulin", "water", "rescue"...) */
    val item: String,
    /** short English-ish place token, <= 24 bytes ("school"); empty if none found */
    val landmark: String,
    /** English summary, <= 40 bytes */
    val summary: String,
    val lang: Lang,
    /**
     * Other categories the text also asks for ("insulin, medical supplies and food" -> [FOOD]), most urgent first,
     * never [category] itself or OTHER. Only things asked for count, not conditions or context.
     */
    val extraCategories: List<Category> = emptyList(),
) {
    val vulnMask: Int get() = VulnFlag.toMask(vulnFlags)

    /** Build the wire-level [Need] from this draft plus the device-supplied fields. */
    fun toNeed(
        msgId: ByteArray,
        originNode: ByteArray,
        createdAtSec: Long,
        latE5: Int,
        lonE5: Int,
        locAcc: Int,
        lang: Lang = this.lang,
        answers: List<NeedAnswer> = emptyList(),
    ): Need = Need(
        msgId = msgId, originNode = originNode, createdAt = createdAtSec, category = category,
        urgency = urgency, people = people, vulnFlags = vulnMask, latE5 = latE5, lonE5 = lonE5,
        locAcc = locAcc, lang = lang, item = item, landmark = landmark, summary = summary,
        answers = answers,
    )
}

/**
 * Offline rule-based extractor for English, Hindi (Devanagari + romanised), Odia (script +
 * romanised), Tagalog, Waray and Cebuano. Pure function, never throws. Matching is
 * language-agnostic on purpose (disaster messages are code-mixed); the language tag only comes from
 * [detectLang].
 */
object RuleExtractor {

    fun extract(text: String, langHint: Lang? = null): NeedDraft {
        return try {
            extractUnsafe(text, langHint)
        } catch (e: RuntimeException) {
            fallback(text, langHint)
        } catch (e: StackOverflowError) {
            fallback(text, langHint)
        }
    }

    private fun fallback(text: String, langHint: Lang?) = NeedDraft(
        category = Category.OTHER, urgency = 1, people = 1, vulnFlags = emptySet(), item = "",
        landmark = "", summary = rawSummary(text), lang = langHint ?: Lang.EN,
    )

    private fun rawSummary(text: String): String =
        Utf8.truncate(text.trim().replace(Regex("\\s+"), " "), SUMMARY_MAX)

    private const val SUMMARY_MAX = 40

    private fun extractUnsafe(text: String, langHint: Lang?): NeedDraft {
        val raw = if (text.length > 1200) text.substring(0, 1200) else text
        val norm = normalizeText(raw)
        val lang = LangDetect.detect(raw, norm, langHint)

        // The landmark phrase is blanked out first so "near the hospital" does not become a medical need.
        val (landmark, withoutLandmark) = Landmarks.extract(norm)

        // Ages ("80 years old") feed the vulnerability flags and are removed before counting people.
        val flags = java.util.EnumSet.noneOf(VulnFlag::class.java)
        val (ageStripped, ages) = Lexicons.stripAges(Lexicons.stripAvailable(withoutLandmark))
        for (a in ages) { if (a >= 60) flags += VulnFlag.ELDERLY; if (a in 0..12) flags += VulnFlag.CHILD }

        // Concepts
        val matched = Lexicons.concepts.filter { it.lex.matches(ageStripped) }
        val primary = matched.filter { !it.context }
            .sortedWith(compareBy({ -it.urgency }, { Lexicons.categoryRank(it.category) }, { Lexicons.concepts.indexOf(it) }))
        val contextOnly = matched.filter { it.context }
        val ordered = primary + contextOnly

        for (c in matched) flags += c.flags
        for ((flag, lex) in Lexicons.flagLexicons) if (lex.matches(ageStripped)) flags += flag

        val people = People.count(ageStripped)
        val subject = Lexicons.findSubject(ageStripped)
        if (subject != null && subject.second != null) flags += subject.second!!

        val top = ordered.firstOrNull()
        if (top == null) {
            return NeedDraft(
                category = Category.OTHER, urgency = 1, people = people, vulnFlags = flags,
                item = "", landmark = landmark, summary = rawSummary(text), lang = lang,
            )
        }
        val category = top.category
        val urgency = top.urgency
        // "send a doctor" beats "unconscious": the item is what is asked for when something specific was asked
        val item = ordered.firstOrNull { it.request && !it.context && it.category == category }?.item
            ?: ordered.firstOrNull { it.item != null && it.category == category }?.item
            ?: top.item ?: Lexicons.defaultItem(category)

        val summary = buildSummary(ordered, subject?.first, people, flags)
        val extras = primary.filter { it.request && it.category != category && it.category != Category.OTHER }
            .map { it.category }.distinct()
        return NeedDraft(
            category = category, urgency = urgency, people = people, vulnFlags = flags,
            item = Utf8.truncate(item, 16), landmark = Utf8.truncate(landmark, 24),
            summary = summary, lang = lang, extraCategories = extras,
        )
    }

    private fun buildSummary(ordered: List<Concept>, subject: String?, people: Int, flags: Set<VulnFlag>): String {
        val phrases = ordered.map { it.phrase }.distinct().take(3)
        val prefix = subject ?: if (people > 1) "$people people" else ""
        val parts = ArrayList<String>()
        parts += (if (prefix.isEmpty()) phrases.first() else "$prefix ${phrases.first()}")
        parts += phrases.drop(1)
        for ((flag, word) in listOf(VulnFlag.ELDERLY to "elderly", VulnFlag.CHILD to "child", VulnFlag.PREGNANT to "pregnant")) {
            if (flag in flags && parts.none { it.contains(word) || it.contains("labour") }) parts += word
        }
        val sb = StringBuilder()
        for (p in parts) {
            val candidate = if (sb.isEmpty()) p else "$sb, $p"
            if (Utf8.truncateToBytes(candidate, SUMMARY_MAX + 1).size > SUMMARY_MAX) {
                if (sb.isEmpty()) sb.append(Utf8.truncate(p, SUMMARY_MAX))
                break
            }
            sb.setLength(0); sb.append(candidate)
        }
        return sb.toString()
    }
}
