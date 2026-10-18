import Foundation

/// One thing a requester can be asking for / reporting. [context] concepts (flood, roof) only decide
/// the category when nothing more specific matched. When several concepts of the winning category match, the
/// item label comes from the most urgent [request] concept ("send a doctor"), else from the most urgent concept.
final class Concept: Sendable {
    let id: String
    let category: Category
    let urgency: Int
    let item: String?
    let phrase: String
    let flags: Set<VulnFlag>
    let context: Bool
    /// True for something a person asks to be sent (medicine, water, an ambulance), as opposed to a condition.
    let request: Bool
    let lex: Lex

    init(_ id: String, _ category: Category, _ urgency: Int, _ item: String?, _ phrase: String,
         _ flags: Set<VulnFlag> = [], context: Bool = false, request: Bool = false, lex: Lex) {
        self.id = id; self.category = category; self.urgency = urgency; self.item = item; self.phrase = phrase
        self.flags = flags; self.context = context; self.request = request; self.lex = lex
    }
}

enum Lexicons {
    private static let none = Set<VulnFlag>()
    private static let injured: Set<VulnFlag> = [.injured]
    private static let trapped: Set<VulnFlag> = [.trapped]
    private static let pregnant: Set<VulnFlag> = [.pregnant]

    /// Tie-break between categories of equal urgency.
    static func categoryRank(_ c: Category) -> Int {
        switch c {
        case .medical: return 0
        case .rescue: return 1
        case .missingPerson: return 2
        case .water: return 3
        case .food: return 4
        case .shelter: return 5
        case .other: return 6
        }
    }

    static func defaultItem(_ c: Category) -> String {
        switch c {
        case .medical: return "medical help"
        case .rescue: return "rescue"
        case .water: return "water"
        case .food: return "food"
        case .shelter: return "shelter"
        case .missingPerson: return "missing person"
        case .other: return ""
        }
    }

    // Order = tie-break priority within equal (urgency, category).
    static let concepts: [Concept] = [
        // ---------------- MEDICAL, life threatening (3)
        Concept("insulin", .medical, 3, "insulin", "needs insulin", request: true, lex: Lex(
            "insulin*", "इंसुलिन*", "इन्सुलिन*", "ଇନସୁଲିନ*", "ইনসুলিন*")),
        Concept("oxygen", .medical, 3, "oxygen", "needs oxygen", request: true, lex: Lex(
            "oxygen*", "oxigen*", "oksijen*", "ऑक्सीजन*", "ओक्सीजन*", "ଅକ୍ସିଜେନ*")),
        Concept("not_breathing", .medical, 3, "first aid", "not breathing", lex: Lex(
            "~not\\s+breathing", "~(?:can'?t|cannot|unable\\s+to)\\s+breathe", "stopped breathing", "no breath",
            "~s(?:aa|a)ns\\s+(?:nahi|nahin|ruk\\w*|band)", "~(?:सांस|साँस)\\s+(?:नहीं|रुक\\w*|बंद)",
            "~hindi\\s+(?:maka-?hinga|humi-?hinga)", "~(?:dili|diri)\\s+(?:maka-?ginhawa|maka-?ginhawa)",
            "~ଶ୍[ୱବ]ାସ\\s+(?:ନାହିଁ|ବନ୍ଦ)")),
        Concept("unconscious", .medical, 3, "first aid", "unconscious", lex: Lex(
            "unconscious*", "passed out", "fainted", "faint*", "behosh*", "behoosh*", "bebosh*",
            "बेहोश*", "ବେହୋସ*", "ବେହୁସ*", "nahimatay*", "walang malay", "nawalan ng malay",
            "~nawad-?an\\s+og\\s+panimuot", "~wala(?:'y|y)?\\s+panimuot")),
        Concept("bleeding", .medical, 3, "first aid", "bleeding", injured, lex: Lex(
            "bleed*", "blood*", "khoon*", "खून*", "रक्त*", "ରକ୍ତ*", "rakt*", "dugo*", "nagdudugo*",
            "nagdugo*", "pagdugo*", "gidugo*")),
        Concept("snake", .medical, 3, "snake bite", "snake bite", lex: Lex(
            "snake*", "saanp*", "sanp*", "सांप*", "साँप*", "सर्प*", "sarp*", "ସାପ*", "sapa*", "ahas*", "bitin*",
            "halas*", "tuklaw*", "natuklaw*")),
        Concept("labour", .medical, 3, "delivery help", "in labour", pregnant, lex: Lex(
            "labour*", "labor", "~in\\s+labor", "delivery pain*", "~delivery\\s+(?:time|due|case)", "prasav*",
            "prasab*", "प्रसव*", "ପ୍ରସବ*", "manganak*", "manganganak*", "nanganak*", "nanganganak*", "panganganak*", "nagpanganak*",
            "pa-anak*")),
        Concept("critical", .medical, 3, "medical help", "critical condition", lex: Lex(
            "heart attack*", "stroke", "seizure*", "convuls*", "chest pain*", "dil ka daura", "दिल का दौरा",
            "हार्ट अटैक", "hemorrhag*", "haemorrhag*")),
        // ---------------- RESCUE, life threatening (3)
        Concept("drowning", .rescue, 3, "rescue", "drowning", lex: Lex(
            "drown*", "doob*", "~du+b(?:a|e|i|na|ne)\\w*", "डूब*", "डुब*", "ଡୁବ*", "lunod*", "nalunod*",
            "malunod*", "nalumos*", "malumos*", "lumos*")),
        Concept("trapped", .rescue, 3, "rescue", "trapped", trapped, lex: Lex(
            "trapped", "entrapped", "buried", "under debris", "under rubble", "under the debris",
            "under the rubble", "malba*", "मलबा*", "मलबे*", "naipit*", "nakulong*", "~na-?trap\\w*", "natrap*",
            "दबे", "दबी", "दबा", "~दब\\s+(?:गया|गई|गए|गये)", "~dab\\s+(?:gaya|gayi|gaye)", "~dabe\\s+(?:hain|hue|huye)")),
        // ---------------- MEDICAL, default (2)
        Concept("medicine", .medical, 2, "medicine", "needs medicine", request: true, lex: Lex(
            "medicine*", "medication*", "medicin*", "pills", "tablet*", "capsule*", "dawai*", "dawa", "dava",
            "davai*", "dawaai*", "दवा*", "दवाई*", "औषध*", "ଔଷଧ*", "ଔଷଦ*", "ausadh*", "aushadh*", "oushadh*",
            "ousadh*", "gamot*", "tambal*", "panambal*", "medisina*")),
        Concept("doctor", .medical, 2, "doctor", "needs doctor", request: true, lex: Lex(
            "doctor*", "doktor*", "duktor*", "nurse*", "paramedic*", "डॉक्टर*", "डाक्टर*", "ଡାକ୍ତର*", "daktar*",
            "dakter*", "dactor*")),
        Concept("ambulance", .medical, 2, "ambulance", "needs ambulance", request: true, lex: Lex(
            "ambulance*", "ambulans*", "एम्बुलेंस*", "एंबुलेंस*", "ଆମ୍ବୁଲାନ୍ସ*")),
        Concept("injured", .medical, 2, "first aid", "injured", injured, lex: Lex(
            "injur*", "hurt", "wound*", "fractur*", "~broken\\s+(?:leg|arm|bone|hand|foot|back)", "chot*", "चोट*", "ଚୋଟ*", "ghaayal*", "ghayl*", "zakhm*", "जख्म*", "ज़ख्म*", "जख़्म*",
            "ghayal*", "घायल*", "zakhmi*", "ଆହତ*", "ahat*", "aghat*", "ଆଘାତ*", "sugat*", "nasugat*", "samad*",
            "nasamad*", "nasamd*", "first aid")),
        Concept("pregnant", .medical, 2, "medical help", "pregnant", pregnant, context: true, lex: Lex(
            "pregnan*", "garbhvati*", "garbhawati*", "garbhavti*", "garbhvti*", "garbh*", "garbhavati*", "garbhwati*", "garbhini*", "गर्भवती*", "गर्भ*", "ଗର୍ଭବତୀ*", "ଗର୍ଭ*",
            "buntis*", "mabdos*")),
        Concept("fever", .medical, 2, "medicine", "fever", lex: Lex(
            "fever*", "bukhar*", "bukhaar*", "बुखार*", "ଜ୍ୱର*", "ଜ୍ବର*", "jwar*", "jwara*", "lagnat*",
            "nilalagnat*", "hilanat*", "gilantan*")),
        Concept("sick", .medical, 2, "medicine", "sick", lex: Lex(
            "sick*", "illness*", "unwell", "bimar*", "बीमार*", "ଅସୁସ୍ଥ*", "asustha*", "sakit*", "masakiton*",
            "diabet*", "asthma*", "dialysis", "diarr*", "cholera*", "vomit*", "dehydrat*", "dengue*", "malaria*")),
        Concept("hospital", .medical, 2, "medical help", "needs medical help", lex: Lex(
            "hospital*", "ospital*", "aspatal*", "haspatal*", "haspital*", "अस्पताल*", "ହସ୍ପିଟାଲ*", "ହାସ୍ପାତାଲ*",
            "medical*")),
        // ---------------- RESCUE, default (2)
        Concept("stuck", .rescue, 2, "rescue", "stuck", trapped, lex: Lex(
            "stuck", "stranded", "marooned", "~(?:can'?t|cannot)\\s+(?:get|come)\\s+out", "~can'?t\\s+leave",
            "fase", "phase", "fasse*", "phasse*", "fasey*", "phasey*", "fasi*", "phanse*", "fanse*", "fasa", "phansa", "fans*", "phans*", "फंस*", "फँस*", "फ़ंस*", "फसे", "फसा",
            "फस", "ଫସି*", "phasi*")),
        Concept("collapsed", .rescue, 2, "rescue", "building collapsed", context: true, lex: Lex(
            "collapse*", "gir gaya", "gir gayi", "gir gaye", "~ghar\\s+gir\\w*", "ढह*", "गिर गया", "गिर गई",
            "गिर गये", "गिर गए", "bhangi*", "ଭାଙ୍ଗି*", "gumuho*", "guho*", "bumagsak*", "nagiba*", "nahugno*",
            "wasak*", "nawasak*")),
        Concept("boat", .rescue, 2, "boat", "needs boat", context: true, lex: Lex(
            "boat*", "bangka*", "naav*", "nav", "नाव*", "नौका*", "ନାଆ*", "ନୌକା*")),
        Concept("rescue", .rescue, 2, "rescue", "needs rescue", request: true, lex: Lex(
            "rescue*", "save us", "save me", "helicopter*", "chopper*", "bachao*", "bachaao*", "bacha lo",
            "bachaiye", "बचाओ*", "बचा लो", "बचाइए", "बचाव*", "ବଞ୍ଚାଅ*", "ବଞ୍ଚାନ୍ତୁ*", "bachantu*", "banchao*",
            "saklolo*", "sagip*", "luwas*", "ଉଦ୍ଧାର*", "uddhar*", "~(?:jaldi\\s+)?nikal(?:o|iye|na)", "~निकाल(?:ो|िए|ना)")),
        // ---------------- WATER / FOOD / SHELTER / MISSING (1)
        Concept("missing", .missingPerson, 1, "missing person", "missing person", lex: Lex(
            "missing", "~lost\\s+(?:my\\s+|our\\s+)?(?:child|son|daughter|mother|father|brother|sister|wife|husband|family|baby|grand\\w+)",
            "gayab*", "lapata*", "laapata*", "लापता*", "गायब*", "nikhoj*", "ନିଖୋଜ*", "ନିରୁଦ୍ଦେଶ*", "nawawala*",
            "nawala*", "hindi mahanap", "hinahanap*", "nawara*", "gipangita*",
            "~(?:looking|searching)\\s+for\\s+(?:my|our)\\s+(?:\\w+\\s+)?(?:child|son|daughter|mother|father|brother|sister|wife|husband|family|baby|grand\\w+|mom|dad|parents?|uncle|aunt|boy|girl|kid\\w*)",
            "~(?:can'?t|cannot|unable\\s+to)\\s+find\\s+(?:my|our|him|her|them)", "~went\\s+missing", "wander*",
            "~kho\\s+(?:gaya|gayi|gaye)", "khoya", "खो गया", "खो गई", "खो गये", "खो गए", "खोया", "खोई",
            "ढूँढ*", "ढूंढ*", "ढूढ*", "dhundh*", "~(?:nahi|nahin)\\s+laut\\w*", "~(?:नहीं|नही)\\s+लौट\\w*",
            "~वापस\\s+(?:नहीं|नही)", "ଖୋଜି*", "ଖୋଜୁ*")),
        Concept("water", .water, 1, "water", "needs water", request: true, lex: Lex(
            "~water(?!\\s+(?:is\\s+)?(?:rising|entering|level|coming|came|inside|up|everywhere|logging))",
            "drinking water", "thirst*", "pyaas*", "pyas*", "प्यास*", "~पानी(?!\\s+(?:भर|बढ|घुस|आ))",
            "~pa+ni(?!\\s+(?:bhar|badh|ghus|aa|aaya|aya))", "पेयजल", "~ପାଣି(?!\\s+(?:ବଢ|ଭର|ପଶ|ଆସ))", "ପାନୀୟ ଜଳ", "pina pani", "tubig*", "inom*", "uhaw*", "inumon*")),
        Concept("milk", .food, 1, "milk", "needs milk", request: true, lex: Lex(
            "milk", "doodh*", "दूध*", "ଦୁଧ*", "gatas*")),
        Concept("food", .food, 1, "food", "needs food", request: true, lex: Lex(
            "food*", "hungry", "hunger*", "starv*", "khana*", "khaana*", "khaane*", "खाना*", "खाने*", "भूख*",
            "bhookh*", "bhukh*", "ଖାଦ୍ୟ*", "ଖାଇବା*", "ଭୋକ*", "bhoka*", "khadya*", "khaddya*", "khaibaku*",
            "kheibaku*", "ration*", "rice", "chawal*", "चावल*", "biscuit*", "pagkain*", "gutom*", "nagugutom*",
            "nagutom*", "kaon*", "pagkaon*", "ginutom*")),
        Concept("blanket", .shelter, 1, "blanket", "needs blankets", request: true, lex: Lex(
            "blanket*", "kambal*", "कंबल*", "कम्बल*", "kumot*", "clothes")),
        Concept("shelter", .shelter, 1, "shelter", "needs shelter", request: true, lex: Lex(
            "shelter*", "homeless", "no home", "~(?:home|house)\\s+(?:is\\s+|was\\s+|got\\s+)?(?:washed|destroyed|gone|damaged)",
            "evacuat*", "relief camp", "tent*", "ashray*", "aashray*", "asra", "aasra", "आश्रय*", "आसरा*", "टेंट*",
            "ଆଶ୍ରୟ*", "tirahan*", "silungan*", "masisilungan*", "kanlungan*", "aashray*", "ghar chahiye",
            "~(?:place|somewhere|nowhere|room|roof)\\s+to\\s+(?:stay|sleep|live)", "~(?:no|nowhere)\\s+to\\s+(?:stay|sleep|go)",
            "~sleeping\\s+(?:on\\s+the\\s+)?(?:road|street|open|outside|ground)", "~(?:sleeping|sleep)\\s+in\\s+the\\s+open",
            "~रहने\\s+(?:की|के\\s+लिए)\\s+(?:कोई\\s+)?(?:जगह|ठिकाना|जगा)", "~खुले\\s+में\\s+(?:सो|रह)\\S*",
            "~(?:rehne|rahne)\\s+(?:ki|ke\\s+liye)\\s+(?:koi\\s+)?(?:jagah|jagha|thikana)", "~khule\\s+mein\\s+(?:so|rah)\\w*",
            "~ରହିବା\\s*(?:ପାଇଁ|ସ୍ଥାନ|ଜାଗା)\\S*", "ଘର ଦରକାର")),
        // ---------------- context only
        Concept("roof", .rescue, 2, "rescue", "on roof", context: true, lex: Lex(
            "roof*", "rooftop*", "chhat*", "chhath", "छत*", "ଛାତ*", "bubong*", "atop*")),
        Concept("flood", .rescue, 2, "rescue", "flood", context: true, lex: Lex(
            "flood*", "baadh*", "baarh*", "बाढ*", "ବନ୍ୟା*", "banya*", "baha*", "pagbaha*", "binaha*", "lunop*",
            "cyclone*", "typhoon*", "bagyo*", "tsunami*",
            "~water\\s+(?:is\\s+)?(?:rising|entering|coming|came|inside|everywhere)", "~(?:pani|paani)\\s+(?:bhar|badh|ghus)\\w*",
            "~पानी\\s+(?:भर|बढ|घुस)\\w*", "~ପାଣି\\s+(?:ବଢ|ଭର|ପଶ|ଆସ)\\S*", "~(?:pani|paani)\\s+(?:badh|bhar|pasi)\\w*")),
        // phones and light: no category of their own on the wire, so OTHER with a plain item
        Concept("powerbank", .other, 1, "power bank", "needs power bank", request: true, lex: Lex(
            "~power\\s?bank\\S*", "charger*", "~पावर\\s?बैंक\\S*", "चार्जर*")),
        Concept("torch", .other, 1, "torch", "needs torch", request: true, lex: Lex(
            "torch*", "flashlight*", "candle*", "टॉर्च*", "मोमबत्ती*", "ଟର୍ଚ୍ଚ*")),
    ]

    private static func F(_ flag: VulnFlag, _ terms: String...) -> (VulnFlag, Lex) { (flag, Lex(terms: terms)) }

    static let flagLexicons: [(VulnFlag, Lex)] = [
        F(.elderly, 
            "grandmother*", "grandfather*", "grandma*", "grandpa*", "granny", "elderly", "senior citizen*",
            "~old\\s+(?:man|woman|lady|person|people|couple|age|mother|father|parents|folks)",
            "~very\\s+old", "~too\\s+old", "aged",
            "dadi", "dada", "nani", "nana", "दादी*", "दादा*", "नानी*", "नाना*", "बुजुर्ग*", "बूढ*", "बुढ*", "budha*",
            "budhi*", "budhiya*", "bujurg*", "buzurg*", "buzurag*", "bujurag*", "budhe*", "bhudhe*", "boodh*", "bood*",
            "vriddh*", "vrudh*", "बुज़ुर्ग*", "बूढे*", "वृद्ध*", "ବୃଦ୍ଧ*", "ବୁଢ*", "ଜେଜେମା*", "ଜେଜେବାପା*", "jejema*", "jejebapa*",
            "lola", "lolo", "lolang", "matanda*", "tigulang*", "tigurang*", "gurang*"),
        F(.child, 
            "child*", "kid", "kids", "baby", "babies", "infant*", "toddler*", "newborn*", "bachcha*", "bachche*",
            "bacche*", "baccha*", "bachchi*", "bacchi*", "bachi*", "bachha*", "bachhe*", "bacchon*", "bachchon*", "bachhon*", "बच्च*", "शिशु*", "ଶିଶୁ*", "ପିଲା*", "ଛୁଆ*", "chhua*",
            "pila", "pilamane*", "sanggol*", "bata", "batang", "mga bata", "anak*"),
    ]

    // ---- relations: first one mentioned becomes the summary subject
    private struct Relation {
        let name: String
        let flag: VulnFlag?
        let lex: Lex
        init(_ name: String, _ flag: VulnFlag?, _ lex: Lex) { self.name = name; self.flag = flag; self.lex = lex }
    }

    private static let relations: [Relation] = [
        Relation("grandmother", .elderly, Lex("grandmother*", "grandma*", "granny", "dadi", "nani", "दादी*", "नानी*", "ଜେଜେମା*", "jejema*", "lola")),
        Relation("grandfather", .elderly, Lex("grandfather*", "grandpa*", "दादा*", "नाना*", "ଜେଜେବାପା*", "jejebapa*", "lolo")),
        Relation("mother", nil, Lex("mother*", "mom", "mum", "mummy", "mama", "maa", "ma", "maata", "माँ*", "मां*", "माता*", "मम्मी*", "ମା", "ମାଆ*", "ମାଙ୍କ*", "nanay*", "nanai", "inay", "iloy*")),
        Relation("father", nil, Lex("father*", "dad", "daddy", "papa", "pita", "पिता*", "पापा*", "ବାପା*", "tatay*", "itay", "amahan*")),
        Relation("wife", nil, Lex("wife", "biwi", "patni", "पत्नी*", "बीवी*", "ସ୍ତ୍ରୀ*")),
        Relation("husband", nil, Lex("husband", "पति*", "ସ୍ୱାମୀ*")),
        Relation("son", nil, Lex("son", "beta", "बेटा*", "ପୁଅ*")),
        Relation("daughter", nil, Lex("daughter*", "beti", "बेटी*", "ଝିଅ*")),
        Relation("brother", nil, Lex("brother*", "bhai", "भाई*", "ଭାଇ*", "kuya")),
        Relation("sister", nil, Lex("sister*", "behen", "bahan", "बहन*", "ଭଉଣୀ*", "ate")),
    ]

    /// (English relation word, implied flag) of the earliest-mentioned relative, if any.
    static func findSubject(_ text: String) -> (String, VulnFlag?)? {
        var best: (String, VulnFlag?)?
        var bestPos = Int.max
        for r in relations {
            guard let m = r.lex.find(text) else { continue }
            if m.range.location < bestPos { bestPos = m.range.location; best = (r.name, r.flag) }
        }
        return best
    }

    // ---- supplies the requester says they already have ("we have food but no water", "पानी है लेकिन खाना नहीं है")
    private static let availableRegex = Rx(
        "(?<!n't )(?<!not )(?<!never )(?<!\(W))(?:have|has|got)\\s+(?:some\\s+|enough\\s+|plenty\\s+of\\s+|a\\s+little\\s+)?" +
            "(?:drinking\\s+|clean\\s+)?(?:water|food|milk|medicines?|blankets?|rice)(?!\(W))(?!\\s+(?:in|inside|entering|rising|everywhere|up|coming|came|level))" +
            "|(?<!\(W))(?:पानी|खाना|खाने|दवा|दवाई|राशन|दूध|pani|paani|khana|khaana|dawai|dawa|rashan|doodh|ପାଣି|ଖାଦ୍ୟ|ଔଷଧ)" +
            "\\s*(?:to\\s+|toh\\s+|bhi\\s+|तो\\s+|भी\\s+)?(?:hai|hain|है|हैं|achhi|ଅଛି)(?!\\s+(?:nahi|nahin|नहीं|नही))(?!\(W))"
    )


    /// Blanks out "we have X" / "X है" statements so a supply the person already has is not read as a need.
    static func stripAvailable(_ text: String) -> String { availableRegex.replace(text, with: " ") }

    // ---- ages
    private static let ageUnits = "years?|yrs?|yr|y/o|saal|sal|वर्ष|साल|बरस|ବର୍ଷ|taon|anyos|taong|tuig|katuigan"
    private static let ageRegex = Rx(
        "(?<!\(W))(\\p{Nd}{1,3}|\(People.numAlt))\\s*-?\\s*(?:\(ageUnits))(?:[ -]old)?(?!\(W))" +
            "|(?<!\(W))(?:age|aged|umar|umra|उम्र|ବୟସ|edad)\\s*(?:of\\s*)?(\\p{Nd}{1,3}|\(People.numAlt))(?!\(W))" +
            // "my father is seventy": a spelled-out decade after is/aged, no unit needed
            "|(?<!\(W))(?:is|aged)\\s+(\(People.decadeAlt))(?!\\s*(?:people|persons|of\\s+us))(?!\(W))"
    )


    /// Removes "80 years old"-style phrases; returns the remaining text and the ages found.
    static func stripAges(_ text: String) -> (String, [Int]) {
        var ages: [Int] = []
        let stripped = ageRegex.replace(text) { m in
            let g = (1...3).map { m.group($0) }.first { !$0.isEmpty } ?? ""
            ages.append(People.valueOf(g))
            return " "
        }
        return (stripped, ages)
    }

    static func digits(_ s: String) -> Int {
        var v = 0
        for scalar in s.unicodeScalars {
            if scalar.properties.numericType == .decimal, let n = scalar.properties.numericValue {
                v = v * 10 + Int(n)
            }
            if v > 100_000 { break }
        }
        return v
    }
}
