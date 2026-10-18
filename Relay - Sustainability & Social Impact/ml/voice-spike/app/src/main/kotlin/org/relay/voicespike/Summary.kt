package org.relay.voicespike

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/**
 * On-device scoring + plain-text summary of a results.jsonl. Mirrors eval/summarize.py (keep the two in sync):
 * item/landmark are normalised (lowercase, punctuation stripped, leading near/by/at/behind/the/a/an dropped
 * repeatedly, trailing Hindi "के पास" dropped), then exact OR difflib-style similarity >= 0.8.
 */
object Summary {
    private const val SIM = 0.8
    private val LEADING = setOf("near", "by", "at", "behind", "the", "a", "an")
    private val POSTPOSITION = listOf("के", "पास")

    fun norm(s: String?): String {
        val n = Normalizer.normalize(s ?: "", Normalizer.Form.NFC).lowercase(Locale.ROOT)
        val sb = StringBuilder()
        var i = 0
        while (i < n.length) {
            val cp = n.codePointAt(i)
            i += Character.charCount(cp)
            val keep = when (Character.getType(cp).toByte()) {
                Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
                Character.MODIFIER_LETTER, Character.OTHER_LETTER,
                Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
                Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
                else -> false
            }
            if (keep) sb.appendCodePoint(cp) else sb.append(' ')
        }
        val toks = sb.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            if (toks.isNotEmpty() && toks[0] in LEADING) { toks.removeAt(0); changed = true }
            if (toks.size >= 2 && toks.subList(toks.size - 2, toks.size) == POSTPOSITION) {
                toks.subList(toks.size - 2, toks.size).clear(); changed = true
            }
        }
        return toks.joinToString(" ")
    }

    fun similar(a0: String?, b0: String?): Boolean {
        val a = norm(a0); val b = norm(b0)
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        return ratio(a, b) >= SIM
    }

    /** difflib.SequenceMatcher.ratio() for short strings without junk: 2*M/T, M from recursive longest blocks. */
    fun ratio(a: String, b: String): Double {
        val t = a.length + b.length
        return if (t == 0) 1.0 else 2.0 * matching(a, 0, a.length, b, 0, b.length) / t
    }

    private fun matching(a: String, alo: Int, ahi: Int, b: String, blo: Int, bhi: Int): Int {
        if (alo >= ahi || blo >= bhi) return 0
        var bestI = alo; var bestJ = blo; var bestK = 0
        var prev = IntArray(bhi - blo + 1)
        for (i in alo until ahi) {
            val cur = IntArray(bhi - blo + 1)
            for (j in blo until bhi) {
                if (a[i] == b[j]) {
                    val k = prev[j - blo] + 1
                    cur[j - blo + 1] = k
                    if (k > bestK) { bestK = k; bestI = i - k + 1; bestJ = j - k + 1 }
                }
            }
            prev = cur // prev[j - blo + 1] = common-suffix run length ending at (i, j)
        }
        if (bestK == 0) return 0
        return bestK + matching(a, alo, bestI, b, blo, bestJ) + matching(a, bestI + bestK, ahi, b, bestJ + bestK, bhi)
    }

    private fun vset(a: JSONArray?): Set<String> {
        val s = HashSet<String>()
        if (a != null) for (i in 0 until a.length()) s += a.optString(i).uppercase()
        return s
    }

    class Z(var cat: Boolean = false, var item: Boolean = false, var lmk: Boolean = false, var urg: Boolean = false,
            var ppl: Boolean = false, var tp: Int = 0, var fp: Int = 0, var fn: Int = 0)

    private fun score(pred: JSONObject?, ok: Boolean?, exp: JSONObject): Z {
        val expV = vset(exp.optJSONArray("vuln"))
        val z = Z(fn = expV.size)
        if (pred == null || ok == false) return z
        z.cat = pred.optString("category").uppercase() == exp.optString("category").uppercase()
        z.item = similar(pred.optString("item"), exp.optString("item"))
        z.lmk = similar(pred.optString("landmark"), exp.optString("landmark"))
        z.urg = pred.has("urgency") && abs(pred.optInt("urgency", 99) - exp.optInt("urgency", -99)) <= 1
        z.ppl = pred.has("people") && pred.optInt("people", -1) == exp.optInt("people", -2)
        val pv = vset(pred.optJSONArray("vuln"))
        z.tp = pv.intersect(expV).size; z.fp = (pv - expV).size; z.fn = (expV - pv).size
        return z
    }

    private class Pick(val pred: JSONObject?, val ok: Boolean?, val total: Long?, val first: Long?)

    private fun pick(row: JSONObject, system: String): Pick {
        if (system == "rule") {
            val d = row.optJSONObject("rule")
            return Pick(d, null, d?.optDouble("latency_ms")?.toLong(), null)
        }
        val g = row.optJSONObject(system) ?: return Pick(null, false, null, null)
        val lat = g.optJSONObject("latency_ms")
        val parsed = g.optJSONObject("parsed")
        val usable = if (g.optBoolean("json_found") || parsed != null) parsed else null
        return Pick(usable, g.optBoolean("valid"),
            if (lat?.has("total") == true) lat.optLong("total") else null,
            if (lat?.has("first_token") == true) lat.optLong("first_token") else null)
    }

    private fun pct(v: List<Long>, p: Int): String {
        if (v.isEmpty()) return "-"
        val s = v.sorted()
        return s[maxOf(0, ceil(p / 100.0 * s.size).toInt() - 1)].toString()
    }

    private fun diff(row: JSONObject, p: Pick, exp: JSONObject): List<String> {
        if (p.pred == null || p.ok == false) {
            val g = row.optJSONObject("gemma_audio")
            val iss = g?.optJSONArray("issues")
            val why = if (iss != null && iss.length() > 0) iss.optString(0) else (g?.optString("error") ?: "no JSON")
            return listOf("INVALID ($why)")
        }
        val z = score(p.pred, p.ok, exp)
        val o = ArrayList<String>()
        if (!z.cat) o += "cat ${exp.optString("category")}->${p.pred.optString("category")}"
        if (!z.urg) o += "urg ${exp.optInt("urgency")}->${p.pred.opt("urgency")}"
        if (!z.ppl) o += "ppl ${exp.optInt("people")}->${p.pred.opt("people")}"
        if (z.fp > 0 || z.fn > 0) o += "vuln ${vset(exp.optJSONArray("vuln")).sorted()}->${vset(p.pred.optJSONArray("vuln")).sorted()}"
        if (!z.item) o += "item '${exp.optString("item")}'->'${p.pred.optString("item")}'"
        if (!z.lmk) o += "lmk '${exp.optString("landmark")}'->'${p.pred.optString("landmark")}'"
        return o
    }

    fun readRows(results: File): List<JSONObject> =
        results.readLines().map { it.trim() }.filter { it.isNotEmpty() }.map { JSONObject(it) }.filter { !it.has("fatal") }

    /** Plain-text tables (monospace-friendly) + misses for the audio system. */
    fun build(rows: List<JSONObject>): String {
        if (rows.isEmpty()) return "no rows"
        val sb = StringBuilder()
        val m = rows[0]
        sb.append("clips=${rows.size} backend=${m.optString("backend")} load=${m.opt("model_load_ms")} ms\n")
        val systems = listOf("gemma_audio" to "Gemma on AUDIO", "gemma_text" to "Gemma on reference TEXT", "rule" to "RuleExtractor on TEXT")
        for ((key, title) in systems) {
            val by = java.util.TreeMap<String, MutableList<JSONObject>>()
            for (r in rows) {
                if (key == "gemma_audio" && r.optString("file").isEmpty()) continue
                if (key != "gemma_audio" && r.optString("reference_transcript").isEmpty()) continue
                by.getOrPut(r.optString("lang", "?")) { ArrayList() } += r
            }
            if (by.isEmpty()) continue
            sb.append("\n== $title\n")
            sb.append(String.format("%-4s %3s %5s %5s %5s %5s %5s %6s %5s %6s\n",
                "lang", "n", "cat", "item", "lmk", "urg", "ppl", "vulnF1", "valid", "p50ms"))
            val all = by.values.flatten()
            val groups = by.entries.map { it.key to it.value } + ("ALL" to all)
            for ((lang, rs) in groups) {
                val n = rs.size
                var cat = 0; var item = 0; var lmk = 0; var urg = 0; var ppl = 0; var valid = 0
                var tp = 0; var fp = 0; var fn = 0
                val tot = ArrayList<Long>()
                for (r in rs) {
                    val p = pick(r, key)
                    if (p.ok == true) valid++
                    p.total?.let { tot += it }
                    val z = score(p.pred, p.ok, r.optJSONObject("expected") ?: JSONObject())
                    if (z.cat) cat++; if (z.item) item++; if (z.lmk) lmk++; if (z.urg) urg++; if (z.ppl) ppl++
                    tp += z.tp; fp += z.fp; fn += z.fn
                }
                val d = 2 * tp + fp + fn
                val f1 = if (d == 0) "n/a" else String.format("%.2f", 2.0 * tp / d)
                sb.append(String.format("%-4s %3d %5s %5s %5s %5s %5s %6s %5s %6s\n", lang, n,
                    "$cat/$n", "$item/$n", "$lmk/$n", "$urg/$n", "$ppl/$n", f1,
                    if (key == "rule") "n/a" else "$valid/$n", pct(tot, 50)))
            }
            if (key == "gemma_audio") {
                val misses = all.mapNotNull { r ->
                    val d = diff(r, pick(r, key), r.optJSONObject("expected") ?: JSONObject())
                    if (d.isEmpty()) null else "${r.optString("file").ifEmpty { "#" + r.optInt("index") }}: ${d.joinToString(" | ")}"
                }
                sb.append("\nmisses (audio): ${misses.size}/${all.size}\n")
                misses.forEach { sb.append("  ").append(it).append('\n') }
            }
        }
        return sb.toString()
    }
}
