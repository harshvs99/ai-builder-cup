package org.relay.core.triage

import org.relay.core.intake.QuestionBank
import org.relay.core.payload.Lang

/**
 * The fixed words triage says. Like [QuestionBank], neither the rules nor a model ever writes any of it: they choose which
 * question to ask and which advisory applies, and these texts are what the person hears and reads. A mistranslated first
 * aid line is a safety failure, so there is no free-form model output on this path. The only words spliced in are a
 * relation from a fixed list ("your mother") or an ordinal ("the second person").
 *
 * Non-English text is pending native-speaker review (like the ACK templates and [QuestionBank]). Waray and Cebuano
 * speakers get the Tagalog text, which they hear in the same recogniser fallback; English is the last resort.
 */
object TriageBank {
    private fun pick(m: Map<Lang, String>, lang: Lang): String = m[lang]
        ?: (if (lang == Lang.WAR || lang == Lang.CEB) m[Lang.TL] else null)
        ?: m.getValue(Lang.EN)

    private fun t(en: String, hi: String, or: String, tl: String) = mapOf(Lang.EN to en, Lang.HI to hi, Lang.OR to or, Lang.TL to tl)

    // ---------------------------------------------------------------- who

    private val relationLabel: Map<Relation, Map<Lang, String>> = mapOf(
        Relation.MOTHER to t("your mother", "आपकी माँ", "ଆପଣଙ୍କ ମା", "ang inyong ina"),
        Relation.FATHER to t("your father", "आपके पिता", "ଆପଣଙ୍କ ବାପା", "ang inyong ama"),
        Relation.WIFE to t("your wife", "आपकी पत्नी", "ଆପଣଙ୍କ ପତ୍ନୀ", "ang inyong asawa"),
        Relation.HUSBAND to t("your husband", "आपके पति", "ଆପଣଙ୍କ ସ୍ୱାମୀ", "ang inyong asawa"),
        Relation.SON to t("your son", "आपका बेटा", "ଆପଣଙ୍କ ପୁଅ", "ang inyong anak na lalaki"),
        Relation.DAUGHTER to t("your daughter", "आपकी बेटी", "ଆପଣଙ୍କ ଝିଅ", "ang inyong anak na babae"),
        Relation.CHILD to t("your child", "आपका बच्चा", "ଆପଣଙ୍କ ପିଲା", "ang inyong anak"),
        Relation.BABY to t("your baby", "आपका शिशु", "ଆପଣଙ୍କ ଶିଶୁ", "ang inyong sanggol"),
        Relation.GRANDMOTHER to t("your grandmother", "आपकी दादी या नानी", "ଆପଣଙ୍କ ଜେଜେମା", "ang inyong lola"),
        Relation.GRANDFATHER to t("your grandfather", "आपके दादा या नाना", "ଆପଣଙ୍କ ଜେଜେବାପା", "ang inyong lolo"),
        Relation.BROTHER to t("your brother", "आपका भाई", "ଆପଣଙ୍କ ଭାଇ", "ang inyong kapatid na lalaki"),
        Relation.SISTER to t("your sister", "आपकी बहन", "ଆପଣଙ୍କ ଭଉଣୀ", "ang inyong kapatid na babae"),
    )
    private val ordinalLabel: List<Map<Lang, String>> = listOf(
        t("the first person", "पहला व्यक्ति", "ପ୍ରଥମ ବ୍ୟକ୍ତି", "ang unang tao"),
        t("the second person", "दूसरा व्यक्ति", "ଦ୍ୱିତୀୟ ବ୍ୟକ୍ତି", "ang pangalawang tao"),
        t("the third person", "तीसरा व्यक्ति", "ତୃତୀୟ ବ୍ୟକ୍ତି", "ang pangatlong tao"),
    )
    private val theOnlyPerson = t("the person", "वह व्यक्ति", "ସେହି ବ୍ୟକ୍ତି", "ang tao")

    /** How a question refers to subject [id] of [subjects]: "your mother", "the second person", or "the person" when alone. */
    fun who(subjects: List<Subject>, id: Int, lang: Lang): String {
        val s = subjects.firstOrNull { it.id == id }
        return when {
            s?.relation != null -> pick(relationLabel.getValue(s.relation), lang)
            subjects.size <= 1 -> pick(theOnlyPerson, lang)
            else -> pick(ordinalLabel[(id - 1).coerceIn(0, ordinalLabel.lastIndex)], lang)
        }
    }

    // ---------------------------------------------------------------- questions

    // {who} is replaced by [who]. Phrased to work for any gender and the honorific form.
    private val factText: Map<Fact, Map<Lang, String>> = mapOf(
        Fact.CONSCIOUS to t(
            "Is {who} awake and answering you?", "क्या {who} होश में हैं और आपको जवाब दे रहे हैं?",
            "{who} ହୋସରେ ଅଛନ୍ତି କି ଏବଂ ଆପଣଙ୍କୁ ଉତ୍ତର ଦେଉଛନ୍ତି କି?", "Gising ba at sumasagot sa inyo {who}?"),
        Fact.BREATHING to t(
            "Is {who} breathing normally?", "क्या {who} सामान्य रूप से साँस ले रहे हैं?",
            "{who} ସାଧାରଣ ଭାବରେ ଶ୍ୱାସ ନେଉଛନ୍ତି କି?", "Humihinga ba nang normal {who}?"),
        Fact.HEAVY_BLEEDING to t(
            "Is {who} bleeding heavily, with the bleeding not stopping?", "क्या {who} को बहुत खून बह रहा है जो रुक नहीं रहा?",
            "{who}: ବହୁତ ରକ୍ତ ବାହାରୁଛି କି, ଯାହା ବନ୍ଦ ହେଉନାହିଁ?", "May matinding pagdurugo ba {who} na hindi tumitigil?"),
        Fact.ANYONE_HURT to t(
            "Is anyone with you hurt or unwell?", "क्या आपके साथ कोई घायल या बीमार है?",
            "ଆପଣଙ୍କ ସାଙ୍ଗରେ କେହି ଆହତ ବା ଅସୁସ୍ଥ ଅଛନ୍ତି କି?", "May kasama ba kayong nasaktan o may sakit?"),
        Fact.LIVE_WIRE to t(
            "Is there a fallen electric pole or wire in the water or near you?",
            "क्या पानी में या आपके पास बिजली का खंभा या तार गिरा है?",
            "ପାଣିରେ କିମ୍ବା ଆପଣଙ୍କ ପାଖରେ ବିଜୁଳି ଖୁଣ୍ଟ ବା ତାର ପଡ଼ିଛି କି?", "May nakabuwal bang poste o kable ng kuryente sa tubig o malapit sa inyo?"),
        Fact.GAS_SMELL to t(
            "Do you smell gas?", "क्या आपको गैस की गंध आ रही है?", "ଆପଣଙ୍କୁ ଗ୍ୟାସ୍ ଗନ୍ଧ ଆସୁଛି କି?", "May naaamoy ba kayong gas?"),
        Fact.BUILDING_UNSAFE to t(
            "Is the building cracked, leaning or damaged?", "क्या इमारत में दरारें हैं, वह झुकी है या क्षतिग्रस्त है?",
            "ଘରରେ ଫାଟ ଅଛି, ଆଉଜି ପଡ଼ିଛି କିମ୍ବା କ୍ଷତିଗ୍ରସ୍ତ ହୋଇଛି କି?", "May bitak, nakatagilid, o nasira ba ang gusali?"),
        Fact.STILL_SHAKING to t(
            "Is the ground still shaking?", "क्या ज़मीन अभी भी हिल रही है?", "ଭୂଇଁ ଏବେ ବି କମ୍ପୁଛି କି?", "Umuuga pa rin ba ang lupa?"),
        Fact.SHELTER_HOLDING to t(
            "Are the roof and walls where you are holding up?", "जहाँ आप हैं वहाँ की छत और दीवारें टिकी हुई हैं?",
            "ଆପଣ ଯେଉଁଠି ଅଛନ୍ତି ସେଠାର ଛାତ ଓ କାନ୍ଥ ଠିକ୍ ଅଛି କି?", "Matibay pa ba ang bubong at pader kung nasaan kayo?"),
        Fact.SLOPE_MOVING to t(
            "Is the ground or hillside still moving?", "क्या ज़मीन या पहाड़ी अभी भी खिसक रही है?",
            "ମାଟି ବା ପାହାଡ଼ ଏବେ ବି ଖସୁଛି କି?", "Gumagalaw o gumuguho pa ba ang lupa o gilid ng burol?"),
    )

    private val choiceText: Map<Choice, Map<Lang, String>> = mapOf(
        Choice.PATIENTS to t(
            "How many people need medical help?", "कितने लोगों को चिकित्सा मदद चाहिए?",
            "କେତେ ଜଣଙ୍କୁ ଚିକିତ୍ସା ସାହାଯ୍ୟ ଦରକାର?", "Ilang tao ang nangangailangan ng medikal na tulong?"),
        Choice.LOCATION to t(
            "Where exactly are you?", "आप ठीक-ठीक कहाँ हैं?", "ଆପଣ ଠିକ୍ କେଉଁଠି ଅଛନ୍ତି?", "Nasaan po kayo eksakto?"),
        Choice.FLOOR to t(
            "How high up are you?", "आप कितनी ऊँचाई पर हैं?", "ଆପଣ କେତେ ଉଚ୍ଚରେ ଅଛନ୍ତି?", "Gaano kataas kayo?"),
        Choice.WATER_DEPTH to t(
            "How deep is the water where you are?", "जहाँ आप हैं वहाँ पानी कितना गहरा है?",
            "ଆପଣ ଯେଉଁଠି ଅଛନ୍ତି ସେଠାରେ ପାଣି କେତେ ଗଭୀର?", "Gaano kalalim ang tubig kung nasaan kayo?"),
    )

    private val optionLabel: Map<String, Map<Lang, String>> = mapOf(
        "roof" to t("On the roof", "छत पर", "ଛାତ ଉପରେ", "Sa bubong"),
        "upper_floor" to t("On an upper floor", "ऊपरी मंज़िल पर", "ଉପର ମହଲାରେ", "Sa itaas na palapag"),
        "ground_floor" to t("On the ground floor", "भूतल पर", "ଭୂତଳ ମହଲାରେ", "Sa ibabang palapag"),
        "basement" to t("Basement or underground", "तहखाने या ज़मीन के नीचे", "ତଳ ଘରେ ବା ମାଟି ତଳେ", "Sa basement o ilalim ng lupa"),
        "vehicle" to t("In a vehicle or boat", "गाड़ी या नाव में", "ଗାଡ଼ି ବା ନାଆରେ", "Sa sasakyan o bangka"),
        "outdoors" to t("Outside, in the open", "बाहर, खुले में", "ବାହାରେ, ଖୋଲା ସ୍ଥାନରେ", "Sa labas, bukas na lugar"),
        "rubble" to t("Under rubble or debris", "मलबे के नीचे", "ଧ୍ୱଂସାବଶେଷ ତଳେ", "Sa ilalim ng guho"),
        "floor_1" to t("One floor up", "एक मंज़िल ऊपर", "ଏକ ମହଲା ଉପରେ", "Isang palapag pataas"),
        "floor_2" to t("Two floors up", "दो मंज़िल ऊपर", "ଦୁଇ ମହଲା ଉପରେ", "Dalawang palapag pataas"),
        "floor_3plus" to t("Three or more floors up", "तीन या अधिक मंज़िल ऊपर", "ତିନି ବା ଅଧିକ ମହଲା ଉପରେ", "Tatlo o higit pang palapag pataas"),
        "ankle" to t("Ankle deep", "टखने तक", "ଗୋଡ଼ ଗଣ୍ଠି ପର୍ଯ୍ୟନ୍ତ", "Hanggang bukung-bukong"),
        "knee" to t("Knee deep", "घुटने तक", "ଆଣ୍ଠୁ ପର୍ଯ୍ୟନ୍ତ", "Hanggang tuhod"),
        "waist" to t("Waist deep", "कमर तक", "ଅଣ୍ଟା ପର୍ଯ୍ୟନ୍ତ", "Hanggang baywang"),
        "chest" to t("Chest deep", "सीने तक", "ଛାତି ପର୍ଯ୍ୟନ୍ତ", "Hanggang dibdib"),
        "over_head" to t("Over my head", "सिर से ऊपर", "ମୁଣ୍ଡ ଉପରେ", "Higit sa ulo"),
        "one" to t("Just one person", "सिर्फ़ एक व्यक्ति", "କେବଳ ଜଣେ", "Isang tao lang"),
        "two" to t("Two people", "दो लोग", "ଦୁଇ ଜଣ", "Dalawang tao"),
        "three_plus" to t("Three or more", "तीन या ज़्यादा", "ତିନି ବା ଅଧିକ", "Tatlo o higit pa"),
    )

    fun optionText(key: String, lang: Lang): String = pick(optionLabel.getValue(key), lang)

    /** The text of a yes/no question about [subject] (ignored for scene facts). */
    fun question(f: Fact, subject: Int, subjects: List<Subject>, lang: Lang): String {
        factText[f]?.let { return pick(it, lang).replace("{who}", who(subjects, subject, lang)) }
        return QuestionBank.byId(f.bankId!!)!!.text(lang)
    }

    /** Single-subject wording, for callers without a conversation (tests, previews). */
    fun question(f: Fact, lang: Lang): String = question(f, 1, emptyList(), lang)

    fun question(c: Choice, lang: Lang): String = pick(choiceText.getValue(c), lang)

    // ---------------------------------------------------------------- advisories

    enum class AdvisoryId { CPR, RECOVERY, BLEEDING, SNAKE, LABOUR, ELECTRIC, FLOOD, TRAPPED, CYCLONE, LANDSLIDE, GAS, UNSAFE_BUILDING }

    /** Short lines, each speakable on its own. [priority]: higher is said first. */
    class Advisory(val id: AdvisoryId, val priority: Int, private val title: Map<Lang, String>, private val lines: Map<Lang, List<String>>) {
        fun title(lang: Lang): String = pick(title, lang)
        fun lines(lang: Lang): List<String> =
            lines[lang] ?: (if (lang == Lang.WAR || lang == Lang.CEB) lines[Lang.TL] else null) ?: lines.getValue(Lang.EN)
    }

    private fun a(id: AdvisoryId, priority: Int, title: Map<Lang, String>, en: List<String>, hi: List<String>, or: List<String>, tl: List<String>) =
        Advisory(id, priority, title, mapOf(Lang.EN to en, Lang.HI to hi, Lang.OR to or, Lang.TL to tl))

    val advisories: Map<AdvisoryId, Advisory> = listOf(
        a(AdvisoryId.CPR, 100, t("Not breathing: start chest pushes", "साँस नहीं: छाती दबाना शुरू करें", "ଶ୍ୱାସ ନାହିଁ: ଛାତି ଦବାନ୍ତୁ", "Hindi humihinga: simulan ang pagpindot sa dibdib"),
            listOf(
                "Lay the person flat on their back on a firm surface.",
                "Push hard and fast in the middle of the chest, about two pushes every second. For a baby or small child, push gently with two fingers.",
                "Do not stop until they breathe or help arrives. Give nothing to eat or drink.",
            ),
            listOf(
                "व्यक्ति को सख़्त जगह पर पीठ के बल सीधा लिटाएँ।",
                "छाती के बीच में ज़ोर से और तेज़ी से दबाएँ, हर सेकंड में लगभग दो बार। बच्चे के लिए दो उँगलियों से हल्के से दबाएँ।",
                "जब तक वह साँस न ले या मदद न आए, रुकें नहीं। कुछ खाने या पीने को न दें।",
            ),
            listOf(
                "ବ୍ୟକ୍ତିଙ୍କୁ ଶକ୍ତ ସ୍ଥାନରେ ପିଠି ଉପରେ ସିଧା ଶୁଆନ୍ତୁ।",
                "ଛାତିର ମଝିରେ ଜୋରରେ ଓ ଶୀଘ୍ର ଦବାନ୍ତୁ, ପ୍ରତି ସେକେଣ୍ଡରେ ପ୍ରାୟ ଦୁଇଥର। ଶିଶୁ ପାଇଁ ଦୁଇ ଆଙ୍ଗୁଳିରେ ଧୀରେ ଦବାନ୍ତୁ।",
                "ସେ ଶ୍ୱାସ ନେଇଯିବା ପର୍ଯ୍ୟନ୍ତ କିମ୍ବା ସାହାଯ୍ୟ ଆସିବା ପର୍ଯ୍ୟନ୍ତ ବନ୍ଦ କରନ୍ତୁ ନାହିଁ। କିଛି ଖାଇବାକୁ ବା ପିଇବାକୁ ଦିଅନ୍ତୁ ନାହିଁ।",
            ),
            listOf(
                "Ihiga ang tao nang nakatihaya sa matigas na lapag.",
                "Diinan nang malakas at mabilis ang gitna ng dibdib, mga dalawang diin bawat segundo. Sa sanggol o batang maliit, dahan-dahang diinan gamit ang dalawang daliri.",
                "Huwag tumigil hanggang makahinga siya o dumating ang tulong. Huwag bigyan ng pagkain o inumin.",
            )),
        a(AdvisoryId.RECOVERY, 90, t("Not awake: keep the airway open", "होश में नहीं: साँस का रास्ता खुला रखें", "ହୋସ୍ ନାହିଁ: ଶ୍ୱାସ ପଥ ଖୋଲା ରଖନ୍ତୁ", "Walang malay: panatilihing bukas ang daanan ng hangin"),
            listOf(
                "Roll the person onto their side, with the top knee bent and the head tilted back a little.",
                "Stay with them and watch their breathing. If it stops, start chest pushes.",
                "Give nothing to eat or drink.",
            ),
            listOf(
                "व्यक्ति को करवट से लिटाएँ, ऊपर वाला घुटना मोड़ें और सिर को थोड़ा पीछे झुकाएँ।",
                "उनके पास रहें और साँस देखते रहें। साँस रुक जाए तो छाती दबाना शुरू करें।",
                "कुछ खाने या पीने को न दें।",
            ),
            listOf(
                "ବ୍ୟକ୍ତିଙ୍କୁ କଡ଼ ପାଖେ ଶୁଆନ୍ତୁ, ଉପର ଆଣ୍ଠୁ ମୋଡ଼ନ୍ତୁ ଓ ମୁଣ୍ଡକୁ ଟିକେ ପଛକୁ ନୁଆଁନ୍ତୁ।",
                "ସାଙ୍ଗରେ ରୁହନ୍ତୁ ଓ ଶ୍ୱାସ ଦେଖନ୍ତୁ। ଶ୍ୱାସ ବନ୍ଦ ହେଲେ ଛାତି ଦବାନ୍ତୁ।",
                "କିଛି ଖାଇବାକୁ ବା ପିଇବାକୁ ଦିଅନ୍ତୁ ନାହିଁ।",
            ),
            listOf(
                "Ihiga ang tao nang nakatagilid, nakabaluktot ang tuhod sa itaas, at bahagyang nakatingala ang ulo.",
                "Manatili sa tabi niya at bantayan ang paghinga. Kapag tumigil, simulan ang pagpindot sa dibdib.",
                "Huwag bigyan ng pagkain o inumin.",
            )),
        a(AdvisoryId.BLEEDING, 80, t("Heavy bleeding: press on the wound", "ज़्यादा खून: घाव को दबाएँ", "ଅଧିକ ରକ୍ତ: କ୍ଷତକୁ ଦବାନ୍ତୁ", "Matinding pagdurugo: diinan ang sugat"),
            listOf(
                "Press firmly on the wound with a clean cloth. Do not lift it to look; add more cloth on top if it soaks through.",
                "Keep pressing for at least ten minutes. If it is an arm or leg, raise it above the heart.",
                "Keep the person lying down and warm.",
            ),
            listOf(
                "साफ़ कपड़े से घाव को मज़बूती से दबाएँ। देखने के लिए उठाएँ नहीं; भीग जाए तो ऊपर और कपड़ा रखें।",
                "कम से कम दस मिनट तक दबाए रखें। हाथ या पैर हो तो उसे दिल से ऊपर उठाएँ।",
                "व्यक्ति को लेटा रखें और गर्म रखें।",
            ),
            listOf(
                "ସଫା କପଡ଼ାରେ କ୍ଷତକୁ ଜୋରରେ ଦବାନ୍ତୁ। ଦେଖିବା ପାଇଁ ଉଠାନ୍ତୁ ନାହିଁ; ଭିଜିଗଲେ ଉପରେ ଆଉ କପଡ଼ା ରଖନ୍ତୁ।",
                "କମ୍ ରୁ କମ୍ ଦଶ ମିନିଟ୍ ଦବାଇ ରଖନ୍ତୁ। ହାତ ବା ଗୋଡ଼ ହେଲେ ହୃଦୟଠାରୁ ଉପରକୁ ଉଠାନ୍ତୁ।",
                "ବ୍ୟକ୍ତିଙ୍କୁ ଶୋଇ ରହିବାକୁ ଦିଅନ୍ତୁ ଓ ଗରମ ରଖନ୍ତୁ।",
            ),
            listOf(
                "Diinan nang mahigpit ang sugat gamit ang malinis na tela. Huwag itaas para tingnan; dagdagan ng tela sa ibabaw kung mabasa.",
                "Diinan nang hindi bababa sa sampung minuto. Kung braso o binti, itaas ito nang mas mataas sa puso.",
                "Panatilihing nakahiga at mainit ang tao.",
            )),
        a(AdvisoryId.SNAKE, 70, t("Snake bite: keep still", "साँप का काटना: स्थिर रहें", "ସାପ କାମୁଡ଼ା: ସ୍ଥିର ରୁହନ୍ତୁ", "Kagat ng ahas: manatiling hindi gumagalaw"),
            listOf(
                "Keep the person calm and still. Keep the bitten arm or leg below the level of the heart.",
                "Take off rings and tight clothes near the bite. Do not cut the bite, suck it, or tie anything tight.",
                "Note the time of the bite. They need anti-venom at a hospital as soon as it can be reached.",
            ),
            listOf(
                "व्यक्ति को शांत और स्थिर रखें। काटे गए हाथ या पैर को दिल के स्तर से नीचे रखें।",
                "काटे की जगह के पास से अँगूठी और तंग कपड़े उतार दें। घाव को काटें नहीं, चूसें नहीं, और कसकर कुछ न बाँधें।",
                "काटने का समय याद रखें। जैसे ही पहुँच सकें, अस्पताल में एंटी-वेनम चाहिए।",
            ),
            listOf(
                "ବ୍ୟକ୍ତିଙ୍କୁ ଶାନ୍ତ ଓ ସ୍ଥିର ରଖନ୍ତୁ। କାମୁଡ଼ା ହାତ ବା ଗୋଡ଼କୁ ହୃଦୟ ତଳେ ରଖନ୍ତୁ।",
                "କାମୁଡ଼ା ପାଖରୁ ମୁଦି ଓ ଟାଇଟ୍ ଲୁଗା ଖୋଲିଦିଅନ୍ତୁ। କ୍ଷତ କାଟନ୍ତୁ ନାହିଁ, ଚୁଷନ୍ତୁ ନାହିଁ, ଟାଣି ବାନ୍ଧନ୍ତୁ ନାହିଁ।",
                "କାମୁଡ଼ିବା ସମୟ ମନେ ରଖନ୍ତୁ। ପହଞ୍ଚିପାରିଲେ ଡାକ୍ତରଖାନାରେ ଆଣ୍ଟି-ଭେନମ୍ ଦରକାର।",
            ),
            listOf(
                "Panatilihing kalmado at hindi gumagalaw ang tao. Ibaba ang kinagat na braso o binti kaysa sa puso.",
                "Tanggalin ang singsing at masisikip na damit malapit sa kagat. Huwag hiwain, sipsipin, o gapusin nang mahigpit.",
                "Tandaan ang oras ng kagat. Kailangan nila ng anti-venom sa ospital sa lalong madaling panahon.",
            )),
        a(AdvisoryId.LABOUR, 70, t("Childbirth: stay calm, lie on the side", "प्रसव: शांत रहें, करवट लेटें", "ପ୍ରସବ: ଶାନ୍ତ ରୁହନ୍ତୁ, କଡ଼ ପାଖେ ଶୁଅନ୍ତୁ", "Panganganak: manatiling kalmado, humiga nang patagilid"),
            listOf(
                "Find a clean, dry, warm place and let her lie on her side.",
                "Do not pull the baby. If the baby comes, dry it with a clean cloth, keep it on her chest skin to skin, and cover both.",
                "Do not cut the cord. Keep both warm and wait for help.",
            ),
            listOf(
                "साफ़, सूखी, गर्म जगह ढूँढें और उन्हें करवट से लिटाएँ।",
                "बच्चे को खींचें नहीं। बच्चा हो जाए तो साफ़ कपड़े से पोंछें, माँ की छाती पर त्वचा से लगाकर रखें और दोनों को ढकें।",
                "नाल न काटें। दोनों को गर्म रखें और मदद का इंतज़ार करें।",
            ),
            listOf(
                "ସଫା, ଶୁଖିଲା, ଗରମ ସ୍ଥାନ ଖୋଜନ୍ତୁ ଓ ତାଙ୍କୁ କଡ଼ ପାଖେ ଶୁଆନ୍ତୁ।",
                "ଶିଶୁକୁ ଟାଣନ୍ତୁ ନାହିଁ। ଶିଶୁ ଜନ୍ମ ହେଲେ ସଫା କପଡ଼ାରେ ପୋଛନ୍ତୁ, ମାଆର ଛାତିରେ ଚର୍ମ ସହ ଲଗାଇ ରଖନ୍ତୁ ଓ ଦୁହିଁଙ୍କୁ ଘୋଡ଼ାନ୍ତୁ।",
                "ନାଭି ନଳୀ କାଟନ୍ତୁ ନାହିଁ। ଦୁହିଁଙ୍କୁ ଗରମ ରଖି ସାହାଯ୍ୟ ଅପେକ୍ଷା କରନ୍ତୁ।",
            ),
            listOf(
                "Humanap ng malinis, tuyo, at mainit na lugar at ihiga siya nang patagilid.",
                "Huwag hilahin ang sanggol. Kung ipanganak na, punasan ng malinis na tela, ilagay sa dibdib niya nang balat sa balat, at takpan silang dalawa.",
                "Huwag putulin ang pusod. Panatilihing mainit ang dalawa at hintayin ang tulong.",
            )),
        a(AdvisoryId.ELECTRIC, 95, t("Fallen pole or wire: keep away", "गिरा खंभा या तार: दूर रहें", "ପଡ଼ିଥିବା ଖୁଣ୍ଟ ବା ତାର: ଦୂରରେ ରୁହନ୍ତୁ", "Nakabuwal na poste o kable: lumayo"),
            listOf(
                "Stay far away from any fallen pole or wire, at least ten metres. Water around it can carry electricity.",
                "Do not wade through water near a pole or wire. Do not touch anyone who is touching a wire.",
                "Switch off the mains power only if the switch is dry and you can reach it without standing in water.",
            ),
            listOf(
                "गिरे हुए खंभे या तार से कम से कम दस मीटर दूर रहें। आसपास का पानी बिजली ले जा सकता है।",
                "खंभे या तार के पास के पानी में न चलें। तार को छू रहे व्यक्ति को न छुएँ।",
                "मेन स्विच तभी बंद करें जब वह सूखा हो और पानी में खड़े हुए बिना पहुँच सकें।",
            ),
            listOf(
                "ପଡ଼ିଥିବା ଖୁଣ୍ଟ ବା ତାରଠାରୁ ଅନ୍ତତଃ ଦଶ ମିଟର ଦୂରରେ ରୁହନ୍ତୁ। ଆଖପାଖ ପାଣି ବିଜୁଳି ବହନ କରିପାରେ।",
                "ଖୁଣ୍ଟ ବା ତାର ପାଖ ପାଣିରେ ଚାଲନ୍ତୁ ନାହିଁ। ତାରକୁ ଛୁଇଁଥିବା ଲୋକଙ୍କୁ ଛୁଅନ୍ତୁ ନାହିଁ।",
                "ମେନ୍ ସୁଇଚ୍ ଶୁଖିଲା ଥିଲେ ଓ ପାଣିରେ ନ ଛିଡ଼ା ହୋଇ ପହଞ୍ଚିପାରିଲେ ହିଁ ବନ୍ଦ କରନ୍ତୁ।",
            ),
            listOf(
                "Lumayo sa anumang nakabuwal na poste o kable, hindi bababa sa sampung metro. Maaaring dumaloy ang kuryente sa tubig sa paligid nito.",
                "Huwag lumusong sa tubig malapit sa poste o kable. Huwag hawakan ang sinumang humahawak sa kable.",
                "Patayin ang main switch kung tuyo ito at maaabot nang hindi nakatayo sa tubig.",
            )),
        a(AdvisoryId.FLOOD, 85, t("Rising water: go up", "बढ़ता पानी: ऊपर जाएँ", "ବଢ଼ୁଥିବା ପାଣି: ଉପରକୁ ଯାଆନ୍ତୁ", "Tumataas na tubig: umakyat"),
            listOf(
                "Move to the highest safe place, an upper floor or a strong roof, before the water reaches you.",
                "Do not walk or drive through moving water. Even knee-deep water can knock a person over.",
                "Take drinking water, medicine, a torch and your phone. Stay where rescuers can see you, and wave a bright cloth.",
            ),
            listOf(
                "पानी पहुँचने से पहले सबसे ऊँची सुरक्षित जगह जाएँ: ऊपरी मंज़िल या मज़बूत छत।",
                "बहते पानी में न चलें और न गाड़ी चलाएँ। घुटने तक का पानी भी इंसान को गिरा सकता है।",
                "पीने का पानी, दवाइयाँ, टॉर्च और फ़ोन साथ लें। ऐसी जगह रहें जहाँ बचावकर्ता देख सकें, और चमकीला कपड़ा हिलाएँ।",
            ),
            listOf(
                "ପାଣି ପହଞ୍ଚିବା ପୂର୍ବରୁ ସବୁଠାରୁ ଉଚ୍ଚ ସୁରକ୍ଷିତ ସ୍ଥାନକୁ ଯାଆନ୍ତୁ: ଉପର ମହଲା ବା ଶକ୍ତ ଛାତ।",
                "ବହୁଥିବା ପାଣିରେ ଚାଲନ୍ତୁ ନାହିଁ କି ଗାଡ଼ି ଚଲାନ୍ତୁ ନାହିଁ। ଆଣ୍ଠୁ ପର୍ଯ୍ୟନ୍ତ ପାଣିରେ ବି ଲୋକ ପଡ଼ିଯାଇପାରେ।",
                "ପିଇବା ପାଣି, ଔଷଧ, ଟର୍ଚ୍ଚ ଓ ଫୋନ ନିଅନ୍ତୁ। ଉଦ୍ଧାରକାରୀ ଦେଖିପାରିବେ ଏପରି ସ୍ଥାନରେ ରୁହନ୍ତୁ ଓ ଉଜ୍ଜ୍ୱଳ କପଡ଼ା ହଲାନ୍ତୁ।",
            ),
            listOf(
                "Pumunta sa pinakamataas na ligtas na lugar, itaas na palapag o matibay na bubong, bago umabot ang tubig.",
                "Huwag lumusong o magmaneho sa umaagos na tubig. Kahit hanggang tuhod ay maaaring matumba ang tao.",
                "Dalhin ang inuming tubig, gamot, flashlight, at telepono. Manatili kung saan makikita ng mga rescuer, at iwagayway ang maliwanag na tela.",
            )),
        a(AdvisoryId.TRAPPED, 75, t("Trapped: save your breath, make noise", "फँसे हैं: ताक़त बचाएँ, आवाज़ करें", "ଫସିଛନ୍ତି: ଶକ୍ତି ବଞ୍ଚାନ୍ତୁ, ଶବ୍ଦ କରନ୍ତୁ", "Naipit: magtipid ng lakas, gumawa ng ingay"),
            listOf(
                "Cover your mouth and nose with cloth to keep out dust.",
                "Tap on a pipe or wall, or blow a whistle. Shout only as a last resort, it wastes strength.",
                "Do not light a match or lighter, there may be gas. Move as little as possible.",
            ),
            listOf(
                "धूल से बचने के लिए मुँह और नाक कपड़े से ढकें।",
                "पाइप या दीवार थपथपाएँ, या सीटी बजाएँ। चिल्लाना आख़िरी उपाय है, इससे ताक़त जाती है।",
                "माचिस या लाइटर न जलाएँ, गैस हो सकती है। जितना कम हो सके हिलें।",
            ),
            listOf(
                "ଧୂଳିରୁ ବଞ୍ଚିବାକୁ ପାଟି ଓ ନାକକୁ କପଡ଼ାରେ ଘୋଡ଼ାନ୍ତୁ।",
                "ପାଇପ୍ ବା କାନ୍ଥରେ ଠକ୍ ଠକ୍ କରନ୍ତୁ, କିମ୍ବା ସିଟି ବଜାନ୍ତୁ। ଚିତ୍କାର ଶେଷ ଉପାୟ, ଏଥିରେ ଶକ୍ତି ଯାଏ।",
                "ଦିଆସିଲି ବା ଲାଇଟର୍ ଜଳାନ୍ତୁ ନାହିଁ, ଗ୍ୟାସ୍ ଥାଇପାରେ। ଯେତେ କମ୍ ହେଲାନ୍ତୁ।",
            ),
            listOf(
                "Takpan ng tela ang bibig at ilong para hindi malanghap ang alikabok.",
                "Kumatok sa tubo o pader, o gumamit ng pito. Sumigaw lang bilang huling paraan, nakakaubos ito ng lakas.",
                "Huwag magsindi ng posporo o lighter, maaaring may gas. Kumilos nang kaunti hangga't maaari.",
            )),
        a(AdvisoryId.CYCLONE, 60, t("Storm: stay in a strong building", "तूफ़ान: मज़बूत इमारत में रहें", "ଝଡ଼: ଶକ୍ତ ଘରେ ରୁହନ୍ତୁ", "Bagyo: manatili sa matibay na gusali"),
            listOf(
                "Stay inside a strong building, in a room without windows if you can. Do not go out until the storm has fully passed.",
                "The calm in the middle of a cyclone is not the end. Wait for the second wind to pass.",
                "Keep water, torch, phone charge and medicine close.",
            ),
            listOf(
                "मज़बूत इमारत के अंदर रहें, हो सके तो बिना खिड़की वाले कमरे में। तूफ़ान पूरी तरह गुज़रने तक बाहर न निकलें।",
                "चक्रवात के बीच की शांति अंत नहीं है। दूसरी हवा गुज़रने तक रुकें।",
                "पानी, टॉर्च, फ़ोन चार्ज और दवाइयाँ पास रखें।",
            ),
            listOf(
                "ଶକ୍ତ ଘର ଭିତରେ ରୁହନ୍ତୁ, ସମ୍ଭବ ହେଲେ ଝରକା ନଥିବା କୋଠରୀରେ। ଝଡ଼ ପୂରା ଗଲା ପରେ ହିଁ ବାହାରକୁ ଯାଆନ୍ତୁ।",
                "ବାତ୍ୟା ମଝିର ଶାନ୍ତି ଶେଷ ନୁହେଁ। ଦ୍ୱିତୀୟ ପବନ ଗଲା ପର୍ଯ୍ୟନ୍ତ ଅପେକ୍ଷା କରନ୍ତୁ।",
                "ପାଣି, ଟର୍ଚ୍ଚ, ଫୋନ୍ ଚାର୍ଜ ଓ ଔଷଧ ପାଖରେ ରଖନ୍ତୁ।",
            ),
            listOf(
                "Manatili sa loob ng matibay na gusali, sa silid na walang bintana kung maaari. Huwag lumabas hangga't hindi lubusang lumipas ang bagyo.",
                "Ang katahimikan sa gitna ng bagyo ay hindi pa ang wakas. Hintayin ang ikalawang hangin.",
                "Ilapit ang tubig, flashlight, charge ng telepono, at gamot.",
            )),
        a(AdvisoryId.LANDSLIDE, 65, t("Landslide: move sideways, not downhill", "भूस्खलन: बगल में हटें, नीचे नहीं", "ଭୂସ୍ଖଳନ: ପାଖକୁ ହଟନ୍ତୁ, ତଳକୁ ନୁହେଁ", "Guho: lumayo nang patagilid, hindi pababa"),
            listOf(
                "Move away from the slope to the side, out of the path of falling earth. Do not run downhill along it.",
                "Stay away from the area. More earth can fall after the first slide.",
                "Do not return to a damaged building until it has been checked.",
            ),
            listOf(
                "ढलान से बगल की ओर हटें, गिरती मिट्टी के रास्ते से बाहर। उसी के साथ नीचे की ओर न भागें।",
                "उस इलाके से दूर रहें। पहले खिसकाव के बाद और मिट्टी गिर सकती है।",
                "क्षतिग्रस्त इमारत में जाँच होने तक वापस न जाएँ।",
            ),
            listOf(
                "ଢଳରୁ ପାଖକୁ ହଟନ୍ତୁ, ଖସୁଥିବା ମାଟିର ବାଟରୁ ବାହାରକୁ। ସେହି ବାଟରେ ତଳକୁ ଦୌଡ଼ନ୍ତୁ ନାହିଁ।",
                "ସେହି ଅଞ୍ଚଳରୁ ଦୂରରେ ରୁହନ୍ତୁ। ପ୍ରଥମ ଖସିବା ପରେ ଆଉ ମାଟି ଖସିପାରେ।",
                "କ୍ଷତିଗ୍ରସ୍ତ ଘରକୁ ଯାଞ୍ଚ ନହେବା ପର୍ଯ୍ୟନ୍ତ ଫେରନ୍ତୁ ନାହିଁ।",
            ),
            listOf(
                "Lumayo sa gilid ng dalisdis, labas sa daraanan ng gumuguhong lupa. Huwag tumakbo pababa kasabay nito.",
                "Lumayo sa lugar. Maaaring may gumuho pang lupa pagkatapos ng una.",
                "Huwag bumalik sa nasirang gusali hangga't hindi ito nasusuri.",
            )),
        a(AdvisoryId.GAS, 92, t("Gas smell: no flames, no switches", "गैस की गंध: आग या स्विच नहीं", "ଗ୍ୟାସ୍ ଗନ୍ଧ: ନିଆଁ ବା ସୁଇଚ୍ ନୁହେଁ", "Amoy gas: walang apoy, walang switch"),
            listOf(
                "Do not light a flame, match or lighter, and do not switch any electrical device on or off.",
                "Open windows and doors if you can reach them safely, and move away from the smell.",
                "If you can, close the gas cylinder valve. Do not use your phone near the smell if you can step away first.",
            ),
            listOf(
                "आग, माचिस या लाइटर न जलाएँ, और कोई बिजली का स्विच चालू या बंद न करें।",
                "सुरक्षित पहुँच में हों तो खिड़की-दरवाज़े खोलें, और गंध से दूर हट जाएँ।",
                "हो सके तो गैस सिलेंडर का वाल्व बंद करें। हो सके तो पहले गंध से दूर जाकर फिर फ़ोन इस्तेमाल करें।",
            ),
            listOf(
                "ନିଆଁ, ଦିଆସିଲି ବା ଲାଇଟର୍ ଜଳାନ୍ତୁ ନାହିଁ, ଏବଂ କୌଣସି ବିଜୁଳି ସୁଇଚ୍ ଚାଲୁ ବା ବନ୍ଦ କରନ୍ତୁ ନାହିଁ।",
                "ସୁରକ୍ଷିତ ଭାବେ ପହଞ୍ଚିପାରିଲେ ଝରକା ଓ ଦ୍ୱାର ଖୋଲନ୍ତୁ, ଏବଂ ଗନ୍ଧଠାରୁ ଦୂରକୁ ଯାଆନ୍ତୁ।",
                "ସମ୍ଭବ ହେଲେ ଗ୍ୟାସ୍ ସିଲିଣ୍ଡରର ଭାଲ୍ବ ବନ୍ଦ କରନ୍ତୁ। ପ୍ରଥମେ ଗନ୍ଧଠାରୁ ଦୂରକୁ ଯାଇ ତା'ପରେ ଫୋନ ବ୍ୟବହାର କରନ୍ତୁ।",
            ),
            listOf(
                "Huwag magsindi ng apoy, posporo o lighter, at huwag buksan o patayin ang anumang de-kuryenteng gamit.",
                "Buksan ang mga bintana at pinto kung ligtas na maaabot, at lumayo sa amoy.",
                "Kung kaya, isara ang balbula ng tangke ng gas. Lumayo muna sa amoy bago gamitin ang telepono.",
            )),
        a(AdvisoryId.UNSAFE_BUILDING, 88, t("Damaged building: get out to open ground", "क्षतिग्रस्त इमारत: खुली जगह पर निकलें", "କ୍ଷତିଗ୍ରସ୍ତ ଘର: ଖୋଲା ସ୍ଥାନକୁ ବାହାରନ୍ତୁ", "Nasirang gusali: lumabas sa bukas na lugar"),
            listOf(
                "If you can leave without climbing through debris, go to open ground away from walls, poles and trees.",
                "Expect more shaking. Do not use lifts, and do not go back inside for belongings.",
                "If you cannot leave, stay low next to a strong wall or under a sturdy table and protect your head.",
            ),
            listOf(
                "मलबे पर चढ़े बिना निकल सकें तो दीवारों, खंभों और पेड़ों से दूर खुली जगह पर जाएँ।",
                "और झटके आ सकते हैं। लिफ़्ट का उपयोग न करें, और सामान लेने वापस अंदर न जाएँ।",
                "निकल न सकें तो मज़बूत दीवार के पास या मज़बूत मेज़ के नीचे झुके रहें और सिर बचाएँ।",
            ),
            listOf(
                "ଧ୍ୱଂସାବଶେଷ ଉପରେ ନ ଚଢ଼ି ବାହାରିପାରିଲେ କାନ୍ଥ, ଖୁଣ୍ଟ ଓ ଗଛଠାରୁ ଦୂରରେ ଖୋଲା ସ୍ଥାନକୁ ଯାଆନ୍ତୁ।",
                "ଆହୁରି କମ୍ପନ ହୋଇପାରେ। ଲିଫ୍ଟ ବ୍ୟବହାର କରନ୍ତୁ ନାହିଁ, ଏବଂ ଜିନିଷ ଆଣିବାକୁ ଭିତରକୁ ଫେରନ୍ତୁ ନାହିଁ।",
                "ବାହାରିନପାରିଲେ ଶକ୍ତ କାନ୍ଥ ପାଖରେ ବା ଶକ୍ତ ଟେବୁଲ ତଳେ ନିଜକୁ ନୁଆଁଇ ମୁଣ୍ଡ ବଞ୍ଚାନ୍ତୁ।",
            ),
            listOf(
                "Kung makalalabas nang hindi umaakyat sa guho, pumunta sa bukas na lugar na malayo sa pader, poste at puno.",
                "Asahan ang karagdagang pagyanig. Huwag gumamit ng elevator, at huwag nang bumalik sa loob para sa gamit.",
                "Kung hindi makalabas, manatiling mababa sa tabi ng matibay na pader o sa ilalim ng matibay na mesa at protektahan ang ulo.",
            )),
    ).associateBy { it.id }

    /** What every triage ends with: the request is not an instant rescue, so say what happens and what to do meanwhile. */
    fun sentNote(lang: Lang): String = pick(t(
        "Your request is being sent through nearby phones to the control room. This can take time. Stay where you are if it is safe, and keep your phone on.",
        "आपका अनुरोध पास के फ़ोनों के ज़रिए कंट्रोल रूम को भेजा जा रहा है। इसमें समय लग सकता है। सुरक्षित हो तो वहीं रहें, और फ़ोन चालू रखें।",
        "ଆପଣଙ୍କ ଅନୁରୋଧ ପାଖ ଫୋନ ମାଧ୍ୟମରେ କଣ୍ଟ୍ରୋଲ ରୁମକୁ ପଠାଯାଉଛି। ଏଥିରେ ସମୟ ଲାଗିପାରେ। ସୁରକ୍ଷିତ ଥିଲେ ସେଇଠି ରୁହନ୍ତୁ, ଫୋନ ଚାଲୁ ରଖନ୍ତୁ।",
        "Ipinapadala ang inyong kahilingan sa control room sa pamamagitan ng mga kalapit na telepono. Maaaring magtagal ito. Manatili kung ligtas, at panatilihing bukas ang telepono."), lang)

    // ---------------------------------------------------------------- places from the offline map

    // {place}, {dist} and {dir} are filled by code from the map pack and the phone's position, never by a model.
    private val placeTemplate: Map<PlaceAdvice, Map<Lang, String>> = mapOf(
        PlaceAdvice.GO_SHELTER to t(
            "Nearest shelter on the map: {place}, about {dist} to the {dir}. Go there only if you can move safely and the wind and water allow; otherwise stay where you are and wait for rescuers.",
            "नक्शे पर सबसे नज़दीकी आश्रय: {place}, लगभग {dist} {dir} की ओर। वहाँ तभी जाएँ जब आप सुरक्षित चल सकें और हवा और पानी इजाज़त दें; नहीं तो जहाँ हैं वहीं रहें और बचाव दल का इंतज़ार करें।",
            "ମ୍ୟାପରେ ସବୁଠାରୁ ନିକଟ ଆଶ୍ରୟସ୍ଥଳ: {place}, ପ୍ରାୟ {dist} {dir} ଦିଗରେ। ସୁରକ୍ଷିତ ଭାବେ ଯାଇପାରିଲେ ଏବଂ ପବନ ଓ ପାଣି ଅନୁମତି ଦେଲେ ହିଁ ସେଠାକୁ ଯାଆନ୍ତୁ; ନଚେତ୍ ଯେଉଁଠି ଅଛନ୍ତି ସେଠାରେ ରହି ଉଦ୍ଧାରକାରୀଙ୍କୁ ଅପେକ୍ଷା କରନ୍ତୁ।",
            "Pinakamalapit na silungan sa mapa: {place}, mga {dist} sa {dir}. Pumunta lamang doon kung ligtas kayong makakakilos at pinapayagan ng hangin at tubig; kung hindi, manatili kung nasaan kayo at hintayin ang mga tagasagip."),
        PlaceAdvice.HOSPITAL to t(
            "Nearest hospital on the map: {place}, about {dist} to the {dir}. Tell the rescuers. Take the person there yourself only if they can be moved and the way is safe.",
            "नक्शे पर सबसे नज़दीकी अस्पताल: {place}, लगभग {dist} {dir} की ओर। बचाव दल को बताएँ। मरीज़ को खुद तभी ले जाएँ जब उन्हें हिलाया जा सके और रास्ता सुरक्षित हो।",
            "ମ୍ୟାପରେ ସବୁଠାରୁ ନିକଟ ହସ୍ପିଟାଲ: {place}, ପ୍ରାୟ {dist} {dir} ଦିଗରେ। ଉଦ୍ଧାରକାରୀଙ୍କୁ କୁହନ୍ତୁ। ରୋଗୀଙ୍କୁ ହଲାଇ ହେଲେ ଏବଂ ରାସ୍ତା ସୁରକ୍ଷିତ ଥିଲେ ହିଁ ନିଜେ ନେଇଯାଆନ୍ତୁ।",
            "Pinakamalapit na ospital sa mapa: {place}, mga {dist} sa {dir}. Sabihin sa mga tagasagip. Dalhin lamang ninyo mismo ang tao kung maaari siyang ilipat at ligtas ang daan."),
        PlaceAdvice.AWAY_FROM_WATER to t(
            "{place} is about {dist} to the {dir}. Move away from it, to higher ground. Do not walk along the bank or through moving water.",
            "{place} लगभग {dist} {dir} की ओर है। उससे दूर, ऊँची जगह पर जाएँ। किनारे के साथ या बहते पानी में से न चलें।",
            "{place} ପ୍ରାୟ {dist} {dir} ଦିଗରେ ଅଛି। ସେଠାରୁ ଦୂରକୁ, ଉଚ୍ଚ ସ୍ଥାନକୁ ଯାଆନ୍ତୁ। କୂଳେ କୂଳେ କିମ୍ବା ବୋହୁଥିବା ପାଣି ଦେଇ ଚାଲନ୍ତୁ ନାହିଁ।",
            "Ang {place} ay mga {dist} sa {dir}. Lumayo rito, pumunta sa mataas na lugar. Huwag maglakad sa pampang o sa umaagos na tubig."),
    )

    private val compassLabel: Map<Compass, Map<Lang, String>> = mapOf(
        Compass.N to t("north", "उत्तर", "ଉତ୍ତର", "hilaga"),
        Compass.NE to t("north-east", "उत्तर-पूर्व", "ଉତ୍ତର-ପୂର୍ବ", "hilagang-silangan"),
        Compass.E to t("east", "पूर्व", "ପୂର୍ବ", "silangan"),
        Compass.SE to t("south-east", "दक्षिण-पूर्व", "ଦକ୍ଷିଣ-ପୂର୍ବ", "timog-silangan"),
        Compass.S to t("south", "दक्षिण", "ଦକ୍ଷିଣ", "timog"),
        Compass.SW to t("south-west", "दक्षिण-पश्चिम", "ଦକ୍ଷିଣ-ପଶ୍ଚିମ", "timog-kanluran"),
        Compass.W to t("west", "पश्चिम", "ପଶ୍ଚିମ", "kanluran"),
        Compass.NW to t("north-west", "उत्तर-पश्चिम", "ଉତ୍ତର-ପଶ୍ଚିମ", "hilagang-kanluran"),
    )
    private val metres = t("{n} metres", "{n} मीटर", "{n} ମିଟର", "{n} metro")
    private val kilometres = t("{n} kilometres", "{n} किलोमीटर", "{n} କିଲୋମିଟର", "{n} kilometro")

    /** "1.5 kilometres", "300 मीटर": units as words, so a voice reads them right. */
    fun distanceText(m: Int, lang: Lang): String {
        val km = MapContext.spokenDistance(m)
        if (m == 1000 && lang == Lang.EN) return "1 kilometre"
        return if (m < 1000) pick(metres, lang).replace("{n}", m.toString())
        else pick(kilometres, lang).replace("{n}", km.removeSuffix(" km"))
    }

    /** The advice naming [ref]'s place, in [lang]. Place names come from the map pack, in the reader's script when it has one. */
    fun placeText(advice: PlaceAdvice, ref: PlaceRef, lang: Lang): String =
        pick(placeTemplate.getValue(advice), lang)
            .replace("{place}", ref.place.nameIn(lang))
            .replace("{dist}", distanceText(ref.distanceM, lang))
            .replace("{dir}", pick(compassLabel.getValue(ref.bearing), lang))
}
