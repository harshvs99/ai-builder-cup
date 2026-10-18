package org.relay.core.nlu

import org.relay.core.payload.Utf8

/** Landmark phrase extraction and normalisation to short English-ish tokens. */
internal object Landmarks {

    private val canonical: List<Pair<String, Lex>> = listOf(
        "cyclone shelter" to Lex("~cyclone\\s+shelter\\w*", "साइक्लोन शेल्टर*", "ସାଇକ୍ଲୋନ ଶେଲ୍ଟର*"),
        "police station" to Lex("police*", "thana*", "थाना*", "ଥାନା*", "pulis*"),
        "bus stand" to Lex("~bus\\s+(?:stand|stop|station)", "बस स्टैंड*", "ବସ ଷ୍ଟାଣ୍ଡ*", "ବସ୍ ଷ୍ଟାଣ୍ଡ*"),
        "post office" to Lex("post office", "dak ghar", "डाकघर*"),
        "panchayat office" to Lex("panchayat*", "पंचायत*", "ପଞ୍ଚାୟତ*", "block office", "bdo office"),
        "barangay hall" to Lex("barangay*", "brgy*", "kapitolyo*"),
        "town hall" to Lex("town hall", "city hall", "municipal*", "munisipyo*"),
        "anganwadi" to Lex("anganwadi*", "आंगनवाड़ी*", "ଅଙ୍ଗନୱାଡ଼ି*"),
        "gurudwara" to Lex("gurudwara*", "gurdwara*", "गुरुद्वारा*", "गुरुद्वारे*", "ଗୁରୁଦ୍ୱାରା*"),
        "school" to Lex("school*", "paaralan*", "eskwela*", "eskwelahan*", "iskwela*", "eskuylahan*", "skul*", "skool*",
            "स्कूल*", "विद्यालय*", "पाठशाला*", "pathshala*", "vidyalaya*", "bidyalaya*", "ବିଦ୍ୟାଳୟ*", "ସ୍କୁଲ*", "madrasa*"),
        "college" to Lex("college*", "कॉलेज*", " କଲେଜ*"),
        "dispensary" to Lex("dispensary", "औषधालय*", "ଔଷଧାଳୟ*"),
        "hospital" to Lex("hospital*", "ospital*", "aspatal*", "haspatal*", "haspital*", "अस्पताल*", "ହସ୍ପିଟାଲ*",
            "ହାସ୍ପାତାଲ*", "ଡାକ୍ତରଖାନା*", "clinic*", "~health\\s+cent\\w*", "phc", "chc"),
        "temple" to Lex("temple*", "mandir*", "mandira*", "मंदिर*", "ମନ୍ଦିର*", "gurudwara*"),
        "church" to Lex("church*", "simbahan*", "iglesia*", "gereja*", "चर्च*", "ଗୀର୍ଜା*"),
        "mosque" to Lex("mosque*"),
        "masjid" to Lex("masjid*", "मस्जिद*", "ମସଜିଦ*"),
        "river" to Lex("river*", "nadi", "nadee", "नदी*", "ନଦୀ*", "ilog*", "suba", "creek*", "stream*"),
        "canal" to Lex("canal*", "nala*", "नाला*", "ନାଳ*"),
        "pond" to Lex("pond*", "lake*", "talab*", "तालाब*", "ପୋଖରୀ*"),
        "market" to Lex("market*", "bazaar*", "bazar*", "बाजार*", "बाज़ार*", "ବଜାର*", "palengke*", "merkado*"),
        "shop" to Lex("shop*", "dukan*", "दुकान*", "tindahan*", "store"),
        "bridge" to Lex("bridge*", "pul", "पुल*", "ସେତୁ*", "setu", "tulay*", "taytay*"),
        "railway station" to Lex("~rail(?:way)?\\s+station", "train station", "रेलवे स्टेशन*", "station*", "स्टेशन*", "ଷ୍ଟେସନ*"),
        "hand pump" to Lex("hand ?pump*", "tube ?well*", "borewell*", "हैंडपंप*", "ହ୍ୟାଣ୍ଡପମ୍ପ*"),
        "petrol pump" to Lex("petrol*", "gas station*", "पेट्रोल*", "पेट्रोल पंप*", "पेट्रोल पम्प*", "ପେଟ୍ରୋଲ*"),
        "water tank" to Lex("~(?:(?:water|pani|paani|पानी|ପାଣି)\\s+(?:ki\\s+|ka\\s+|की\\s+|का\\s+)?)?(?:tank|tanki|टंकी|टैंक|ଟାଙ୍କି)\\S*"),
        "tree" to Lex("tree*", "banyan*", "peepal*", "बरगद*", "पेड़*", "पेड*", "ଗଛ*", "gachh*", "puno*", "kahoy*"),
        "well" to Lex("well", "कुआँ*", "कुआ", "kuan*"),
        "road" to Lex("road*", "highway*", "sadak*", "सड़क*", "सडक*", "ସଡ଼କ*", "kalsada*", "karsada*", "dalan*"),
        "ground" to Lex("ground*", "playground*", "maidan*", "मैदान*"),
        "bank" to Lex("bank*"),
        "tower" to Lex("tower*"),
    )

    private const val PREP_EN =
        "near(?:by)?(?:\\s+to)?(?:\\s+the)?|beside(?:\\s+the)?|behind(?:\\s+the)?|next\\s+to(?:\\s+the)?|" +
            "close\\s+to(?:\\s+the)?|opposite(?:\\s+(?:to\\s+)?the)?|in\\s+front\\s+of(?:\\s+the)?|at\\s+the|by\\s+the|" +
            "around\\s+the|across\\s+(?:from\\s+)?the|(?:away|wandered|went|came)\\s+(?:away\\s+)?from(?:\\s+the)?"
    private const val PREP_TL =
        "malapit\\s+(?:po\\s+)?sa|sa\\s+tabi\\s+ng|sa\\s+likod\\s+ng|sa\\s+harap\\s+ng"
    private const val PREP_CEB =
        "duol(?:\\s+(?:kaayo|lang))?\\s+sa|dapit\\s+sa|tupad\\s+sa|sa\\s+luyo\\s+sa|atbang\\s+sa"
    private const val PREP_WAR =
        "harani(?:\\s+(?:ha|han|hit))?|higad\\s+(?:ha|han)|luyo\\s+(?:ha|han)|atubang\\s+(?:ha|han)"

    private val prefixRegex = Regex(
        "(?<!$W)(?:$PREP_EN|$PREP_TL|$PREP_CEB|$PREP_WAR)\\s+([^,.;:!?\\n]{1,60})"
    )

    private const val POST_HI =
        "(?:(?:ke|ki|ka)\\s+(?:paas|pass|pas|nazdeek|nazdik|najdik|pichhe|peeche|piche|saamne|samne|bagal|baju|aaspaas|aas\\s+paas)" +
            "|(?:के|की|का)\\s+(?:पास|नज़दीक|नजदीक|पीछे|सामने|बगल|आसपास|आस\\s*पास))"
    private const val POST_OR =
        "(?:(?:ra\\s+)?(?:pakhare|pakhe|pakhara|nikate|nikat|pachhare|pachhe|aagare)" +
            "|(?:ର\\s*)?(?:ପାଖରେ|ପାଖ|ନିକଟରେ|ନିକଟ|ପଛରେ|ଆଗରେ))"

    private val postRegex = Regex(
        "(?<!$W)((?:[\\p{L}\\p{M}\\p{N}']+\\s+){0,2}[\\p{L}\\p{M}\\p{N}']+)\\s+(?:$POST_HI|$POST_OR)(?!$W)"
    )

    private val stop = setOf(
        // en
        "and", "but", "we", "i", "please", "pls", "plz", "with", "since", "because", "there", "is", "are", "has",
        "have", "who", "which", "our", "my", "need", "needs", "help", "no", "not", "so", "then", "also", "asap",
        "urgent", "urgently", "it", "they", "he", "she", "can't", "cant", "cannot", "very", "too",
        "this", "since", "yesterday", "today", "tonight", "tomorrow", "last", "morning", "night", "when", "while", "after",
        "before", "for",
        // hinglish / odia
        "hum", "ham", "hai", "hain", "aur", "par", "pe", "mein", "me", "ko", "bahut", "mere", "meri", "mera",
        "hamare", "humare", "kripya", "ଆମେ", "ଏବଂ", "ଓ", "ମୋ",
        "और", "हम", "है", "हैं", "में", "पर", "को", "कृपया",
        // tl / ceb / war
        "at", "po", "kami", "namin", "ako", "ang", "may", "walang", "kasi", "pero", "mi", "ug", "og", "ni", "naa",
        "kay", "ngan", "kun", "mayda", "amon", "amo", "hi", "hiya", "tabang", "bulig", "tulong", "palihug", "paki",
    )

    private val fillers = stop + setOf("ke", "ki", "ka", "ra", "ର", "के", "की", "का", "the", "a", "an", "of")

    /** "we are" / "I stay" left dangling in front of a removed landmark phrase: a location statement, not a head-count. */
    private val locationSubject = Regex(
        "(?<!$W)(?:we|i|they)(?:'re|'m|\\s+(?:are|am|were|was|live|stay|stand|reside))" +
            "(?:\\s+(?:all|still|now|here|just|last|staying|stuck|waiting|currently))*\\s*$"
    )

    private val leadingArticles = setOf("the", "a", "an", "sa", "ng", "ha", "han", "hit", "ang")

    /**
     * Returns the normalised landmark ("" if none) and [norm] with the landmark phrase blanked out so
     * that place names such as "hospital" do not create a need for a hospital.
     */
    fun extract(norm: String): Pair<String, String> {
        prefixRegex.find(norm)?.let { m ->
            val grp = m.groups[1]!!
            val tokens = Regex("\\S+").findAll(grp.value).toList()
            val kept = ArrayList<MatchResult>()
            for (t in tokens) {
                if (kept.size >= 4) break
                if (t.value in stop) break
                kept += t
            }
            // drop leading articles/linkers
            while (kept.isNotEmpty() && kept.first().value in leadingArticles) kept.removeAt(0)
            if (kept.isNotEmpty()) {
                val phrase = kept.joinToString(" ") { it.value }
                val end = grp.range.first + kept.last().range.last + 1
                val before = locationSubject.replace(norm.substring(0, m.range.first), "")
                val blanked = before + " " + norm.substring(end)
                return normalizeLandmark(phrase, tailOnly = false) to blanked
            }
        }
        postRegex.find(norm)?.let { m ->
            val grp = m.groups[1]!!
            val phrase = grp.value
            val (lm, from) = tailLandmark(phrase)
            if (lm.isNotEmpty()) {
                // Blank only the landmark words: "hum paanch log gurudwara ke paas" must keep "paanch log".
                // A pronoun-only lead-in ("हम पेट्रोल पंप के पास हैं") is part of the location statement.
                val lead = phrase.substring(0, from).trim()
                val keep = if (lead.isEmpty() || lead.split(" ").all { it in fillers }) 0 else from
                val blanked = norm.substring(0, grp.range.first + keep) + " " + norm.substring(m.range.last + 1)
                return lm to blanked
            }
        }
        return "" to norm
    }

    /** Landmark of a "<place> ke paas" phrase and the offset in [phrase] where its words start. */
    private fun tailLandmark(phrase: String): Pair<String, Int> {
        for ((name, lex) in canonical) lex.find(phrase)?.let { return name to it.range.first }
        val words = Regex("\\S+").findAll(phrase).filter { it.value !in fillers }.toList().takeLast(2)
        if (words.isEmpty()) return "" to 0
        return Utf8.truncate(words.joinToString(" ") { it.value }.trim(), 24) to words.first().range.first
    }

    private fun normalizeLandmark(phrase: String, tailOnly: Boolean): String {
        for ((name, lex) in canonical) if (lex.matches(phrase)) return name
        var words = phrase.split(" ").filter { it.isNotEmpty() }
        if (tailOnly) {
            words = words.filter { it !in fillers }
            words = words.takeLast(2)
        } else {
            words = words.filter { it !in setOf("the", "a", "an") }
        }
        return Utf8.truncate(words.joinToString(" ").trim(), 24)
    }
}
