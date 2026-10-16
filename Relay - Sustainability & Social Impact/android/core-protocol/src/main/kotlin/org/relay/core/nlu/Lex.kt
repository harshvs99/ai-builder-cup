package org.relay.core.nlu

import java.text.Normalizer
import java.util.Locale

internal const val W = "[\\p{L}\\p{M}\\p{N}]"

/** NFC + lowercase + apostrophe unification + whitespace collapse. */
internal fun normalizeText(s: String, maxChars: Int = 1200): String {
    val cut = if (s.length > maxChars) s.substring(0, maxChars) else s
    val nfc = Normalizer.normalize(cut, Normalizer.Form.NFC)
    return nfc.lowercase(Locale.ROOT)
        .replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')
        .replace(Regex("\\s+"), " ").trim()
}

private fun nfcLower(s: String) = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)

/**
 * A set of lexicon terms compiled into one regex with word boundaries that understand combining
 * marks (Devanagari / Odia matras are \p{M}). Term syntax:
 *  - plain: matched as a whole word / phrase (spaces match any whitespace run)
 *  - trailing `*`: prefix match (`bleed*` matches bleeding, bleeds)
 *  - leading `~`: raw regex fragment (still wrapped in the word-boundary guards)
 */
internal class Lex(vararg terms: String) {
    val regex: Regex

    init {
        val alts = terms.joinToString("|") { toAlt(it) }
        regex = Regex("(?<!$W)(?:$alts)(?!$W)")
    }

    fun find(text: String, from: Int = 0): MatchResult? = regex.find(text, from)
    fun matches(text: String): Boolean = regex.containsMatchIn(text)

    private fun toAlt(term: String): String {
        if (term.startsWith("~")) return "(?:" + nfcLower(term.substring(1)) + ")"
        return nfcLower(term).trim().split(Regex("\\s+")).joinToString("\\s+") { tok ->
            if (tok.endsWith("*")) Regex.escape(tok.dropLast(1)) + "$W*" else Regex.escape(tok)
        }
    }
}
