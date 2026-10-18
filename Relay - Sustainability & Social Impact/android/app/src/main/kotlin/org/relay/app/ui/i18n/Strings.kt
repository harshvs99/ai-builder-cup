package org.relay.app.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf
import org.relay.core.payload.Lang

/**
 * All UI text for one app language, chosen in-app (Settings language), independent of the phone's locale.
 *
 * Each screen area owns one file with one class, so agents do not collide:
 * [CommonStrings] (shared), [HomeStrings], [AskStrings], [RequestStrings], [NetworkSettingsStrings].
 *
 * To add a string: open your area's file and add a `val` (or a `fun` for text with parameters) that calls
 * `tr(lang, en = ..., hi = ..., or = ..., tl = ..., war = ...)` with all five languages side by side, and mark
 * non-English text `// needs native review`. Strings added for contract v2 use [trEnHi]: Hindi is a draft, and Odia,
 * Filipino and Waray show English until a reviewer supplies text (those languages are hidden meanwhile). In a composable: `val s = LocalStrings.current; Text(s.home.title)`.
 */
class Strings(val lang: Lang) {
    val common = CommonStrings(lang)
    val home = HomeStrings(lang)
    val ask = AskStrings(lang)
    val request = RequestStrings(lang)
    val network = NetworkSettingsStrings(lang)
}

private val cache = HashMap<Lang, Strings>()

/** Strings for [lang]; one shared instance per language. */
fun stringsFor(lang: Lang): Strings = synchronized(cache) { cache.getOrPut(lang) { Strings(lang) } }

/** Provided by RelayRoot from the Settings language; English until then (previews, tests). */
val LocalStrings = staticCompositionLocalOf { stringsFor(Lang.EN) }

/**
 * The five languages that have string tables, with their native and English names (names are not translated).
 * Only English and Hindi are reviewed. Odia, Filipino and Waray keep their strings in every table (marked
 * `needs native review`) but are [PENDING_REVIEW]: the picker shows them only when the build config and the backend's
 * `/v1/languages` both switch them on (contract v2 section 1), so enabling one later is a config change.
 */
object AppLanguages {
    data class Entry(val lang: Lang, val nativeName: String, val englishName: String)

    val all: List<Entry> = listOf(
        Entry(Lang.EN, "English", "English"),
        Entry(Lang.HI, "हिन्दी", "Hindi"),
        Entry(Lang.OR, "ଓଡ଼ିଆ", "Odia"),
        Entry(Lang.TL, "Tagalog", "Filipino"),
        Entry(Lang.WAR, "Waray", "Waray-Waray"),
    )

    /** Languages whose strings no native speaker on the team has reviewed. */
    val PENDING_REVIEW: Set<Lang> = setOf(Lang.OR, Lang.TL, Lang.WAR)

    fun of(lang: Lang): Entry = all.firstOrNull { it.lang == lang } ?: all.first()

    /** The entries for [offered] (see [org.relay.core.lang.LanguageCatalog.offered]), in table order; a language without strings is skipped. */
    fun offered(offered: Collection<Lang>): List<Entry> = all.filter { it.lang in offered }
}
