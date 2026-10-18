package org.relay.app.gemma

import org.json.JSONObject
import org.relay.core.intake.ModelFields
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.Utf8
import org.relay.core.payload.VulnFlag

/**
 * A validated model reply. [valid] = a JSON object with all nine keys and legal enum/numeric values. Clamps and byte
 * truncations go in [repairs] and do not make a reply invalid; [issues] says why it is not valid.
 */
data class ParsedNeed(
    val valid: Boolean,
    val jsonFound: Boolean,
    val issues: List<String>,
    val repairs: List<String>,
    val transcript: String,
    val lang: Lang,
    val category: Category,
    val urgency: Int,
    val people: Int,
    val vuln: Set<VulnFlag>,
    val item: String,
    val landmark: String,
    val summary: String,
) {
    /** Fields for `FieldMerge`. People 0 means "unknown" and becomes null; an invalid reply gives nothing. */
    fun toModelFields(): ModelFields? =
        if (!jsonFound || !valid) null
        else ModelFields(
            category = category.takeIf { it != Category.OTHER }, item = item, landmark = landmark, urgency = urgency,
            people = people.takeIf { it > 0 }, vulnFlags = vuln, summary = summary,
        )
}

/** Parses the one JSON object Gemma is asked for. Port of `ml/voice-spike/.../NeedJson.kt`, typed to the app's enums. */
object GemmaNeedParser {
    private val REQUIRED = listOf("transcript", "lang", "category", "urgency", "people", "vuln", "item", "landmark", "summary")

    /** First balanced {...} object in [raw] after stripping code fences; string/escape aware. */
    fun extractJsonObject(raw: String): String? {
        val s = raw.replace("```json", "").replace("```JSON", "").replace("```", "")
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return s.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun empty(issues: List<String>, jsonFound: Boolean) = ParsedNeed(
        valid = false, jsonFound = jsonFound, issues = issues, repairs = emptyList(), transcript = "", lang = Lang.EN,
        category = Category.OTHER, urgency = 1, people = 0, vuln = emptySet(), item = "", landmark = "", summary = "",
    )

    private fun Any?.toNumber(): Double? = when (this) {
        is Number -> toDouble()
        is String -> trim().toDoubleOrNull()
        else -> null
    }

    fun parse(raw: String): ParsedNeed {
        val text = extractJsonObject(raw) ?: return empty(listOf("no JSON object in output"), false)
        val o = try { JSONObject(text) } catch (e: Exception) {
            return empty(listOf("JSON parse error: ${e.message?.take(80)}"), true)
        }
        val issues = ArrayList<String>()
        val repairs = ArrayList<String>()
        for (k in REQUIRED) if (!o.has(k) || o.isNull(k)) issues += "missing key $k"

        val langRaw = o.optString("lang", "").trim().lowercase()
        val lang = Lang.fromTag(langRaw) ?: run { if (o.has("lang")) issues += "bad lang '$langRaw'"; Lang.EN }

        val catRaw = o.optString("category", "").trim().uppercase().replace(' ', '_')
        val cat = Category.entries.firstOrNull { it.name == catRaw }
        if (o.has("category") && cat == null) issues += "bad category '$catRaw'"

        val urgNum = o.opt("urgency").toNumber()
        if (o.has("urgency") && urgNum == null) issues += "urgency not numeric"
        val urgRaw = urgNum?.toInt() ?: 1
        val urgency = urgRaw.coerceIn(0, 3)
        if (urgNum != null && urgency != urgRaw) repairs += "urgency clamped $urgRaw->$urgency"

        val pplNum = o.opt("people").toNumber()
        if (o.has("people") && pplNum == null) issues += "people not numeric"
        val pplRaw = pplNum?.toInt() ?: 0
        val people = pplRaw.coerceIn(0, 255)
        if (pplNum != null && people != pplRaw) repairs += "people clamped $pplRaw->$people"

        val vuln = LinkedHashSet<VulnFlag>()
        val va = o.optJSONArray("vuln")
        if (o.has("vuln") && va == null) issues += "vuln not an array"
        if (va != null) for (i in 0 until va.length()) {
            val v = va.optString(i, "").trim().uppercase()
            val f = VulnFlag.entries.firstOrNull { it.name == v }
            if (f != null) vuln += f else issues += "bad vuln '$v'"
        }

        fun cap(key: String, maxBytes: Int): String {
            val s = o.optString(key, "").trim()
            val t = Utf8.truncate(s, maxBytes)
            if (t != s) repairs += "$key truncated to $maxBytes bytes"
            return t
        }
        return ParsedNeed(
            valid = issues.isEmpty(), jsonFound = true, issues = issues, repairs = repairs,
            transcript = o.optString("transcript", "").trim(), lang = lang, category = cat ?: Category.OTHER,
            urgency = urgency, people = people, vuln = vuln, item = cap("item", 16), landmark = cap("landmark", 24),
            summary = cap("summary", 40),
        )
    }
}
