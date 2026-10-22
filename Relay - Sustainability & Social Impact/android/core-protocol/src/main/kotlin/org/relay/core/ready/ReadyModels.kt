package org.relay.core.ready

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The phone side of the Ready contract (`backend/app/ready.py`, contract v2 section 3). Parsing is lenient on purpose: a field the
 * backend adds later is ignored, a missing optional field is null or empty, and only a body without the fields the screens cannot do
 * without (district, version, shelters list, steps, plan id) is refused.
 */

/** `warning` of the pack. [severity] is `watch`, `warning` or `alert`; a value this build does not know is kept as is. */
data class ReadyWarning(
    val id: Long,
    val source: String,
    val severity: String,
    val headline: String,
    val issuedAt: Long,
    val expectedLandfallAt: Long?,
    val expiresAt: Long?,
)

/** One shelter of the pack. [demo] true = a placeholder list that no officer has verified (demo builds only). */
data class ReadyShelter(
    val id: String,
    val name: String,
    val nameEn: String,
    /** `cyclone_shelter`, `school`, `relief_camp`, `hospital` or `other`. */
    val kind: String,
    val lat: Double,
    val lon: Double,
    val capacity: Int?,
    val facilities: List<String>,
    val block: String?,
    val demo: Boolean,
)

/** `GET /v1/ready/pack`. */
data class ReadyPack(
    val district: String,
    val lang: String,
    val generatedAt: Long,
    /** Hash of the content except [generatedAt]: it changes exactly when the warning, the shelter list or the checklist does. */
    val version: String,
    val warning: ReadyWarning?,
    val shelters: List<ReadyShelter>,
    /** `published` (an officer verified the list) or `demo`. */
    val sheltersSource: String,
    val sheltersPublishedAt: Long?,
    val checklist: List<String>,
    val checklistLang: String,
) {
    val isPublished: Boolean get() = sheltersSource == "published"
}

data class NearestShelter(val id: String, val name: String, val distanceKm: Double)

/** `POST /v1/ready/plan`. [lang] is what the phone asked for, [stepsLang] what the steps are written in (they differ when that language is off). */
data class ReadyPlan(
    val planId: String,
    val lang: String,
    val stepsLang: String,
    val district: String,
    val steps: List<String>,
    /** `gemini` or `template`. */
    val source: String,
    val warningId: Long?,
    val nearestShelters: List<NearestShelter>,
    /** Relative path of the spoken plan (`audio/wav`, PCM16 mono 24 kHz), or null when the backend has no audio. */
    val audioUrl: String?,
)

/**
 * The household profile (contract section 3.2). Stays on the phone except as the anonymous flags sent to `/v1/ready/plan`.
 * [medicalNeeds] is free text typed by the person: at most 100 characters, never an identifier.
 */
data class Household(
    val people: Int = 1,
    val elderly: Boolean = false,
    val children: Boolean = false,
    val infants: Boolean = false,
    val pregnant: Boolean = false,
    val disability: Boolean = false,
    val medicalNeeds: String = "",
    val livestock: Boolean = false,
) {
    /** The profile with its limits applied: 1 to 20 people, medical text trimmed to 100 characters. */
    fun normalized(): Household = copy(people = people.coerceIn(MIN_PEOPLE, MAX_PEOPLE), medicalNeeds = medicalNeeds.trim().take(MAX_MEDICAL))

    /** The body of `POST /v1/ready/plan`'s `household` object. */
    fun toJson(): JSONObject = normalized().let { h ->
        JSONObject().put("people", h.people).put("elderly", h.elderly).put("children", h.children).put("infants", h.infants)
            .put("pregnant", h.pregnant).put("disability", h.disability).put("medical_needs", h.medicalNeeds).put("livestock", h.livestock)
    }

    /** Stable text that changes exactly when the plan would: the refresher fetches a new plan when it differs from the stored one. */
    val key: String get() = normalized().let { h ->
        "p${h.people}|e${h.elderly.bit}|c${h.children.bit}|i${h.infants.bit}|g${h.pregnant.bit}|d${h.disability.bit}|l${h.livestock.bit}|m:${h.medicalNeeds.lowercase()}"
    }

    private val Boolean.bit get() = if (this) 1 else 0

    companion object {
        const val MIN_PEOPLE = 1
        const val MAX_PEOPLE = 20
        const val MAX_MEDICAL = 100

        fun fromJson(json: String?): Household {
            if (json.isNullOrBlank()) return Household()
            return try {
                val o = JSONObject(json)
                Household(
                    o.optInt("people", 1), o.optBoolean("elderly"), o.optBoolean("children"), o.optBoolean("infants"),
                    o.optBoolean("pregnant"), o.optBoolean("disability"), o.optString("medical_needs", ""), o.optBoolean("livestock"),
                ).normalized()
            } catch (e: JSONException) {
                Household()
            }
        }
    }
}

object ReadyJson {
    fun parsePack(json: String?): ReadyPack? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val shelters = o.optJSONArray("shelters") ?: return null
            ReadyPack(
                district = o.getString("district"),
                lang = o.optString("lang", "en"),
                generatedAt = o.optLong("generated_at", 0),
                version = o.getString("version"),
                warning = o.optJSONObject("warning")?.let(::warning),
                shelters = (0 until shelters.length()).mapNotNull { i -> shelters.optJSONObject(i)?.let(::shelter) },
                sheltersSource = o.optString("shelters_source", "demo"),
                sheltersPublishedAt = o.longOrNull("shelters_published_at"),
                checklist = strings(o.optJSONArray("checklist")),
                checklistLang = o.optString("checklist_lang", "en"),
            )
        } catch (e: JSONException) {
            null
        }
    }

    fun parsePlan(json: String?): ReadyPlan? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val steps = strings(o.optJSONArray("steps"))
            if (steps.isEmpty()) return null
            val near = o.optJSONArray("nearest_shelters")
            ReadyPlan(
                planId = o.getString("plan_id"),
                lang = o.optString("lang", "en"),
                stepsLang = o.optString("steps_lang", o.optString("lang", "en")),
                district = o.optString("district", ""),
                steps = steps,
                source = o.optString("source", "template"),
                warningId = o.longOrNull("warning_id"),
                nearestShelters = if (near == null) emptyList() else (0 until near.length()).mapNotNull { i ->
                    near.optJSONObject(i)?.let { NearestShelter(it.optString("id"), it.optString("name"), it.optDouble("distance_km", 0.0)) }
                },
                audioUrl = o.stringOrNull("audio_url"),
            )
        } catch (e: JSONException) {
            null
        }
    }

    private fun warning(o: JSONObject): ReadyWarning? = try {
        ReadyWarning(
            id = o.getLong("id"), source = o.optString("source", ""), severity = o.optString("severity", "warning"),
            headline = o.optString("headline", ""), issuedAt = o.optLong("issued_at", 0),
            expectedLandfallAt = o.longOrNull("expected_landfall_at"), expiresAt = o.longOrNull("expires_at"),
        )
    } catch (e: JSONException) {
        null
    }

    private fun shelter(o: JSONObject): ReadyShelter? = try {
        ReadyShelter(
            id = o.getString("id"), name = o.getString("name"), nameEn = o.optString("name_en", o.getString("name")),
            kind = o.optString("kind", "other"), lat = o.getDouble("lat"), lon = o.getDouble("lon"),
            capacity = o.longOrNull("capacity")?.toInt(), facilities = strings(o.optJSONArray("facilities")),
            block = o.stringOrNull("block"), demo = o.optBoolean("demo", false),
        )
    } catch (e: JSONException) {
        null
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optString(i, "").takeIf { it.isNotBlank() } }

    private fun JSONObject.longOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null
    private fun JSONObject.stringOrNull(k: String): String? = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotBlank() } else null
}
