package org.relay.core.triage

import org.relay.core.nlu.Lex
import org.relay.core.nlu.normalizeText

/**
 * Dangers a requester can mention in passing. They pick which advisories apply and whether the request needs triage at
 * all. Detection is rules only and errs towards "yes": a false hazard costs one extra safety line, a missed one costs more.
 */
enum class Hazard {
    NOT_BREATHING, UNCONSCIOUS, BLEEDING, SNAKE, LABOUR, ELECTRIC, FLOOD, TRAPPED, CYCLONE, LANDSLIDE;

    /** Dangers that route a request to triage even if the extractor called it something simpler. */
    val lifeThreatening: Boolean get() = this != FLOOD && this != CYCLONE
}

object HazardLexicon {
    private val lexicon: List<Pair<Hazard, Lex>> = listOf(
        Hazard.NOT_BREATHING to Lex(
            "~not\\s+breathing", "~(?:can'?t|cannot|unable\\s+to)\\s+breathe", "stopped breathing", "no breath", "gasping",
            "~s(?:aa|a)ns\\s+(?:nahi|nahin|ruk\\w*|band)", "~(?:सांस|साँस)\\s+(?:नहीं|रुक\\w*|बंद)", "~ଶ୍[ୱବ]ାସ\\s+(?:ନାହିଁ|ବନ୍ଦ)",
            "~hindi\\s+(?:maka-?hinga|humi-?hinga)"),
        Hazard.UNCONSCIOUS to Lex(
            "unconscious*", "passed out", "fainted", "not responding", "unresponsive", "behosh*", "behoosh*", "बेहोश*",
            "ବେହୋସ*", "ବେହୁସ*", "nahimatay*", "walang malay", "nawalan ng malay"),
        Hazard.BLEEDING to Lex(
            "bleed*", "blood*", "khoon*", "खून*", "रक्त*", "ରକ୍ତ*", "rakt*", "dugo*", "nagdudugo*", "nagdugo*"),
        Hazard.SNAKE to Lex(
            "snake*", "saanp*", "sanp*", "सांप*", "साँप*", "सर्प*", "ସାପ*", "ahas*", "bitin*", "tuklaw*", "natuklaw*"),
        Hazard.LABOUR to Lex(
            "labour*", "labor", "~in\\s+labor", "delivery pain*", "prasav*", "prasab*", "प्रसव*", "ପ୍ରସବ*", "manganak*",
            "nanganak*", "panganganak*", "contraction*"),
        Hazard.ELECTRIC to Lex(
            "electric*", "~live\\s+wire", "~(?:power|electric|light)\\s*(?:line|pole|post|wire|cable)s?", "pole", "poles", "wire", "wires",
            "short circuit", "bijli*", "bijali*", "बिजली*", "करंट*", "खंभा", "खम्भा", "तार", "ବିଜୁଳି*", "କରେଣ୍ଟ*",
            "ଖୁଣ୍ଟ*", "kuryente*", "poste*", "kawad*"),
        Hazard.FLOOD to Lex(
            "flood*", "water rising", "~water\\s+(?:is\\s+)?(?:rising|coming|entering)", "~rising\\s+water", "submerged", "inundat*",
            "baadh*", "badh", "बाढ़*", "बाढ*", "पानी बढ़*", "पानी भर*", "ବନ୍ୟା*", "ପାଣି ବଢ*", "baha", "bumabaha", "binaha", "tubig baha"),
        Hazard.TRAPPED to Lex(
            "trapped", "buried", "under debris", "under rubble", "under the debris", "under the rubble", "malba*", "मलबा*",
            "मलबे*", "दबे", "दबी", "दबा", "naipit*", "nakulong*"),
        Hazard.CYCLONE to Lex(
            "cyclone*", "storm*", "hurricane*", "typhoon*", "tufan*", "toofan*", "तूफ़ान*", "तूफान*", "चक्रवात*", "ଘୂର୍ଣ୍ଣିବାତ୍ୟା*", "ବାତ୍ୟା*",
            "bagyo*", "unos"),
        Hazard.LANDSLIDE to Lex(
            "landslide*", "mudslide*", "land slide", "~hill\\s*(?:slide|collapse|fell)", "~bhu-?skhalan", "भूस्खलन*", "पहाड़ गिर*", "pahad gir*",
            "ଭୂସ୍ଖଳନ*", "guho*", "pagguho*"),
    )

    /** Every hazard the text mentions, in [Hazard] declaration order. Never throws. */
    fun detect(text: String): Set<Hazard> {
        val t = try { normalizeText(text) } catch (_: Exception) { return emptySet() }
        if (t.isEmpty()) return emptySet()
        return lexicon.filter { (_, lex) -> lex.matches(t) }.mapTo(java.util.EnumSet.noneOf(Hazard::class.java)) { it.first }
    }
}
