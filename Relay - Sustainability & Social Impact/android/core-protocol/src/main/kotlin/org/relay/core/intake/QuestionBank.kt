package org.relay.core.intake

import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.VulnFlag

/**
 * The fixed follow-up questions a dispatcher would ask, as plain data. Neither the rules nor a model ever writes a
 * question: they only choose ids from this bank, and the app shows the text below in the requester's language.
 *
 * Ids and answer values are wire values (SPEC 5.1 answer bytes) and must never be renumbered. Answer value 0 is
 * always "unknown". "How many people" is not a question here: it fills the NEED's `people` field.
 *
 * Non-English text is pending native-speaker review (like the ACK templates). Cebuano has no text yet and falls back
 * to English.
 */
data class AnswerOption(val value: Int, val key: String, private val labels: Map<Lang, String>) {
    fun label(lang: Lang): String = labels[lang] ?: labels.getValue(Lang.EN)
}

data class Question(
    val id: Int,
    /** stable name used in the backend / dashboard JSON (`answers: {"conscious": "no"}`) */
    val key: String,
    val categories: Set<Category>,
    val options: List<AnswerOption>,
    private val text: Map<Lang, String>,
) {
    fun text(lang: Lang): String = text[lang] ?: text.getValue(Lang.EN)
    fun option(value: Int): AnswerOption? = options.firstOrNull { it.value == value }
    fun optionByKey(key: String): AnswerOption? = options.firstOrNull { it.key == key }
}

object QuestionBank {
    /** At most this many answers fit in a NEED (SPEC 5.1). */
    const val MAX_ANSWERS = 3

    private fun labels(en: String, hi: String, or: String, tl: String, war: String) =
        mapOf(Lang.EN to en, Lang.HI to hi, Lang.OR to or, Lang.TL to tl, Lang.WAR to war)

    private val unknown = AnswerOption(0, "unknown", labels("Don't know", "पता नहीं", "ଜଣା ନାହିଁ", "Hindi alam", "Diri aram"))
    private val yes = AnswerOption(1, "yes", labels("Yes", "हाँ", "ହଁ", "Oo", "Oo"))
    private val no = AnswerOption(2, "no", labels("No", "नहीं", "ନା", "Hindi", "Diri"))
    private val yesNoUnknown = listOf(unknown, yes, no)

    private val roof = AnswerOption(1, "roof", labels("Roof", "छत", "ଛାତ", "Bubong", "Atop"))
    private val ground = AnswerOption(2, "ground", labels("Ground", "ज़मीन", "ଭୂମି", "Lupa", "Tuna"))

    private val howLong = listOf(
        unknown,
        AnswerOption(1, "today", labels("Since today", "आज से", "ଆଜିଠାରୁ", "Mula ngayon", "Tikang yana")),
        AnswerOption(2, "1-2 days", labels("1-2 days", "1-2 दिन", "1-2 ଦିନ", "1-2 araw", "1-2 ka adlaw")),
        AnswerOption(3, "3+ days", labels("3+ days", "3+ दिन", "3+ ଦିନ", "3+ araw", "3+ ka adlaw")),
    )

    val all: List<Question> = listOf(
        Question(1, "conscious", setOf(Category.MEDICAL), yesNoUnknown, labels(
            "Is the person awake and responding?", "क्या व्यक्ति होश में है और जवाब दे रहा है?",
            "ବ୍ୟକ୍ତି ହୋସରେ ଅଛନ୍ତି କି ଓ ଉତ୍ତର ଦେଉଛନ୍ତି କି?", "Gising ba at sumasagot ang tao?",
            "Nagmamata ba ngan nasagot an tawo?")),
        Question(2, "road_reachable", setOf(Category.MEDICAL), yesNoUnknown, labels(
            "Can a vehicle reach you by road?", "क्या गाड़ी सड़क से आप तक पहुँच सकती है?",
            "ରାସ୍ତାରେ ଗାଡ଼ି ଆପଣଙ୍କ ପାଖରେ ପହଞ୍ଚିପାରିବ କି?", "Maaabot ba kayo ng sasakyan sa kalsada?",
            "Maabot ba kamo han sakayan ha dalan?")),
        Question(3, "water_rising", setOf(Category.RESCUE), yesNoUnknown, labels(
            "Is the water still rising?", "क्या पानी अभी भी बढ़ रहा है?", "ପାଣି ଏବେ ବି ବଢୁଛି କି?",
            "Tumataas pa ba ang tubig?", "Nagtataas pa ba an tubig?")),
        Question(4, "roof_or_ground", setOf(Category.RESCUE), listOf(unknown, roof, ground), labels(
            "Are you on a roof or on the ground?", "आप छत पर हैं या ज़मीन पर?",
            "ଆପଣ ଛାତ ଉପରେ ଅଛନ୍ତି ନା ଭୂମିରେ?", "Nasa bubong ba kayo o nasa lupa?", "Ha atop ba kamo o ha tuna?")),
        Question(5, "children_or_elderly", setOf(Category.WATER, Category.FOOD), yesNoUnknown, labels(
            "Are there children or elderly people with you?", "क्या आपके साथ बच्चे या बुज़ुर्ग हैं?",
            "ଆପଣଙ୍କ ସାଙ୍ଗରେ ପିଲା କିମ୍ବା ବୃଦ୍ଧ ଲୋକ ଅଛନ୍ତି କି?", "May kasama ba kayong bata o matanda?",
            "May kaupod ba kamo nga bata o tigurang?")),
        Question(6, "how_long", setOf(Category.WATER, Category.FOOD), howLong, labels(
            "How long have you been without?", "आपको कितने समय से नहीं मिला है?", "କେତେ ଦିନ ହେଲା ପାଇ ନାହାନ୍ତି?",
            "Gaano na katagal kayong walang nakukuha?", "Pira na ka adlaw nga waray kamo nakakarawat?")),
        Question(7, "house_damaged", setOf(Category.SHELTER), yesNoUnknown, labels(
            "Is your house damaged?", "क्या आपका घर क्षतिग्रस्त है?", "ଆପଣଙ୍କ ଘର କ୍ଷତିଗ୍ରସ୍ତ ହୋଇଛି କି?",
            "Nasira ba ang inyong bahay?", "Naguba ba an iyo balay?")),
        Question(8, "last_seen_today", setOf(Category.MISSING_PERSON), yesNoUnknown, labels(
            "Was the person seen today?", "क्या व्यक्ति आज दिखाई दिया था?", "ବ୍ୟକ୍ତିଙ୍କୁ ଆଜି ଦେଖାଯାଇଥିଲା କି?",
            "Nakita ba ang tao ngayong araw?", "Nakita ba an tawo yana nga adlaw?")),
    )

    /** Largest id the 5-bit wire field can carry. */
    const val MAX_ID = 31

    private val byId = all.associateBy { it.id }
    private val byKey = all.associateBy { it.key }

    fun byId(id: Int): Question? = byId[id]
    fun byKey(key: String): Question? = byKey[key]

    /** The category's questions in asking order. OTHER has none. */
    fun forCategory(category: Category): List<Question> = all.filter { category in it.categories }

    /** True if [id] is a bank question and [value] one of its answers. */
    fun isValid(id: Int, value: Int): Boolean = byId[id]?.option(value) != null

    /**
     * Up to [MAX_ANSWERS] question ids for a request of [category], in asking order. Rules only: skips a question the
     * text already answered (children or elderly flagged by the parser).
     */
    fun select(category: Category, vulnFlags: Set<VulnFlag>): List<Int> =
        forCategory(category)
            .filterNot { it.key == "children_or_elderly" && (VulnFlag.CHILD in vulnFlags || VulnFlag.ELDERLY in vulnFlags) }
            .take(MAX_ANSWERS).map { it.id }
}
