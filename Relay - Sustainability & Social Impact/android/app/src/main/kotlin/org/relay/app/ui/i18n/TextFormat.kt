package org.relay.app.ui.i18n

import org.relay.core.payload.Lang
import java.time.Instant
import java.time.ZoneId

/** Picks the text for [lang]. Cebuano has no UI text yet and shows English. */
fun tr(lang: Lang, en: String, hi: String, or: String, tl: String, war: String): String = when (lang) {
    Lang.HI -> hi
    Lang.OR -> or
    Lang.TL -> tl
    Lang.WAR -> war
    Lang.EN, Lang.CEB -> en
}

/**
 * Text for a string added after the language freeze: English and Hindi only. Hindi is a draft for native review; Odia,
 * Filipino and Waray (hidden, pending review) show the English text rather than an invented translation.
 */
fun trEnHi(lang: Lang, en: String, hi: String): String = if (lang == Lang.HI) hi else en

/** Plural rule of the app languages, for "1 person" / "N people". Pure. */
object Plural {
    /** True when [n] takes the singular form. Hindi treats 0 as singular too; the rest are n == 1. */
    fun isOne(lang: Lang, n: Int): Boolean = when (lang) {
        Lang.HI -> n == 0 || n == 1
        else -> n == 1
    }

    fun pick(lang: Lang, n: Int, one: String, other: String): String = if (isOne(lang, n)) one else other
}

/** How long ago something happened, as a language-neutral bucket. Pure; [CommonStrings.ago] words it. */
object RelativeTime {
    sealed interface Bucket {
        data object JustNow : Bucket
        data class Minutes(val n: Int) : Bucket
        data class Hours(val n: Int) : Bucket
        data object Yesterday : Bucket
        data class Days(val n: Int) : Bucket
    }

    /**
     * Under a minute is JustNow, under an hour Minutes, the same calendar day Hours, the previous calendar day
     * Yesterday, then Days. A [thenMs] in the future counts as JustNow (clock skew).
     */
    fun bucket(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Bucket {
        val diff = nowMs - thenMs
        if (diff < 60_000L) return Bucket.JustNow
        if (diff < 3_600_000L) return Bucket.Minutes((diff / 60_000L).toInt())
        val thenDay = Instant.ofEpochMilli(thenMs).atZone(zone).toLocalDate()
        val nowDay = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val days = nowDay.toEpochDay() - thenDay.toEpochDay()
        return when {
            days <= 0L -> Bucket.Hours((diff / 3_600_000L).toInt())
            days == 1L -> Bucket.Yesterday
            else -> Bucket.Days(days.toInt())
        }
    }
}
