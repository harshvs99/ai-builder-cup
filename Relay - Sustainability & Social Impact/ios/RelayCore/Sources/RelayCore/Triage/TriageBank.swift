import Foundation

/// An answer to a yes/no triage question.
public enum Tri: String, Sendable, Hashable {
    case yes, no, unknown
    public var key: String { rawValue }
    public static func fromKey(_ k: String?) -> Tri? {
        guard let k else { return nil }
        return Tri(rawValue: k.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
    }
}

/// The fixed words triage says. Like `QuestionBank`, neither the rules nor a model ever writes any of it: they choose which
/// question to ask and which advisory applies, and these texts are what the person hears and reads. A mistranslated first
/// aid line is a safety failure, so there is no free-form model output on this path. The only words spliced in are a
/// relation from a fixed list ("your mother") or an ordinal ("the second person").
///
/// Non-English text is pending native-speaker review. Waray and Cebuano speakers get the Tagalog text; English is the
/// last resort. Generated from the Android `TriageBank.kt` (texts verbatim).
public enum TriageBank {
    private static func pick(_ m: [Lang: String], _ lang: Lang) -> String {
        if let v = m[lang] { return v }
        if lang == .war || lang == .ceb, let v = m[.tl] { return v }
        return m[.en]!
    }

    // ---------------------------------------------------------------- who

    private static let relationLabel: [Relation: [Lang: String]] = [
        .mother: [.en: "your mother", .hi: "आपकी माँ", .or: "ଆପଣଙ୍କ ମା", .tl: "ang inyong ina"],
        .father: [.en: "your father", .hi: "आपके पिता", .or: "ଆପଣଙ୍କ ବାପା", .tl: "ang inyong ama"],
        .wife: [.en: "your wife", .hi: "आपकी पत्नी", .or: "ଆପଣଙ୍କ ପତ୍ନୀ", .tl: "ang inyong asawa"],
        .husband: [.en: "your husband", .hi: "आपके पति", .or: "ଆପଣଙ୍କ ସ୍ୱାମୀ", .tl: "ang inyong asawa"],
        .son: [.en: "your son", .hi: "आपका बेटा", .or: "ଆପଣଙ୍କ ପୁଅ", .tl: "ang inyong anak na lalaki"],
        .daughter: [.en: "your daughter", .hi: "आपकी बेटी", .or: "ଆପଣଙ୍କ ଝିଅ", .tl: "ang inyong anak na babae"],
        .child: [.en: "your child", .hi: "आपका बच्चा", .or: "ଆପଣଙ୍କ ପିଲା", .tl: "ang inyong anak"],
        .baby: [.en: "your baby", .hi: "आपका शिशु", .or: "ଆପଣଙ୍କ ଶିଶୁ", .tl: "ang inyong sanggol"],
        .grandmother: [.en: "your grandmother", .hi: "आपकी दादी या नानी", .or: "ଆପଣଙ୍କ ଜେଜେମା", .tl: "ang inyong lola"],
        .grandfather: [.en: "your grandfather", .hi: "आपके दादा या नाना", .or: "ଆପଣଙ୍କ ଜେଜେବାପା", .tl: "ang inyong lolo"],
        .brother: [.en: "your brother", .hi: "आपका भाई", .or: "ଆପଣଙ୍କ ଭାଇ", .tl: "ang inyong kapatid na lalaki"],
        .sister: [.en: "your sister", .hi: "आपकी बहन", .or: "ଆପଣଙ୍କ ଭଉଣୀ", .tl: "ang inyong kapatid na babae"],
    ]
    private static let ordinalLabel: [[Lang: String]] = [
        [.en: "the first person", .hi: "पहला व्यक्ति", .or: "ପ୍ରଥମ ବ୍ୟକ୍ତି", .tl: "ang unang tao"],
        [.en: "the second person", .hi: "दूसरा व्यक्ति", .or: "ଦ୍ୱିତୀୟ ବ୍ୟକ୍ତି", .tl: "ang pangalawang tao"],
        [.en: "the third person", .hi: "तीसरा व्यक्ति", .or: "ତୃତୀୟ ବ୍ୟକ୍ତି", .tl: "ang pangatlong tao"],
    ]
    private static let theOnlyPerson: [Lang: String] = [.en: "the person", .hi: "वह व्यक्ति", .or: "ସେହି ବ୍ୟକ୍ତି", .tl: "ang tao"]

    /// How a question refers to subject `id` of `subjects`: "your mother", "the second person", or "the person" when alone.
    public static func who(_ subjects: [Subject], _ id: Int, _ lang: Lang) -> String {
        let s = subjects.first { $0.id == id }
        if let rel = s?.relation, let m = relationLabel[rel] { return pick(m, lang) }
        if subjects.count <= 1 { return pick(theOnlyPerson, lang) }
        return pick(ordinalLabel[min(max(id - 1, 0), ordinalLabel.count - 1)], lang)
    }

    // ---------------------------------------------------------------- questions

    // {who} is replaced by `who`. Phrased to work for any gender and the honorific form.
    private static let factText: [Fact: [Lang: String]] = [
        .conscious: [.en: "Is {who} awake and answering you?", .hi: "क्या {who} होश में हैं और आपको जवाब दे रहे हैं?", .or: "{who} ହୋସରେ ଅଛନ୍ତି କି ଏବଂ ଆପଣଙ୍କୁ ଉତ୍ତର ଦେଉଛନ୍ତି କି?", .tl: "Gising ba at sumasagot sa inyo {who}?"],
        .breathing: [.en: "Is {who} breathing normally?", .hi: "क्या {who} सामान्य रूप से साँस ले रहे हैं?", .or: "{who} ସାଧାରଣ ଭାବରେ ଶ୍ୱାସ ନେଉଛନ୍ତି କି?", .tl: "Humihinga ba nang normal {who}?"],
        .heavyBleeding: [.en: "Is {who} bleeding heavily, with the bleeding not stopping?", .hi: "क्या {who} को बहुत खून बह रहा है जो रुक नहीं रहा?", .or: "{who}: ବହୁତ ରକ୍ତ ବାହାରୁଛି କି, ଯାହା ବନ୍ଦ ହେଉନାହିଁ?", .tl: "May matinding pagdurugo ba {who} na hindi tumitigil?"],
        .anyoneHurt: [.en: "Is anyone with you hurt or unwell?", .hi: "क्या आपके साथ कोई घायल या बीमार है?", .or: "ଆପଣଙ୍କ ସାଙ୍ଗରେ କେହି ଆହତ ବା ଅସୁସ୍ଥ ଅଛନ୍ତି କି?", .tl: "May kasama ba kayong nasaktan o may sakit?"],
        .liveWire: [.en: "Is there a fallen electric pole or wire in the water or near you?", .hi: "क्या पानी में या आपके पास बिजली का खंभा या तार गिरा है?", .or: "ପାଣିରେ କିମ୍ବା ଆପଣଙ୍କ ପାଖରେ ବିଜୁଳି ଖୁଣ୍ଟ ବା ତାର ପଡ଼ିଛି କି?", .tl: "May nakabuwal bang poste o kable ng kuryente sa tubig o malapit sa inyo?"],
        .gasSmell: [.en: "Do you smell gas?", .hi: "क्या आपको गैस की गंध आ रही है?", .or: "ଆପଣଙ୍କୁ ଗ୍ୟାସ୍ ଗନ୍ଧ ଆସୁଛି କି?", .tl: "May naaamoy ba kayong gas?"],
        .buildingUnsafe: [.en: "Is the building cracked, leaning or damaged?", .hi: "क्या इमारत में दरारें हैं, वह झुकी है या क्षतिग्रस्त है?", .or: "ଘରରେ ଫାଟ ଅଛି, ଆଉଜି ପଡ଼ିଛି କିମ୍ବା କ୍ଷତିଗ୍ରସ୍ତ ହୋଇଛି କି?", .tl: "May bitak, nakatagilid, o nasira ba ang gusali?"],
        .stillShaking: [.en: "Is the ground still shaking?", .hi: "क्या ज़मीन अभी भी हिल रही है?", .or: "ଭୂଇଁ ଏବେ ବି କମ୍ପୁଛି କି?", .tl: "Umuuga pa rin ba ang lupa?"],
        .shelterHolding: [.en: "Are the roof and walls where you are holding up?", .hi: "जहाँ आप हैं वहाँ की छत और दीवारें टिकी हुई हैं?", .or: "ଆପଣ ଯେଉଁଠି ଅଛନ୍ତି ସେଠାର ଛାତ ଓ କାନ୍ଥ ଠିକ୍ ଅଛି କି?", .tl: "Matibay pa ba ang bubong at pader kung nasaan kayo?"],
        .slopeMoving: [.en: "Is the ground or hillside still moving?", .hi: "क्या ज़मीन या पहाड़ी अभी भी खिसक रही है?", .or: "ମାଟି ବା ପାହାଡ଼ ଏବେ ବି ଖସୁଛି କି?", .tl: "Gumagalaw o gumuguho pa ba ang lupa o gilid ng burol?"],
    ]

    private static let choiceText: [Choice: [Lang: String]] = [
        .patients: [.en: "How many people need medical help?", .hi: "कितने लोगों को चिकित्सा मदद चाहिए?", .or: "କେତେ ଜଣଙ୍କୁ ଚିକିତ୍ସା ସାହାଯ୍ୟ ଦରକାର?", .tl: "Ilang tao ang nangangailangan ng medikal na tulong?"],
        .location: [.en: "Where exactly are you?", .hi: "आप ठीक-ठीक कहाँ हैं?", .or: "ଆପଣ ଠିକ୍ କେଉଁଠି ଅଛନ୍ତି?", .tl: "Nasaan po kayo eksakto?"],
        .floor: [.en: "How high up are you?", .hi: "आप कितनी ऊँचाई पर हैं?", .or: "ଆପଣ କେତେ ଉଚ୍ଚରେ ଅଛନ୍ତି?", .tl: "Gaano kataas kayo?"],
        .waterDepth: [.en: "How deep is the water where you are?", .hi: "जहाँ आप हैं वहाँ पानी कितना गहरा है?", .or: "ଆପଣ ଯେଉଁଠି ଅଛନ୍ତି ସେଠାରେ ପାଣି କେତେ ଗଭୀର?", .tl: "Gaano kalalim ang tubig kung nasaan kayo?"],
    ]

    private static let optionLabel: [String: [Lang: String]] = [
        "roof": [.en: "On the roof", .hi: "छत पर", .or: "ଛାତ ଉପରେ", .tl: "Sa bubong"],
        "upper_floor": [.en: "On an upper floor", .hi: "ऊपरी मंज़िल पर", .or: "ଉପର ମହଲାରେ", .tl: "Sa itaas na palapag"],
        "ground_floor": [.en: "On the ground floor", .hi: "भूतल पर", .or: "ଭୂତଳ ମହଲାରେ", .tl: "Sa ibabang palapag"],
        "basement": [.en: "Basement or underground", .hi: "तहखाने या ज़मीन के नीचे", .or: "ତଳ ଘରେ ବା ମାଟି ତଳେ", .tl: "Sa basement o ilalim ng lupa"],
        "vehicle": [.en: "In a vehicle or boat", .hi: "गाड़ी या नाव में", .or: "ଗାଡ଼ି ବା ନାଆରେ", .tl: "Sa sasakyan o bangka"],
        "outdoors": [.en: "Outside, in the open", .hi: "बाहर, खुले में", .or: "ବାହାରେ, ଖୋଲା ସ୍ଥାନରେ", .tl: "Sa labas, bukas na lugar"],
        "rubble": [.en: "Under rubble or debris", .hi: "मलबे के नीचे", .or: "ଧ୍ୱଂସାବଶେଷ ତଳେ", .tl: "Sa ilalim ng guho"],
        "floor_1": [.en: "One floor up", .hi: "एक मंज़िल ऊपर", .or: "ଏକ ମହଲା ଉପରେ", .tl: "Isang palapag pataas"],
        "floor_2": [.en: "Two floors up", .hi: "दो मंज़िल ऊपर", .or: "ଦୁଇ ମହଲା ଉପରେ", .tl: "Dalawang palapag pataas"],
        "floor_3plus": [.en: "Three or more floors up", .hi: "तीन या अधिक मंज़िल ऊपर", .or: "ତିନି ବା ଅଧିକ ମହଲା ଉପରେ", .tl: "Tatlo o higit pang palapag pataas"],
        "ankle": [.en: "Ankle deep", .hi: "टखने तक", .or: "ଗୋଡ଼ ଗଣ୍ଠି ପର୍ଯ୍ୟନ୍ତ", .tl: "Hanggang bukung-bukong"],
        "knee": [.en: "Knee deep", .hi: "घुटने तक", .or: "ଆଣ୍ଠୁ ପର୍ଯ୍ୟନ୍ତ", .tl: "Hanggang tuhod"],
        "waist": [.en: "Waist deep", .hi: "कमर तक", .or: "ଅଣ୍ଟା ପର୍ଯ୍ୟନ୍ତ", .tl: "Hanggang baywang"],
        "chest": [.en: "Chest deep", .hi: "सीने तक", .or: "ଛାତି ପର୍ଯ୍ୟନ୍ତ", .tl: "Hanggang dibdib"],
        "over_head": [.en: "Over my head", .hi: "सिर से ऊपर", .or: "ମୁଣ୍ଡ ଉପରେ", .tl: "Higit sa ulo"],
        "one": [.en: "Just one person", .hi: "सिर्फ़ एक व्यक्ति", .or: "କେବଳ ଜଣେ", .tl: "Isang tao lang"],
        "two": [.en: "Two people", .hi: "दो लोग", .or: "ଦୁଇ ଜଣ", .tl: "Dalawang tao"],
        "three_plus": [.en: "Three or more", .hi: "तीन या ज़्यादा", .or: "ତିନି ବା ଅଧିକ", .tl: "Tatlo o higit pa"],
    ]

    public static func optionText(_ key: String, _ lang: Lang) -> String { pick(optionLabel[key]!, lang) }

    /// The text of a yes/no question about `subject` (ignored for scene facts).
    public static func question(_ f: Fact, subject: Int, subjects: [Subject], _ lang: Lang) -> String {
        if let m = factText[f] { return pick(m, lang).replacingOccurrences(of: "{who}", with: who(subjects, subject, lang)) }
        return QuestionBank.byId(f.bankId!)!.text(lang)
    }

    /// Single-subject wording, for callers without a conversation (tests, previews).
    public static func question(_ f: Fact, _ lang: Lang) -> String { question(f, subject: 1, subjects: [], lang) }

    public static func question(_ c: Choice, _ lang: Lang) -> String { pick(choiceText[c]!, lang) }

    // ---------------------------------------------------------------- advisories

    public enum AdvisoryId: CaseIterable, Sendable, Hashable { case cpr, recovery, bleeding, snake, labour, electric, flood, trapped, cyclone, landslide, gas, unsafeBuilding }

    /// Short lines, each speakable on its own. `priority`: higher is said first.
    public struct Advisory: Sendable {
        public let id: AdvisoryId
        public let priority: Int
        private let titles: [Lang: String]
        private let lineTable: [Lang: [String]]

        init(_ id: AdvisoryId, _ priority: Int, _ titles: [Lang: String], _ lines: [Lang: [String]]) {
            self.id = id; self.priority = priority; self.titles = titles; self.lineTable = lines
        }

        public func title(_ lang: Lang) -> String { TriageBank.pick(titles, lang) }
        public func lines(_ lang: Lang) -> [String] {
            if let v = lineTable[lang] { return v }
            if lang == .war || lang == .ceb, let v = lineTable[.tl] { return v }
            return lineTable[.en]!
        }
    }

    private static func a(_ id: AdvisoryId, _ priority: Int, _ title: [Lang: String], _ en: [String], _ hi: [String], _ or: [String], _ tl: [String]) -> Advisory {
        Advisory(id, priority, title, [.en: en, .hi: hi, .or: or, .tl: tl])
    }

    public static let advisories: [AdvisoryId: Advisory] = {
        let list: [Advisory] = [
            a(.cpr, 100, [.en: "Not breathing: start chest pushes", .hi: "साँस नहीं: छाती दबाना शुरू करें", .or: "ଶ୍ୱାସ ନାହିଁ: ଛାତି ଦବାନ୍ତୁ", .tl: "Hindi humihinga: simulan ang pagpindot sa dibdib"],
                [
                "Lay the person flat on their back on a firm surface.",
                "Push hard and fast in the middle of the chest, about two pushes every second. For a baby or small child, push gently with two fingers.",
                "Do not stop until they breathe or help arrives. Give nothing to eat or drink.",
            ],
                [
                "व्यक्ति को सख़्त जगह पर पीठ के बल सीधा लिटाएँ।",
                "छाती के बीच में ज़ोर से और तेज़ी से दबाएँ, हर सेकंड में लगभग दो बार। बच्चे के लिए दो उँगलियों से हल्के से दबाएँ।",
                "जब तक वह साँस न ले या मदद न आए, रुकें नहीं। कुछ खाने या पीने को न दें।",
            ],
                [
                "ବ୍ୟକ୍ତିଙ୍କୁ ଶକ୍ତ ସ୍ଥାନରେ ପିଠି ଉପରେ ସିଧା ଶୁଆନ୍ତୁ।",
                "ଛାତିର ମଝିରେ ଜୋରରେ ଓ ଶୀଘ୍ର ଦବାନ୍ତୁ, ପ୍ରତି ସେକେଣ୍ଡରେ ପ୍ରାୟ ଦୁଇଥର। ଶିଶୁ ପାଇଁ ଦୁଇ ଆଙ୍ଗୁଳିରେ ଧୀରେ ଦବାନ୍ତୁ।",
                "ସେ ଶ୍ୱାସ ନେଇଯିବା ପର୍ଯ୍ୟନ୍ତ କିମ୍ବା ସାହାଯ୍ୟ ଆସିବା ପର୍ଯ୍ୟନ୍ତ ବନ୍ଦ କରନ୍ତୁ ନାହିଁ। କିଛି ଖାଇବାକୁ ବା ପିଇବାକୁ ଦିଅନ୍ତୁ ନାହିଁ।",
            ],
                [
                "Ihiga ang tao nang nakatihaya sa matigas na lapag.",
                "Diinan nang malakas at mabilis ang gitna ng dibdib, mga dalawang diin bawat segundo. Sa sanggol o batang maliit, dahan-dahang diinan gamit ang dalawang daliri.",
                "Huwag tumigil hanggang makahinga siya o dumating ang tulong. Huwag bigyan ng pagkain o inumin.",
            ]),
            a(.recovery, 90, [.en: "Not awake: keep the airway open", .hi: "होश में नहीं: साँस का रास्ता खुला रखें", .or: "ହୋସ୍ ନାହିଁ: ଶ୍ୱାସ ପଥ ଖୋଲା ରଖନ୍ତୁ", .tl: "Walang malay: panatilihing bukas ang daanan ng hangin"],
                [
                "Roll the person onto their side, with the top knee bent and the head tilted back a little.",
                "Stay with them and watch their breathing. If it stops, start chest pushes.",
                "Give nothing to eat or drink.",
            ],
                [
                "व्यक्ति को करवट से लिटाएँ, ऊपर वाला घुटना मोड़ें और सिर को थोड़ा पीछे झुकाएँ।",
                "उनके पास रहें और साँस देखते रहें। साँस रुक जाए तो छाती दबाना शुरू करें।",
                "कुछ खाने या पीने को न दें।",
            ],
                [
                "ବ୍ୟକ୍ତିଙ୍କୁ କଡ଼ ପାଖେ ଶୁଆନ୍ତୁ, ଉପର ଆଣ୍ଠୁ ମୋଡ଼ନ୍ତୁ ଓ ମୁଣ୍ଡକୁ ଟିକେ ପଛକୁ ନୁଆଁନ୍ତୁ।",
                "ସାଙ୍ଗରେ ରୁହନ୍ତୁ ଓ ଶ୍ୱାସ ଦେଖନ୍ତୁ। ଶ୍ୱାସ ବନ୍ଦ ହେଲେ ଛାତି ଦବାନ୍ତୁ।",
                "କିଛି ଖାଇବାକୁ ବା ପିଇବାକୁ ଦିଅନ୍ତୁ ନାହିଁ।",
            ],
                [
                "Ihiga ang tao nang nakatagilid, nakabaluktot ang tuhod sa itaas, at bahagyang nakatingala ang ulo.",
                "Manatili sa tabi niya at bantayan ang paghinga. Kapag tumigil, simulan ang pagpindot sa dibdib.",
                "Huwag bigyan ng pagkain o inumin.",
            ]),
            a(.bleeding, 80, [.en: "Heavy bleeding: press on the wound", .hi: "ज़्यादा खून: घाव को दबाएँ", .or: "ଅଧିକ ରକ୍ତ: କ୍ଷତକୁ ଦବାନ୍ତୁ", .tl: "Matinding pagdurugo: diinan ang sugat"],
                [
                "Press firmly on the wound with a clean cloth. Do not lift it to look; add more cloth on top if it soaks through.",
                "Keep pressing for at least ten minutes. If it is an arm or leg, raise it above the heart.",
                "Keep the person lying down and warm.",
            ],
                [
                "साफ़ कपड़े से घाव को मज़बूती से दबाएँ। देखने के लिए उठाएँ नहीं; भीग जाए तो ऊपर और कपड़ा रखें।",
                "कम से कम दस मिनट तक दबाए रखें। हाथ या पैर हो तो उसे दिल से ऊपर उठाएँ।",
                "व्यक्ति को लेटा रखें और गर्म रखें।",
            ],
                [
                "ସଫା କପଡ଼ାରେ କ୍ଷତକୁ ଜୋରରେ ଦବାନ୍ତୁ। ଦେଖିବା ପାଇଁ ଉଠାନ୍ତୁ ନାହିଁ; ଭିଜିଗଲେ ଉପରେ ଆଉ କପଡ଼ା ରଖନ୍ତୁ।",
                "କମ୍ ରୁ କମ୍ ଦଶ ମିନିଟ୍ ଦବାଇ ରଖନ୍ତୁ। ହାତ ବା ଗୋଡ଼ ହେଲେ ହୃଦୟଠାରୁ ଉପରକୁ ଉଠାନ୍ତୁ।",
                "ବ୍ୟକ୍ତିଙ୍କୁ ଶୋଇ ରହିବାକୁ ଦିଅନ୍ତୁ ଓ ଗରମ ରଖନ୍ତୁ।",
            ],
                [
                "Diinan nang mahigpit ang sugat gamit ang malinis na tela. Huwag itaas para tingnan; dagdagan ng tela sa ibabaw kung mabasa.",
                "Diinan nang hindi bababa sa sampung minuto. Kung braso o binti, itaas ito nang mas mataas sa puso.",
                "Panatilihing nakahiga at mainit ang tao.",
            ]),
            a(.snake, 70, [.en: "Snake bite: keep still", .hi: "साँप का काटना: स्थिर रहें", .or: "ସାପ କାମୁଡ଼ା: ସ୍ଥିର ରୁହନ୍ତୁ", .tl: "Kagat ng ahas: manatiling hindi gumagalaw"],
                [
                "Keep the person calm and still. Keep the bitten arm or leg below the level of the heart.",
                "Take off rings and tight clothes near the bite. Do not cut the bite, suck it, or tie anything tight.",
                "Note the time of the bite. They need anti-venom at a hospital as soon as it can be reached.",
            ],
                [
                "व्यक्ति को शांत और स्थिर रखें। काटे गए हाथ या पैर को दिल के स्तर से नीचे रखें।",
                "काटे की जगह के पास से अँगूठी और तंग कपड़े उतार दें। घाव को काटें नहीं, चूसें नहीं, और कसकर कुछ न बाँधें।",
                "काटने का समय याद रखें। जैसे ही पहुँच सकें, अस्पताल में एंटी-वेनम चाहिए।",
            ],
                [
                "ବ୍ୟକ୍ତିଙ୍କୁ ଶାନ୍ତ ଓ ସ୍ଥିର ରଖନ୍ତୁ। କାମୁଡ଼ା ହାତ ବା ଗୋଡ଼କୁ ହୃଦୟ ତଳେ ରଖନ୍ତୁ।",
                "କାମୁଡ଼ା ପାଖରୁ ମୁଦି ଓ ଟାଇଟ୍ ଲୁଗା ଖୋଲିଦିଅନ୍ତୁ। କ୍ଷତ କାଟନ୍ତୁ ନାହିଁ, ଚୁଷନ୍ତୁ ନାହିଁ, ଟାଣି ବାନ୍ଧନ୍ତୁ ନାହିଁ।",
                "କାମୁଡ଼ିବା ସମୟ ମନେ ରଖନ୍ତୁ। ପହଞ୍ଚିପାରିଲେ ଡାକ୍ତରଖାନାରେ ଆଣ୍ଟି-ଭେନମ୍ ଦରକାର।",
            ],
                [
                "Panatilihing kalmado at hindi gumagalaw ang tao. Ibaba ang kinagat na braso o binti kaysa sa puso.",
                "Tanggalin ang singsing at masisikip na damit malapit sa kagat. Huwag hiwain, sipsipin, o gapusin nang mahigpit.",
                "Tandaan ang oras ng kagat. Kailangan nila ng anti-venom sa ospital sa lalong madaling panahon.",
            ]),
            a(.labour, 70, [.en: "Childbirth: stay calm, lie on the side", .hi: "प्रसव: शांत रहें, करवट लेटें", .or: "ପ୍ରସବ: ଶାନ୍ତ ରୁହନ୍ତୁ, କଡ଼ ପାଖେ ଶୁଅନ୍ତୁ", .tl: "Panganganak: manatiling kalmado, humiga nang patagilid"],
                [
                "Find a clean, dry, warm place and let her lie on her side.",
                "Do not pull the baby. If the baby comes, dry it with a clean cloth, keep it on her chest skin to skin, and cover both.",
                "Do not cut the cord. Keep both warm and wait for help.",
            ],
                [
                "साफ़, सूखी, गर्म जगह ढूँढें और उन्हें करवट से लिटाएँ।",
                "बच्चे को खींचें नहीं। बच्चा हो जाए तो साफ़ कपड़े से पोंछें, माँ की छाती पर त्वचा से लगाकर रखें और दोनों को ढकें।",
                "नाल न काटें। दोनों को गर्म रखें और मदद का इंतज़ार करें।",
            ],
                [
                "ସଫା, ଶୁଖିଲା, ଗରମ ସ୍ଥାନ ଖୋଜନ୍ତୁ ଓ ତାଙ୍କୁ କଡ଼ ପାଖେ ଶୁଆନ୍ତୁ।",
                "ଶିଶୁକୁ ଟାଣନ୍ତୁ ନାହିଁ। ଶିଶୁ ଜନ୍ମ ହେଲେ ସଫା କପଡ଼ାରେ ପୋଛନ୍ତୁ, ମାଆର ଛାତିରେ ଚର୍ମ ସହ ଲଗାଇ ରଖନ୍ତୁ ଓ ଦୁହିଁଙ୍କୁ ଘୋଡ଼ାନ୍ତୁ।",
                "ନାଭି ନଳୀ କାଟନ୍ତୁ ନାହିଁ। ଦୁହିଁଙ୍କୁ ଗରମ ରଖି ସାହାଯ୍ୟ ଅପେକ୍ଷା କରନ୍ତୁ।",
            ],
                [
                "Humanap ng malinis, tuyo, at mainit na lugar at ihiga siya nang patagilid.",
                "Huwag hilahin ang sanggol. Kung ipanganak na, punasan ng malinis na tela, ilagay sa dibdib niya nang balat sa balat, at takpan silang dalawa.",
                "Huwag putulin ang pusod. Panatilihing mainit ang dalawa at hintayin ang tulong.",
            ]),
            a(.electric, 95, [.en: "Fallen pole or wire: keep away", .hi: "गिरा खंभा या तार: दूर रहें", .or: "ପଡ଼ିଥିବା ଖୁଣ୍ଟ ବା ତାର: ଦୂରରେ ରୁହନ୍ତୁ", .tl: "Nakabuwal na poste o kable: lumayo"],
                [
                "Stay far away from any fallen pole or wire, at least ten metres. Water around it can carry electricity.",
                "Do not wade through water near a pole or wire. Do not touch anyone who is touching a wire.",
                "Switch off the mains power only if the switch is dry and you can reach it without standing in water.",
            ],
                [
                "गिरे हुए खंभे या तार से कम से कम दस मीटर दूर रहें। आसपास का पानी बिजली ले जा सकता है।",
                "खंभे या तार के पास के पानी में न चलें। तार को छू रहे व्यक्ति को न छुएँ।",
                "मेन स्विच तभी बंद करें जब वह सूखा हो और पानी में खड़े हुए बिना पहुँच सकें।",
            ],
                [
                "ପଡ଼ିଥିବା ଖୁଣ୍ଟ ବା ତାରଠାରୁ ଅନ୍ତତଃ ଦଶ ମିଟର ଦୂରରେ ରୁହନ୍ତୁ। ଆଖପାଖ ପାଣି ବିଜୁଳି ବହନ କରିପାରେ।",
                "ଖୁଣ୍ଟ ବା ତାର ପାଖ ପାଣିରେ ଚାଲନ୍ତୁ ନାହିଁ। ତାରକୁ ଛୁଇଁଥିବା ଲୋକଙ୍କୁ ଛୁଅନ୍ତୁ ନାହିଁ।",
                "ମେନ୍ ସୁଇଚ୍ ଶୁଖିଲା ଥିଲେ ଓ ପାଣିରେ ନ ଛିଡ଼ା ହୋଇ ପହଞ୍ଚିପାରିଲେ ହିଁ ବନ୍ଦ କରନ୍ତୁ।",
            ],
                [
                "Lumayo sa anumang nakabuwal na poste o kable, hindi bababa sa sampung metro. Maaaring dumaloy ang kuryente sa tubig sa paligid nito.",
                "Huwag lumusong sa tubig malapit sa poste o kable. Huwag hawakan ang sinumang humahawak sa kable.",
                "Patayin ang main switch kung tuyo ito at maaabot nang hindi nakatayo sa tubig.",
            ]),
            a(.flood, 85, [.en: "Rising water: go up", .hi: "बढ़ता पानी: ऊपर जाएँ", .or: "ବଢ଼ୁଥିବା ପାଣି: ଉପରକୁ ଯାଆନ୍ତୁ", .tl: "Tumataas na tubig: umakyat"],
                [
                "Move to the highest safe place, an upper floor or a strong roof, before the water reaches you.",
                "Do not walk or drive through moving water. Even knee-deep water can knock a person over.",
                "Take drinking water, medicine, a torch and your phone. Stay where rescuers can see you, and wave a bright cloth.",
            ],
                [
                "पानी पहुँचने से पहले सबसे ऊँची सुरक्षित जगह जाएँ: ऊपरी मंज़िल या मज़बूत छत।",
                "बहते पानी में न चलें और न गाड़ी चलाएँ। घुटने तक का पानी भी इंसान को गिरा सकता है।",
                "पीने का पानी, दवाइयाँ, टॉर्च और फ़ोन साथ लें। ऐसी जगह रहें जहाँ बचावकर्ता देख सकें, और चमकीला कपड़ा हिलाएँ।",
            ],
                [
                "ପାଣି ପହଞ୍ଚିବା ପୂର୍ବରୁ ସବୁଠାରୁ ଉଚ୍ଚ ସୁରକ୍ଷିତ ସ୍ଥାନକୁ ଯାଆନ୍ତୁ: ଉପର ମହଲା ବା ଶକ୍ତ ଛାତ।",
                "ବହୁଥିବା ପାଣିରେ ଚାଲନ୍ତୁ ନାହିଁ କି ଗାଡ଼ି ଚଲାନ୍ତୁ ନାହିଁ। ଆଣ୍ଠୁ ପର୍ଯ୍ୟନ୍ତ ପାଣିରେ ବି ଲୋକ ପଡ଼ିଯାଇପାରେ।",
                "ପିଇବା ପାଣି, ଔଷଧ, ଟର୍ଚ୍ଚ ଓ ଫୋନ ନିଅନ୍ତୁ। ଉଦ୍ଧାରକାରୀ ଦେଖିପାରିବେ ଏପରି ସ୍ଥାନରେ ରୁହନ୍ତୁ ଓ ଉଜ୍ଜ୍ୱଳ କପଡ଼ା ହଲାନ୍ତୁ।",
            ],
                [
                "Pumunta sa pinakamataas na ligtas na lugar, itaas na palapag o matibay na bubong, bago umabot ang tubig.",
                "Huwag lumusong o magmaneho sa umaagos na tubig. Kahit hanggang tuhod ay maaaring matumba ang tao.",
                "Dalhin ang inuming tubig, gamot, flashlight, at telepono. Manatili kung saan makikita ng mga rescuer, at iwagayway ang maliwanag na tela.",
            ]),
            a(.trapped, 75, [.en: "Trapped: save your breath, make noise", .hi: "फँसे हैं: ताक़त बचाएँ, आवाज़ करें", .or: "ଫସିଛନ୍ତି: ଶକ୍ତି ବଞ୍ଚାନ୍ତୁ, ଶବ୍ଦ କରନ୍ତୁ", .tl: "Naipit: magtipid ng lakas, gumawa ng ingay"],
                [
                "Cover your mouth and nose with cloth to keep out dust.",
                "Tap on a pipe or wall, or blow a whistle. Shout only as a last resort, it wastes strength.",
                "Do not light a match or lighter, there may be gas. Move as little as possible.",
            ],
                [
                "धूल से बचने के लिए मुँह और नाक कपड़े से ढकें।",
                "पाइप या दीवार थपथपाएँ, या सीटी बजाएँ। चिल्लाना आख़िरी उपाय है, इससे ताक़त जाती है।",
                "माचिस या लाइटर न जलाएँ, गैस हो सकती है। जितना कम हो सके हिलें।",
            ],
                [
                "ଧୂଳିରୁ ବଞ୍ଚିବାକୁ ପାଟି ଓ ନାକକୁ କପଡ଼ାରେ ଘୋଡ଼ାନ୍ତୁ।",
                "ପାଇପ୍ ବା କାନ୍ଥରେ ଠକ୍ ଠକ୍ କରନ୍ତୁ, କିମ୍ବା ସିଟି ବଜାନ୍ତୁ। ଚିତ୍କାର ଶେଷ ଉପାୟ, ଏଥିରେ ଶକ୍ତି ଯାଏ।",
                "ଦିଆସିଲି ବା ଲାଇଟର୍ ଜଳାନ୍ତୁ ନାହିଁ, ଗ୍ୟାସ୍ ଥାଇପାରେ। ଯେତେ କମ୍ ହେଲାନ୍ତୁ।",
            ],
                [
                "Takpan ng tela ang bibig at ilong para hindi malanghap ang alikabok.",
                "Kumatok sa tubo o pader, o gumamit ng pito. Sumigaw lang bilang huling paraan, nakakaubos ito ng lakas.",
                "Huwag magsindi ng posporo o lighter, maaaring may gas. Kumilos nang kaunti hangga't maaari.",
            ]),
            a(.cyclone, 60, [.en: "Storm: stay in a strong building", .hi: "तूफ़ान: मज़बूत इमारत में रहें", .or: "ଝଡ଼: ଶକ୍ତ ଘରେ ରୁହନ୍ତୁ", .tl: "Bagyo: manatili sa matibay na gusali"],
                [
                "Stay inside a strong building, in a room without windows if you can. Do not go out until the storm has fully passed.",
                "The calm in the middle of a cyclone is not the end. Wait for the second wind to pass.",
                "Keep water, torch, phone charge and medicine close.",
            ],
                [
                "मज़बूत इमारत के अंदर रहें, हो सके तो बिना खिड़की वाले कमरे में। तूफ़ान पूरी तरह गुज़रने तक बाहर न निकलें।",
                "चक्रवात के बीच की शांति अंत नहीं है। दूसरी हवा गुज़रने तक रुकें।",
                "पानी, टॉर्च, फ़ोन चार्ज और दवाइयाँ पास रखें।",
            ],
                [
                "ଶକ୍ତ ଘର ଭିତରେ ରୁହନ୍ତୁ, ସମ୍ଭବ ହେଲେ ଝରକା ନଥିବା କୋଠରୀରେ। ଝଡ଼ ପୂରା ଗଲା ପରେ ହିଁ ବାହାରକୁ ଯାଆନ୍ତୁ।",
                "ବାତ୍ୟା ମଝିର ଶାନ୍ତି ଶେଷ ନୁହେଁ। ଦ୍ୱିତୀୟ ପବନ ଗଲା ପର୍ଯ୍ୟନ୍ତ ଅପେକ୍ଷା କରନ୍ତୁ।",
                "ପାଣି, ଟର୍ଚ୍ଚ, ଫୋନ୍ ଚାର୍ଜ ଓ ଔଷଧ ପାଖରେ ରଖନ୍ତୁ।",
            ],
                [
                "Manatili sa loob ng matibay na gusali, sa silid na walang bintana kung maaari. Huwag lumabas hangga't hindi lubusang lumipas ang bagyo.",
                "Ang katahimikan sa gitna ng bagyo ay hindi pa ang wakas. Hintayin ang ikalawang hangin.",
                "Ilapit ang tubig, flashlight, charge ng telepono, at gamot.",
            ]),
            a(.landslide, 65, [.en: "Landslide: move sideways, not downhill", .hi: "भूस्खलन: बगल में हटें, नीचे नहीं", .or: "ଭୂସ୍ଖଳନ: ପାଖକୁ ହଟନ୍ତୁ, ତଳକୁ ନୁହେଁ", .tl: "Guho: lumayo nang patagilid, hindi pababa"],
                [
                "Move away from the slope to the side, out of the path of falling earth. Do not run downhill along it.",
                "Stay away from the area. More earth can fall after the first slide.",
                "Do not return to a damaged building until it has been checked.",
            ],
                [
                "ढलान से बगल की ओर हटें, गिरती मिट्टी के रास्ते से बाहर। उसी के साथ नीचे की ओर न भागें।",
                "उस इलाके से दूर रहें। पहले खिसकाव के बाद और मिट्टी गिर सकती है।",
                "क्षतिग्रस्त इमारत में जाँच होने तक वापस न जाएँ।",
            ],
                [
                "ଢଳରୁ ପାଖକୁ ହଟନ୍ତୁ, ଖସୁଥିବା ମାଟିର ବାଟରୁ ବାହାରକୁ। ସେହି ବାଟରେ ତଳକୁ ଦୌଡ଼ନ୍ତୁ ନାହିଁ।",
                "ସେହି ଅଞ୍ଚଳରୁ ଦୂରରେ ରୁହନ୍ତୁ। ପ୍ରଥମ ଖସିବା ପରେ ଆଉ ମାଟି ଖସିପାରେ।",
                "କ୍ଷତିଗ୍ରସ୍ତ ଘରକୁ ଯାଞ୍ଚ ନହେବା ପର୍ଯ୍ୟନ୍ତ ଫେରନ୍ତୁ ନାହିଁ।",
            ],
                [
                "Lumayo sa gilid ng dalisdis, labas sa daraanan ng gumuguhong lupa. Huwag tumakbo pababa kasabay nito.",
                "Lumayo sa lugar. Maaaring may gumuho pang lupa pagkatapos ng una.",
                "Huwag bumalik sa nasirang gusali hangga't hindi ito nasusuri.",
            ]),
            a(.gas, 92, [.en: "Gas smell: no flames, no switches", .hi: "गैस की गंध: आग या स्विच नहीं", .or: "ଗ୍ୟାସ୍ ଗନ୍ଧ: ନିଆଁ ବା ସୁଇଚ୍ ନୁହେଁ", .tl: "Amoy gas: walang apoy, walang switch"],
                [
                "Do not light a flame, match or lighter, and do not switch any electrical device on or off.",
                "Open windows and doors if you can reach them safely, and move away from the smell.",
                "If you can, close the gas cylinder valve. Do not use your phone near the smell if you can step away first.",
            ],
                [
                "आग, माचिस या लाइटर न जलाएँ, और कोई बिजली का स्विच चालू या बंद न करें।",
                "सुरक्षित पहुँच में हों तो खिड़की-दरवाज़े खोलें, और गंध से दूर हट जाएँ।",
                "हो सके तो गैस सिलेंडर का वाल्व बंद करें। हो सके तो पहले गंध से दूर जाकर फिर फ़ोन इस्तेमाल करें।",
            ],
                [
                "ନିଆଁ, ଦିଆସିଲି ବା ଲାଇଟର୍ ଜଳାନ୍ତୁ ନାହିଁ, ଏବଂ କୌଣସି ବିଜୁଳି ସୁଇଚ୍ ଚାଲୁ ବା ବନ୍ଦ କରନ୍ତୁ ନାହିଁ।",
                "ସୁରକ୍ଷିତ ଭାବେ ପହଞ୍ଚିପାରିଲେ ଝରକା ଓ ଦ୍ୱାର ଖୋଲନ୍ତୁ, ଏବଂ ଗନ୍ଧଠାରୁ ଦୂରକୁ ଯାଆନ୍ତୁ।",
                "ସମ୍ଭବ ହେଲେ ଗ୍ୟାସ୍ ସିଲିଣ୍ଡରର ଭାଲ୍ବ ବନ୍ଦ କରନ୍ତୁ। ପ୍ରଥମେ ଗନ୍ଧଠାରୁ ଦୂରକୁ ଯାଇ ତା'ପରେ ଫୋନ ବ୍ୟବହାର କରନ୍ତୁ।",
            ],
                [
                "Huwag magsindi ng apoy, posporo o lighter, at huwag buksan o patayin ang anumang de-kuryenteng gamit.",
                "Buksan ang mga bintana at pinto kung ligtas na maaabot, at lumayo sa amoy.",
                "Kung kaya, isara ang balbula ng tangke ng gas. Lumayo muna sa amoy bago gamitin ang telepono.",
            ]),
            a(.unsafeBuilding, 88, [.en: "Damaged building: get out to open ground", .hi: "क्षतिग्रस्त इमारत: खुली जगह पर निकलें", .or: "କ୍ଷତିଗ୍ରସ୍ତ ଘର: ଖୋଲା ସ୍ଥାନକୁ ବାହାରନ୍ତୁ", .tl: "Nasirang gusali: lumabas sa bukas na lugar"],
                [
                "If you can leave without climbing through debris, go to open ground away from walls, poles and trees.",
                "Expect more shaking. Do not use lifts, and do not go back inside for belongings.",
                "If you cannot leave, stay low next to a strong wall or under a sturdy table and protect your head.",
            ],
                [
                "मलबे पर चढ़े बिना निकल सकें तो दीवारों, खंभों और पेड़ों से दूर खुली जगह पर जाएँ।",
                "और झटके आ सकते हैं। लिफ़्ट का उपयोग न करें, और सामान लेने वापस अंदर न जाएँ।",
                "निकल न सकें तो मज़बूत दीवार के पास या मज़बूत मेज़ के नीचे झुके रहें और सिर बचाएँ।",
            ],
                [
                "ଧ୍ୱଂସାବଶେଷ ଉପରେ ନ ଚଢ଼ି ବାହାରିପାରିଲେ କାନ୍ଥ, ଖୁଣ୍ଟ ଓ ଗଛଠାରୁ ଦୂରରେ ଖୋଲା ସ୍ଥାନକୁ ଯାଆନ୍ତୁ।",
                "ଆହୁରି କମ୍ପନ ହୋଇପାରେ। ଲିଫ୍ଟ ବ୍ୟବହାର କରନ୍ତୁ ନାହିଁ, ଏବଂ ଜିନିଷ ଆଣିବାକୁ ଭିତରକୁ ଫେରନ୍ତୁ ନାହିଁ।",
                "ବାହାରିନପାରିଲେ ଶକ୍ତ କାନ୍ଥ ପାଖରେ ବା ଶକ୍ତ ଟେବୁଲ ତଳେ ନିଜକୁ ନୁଆଁଇ ମୁଣ୍ଡ ବଞ୍ଚାନ୍ତୁ।",
            ],
                [
                "Kung makalalabas nang hindi umaakyat sa guho, pumunta sa bukas na lugar na malayo sa pader, poste at puno.",
                "Asahan ang karagdagang pagyanig. Huwag gumamit ng elevator, at huwag nang bumalik sa loob para sa gamit.",
                "Kung hindi makalabas, manatiling mababa sa tabi ng matibay na pader o sa ilalim ng matibay na mesa at protektahan ang ulo.",
            ]),
        ]
        return Dictionary(uniqueKeysWithValues: list.map { ($0.id, $0) })
    }()

    /// What every triage ends with: the request is not an instant rescue, so say what happens and what to do meanwhile.
    public static func sentNote(_ lang: Lang) -> String {
        pick([.en: "Your request is being sent through nearby phones to the control room. This can take time. Stay where you are if it is safe, and keep your phone on.", .hi: "आपका अनुरोध पास के फ़ोनों के ज़रिए कंट्रोल रूम को भेजा जा रहा है। इसमें समय लग सकता है। सुरक्षित हो तो वहीं रहें, और फ़ोन चालू रखें।", .or: "ଆପଣଙ୍କ ଅନୁରୋଧ ପାଖ ଫୋନ ମାଧ୍ୟମରେ କଣ୍ଟ୍ରୋଲ ରୁମକୁ ପଠାଯାଉଛି। ଏଥିରେ ସମୟ ଲାଗିପାରେ। ସୁରକ୍ଷିତ ଥିଲେ ସେଇଠି ରୁହନ୍ତୁ, ଫୋନ ଚାଲୁ ରଖନ୍ତୁ।", .tl: "Ipinapadala ang inyong kahilingan sa control room sa pamamagitan ng mga kalapit na telepono. Maaaring magtagal ito. Manatili kung ligtas, at panatilihing bukas ang telepono."], lang)
    }
}
