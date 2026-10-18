package org.relay.core.lang

import org.json.JSONArray
import org.relay.core.payload.Lang

/** One entry of `GET /v1/languages` (no auth). Unknown fields are ignored. */
data class ServerLanguage(val code: String, val englishName: String, val nativeName: String, val enabled: Boolean)

/** What the app shows for [stored] after the picker rules ran: [effective] is used for every string, [unavailable] is the stored one that is off. */
data class LanguageResolution(val effective: Lang, val unavailable: Lang?)

/**
 * Which languages the picker offers (contract v2 section 1). A language is offered only when it is enabled in BOTH the
 * app's build config and the latest `/v1/languages` response; with no response yet the build default (English, Hindi)
 * applies. English is always offered. Odia, Filipino and Waray keep their strings but stay hidden until a community
 * reviewer signs them off and the backend turns them on.
 */
object LanguageCatalog {
    /** First-run default and the set used before any server response: English and Hindi. */
    val BUILD_DEFAULT: List<Lang> = listOf(Lang.EN, Lang.HI)

    /** Parses a build config value such as `"en,hi"`; unknown codes are dropped and English is always present. */
    fun parseBuildLanguages(csv: String?): List<Lang> {
        val listed = (csv ?: "").split(',').mapNotNull { Lang.fromTag(it.trim()) }
        val all = if (listed.isEmpty()) BUILD_DEFAULT else listed
        return Lang.entries.filter { it == Lang.EN || it in all }
    }

    /** Parses the `/v1/languages` body; null when it is not the expected JSON array (so a bad cache falls back to the build default). */
    fun parseServer(json: String?): List<ServerLanguage>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val code = o.optString("code", "").trim().lowercase()
                if (code.isEmpty()) return@mapNotNull null
                ServerLanguage(code, o.optString("english_name", code), o.optString("native_name", code), o.optBoolean("enabled", false))
            }
        } catch (e: Exception) {
            null
        }
    }

    /** The languages to offer, in wire order. [server] null = no usable response yet. */
    fun offered(build: Collection<Lang>, server: List<ServerLanguage>?): List<Lang> {
        val inBuild = Lang.entries.filter { it == Lang.EN || it in build }
        if (server == null) return inBuild.filter { it in BUILD_DEFAULT }
        val on = server.filter { it.enabled }.mapNotNull { Lang.fromTag(it.code) }.toSet()
        return inBuild.filter { it == Lang.EN || it in on }
    }

    /** Falls back to English when the stored language is no longer offered. The stored choice itself is kept, so it returns if re-enabled. */
    fun resolve(stored: Lang, offered: List<Lang>): LanguageResolution =
        if (stored in offered) LanguageResolution(stored, null) else LanguageResolution(Lang.EN, stored)

    /** True when the "X is not available yet; showing English." line should be shown now: once per unavailable language. */
    fun shouldAnnounce(resolution: LanguageResolution, alreadyAnnounced: Lang?): Boolean =
        resolution.unavailable != null && resolution.unavailable != alreadyAnnounced
}
