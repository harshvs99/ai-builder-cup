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
 * non-English text `// needs native review`. In a composable: `val s = LocalStrings.current; Text(s.home.title)`.
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

/** The five app languages with their native and English names (names are not translated). */
object AppLanguages {
    data class Entry(val lang: Lang, val nativeName: String, val englishName: String)

    val all: List<Entry> = listOf(
        Entry(Lang.EN, "English", "English"),
        Entry(Lang.HI, "हिन्दी", "Hindi"),
        Entry(Lang.OR, "ଓଡ଼ିଆ", "Odia"),
        Entry(Lang.TL, "Tagalog", "Filipino"),
        Entry(Lang.WAR, "Waray", "Waray-Waray"),
    )

    fun of(lang: Lang): Entry = all.firstOrNull { it.lang == lang } ?: all.first()
}
