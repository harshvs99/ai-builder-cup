package org.relay.voicespike

import org.json.JSONArray
import org.json.JSONObject
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.Utf8
import org.relay.core.payload.VulnFlag

/**
 * Result of parsing + validating a model reply.
 * [valid] = a JSON object was found with all required keys and legal enum/numeric values. Clamps and
 * byte-truncations are recorded in [repairs] and do NOT make an output invalid. [issues] lists why not valid.
 */
data class ParsedNeed(
    val valid: Boolean,
    val jsonFound: Boolean,
    val issues: List<String>,
    val repairs: List<String>,
    val transcript: String,
    val lang: String,
    val category: String,
    val urgency: Int,
    val people: Int,
    val vuln: List<String>,
    val item: String,
    val landmark: String,
    val summary: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("transcript", transcript).put("lang", lang).put("category", category)
        .put("urgency", urgency).put("people", people).put("vuln", JSONArray(vuln))
        .put("item", item).put("landmark", landmark).put("summary", summary)

    fun toExtraJson(): JSONObject = JSONObject()
        .put("valid", valid).put("json_found", jsonFound)
        .put("issues", JSONArray(issues)).put("repairs", JSONArray(repairs))
        .put("parsed", toJson())
}

object NeedJson {
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
        valid = false, jsonFound = jsonFound, issues = issues, repairs = emptyList(), transcript = "", lang = "",
        category = "OTHER", urgency = 1, people = 0, vuln = emptyList(), item = "", landmark = "", summary = "",
    )

    fun parse(raw: String): ParsedNeed {
        val objText = extractJsonObject(raw) ?: return empty(listOf("no JSON object in output"), false)
        val o = try { JSONObject(objText) } catch (e: Exception) {
            return empty(listOf("JSON parse error: ${e.message?.take(80)}"), true)
        }
        val issues = ArrayList<String>()
        val repairs = ArrayList<String>()
        for (k in REQUIRED) if (!o.has(k) || o.isNull(k)) issues += "missing key $k"

        val transcript = o.optString("transcript", "").trim()

        val langRaw = o.optString("lang", "").trim().lowercase()
        val lang = if (Lang.fromTag(langRaw) != null) langRaw else { if (o.has("lang")) issues += "bad lang '$langRaw'"; "en" }

        val catRaw = o.optString("category", "").trim().uppercase().replace(' ', '_')
        val catOk = Category.entries.any { it.name == catRaw }
        if (o.has("category") && !catOk) issues += "bad category '$catRaw'"
        val category = if (catOk) catRaw else "OTHER"

        val urgNum = o.opt("urgency").toNumber()
        if (o.has("urgency") && urgNum == null) issues += "urgency not numeric"
        val urgRaw = urgNum?.toInt() ?: 1
        val urgency = urgRaw.coerceIn(0, 3)
        if (urgNum != null && urgency != urgRaw) repairs += "urgency clamped $urgRaw->$urgency"

        val pplNum = o.opt("people").toNumber()
        if (o.has("people") && pplNum == null) issues += "people not numeric"
        val pplRaw = pplNum?.toInt() ?: 0
        val people = pplRaw.coerceIn(0, 255) // 0 = unknown (the prompt asks for an explicit count or 0)
        if (pplNum != null && people != pplRaw) repairs += "people clamped $pplRaw->$people"

        val vuln = LinkedHashSet<String>()
        val va = o.optJSONArray("vuln")
        if (o.has("vuln") && va == null) issues += "vuln not an array"
        if (va != null) for (i in 0 until va.length()) {
            val v = va.optString(i, "").trim().uppercase()
            if (VulnFlag.entries.any { it.name == v }) vuln += v else issues += "bad vuln '$v'"
        }

        fun cap(key: String, maxBytes: Int): String {
            val s = o.optString(key, "").trim()
            val t = Utf8.truncate(s, maxBytes)
            if (t != s) repairs += "$key truncated to $maxBytes bytes"
            return t
        }
        return ParsedNeed(
            valid = issues.isEmpty(), jsonFound = true, issues = issues, repairs = repairs, transcript = transcript,
            lang = lang, category = category, urgency = urgency, people = people, vuln = vuln.toList(),
            item = cap("item", 16), landmark = cap("landmark", 24), summary = cap("summary", 40),
        )
    }

    private fun Any?.toNumber(): Double? = when (this) {
        is Number -> this.toDouble()
        is String -> this.trim().toDoubleOrNull()
        else -> null
    }
}
