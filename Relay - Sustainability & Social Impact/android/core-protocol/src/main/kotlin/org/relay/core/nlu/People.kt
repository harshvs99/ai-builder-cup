package org.relay.core.nlu

/** Head-count extraction: digits or number words (en/hi/or/tl/ceb/war) next to a people noun. */
internal object People {
    private val numberWords: Map<String, Int> = buildMap {
        fun add(v: Int, vararg ws: String) { for (w in ws) put(w, v) }
        add(1, "one", "ek", "एक", "isa", "usa", "eka", "ଏକ", "gote", "ଜଣେ")
        add(2, "two", "do", "दो", "dalawa", "duha", "dui", "ଦୁଇ", "duita", "दोनों")
        add(3, "three", "teen", "तीन", "tatlo", "tulo", "tini", "ତିନି", "tinita")
        add(4, "four", "char", "chaar", "चार", "apat", "upat", "chari", "ଚାରି")
        add(5, "five", "paanch", "panch", "पांच", "पाँच", "lima", "pancha", "ପାଞ୍ଚ")
        add(6, "six", "chhe", "chhah", "chah", "छह", "छः", "anim", "unom", "chha", "ଛଅ")
        add(7, "seven", "saat", "सात", "pito", "sata", "ସାତ")
        add(8, "eight", "aath", "आठ", "walo", "atha", "ଅଠ")
        add(9, "nine", "nau", "नौ", "siyam", "nua", "ନଅ")
        add(10, "ten", "das", "दस", "sampu", "napulo", "dasa", "ଦଶ")
        add(11, "eleven", "gyarah", "ग्यारह")
        add(12, "twelve", "barah", "बारह")
        add(15, "fifteen", "pandrah", "पंद्रह")
        add(20, "twenty", "bees", "बीस")
    }

    private val numAlt = numberWords.keys.sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }

    private val groupUnits = setOf(
        "adults", "adult", "children", "child", "kids", "kid", "men", "women", "babies", "elders",
        "bachche", "bachhe", "bacche", "bachcha", "बच्चे", "बच्चों", "bata", "anak",
    )

    private const val UNITS = "people|persons?|adults?|children|child|kids?|members?|men|women|babies|elders|souls|" +
        "log|logon|logo|लोग|लोगों|जन|आदमी|सदस्य|सदस्यों|bachche|bachhe|bacche|bachcha|बच्चे|बच्चों|" +
        "ଜଣ|ଲୋକ|jane|jana|lok|loka|pila|" +
        "tao|katao|tawo|bata|anak|miyembro|kabataan"

    private val unitRegex = Regex(
        "(?<!$W)(\\p{Nd}{1,3}|$numAlt)(?:ng)?(?:\\s+na)?(?:\\s+ka)?(?:\\s+mga)?\\s+(?:more\\s+)?($UNITS)(?!$W)"
    )
    private val familyOf = Regex("(?<!$W)family\\s+of\\s+(\\p{Nd}{1,3}|$numAlt)(?!$W)")
    private val weAre = Regex(
        "(?<!$W)(?:we\\s+are|we're|there\\s+are|total|hum|ham|kami\\s+ay|kami|mi|amo|ame|amey)\\s+(\\p{Nd}{1,3}|$numAlt)" +
            "(?!\\s*(?:years?|yrs?|saal|sal|din|days?|hours?|ghante|taon|anyos|tuig))(?!$W)"
    )
    private val ofUs = Regex(
        "(?<!$W)(\\p{Nd}{1,3}|$numAlt)\\s+(?:of\\s+us|kami|mi|sila|tayo|kita|ham|hum|ame|amey)(?!$W)"
    )
    private val plural = Lex(
        "we", "us", "our", "we're", "family", "families", "everyone", "all of us", "children", "kids", "mga bata",
        "hum", "ham", "hamare", "humare", "humko", "hamko", "hamara", "parivar*", "परिवार*", "परिजन*", "हम", "हमारे",
        "हमें", "हमको", "ଆମେ", "ଆମକୁ", "ଆମର", "ପରିବାର*", "kami", "namin", "amin", "amon", "pamilya*", "kita", "tayo",
        "mi", "among", "atong", "amo",
    )

    private fun valueOf(tok: String): Int =
        numberWords[tok] ?: Lexicons.digits(tok)

    /** Number of people the text speaks about; 1 if unstated, 2 if only a plural subject is stated. */
    fun count(text: String): Int {
        val unitMatches = unitRegex.findAll(text).map { valueOf(it.groupValues[1]) to it.groupValues[2] }.toList()
        var best = 0
        if (unitMatches.size >= 2 && unitMatches.all { it.second in groupUnits }) {
            best = unitMatches.sumOf { it.first }
        } else {
            for ((v, _) in unitMatches) best = maxOf(best, v)
        }
        for (m in familyOf.findAll(text)) best = maxOf(best, valueOf(m.groupValues[1]))
        for (m in weAre.findAll(text)) best = maxOf(best, valueOf(m.groupValues[1]))
        for (m in ofUs.findAll(text)) best = maxOf(best, valueOf(m.groupValues[1]))
        if (best in 1..255) return best
        if (best > 255) return 255
        return if (plural.matches(text)) 2 else 1
    }
}
