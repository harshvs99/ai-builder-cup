package org.relay.core.nlu

import org.relay.core.payload.Lang

/** Script first (Odia / Devanagari), then keyword scoring for Latin-script text. */
internal object LangDetect {
    private fun set(vararg w: String) = w.toHashSet()

    // Function words / markers. Words shared between languages are listed in each (weight 1);
    // distinctive markers get weight 2 via strong sets.
    private val en = set("the", "my", "is", "are", "and", "with", "near", "need", "needs", "please", "we", "our", "has",
        "have", "of", "for", "from", "on", "in", "at", "am", "was", "there", "here", "his", "her", "this", "that",
        "not", "can't", "cant", "help", "stuck", "trapped", "people", "mother", "father", "to", "behind", "water",
        "food", "i", "me", "us", "it", "no", "children", "child", "baby", "house", "roof")
    private val hi = set("hai", "hain", "ho", "ka", "ki", "ke", "ko", "se", "mein", "mai", "main", "hum", "ham", "mera",
        "meri", "mere", "hamare", "humare", "hamara", "chahiye", "chahie", "nahi", "nahin", "madad", "bachao", "paas",
        "pas", "aur", "bahut", "abhi", "jaldi", "kripya", "raha", "rahi", "rahe", "gaya", "gayi", "gaye", "hua", "hui",
        "fase", "phase", "phansa", "par", "pe", "wala", "wali", "ghar", "log", "saath", "bimar", "baadh", "pani",
        "dawai", "khana", "chhat", "bhookh", "maa", "bhai", "behen", "bachche", "bachcha")
    private val orRoman = set("mo", "mora", "mu", "mun", "amara", "amaku", "mate", "mote", "darkar", "darakar", "achhi",
        "achhanti", "achhu", "pakhare", "nikate", "ku", "kana", "kemiti", "sahajya", "sahayya", "ausadha", "aushadha",
        "oushadha", "bhasi", "banya", "phasi", "phasichhanti", "phasichanti", "ghara", "gote", "duita", "jane", "pua",
        "jhia", "bapa", "bou", "aji", "bhoka", "khaibaku", "kheibaku", "paibaku", "diantu", "bachantu", "gala",
        "bhasigala", "pani", "ra", "nahi", "nahin", "bipad", "kichhi", "pina", "dina", "hela", "pilamane", "bhokila",
        "bhokilaa", "nikhoj", "upare", "aashray", "ashray", "chhata", "badhuchhi", "kamudichhi", "sapa", "pua", "phasiachhu")
    private val tlStrong = set("po", "kailangan", "namin", "tulong", "malapit", "nasa", "hindi", "ako", "pakitulong", "naipit",
        "nakulong", "baha", "sugatan", "gamot", "pagkain", "kasi", "dito", "ng", "mga", "ay", "paki", "ang", "walang",
        "lola", "lolo", "nanay", "tatay", "bata", "buntis", "kami", "sa", "may", "na", "tubig", "gutom", "ospital",
        "eskwelahan", "nagugutom", "saklolo", "sugat")
    private val cebStrong = set("kinahanglan", "palihug", "tabang", "duol", "naa", "dili", "wala", "gikinahanglan", "adunay",
        "kaon", "pagkaon", "nasamad", "nalumos", "nga", "tambal", "kay", "unsa", "mi", "og", "ug", "balay", "tigulang",
        "mga", "sa", "kami", "ako", "gutom", "tubig", "bata", "lola", "lolo", "atop", "ni", "unom", "duha", "tulo",
        "upat", "luwasa", "eskwelahan", "ospital", "gikan", "ta", "nag", "naay", "wala'y", "gipangita")
    private val warStrong = set("han", "hin", "harani", "bulig", "waray", "diri", "dire", "amon", "ngan", "kun", "mayda",
        "nawara", "hiya", "akon", "ha", "an", "kami", "nga", "kinahanglan", "tambal", "tubig", "gutom", "bata",
        "lola", "lolo", "nanay", "balay", "atop", "eskwelahan", "namon", "nahulog", "pagkaon", "hi", "hit", "hira",
        "ginbaha", "nasagpan", "ospital")

    // Distinctive subset used to separate tl / ceb / war from each other (weight 2).
    private val tlOnly = set("po", "kailangan", "namin", "tulong", "malapit", "nasa", "hindi", "pakitulong", "naipit",
        "nakulong", "sugatan", "gamot", "pagkain", "kasi", "dito", "ng", "ay", "paki", "walang", "nagugutom", "saklolo", "sugat")
    private val cebOnly = set("kinahanglan", "palihug", "tabang", "duol", "naa", "dili", "wala", "gikinahanglan", "adunay",
        "kaon", "pagkaon", "nasamad", "nalumos", "unsa", "og", "ug", "tigulang", "naay", "luwasa", "gipangita", "kay")
    private val warOnly = set("han", "hin", "harani", "bulig", "waray", "diri", "dire", "amon", "ngan", "kun", "mayda",
        "nawara", "hiya", "akon", "ha", "namon", "nasagpan", "ginbaha", "hira")

    fun detect(raw: String, norm: String, hint: Lang?): Lang {
        var odia = 0
        var deva = 0
        for (ch in raw) {
            when (ch.code) {
                in 0x0B00..0x0B7F -> odia++
                in 0x0900..0x097F -> deva++
            }
        }
        if (odia > 0 && odia >= deva) return Lang.OR
        if (deva > 0) return Lang.HI

        val tokens = Regex("[\\p{L}\\p{N}']+").findAll(norm).map { it.value }.toList()
        val score = HashMap<Lang, Int>()
        fun s(l: Lang, set: Set<String>, strong: Set<String> = emptySet()): Int {
            var v = 0
            for (t in tokens) {
                if (t in strong) v += 2 else if (t in set) v += 1
            }
            return v
        }
        score[Lang.EN] = s(Lang.EN, en)
        score[Lang.HI] = s(Lang.HI, hi)
        score[Lang.OR] = s(Lang.OR, orRoman)
        score[Lang.TL] = s(Lang.TL, tlStrong, tlOnly)
        score[Lang.CEB] = s(Lang.CEB, cebStrong, cebOnly)
        score[Lang.WAR] = s(Lang.WAR, warStrong, warOnly)

        val max = score.values.maxOrNull() ?: 0
        if (max == 0) return hint ?: Lang.EN
        if (hint != null && (score[hint] ?: 0) == max) return hint
        // Deterministic tie-break order: Cebuano before Waray (far more speakers), English first.
        val order = listOf(Lang.EN, Lang.HI, Lang.OR, Lang.TL, Lang.CEB, Lang.WAR)
        return order.first { score[it] == max }
    }
}
