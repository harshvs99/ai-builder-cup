package org.relay.core.nlu

import org.relay.core.payload.Category
import org.relay.core.payload.VulnFlag

/**
 * One thing a requester can be asking for / reporting. [context] concepts (flood, roof) only decide
 * the category when nothing more specific matched. When several concepts of the winning category match, the
 * item label comes from the most urgent [request] concept ("send a doctor"), else from the most urgent concept.
 */
internal class Concept(
    val id: String,
    val category: Category,
    val urgency: Int,
    val item: String?,
    val phrase: String,
    val flags: Set<VulnFlag> = emptySet(),
    val context: Boolean = false,
    /** True for something a person asks to be sent (medicine, water, an ambulance), as opposed to a condition. */
    val request: Boolean = false,
    val lex: Lex,
)

internal object Lexicons {

    private val none = emptySet<VulnFlag>()
    private val injured = setOf(VulnFlag.INJURED)
    private val trapped = setOf(VulnFlag.TRAPPED)
    private val pregnant = setOf(VulnFlag.PREGNANT)

    /** Tie-break between categories of equal urgency. */
    fun categoryRank(c: Category): Int = when (c) {
        Category.MEDICAL -> 0
        Category.RESCUE -> 1
        Category.MISSING_PERSON -> 2
        Category.WATER -> 3
        Category.FOOD -> 4
        Category.SHELTER -> 5
        Category.OTHER -> 6
    }

    fun defaultItem(c: Category): String = when (c) {
        Category.MEDICAL -> "medical help"
        Category.RESCUE -> "rescue"
        Category.WATER -> "water"
        Category.FOOD -> "food"
        Category.SHELTER -> "shelter"
        Category.MISSING_PERSON -> "missing person"
        Category.OTHER -> ""
    }

    // Order = tie-break priority within equal (urgency, category).
    val concepts: List<Concept> = listOf(
        // ---------------- MEDICAL, life threatening (3)
        Concept("insulin", Category.MEDICAL, 3, "insulin", "needs insulin", request = true, lex = Lex(
            "insulin*", "इंसुलिन*", "इन्सुलिन*", "ଇନସୁଲିନ*", "ইনসুলিন*")),
        Concept("oxygen", Category.MEDICAL, 3, "oxygen", "needs oxygen", request = true, lex = Lex(
            "oxygen*", "oxigen*", "oksijen*", "ऑक्सीजन*", "ओक्सीजन*", "ଅକ୍ସିଜେନ*")),
        Concept("not_breathing", Category.MEDICAL, 3, "first aid", "not breathing", lex = Lex(
            "~not\\s+breathing", "~(?:can'?t|cannot|unable\\s+to)\\s+breathe", "stopped breathing", "no breath",
            "~s(?:aa|a)ns\\s+(?:nahi|nahin|ruk\\w*|band)", "~(?:सांस|साँस)\\s+(?:नहीं|रुक\\w*|बंद)",
            "~hindi\\s+(?:maka-?hinga|humi-?hinga)", "~(?:dili|diri)\\s+(?:maka-?ginhawa|maka-?ginhawa)",
            "~ଶ୍[ୱବ]ାସ\\s+(?:ନାହିଁ|ବନ୍ଦ)")),
        Concept("unconscious", Category.MEDICAL, 3, "first aid", "unconscious", lex = Lex(
            "unconscious*", "passed out", "fainted", "faint*", "behosh*", "behoosh*", "bebosh*",
            "बेहोश*", "ବେହୋସ*", "ବେହୁସ*", "nahimatay*", "walang malay", "nawalan ng malay",
            "~nawad-?an\\s+og\\s+panimuot", "~wala(?:'y|y)?\\s+panimuot")),
        Concept("bleeding", Category.MEDICAL, 3, "first aid", "bleeding", injured, lex = Lex(
            "bleed*", "blood*", "khoon*", "खून*", "रक्त*", "ରକ୍ତ*", "rakt*", "dugo*", "nagdudugo*",
            "nagdugo*", "pagdugo*", "gidugo*")),
        Concept("snake", Category.MEDICAL, 3, "snake bite", "snake bite", lex = Lex(
            "snake*", "saanp*", "sanp*", "सांप*", "साँप*", "सर्प*", "sarp*", "ସାପ*", "sapa*", "ahas*", "bitin*",
            "halas*", "tuklaw*", "natuklaw*")),
        Concept("labour", Category.MEDICAL, 3, "delivery help", "in labour", pregnant, lex = Lex(
            "labour*", "labor", "~in\\s+labor", "delivery pain*", "~delivery\\s+(?:time|due|case)", "prasav*",
            "prasab*", "प्रसव*", "ପ୍ରସବ*", "manganak*", "manganganak*", "nanganak*", "nanganganak*", "panganganak*", "nagpanganak*",
            "pa-anak*")),
        Concept("critical", Category.MEDICAL, 3, "medical help", "critical condition", lex = Lex(
            "heart attack*", "stroke", "seizure*", "convuls*", "chest pain*", "dil ka daura", "दिल का दौरा",
            "हार्ट अटैक", "hemorrhag*", "haemorrhag*")),
        // ---------------- RESCUE, life threatening (3)
        Concept("drowning", Category.RESCUE, 3, "rescue", "drowning", lex = Lex(
            "drown*", "doob*", "~du+b(?:a|e|i|na|ne)\\w*", "डूब*", "डुब*", "ଡୁବ*", "lunod*", "nalunod*",
            "malunod*", "nalumos*", "malumos*", "lumos*")),
        Concept("trapped", Category.RESCUE, 3, "rescue", "trapped", trapped, lex = Lex(
            "trapped", "entrapped", "buried", "under debris", "under rubble", "under the debris",
            "under the rubble", "malba*", "मलबा*", "मलबे*", "naipit*", "nakulong*", "~na-?trap\\w*", "natrap*",
            "दबे", "दबी", "दबा", "~दब\\s+(?:गया|गई|गए|गये)", "~dab\\s+(?:gaya|gayi|gaye)", "~dabe\\s+(?:hain|hue|huye)")),
        // ---------------- MEDICAL, default (2)
        Concept("medicine", Category.MEDICAL, 2, "medicine", "needs medicine", request = true, lex = Lex(
            "medicine*", "medication*", "medicin*", "pills", "tablet*", "capsule*", "dawai*", "dawa", "dava",
            "davai*", "dawaai*", "दवा*", "दवाई*", "औषध*", "ଔଷଧ*", "ଔଷଦ*", "ausadh*", "aushadh*", "oushadh*",
            "ousadh*", "gamot*", "tambal*", "panambal*", "medisina*")),
        Concept("doctor", Category.MEDICAL, 2, "doctor", "needs doctor", request = true, lex = Lex(
            "doctor*", "doktor*", "duktor*", "nurse*", "paramedic*", "डॉक्टर*", "डाक्टर*", "ଡାକ୍ତର*", "daktar*",
            "dakter*", "dactor*")),
        Concept("ambulance", Category.MEDICAL, 2, "ambulance", "needs ambulance", request = true, lex = Lex(
            "ambulance*", "ambulans*", "एम्बुलेंस*", "एंबुलेंस*", "ଆମ୍ବୁଲାନ୍ସ*")),
        Concept("injured", Category.MEDICAL, 2, "first aid", "injured", injured, lex = Lex(
            "injur*", "hurt", "wound*", "fractur*", "~broken\\s+(?:leg|arm|bone|hand|foot|back)", "chot*", "चोट*",
            "ghayal*", "घायल*", "zakhmi*", "ଆହତ*", "ahat*", "aghat*", "ଆଘାତ*", "sugat*", "nasugat*", "samad*",
            "nasamad*", "nasamd*", "first aid")),
        Concept("pregnant", Category.MEDICAL, 2, "medical help", "pregnant", pregnant, context = true, lex = Lex(
            "pregnan*", "garbhavati*", "garbhwati*", "garbhini*", "गर्भवती*", "गर्भ*", "ଗର୍ଭବତୀ*", "ଗର୍ଭ*",
            "buntis*", "mabdos*")),
        Concept("fever", Category.MEDICAL, 2, "medicine", "fever", lex = Lex(
            "fever*", "bukhar*", "bukhaar*", "बुखार*", "ଜ୍ୱର*", "ଜ୍ବର*", "jwar*", "jwara*", "lagnat*",
            "nilalagnat*", "hilanat*", "gilantan*")),
        Concept("sick", Category.MEDICAL, 2, "medicine", "sick", lex = Lex(
            "sick*", "illness*", "unwell", "bimar*", "बीमार*", "ଅସୁସ୍ଥ*", "asustha*", "sakit*", "masakiton*",
            "diabet*", "asthma*", "dialysis", "diarr*", "cholera*", "vomit*", "dehydrat*", "dengue*", "malaria*")),
        Concept("hospital", Category.MEDICAL, 2, "medical help", "needs medical help", lex = Lex(
            "hospital*", "ospital*", "aspatal*", "haspatal*", "haspital*", "अस्पताल*", "ହସ୍ପିଟାଲ*", "ହାସ୍ପାତାଲ*",
            "medical*")),
        // ---------------- RESCUE, default (2)
        Concept("stuck", Category.RESCUE, 2, "rescue", "stuck", trapped, lex = Lex(
            "stuck", "stranded", "marooned", "~(?:can'?t|cannot)\\s+(?:get|come)\\s+out", "~can'?t\\s+leave",
            "fase", "phase", "phanse*", "fanse*", "fasa", "phansa", "fans*", "phans*", "फंस*", "फँस*", "फसे", "फसा",
            "फस", "ଫସି*", "phasi*")),
        Concept("collapsed", Category.RESCUE, 2, "rescue", "building collapsed", context = true, lex = Lex(
            "collapse*", "gir gaya", "gir gayi", "gir gaye", "~ghar\\s+gir\\w*", "ढह*", "गिर गया", "गिर गई",
            "गिर गये", "गिर गए", "bhangi*", "ଭାଙ୍ଗି*", "gumuho*", "guho*", "bumagsak*", "nagiba*", "nahugno*",
            "wasak*", "nawasak*")),
        Concept("boat", Category.RESCUE, 2, "boat", "needs boat", context = true, lex = Lex(
            "boat*", "bangka*", "naav*", "nav", "नाव*", "नौका*", "ନାଆ*", "ନୌକା*")),
        Concept("rescue", Category.RESCUE, 2, "rescue", "needs rescue", request = true, lex = Lex(
            "rescue*", "save us", "save me", "helicopter*", "chopper*", "bachao*", "bachaao*", "bacha lo",
            "bachaiye", "बचाओ*", "बचा लो", "बचाइए", "बचाव*", "ବଞ୍ଚାଅ*", "ବଞ୍ଚାନ୍ତୁ*", "bachantu*", "banchao*",
            "saklolo*", "sagip*", "luwas*", "ଉଦ୍ଧାର*", "uddhar*", "~(?:jaldi\\s+)?nikal(?:o|iye|na)", "~निकाल(?:ो|िए|ना)")),
        // ---------------- WATER / FOOD / SHELTER / MISSING (1)
        Concept("missing", Category.MISSING_PERSON, 1, "missing person", "missing person", lex = Lex(
            "missing", "~lost\\s+(?:my\\s+|our\\s+)?(?:child|son|daughter|mother|father|brother|sister|wife|husband|family|baby|grand\\w+)",
            "gayab*", "lapata*", "laapata*", "लापता*", "गायब*", "nikhoj*", "ନିଖୋଜ*", "ନିରୁଦ୍ଦେଶ*", "nawawala*",
            "nawala*", "hindi mahanap", "hinahanap*", "nawara*", "gipangita*",
            "~(?:looking|searching)\\s+for\\s+(?:my|our)\\s+(?:\\w+\\s+)?(?:child|son|daughter|mother|father|brother|sister|wife|husband|family|baby|grand\\w+|mom|dad|parents?|uncle|aunt|boy|girl|kid\\w*)",
            "~(?:can'?t|cannot|unable\\s+to)\\s+find\\s+(?:my|our|him|her|them)", "~went\\s+missing", "wander*",
            "~kho\\s+(?:gaya|gayi|gaye)", "khoya", "खो गया", "खो गई", "खो गये", "खो गए", "खोया", "खोई",
            "ढूँढ*", "ढूंढ*", "ढूढ*", "dhundh*", "~(?:nahi|nahin)\\s+laut\\w*", "~(?:नहीं|नही)\\s+लौट\\w*",
            "~वापस\\s+(?:नहीं|नही)", "ଖୋଜି*", "ଖୋଜୁ*")),
        Concept("water", Category.WATER, 1, "water", "needs water", request = true, lex = Lex(
            "~water(?!\\s+(?:is\\s+)?(?:rising|entering|level|coming|came|inside|up|everywhere|logging))",
            "drinking water", "thirst*", "pyaas*", "pyas*", "प्यास*", "~पानी(?!\\s+(?:भर|बढ|घुस|आ))",
            "~pa+ni(?!\\s+(?:bhar|badh|ghus|aa|aaya|aya))", "पेयजल", "~ପାଣି(?!\\s+(?:ବଢ|ଭର|ପଶ|ଆସ))", "ପାନୀୟ ଜଳ", "pina pani", "tubig*", "inom*", "uhaw*", "inumon*")),
        Concept("milk", Category.FOOD, 1, "milk", "needs milk", request = true, lex = Lex(
            "milk", "doodh*", "दूध*", "ଦୁଧ*", "gatas*")),
        Concept("food", Category.FOOD, 1, "food", "needs food", request = true, lex = Lex(
            "food*", "hungry", "hunger*", "starv*", "khana*", "khaana*", "khaane*", "खाना*", "खाने*", "भूख*",
            "bhookh*", "bhukh*", "ଖାଦ୍ୟ*", "ଖାଇବା*", "ଭୋକ*", "bhoka*", "khadya*", "khaddya*", "khaibaku*",
            "kheibaku*", "ration*", "rice", "chawal*", "चावल*", "biscuit*", "pagkain*", "gutom*", "nagugutom*",
            "nagutom*", "kaon*", "pagkaon*", "ginutom*")),
        Concept("blanket", Category.SHELTER, 1, "blanket", "needs blankets", request = true, lex = Lex(
            "blanket*", "kambal*", "कंबल*", "कम्बल*", "kumot*", "clothes")),
        Concept("shelter", Category.SHELTER, 1, "shelter", "needs shelter", request = true, lex = Lex(
            "shelter*", "homeless", "no home", "~(?:home|house)\\s+(?:is\\s+|was\\s+|got\\s+)?(?:washed|destroyed|gone|damaged)",
            "evacuat*", "relief camp", "tent*", "ashray*", "aashray*", "asra", "aasra", "आश्रय*", "आसरा*", "टेंट*",
            "ଆଶ୍ରୟ*", "tirahan*", "silungan*", "masisilungan*", "kanlungan*", "aashray*", "ghar chahiye",
            "~(?:place|somewhere|nowhere|room|roof)\\s+to\\s+(?:stay|sleep|live)", "~(?:no|nowhere)\\s+to\\s+(?:stay|sleep|go)",
            "~sleeping\\s+(?:on\\s+the\\s+)?(?:road|street|open|outside|ground)", "~(?:sleeping|sleep)\\s+in\\s+the\\s+open",
            "~रहने\\s+(?:की|के\\s+लिए)\\s+(?:कोई\\s+)?(?:जगह|ठिकाना|जगा)", "~खुले\\s+में\\s+(?:सो|रह)\\S*",
            "~(?:rehne|rahne)\\s+(?:ki|ke\\s+liye)\\s+(?:koi\\s+)?(?:jagah|jagha|thikana)", "~khule\\s+mein\\s+(?:so|rah)\\w*",
            "~ରହିବା\\s*(?:ପାଇଁ|ସ୍ଥାନ|ଜାଗା)\\S*", "ଘର ଦରକାର")),
        // ---------------- context only
        Concept("roof", Category.RESCUE, 2, "rescue", "on roof", context = true, lex = Lex(
            "roof*", "rooftop*", "chhat*", "chhath", "छत*", "ଛାତ*", "bubong*", "atop*")),
        Concept("flood", Category.RESCUE, 2, "rescue", "flood", context = true, lex = Lex(
            "flood*", "baadh*", "baarh*", "बाढ*", "ବନ୍ୟା*", "banya*", "baha*", "pagbaha*", "binaha*", "lunop*",
            "cyclone*", "typhoon*", "bagyo*", "tsunami*",
            "~water\\s+(?:is\\s+)?(?:rising|entering|coming|came|inside|everywhere)", "~(?:pani|paani)\\s+(?:bhar|badh|ghus)\\w*",
            "~पानी\\s+(?:भर|बढ|घुस)\\w*", "~ପାଣି\\s+(?:ବଢ|ଭର|ପଶ|ଆସ)\\S*", "~(?:pani|paani)\\s+(?:badh|bhar|pasi)\\w*")),
        // phones and light: no category of their own on the wire, so OTHER with a plain item
        Concept("powerbank", Category.OTHER, 1, "power bank", "needs power bank", request = true, lex = Lex(
            "~power\\s?bank\\S*", "charger*", "~पावर\\s?बैंक\\S*", "चार्जर*")),
        Concept("torch", Category.OTHER, 1, "torch", "needs torch", request = true, lex = Lex(
            "torch*", "flashlight*", "candle*", "टॉर्च*", "मोमबत्ती*", "ଟର୍ଚ୍ଚ*")),
    )

    val flagLexicons: List<Pair<VulnFlag, Lex>> = listOf(
        VulnFlag.ELDERLY to Lex(
            "grandmother*", "grandfather*", "grandma*", "grandpa*", "granny", "elderly", "senior citizen*",
            "~old\\s+(?:man|woman|lady|person|people|couple|age|mother|father|parents|folks)",
            "~very\\s+old", "~too\\s+old", "aged",
            "dadi", "dada", "nani", "nana", "दादी*", "दादा*", "नानी*", "नाना*", "बुजुर्ग*", "बूढ*", "बुढ*", "budha*",
            "budhi*", "budhiya*", "bujurg*", "ବୃଦ୍ଧ*", "ବୁଢ*", "ଜେଜେମା*", "ଜେଜେବାପା*", "jejema*", "jejebapa*",
            "lola", "lolo", "lolang", "matanda*", "tigulang*", "tigurang*", "gurang*"),
        VulnFlag.CHILD to Lex(
            "child*", "kid", "kids", "baby", "babies", "infant*", "toddler*", "newborn*", "bachcha*", "bachche*",
            "bacche*", "bachha*", "bachhe*", "bacchon*", "bachchon*", "bachhon*", "बच्च*", "शिशु*", "ଶିଶୁ*", "ପିଲା*",
            "pila", "pilamane*", "sanggol*", "bata", "batang", "mga bata", "anak*"),
    )

    // ---- relations: first one mentioned becomes the summary subject
    private class Relation(val name: String, val flag: VulnFlag?, val lex: Lex)

    private val relations = listOf(
        Relation("grandmother", VulnFlag.ELDERLY, Lex("grandmother*", "grandma*", "granny", "dadi", "nani", "दादी*", "नानी*", "ଜେଜେମା*", "jejema*", "lola")),
        Relation("grandfather", VulnFlag.ELDERLY, Lex("grandfather*", "grandpa*", "दादा*", "नाना*", "ଜେଜେବାପା*", "jejebapa*", "lolo")),
        Relation("mother", null, Lex("mother*", "mom", "mum", "mummy", "mama", "maa", "ma", "maata", "माँ*", "मां*", "माता*", "मम्मी*", "ମା", "ମାଆ*", "ମାଙ୍କ*", "nanay*", "nanai", "inay", "iloy*")),
        Relation("father", null, Lex("father*", "dad", "daddy", "papa", "pita", "पिता*", "पापा*", "ବାପା*", "tatay*", "itay", "amahan*")),
        Relation("wife", null, Lex("wife", "biwi", "patni", "पत्नी*", "बीवी*", "ସ୍ତ୍ରୀ*")),
        Relation("husband", null, Lex("husband", "पति*", "ସ୍ୱାମୀ*")),
        Relation("son", null, Lex("son", "beta", "बेटा*", "ପୁଅ*")),
        Relation("daughter", null, Lex("daughter*", "beti", "बेटी*", "ଝିଅ*")),
        Relation("brother", null, Lex("brother*", "bhai", "भाई*", "ଭାଇ*", "kuya")),
        Relation("sister", null, Lex("sister*", "behen", "bahan", "बहन*", "ଭଉଣୀ*", "ate")),
    )

    /** (English relation word, implied flag) of the earliest-mentioned relative, if any. */
    fun findSubject(text: String): Pair<String, VulnFlag?>? {
        var best: Pair<String, VulnFlag?>? = null
        var bestPos = Int.MAX_VALUE
        for (r in relations) {
            val m = r.lex.find(text) ?: continue
            if (m.range.first < bestPos) { bestPos = m.range.first; best = r.name to r.flag }
        }
        return best
    }

    // ---- supplies the requester says they already have ("we have food but no water", "पानी है लेकिन खाना नहीं है")
    private val availableRegex = Regex(
        "(?<!n't )(?<!not )(?<!never )(?<!$W)(?:have|has|got)\\s+(?:some\\s+|enough\\s+|plenty\\s+of\\s+|a\\s+little\\s+)?" +
            "(?:drinking\\s+|clean\\s+)?(?:water|food|milk|medicines?|blankets?|rice)(?!$W)(?!\\s+(?:in|inside|entering|rising|everywhere|up|coming|came|level))" +
            "|(?<!$W)(?:पानी|खाना|खाने|दवा|दवाई|राशन|दूध|pani|paani|khana|khaana|dawai|dawa|rashan|doodh|ପାଣି|ଖାଦ୍ୟ|ଔଷଧ)" +
            "\\s*(?:to\\s+|toh\\s+|bhi\\s+|तो\\s+|भी\\s+)?(?:hai|hain|है|हैं|achhi|ଅଛି)(?!\\s+(?:nahi|nahin|नहीं|नही))(?!$W)"
    )

    /** Blanks out "we have X" / "X है" statements so a supply the person already has is not read as a need. */
    fun stripAvailable(text: String): String = availableRegex.replace(text, " ")

    // ---- ages
    private val AGE_UNITS = "years?|yrs?|yr|y/o|saal|sal|वर्ष|साल|बरस|ବର୍ଷ|taon|anyos|taong|tuig|katuigan"
    private val ageRegex = Regex(
        "(?<!$W)(\\p{Nd}{1,3}|${People.numAlt})\\s*-?\\s*(?:$AGE_UNITS)(?:[ -]old)?(?!$W)" +
            "|(?<!$W)(?:age|aged|umar|umra|उम्र|ବୟସ|edad)\\s*(?:of\\s*)?(\\p{Nd}{1,3}|${People.numAlt})(?!$W)" +
            // "my father is seventy": a spelled-out decade after is/aged, no unit needed
            "|(?<!$W)(?:is|aged)\\s+(${People.decadeAlt})(?!\\s*(?:people|persons|of\\s+us))(?!$W)"
    )

    /** Removes "80 years old"-style phrases; returns the remaining text and the ages found. */
    fun stripAges(text: String): Pair<String, List<Int>> {
        val ages = ArrayList<Int>()
        val stripped = ageRegex.replace(text) { m ->
            val g = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
            ages += People.valueOf(g)
            " "
        }
        return stripped to ages
    }

    fun digits(s: String): Int {
        var v = 0
        for (ch in s) {
            val d = Character.digit(ch, 10)
            if (d >= 0) v = v * 10 + d
            if (v > 100_000) break
        }
        return v
    }
}
