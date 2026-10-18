package org.relay.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.nlu.NeedDraft
import org.relay.core.nlu.RuleExtractor
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * Scores [RuleExtractor] per field against the voice spike's labelled prompts
 * (`ml/voice-spike/eval/guided_prompts.json`, 34 prompts, and `sentences.csv`, 8 rows), with the same
 * metric as `ml/voice-spike/eval/summarize.py`:
 *  - category: exact; urgency: within +-1; people: exact; vuln: micro F1 over flags
 *  - item / landmark: normalised (lowercase, punctuation to space, leading near/by/at/behind/the/a/an and
 *    trailing Hindi "के पास" dropped) then exact or difflib ratio >= 0.8
 * Rows in a language the extractor has no lexicon for (Bengali, Bhojpuri: labelled ahead of the work) are scored
 * and listed in the report under their own heading, but the floors apply to the shipped languages only, so a
 * missing lexicon is visible without turning every unrelated lexicon edit red.
 * Writes the table and every miss to `build/reports/rule-eval.txt` (and stdout) and fails if any field
 * drops below the floor recorded after the lexicon work, so a lexicon edit cannot silently regress.
 */
class RuleScoreTest {

    private class Row(val id: String, val lang: String, val text: String, val exp: Exp)
    private class Exp(
        val category: String, val urgency: Int, val people: Int, val vuln: Set<String>, val item: String, val landmark: String,
    )

    private class Tally {
        var n = 0; var cat = 0; var item = 0; var lmk = 0; var urg = 0; var ppl = 0
        var tp = 0; var fp = 0; var fn = 0
        val f1: Double get() = if (2 * tp + fp + fn == 0) Double.NaN else 2.0 * tp / (2 * tp + fp + fn)
    }

    private val dir = File(System.getProperty("relay.labels") ?: error("relay.labels not set"))

    private fun guided(): List<Row> {
        val arr: JsonArray = Json.parseToJsonElement(File(dir, "guided_prompts.json").readText()).jsonArray
        return arr.map { e ->
            val o = e.jsonObject
            val x = o["expected"]!!.jsonObject
            Row(o["id"]!!.jsonPrimitive.content, o["lang"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content,
                Exp(x["category"]!!.jsonPrimitive.content, x["urgency"]!!.jsonPrimitive.int, x["people"]!!.jsonPrimitive.int,
                    x["vuln"]!!.jsonArray.map { it.jsonPrimitive.content.uppercase() }.toSet(),
                    x["item"]!!.jsonPrimitive.content, x["landmark"]!!.jsonPrimitive.content))
        }
    }

    private fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var cur = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                q && c == '"' && text.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == ',' -> { cur.add(sb.toString()); sb.setLength(0) }
                !q && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    cur.add(sb.toString()); sb.setLength(0)
                    if (cur.any { it.isNotEmpty() }) rows.add(cur)
                    cur = ArrayList()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur) }
        return rows
    }

    private fun sentences(): List<Row> {
        val rows = csvRows(File(dir, "sentences.csv").readText())
        val head = rows.first()
        fun col(r: List<String>, n: String) = r[head.indexOf(n)]
        return rows.drop(1).map { r ->
            Row(col(r, "id"), col(r, "lang"), col(r, "text"),
                Exp(col(r, "category"), col(r, "urgency").toInt(), col(r, "people").toInt(),
                    col(r, "vuln").split('|', ' ').filter { it.isNotBlank() }.map { it.uppercase() }.toSet(),
                    col(r, "item"), col(r, "landmark")))
        }
    }

    // ---- metric, mirrors summarize.py
    private fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (ch in n) sb.append(if (isLetterNumberMark(ch)) ch else ' ')
        val toks = sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        val leading = setOf("near", "by", "at", "behind", "the", "a", "an")
        var changed = true
        while (changed) {
            changed = false
            if (toks.isNotEmpty() && toks.first() in leading) { toks.removeAt(0); changed = true }
            if (toks.size >= 2 && toks[toks.size - 2] == "के" && toks.last() == "पास") {
                toks.removeAt(toks.size - 1); toks.removeAt(toks.size - 1); changed = true
            }
        }
        return toks.joinToString(" ")
    }

    private fun isLetterNumberMark(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
        Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    private fun similar(a0: String, b0: String): Boolean {
        val a = norm(a0); val b = norm(b0)
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        return ratio(a, b) >= 0.8
    }

    /** difflib.SequenceMatcher(None, a, b).ratio() (no junk; the strings here are far below the autojunk size). */
    private fun ratio(a: String, b: String): Double {
        fun matched(alo: Int, ahi: Int, blo: Int, bhi: Int): Int {
            if (alo >= ahi || blo >= bhi) return 0
            var bi = alo; var bj = blo; var bk = 0
            var prev = IntArray(bhi - blo + 1)
            for (i in alo until ahi) {
                val cur = IntArray(bhi - blo + 1)
                for (j in blo until bhi) {
                    if (a[i] == b[j]) {
                        val k = prev[j - blo] + 1
                        cur[j - blo + 1] = k
                        if (k > bk) { bk = k; bi = i - k + 1; bj = j - k + 1 }
                    }
                }
                prev = cur
            }
            if (bk == 0) return 0
            return bk + matched(alo, bi, blo, bj) + matched(bi + bk, ahi, bj + bk, bhi)
        }
        return 2.0 * matched(0, a.length, 0, b.length) / (a.length + b.length)
    }

    private fun score(rows: List<Row>, name: String, report: StringBuilder): Tally {
        val t = Tally()
        val misses = ArrayList<String>()
        for (r in rows) {
            val d: NeedDraft = RuleExtractor.extract(r.text)
            t.n++
            val miss = ArrayList<String>()
            if (d.category.name == r.exp.category) t.cat++ else miss += "cat ${r.exp.category}->${d.category}"
            if (similar(d.item, r.exp.item)) t.item++ else miss += "item '${r.exp.item}'->'${d.item}'"
            if (similar(d.landmark, r.exp.landmark)) t.lmk++ else miss += "lmk '${r.exp.landmark}'->'${d.landmark}'"
            if (kotlin.math.abs(d.urgency - r.exp.urgency) <= 1) t.urg++ else miss += "urg ${r.exp.urgency}->${d.urgency}"
            if (d.people == r.exp.people) t.ppl++ else miss += "ppl ${r.exp.people}->${d.people}"
            val pv = d.vulnFlags.map { it.name }.toSet()
            t.tp += (pv intersect r.exp.vuln).size; t.fp += (pv - r.exp.vuln).size; t.fn += (r.exp.vuln - pv).size
            if (pv != r.exp.vuln) miss += "vuln ${r.exp.vuln.sorted()}->${pv.sorted()}"
            if (miss.isNotEmpty()) misses += "  ${r.id}: " + miss.joinToString(" | ")
        }
        report.append("### $name\n")
        report.append("n=${t.n} cat ${t.cat}/${t.n} item ${t.item}/${t.n} landmark ${t.lmk}/${t.n} urgency+-1 ${t.urg}/${t.n} " +
            "people ${t.ppl}/${t.n} vulnF1 ${"%.2f".format(Locale.ROOT, t.f1)}\n")
        report.append("misses: ${misses.size}/${t.n} rows\n")
        for (m in misses) report.append(m).append('\n')
        report.append('\n')
        return t
    }

    @Test fun scoreRuleExtractorPerField() {
        val report = StringBuilder()
        val (shipped, ahead) = guided().partition { it.lang !in NO_LEXICON }
        val g = score(shipped, "guided_prompts.json, shipped languages (34)", report)
        score(ahead, "guided_prompts.json, languages without a lexicon yet (not part of the floors)", report)
        val s = score(sentences(), "sentences.csv (8)", report)
        val out = File(System.getProperty("relay.reportdir") ?: "build/reports").also { it.mkdirs() }
        File(out, "rule-eval.txt").writeText(report.toString())
        println(report)

        assertTrue(g.n == 34 && s.n == 8)
        // Floors = the numbers after the lexicon fixes. BEFORE: cat 23, item 13, landmark 28, urgency 34, people 26, vuln F1 0.83.
        assertTrue("category ${g.cat}/34", g.cat >= FLOOR_CAT)
        assertTrue("item ${g.item}/34", g.item >= FLOOR_ITEM)
        assertTrue("landmark ${g.lmk}/34", g.lmk >= FLOOR_LMK)
        assertTrue("urgency ${g.urg}/34", g.urg >= FLOOR_URG)
        assertTrue("people ${g.ppl}/34", g.ppl >= FLOOR_PPL)
        assertTrue("vuln F1 ${g.f1}", g.f1 >= FLOOR_F1)
    }

    private companion object {
        val NO_LEXICON = setOf("bn", "bho")
        const val FLOOR_CAT = 34
        const val FLOOR_ITEM = 34
        const val FLOOR_LMK = 34
        const val FLOOR_URG = 34
        const val FLOOR_PPL = 33
        const val FLOOR_F1 = 0.97
    }
}
