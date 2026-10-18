import Foundation

/// Landmark phrase extraction and normalisation to short English-ish tokens.
enum Landmarks {

    private static func L(_ name: String, _ terms: String...) -> (String, Lex) { (name, Lex(terms: terms)) }

    private static let canonical: [(String, Lex)] = [
        L("cyclone shelter", "~cyclone\\s+shelter\\w*", "साइक्लोन शेल्टर*", "ସାଇକ୍ଲୋନ ଶେଲ୍ଟର*"),
        L("police station", "police*", "thana*", "थाना*", "ଥାନା*", "pulis*"),
        L("bus stand", "~bus\\s+(?:stand|stop|station)", "बस स्टैंड*", "ବସ ଷ୍ଟାଣ୍ଡ*", "ବସ୍ ଷ୍ଟାଣ୍ଡ*"),
        L("post office", "post office", "dak ghar", "डाकघर*"),
        L("panchayat office", "panchayat*", "पंचायत*", "ପଞ୍ଚାୟତ*", "block office", "bdo office"),
        L("barangay hall", "barangay*", "brgy*", "kapitolyo*"),
        L("town hall", "town hall", "city hall", "municipal*", "munisipyo*"),
        L("anganwadi", "anganwadi*", "आंगनवाड़ी*", "ଅଙ୍ଗନୱାଡ଼ି*"),
        L("gurudwara", "gurudwara*", "gurdwara*", "गुरुद्वारा*", "गुरुद्वारे*", "ଗୁରୁଦ୍ୱାରା*"),
        L("school", "school*", "paaralan*", "eskwela*", "eskwelahan*", "iskwela*", "eskuylahan*", "skul*", "skool*",
            "स्कूल*", "विद्यालय*", "पाठशाला*", "pathshala*", "vidyalaya*", "bidyalaya*", "ବିଦ୍ୟାଳୟ*", "ସ୍କୁଲ*", "madrasa*"),
        L("college", "college*", "कॉलेज*", " କଲେଜ*"),
        L("dispensary", "dispensary", "औषधालय*", "ଔଷଧାଳୟ*"),
        L("hospital", "hospital*", "ospital*", "aspatal*", "haspatal*", "haspital*", "अस्पताल*", "ହସ୍ପିଟାଲ*",
            "ହାସ୍ପାତାଲ*", "ଡାକ୍ତରଖାନା*", "clinic*", "~health\\s+cent\\w*", "phc", "chc"),
        L("temple", "temple*", "mandir*", "mandira*", "मंदिर*", "ମନ୍ଦିର*", "gurudwara*"),
        L("church", "church*", "simbahan*", "iglesia*", "gereja*", "चर्च*", "ଗୀର୍ଜା*"),
        L("mosque", "mosque*"),
        L("masjid", "masjid*", "मस्जिद*", "ମସଜିଦ*"),
        L("river", "river*", "nadi", "nadee", "नदी*", "ନଦୀ*", "ilog*", "suba", "creek*", "stream*"),
        L("canal", "canal*", "nala*", "नाला*", "ନାଳ*"),
        L("pond", "pond*", "lake*", "talab*", "तालाब*", "ପୋଖରୀ*"),
        L("market", "market*", "bazaar*", "bazar*", "बाजार*", "बाज़ार*", "ବଜାର*", "palengke*", "merkado*"),
        L("shop", "shop*", "dukan*", "दुकान*", "tindahan*", "store"),
        L("bridge", "bridge*", "pul", "पुल*", "ସେତୁ*", "setu", "tulay*", "taytay*"),
        L("railway station", "~rail(?:way)?\\s+station", "train station", "रेलवे स्टेशन*", "station*", "स्टेशन*", "ଷ୍ଟେସନ*"),
        L("hand pump", "hand ?pump*", "tube ?well*", "borewell*", "हैंडपंप*", "ହ୍ୟାଣ୍ଡପମ୍ପ*"),
        L("petrol pump", "petrol*", "gas station*", "पेट्रोल*", "पेट्रोल पंप*", "पेट्रोल पम्प*", "ପେଟ୍ରୋଲ*"),
        L("water tank", "~(?:(?:water|pani|paani|पानी|ପାଣି)\\s+(?:ki\\s+|ka\\s+|की\\s+|का\\s+)?)?(?:tank|tanki|टंकी|टैंक|ଟାଙ୍କି)\\S*"),
        L("tree", "tree*", "banyan*", "peepal*", "बरगद*", "पेड़*", "पेड*", "ଗଛ*", "gachh*", "puno*", "kahoy*"),
        L("well", "well", "कुआँ*", "कुआ", "kuan*"),
        L("road", "road*", "highway*", "sadak*", "सड़क*", "सडक*", "ସଡ଼କ*", "kalsada*", "karsada*", "dalan*"),
        L("ground", "ground*", "playground*", "maidan*", "मैदान*"),
        L("bank", "bank*"),
        L("tower", "tower*"),
    ]

    private static let PREP_EN =
        "near(?:by)?(?:\\s+to)?(?:\\s+the)?|beside(?:\\s+the)?|behind(?:\\s+the)?|next\\s+to(?:\\s+the)?|" +
            "close\\s+to(?:\\s+the)?|opposite(?:\\s+(?:to\\s+)?the)?|in\\s+front\\s+of(?:\\s+the)?|at\\s+the|by\\s+the|" +
            "around\\s+the|across\\s+(?:from\\s+)?the|(?:away|wandered|went|came)\\s+(?:away\\s+)?from(?:\\s+the)?"
    private static let PREP_TL =
        "malapit\\s+(?:po\\s+)?sa|sa\\s+tabi\\s+ng|sa\\s+likod\\s+ng|sa\\s+harap\\s+ng"
    private static let PREP_CEB =
        "duol(?:\\s+(?:kaayo|lang))?\\s+sa|dapit\\s+sa|tupad\\s+sa|sa\\s+luyo\\s+sa|atbang\\s+sa"
    private static let PREP_WAR =
        "harani(?:\\s+(?:ha|han|hit))?|higad\\s+(?:ha|han)|luyo\\s+(?:ha|han)|atubang\\s+(?:ha|han)"

    private static let prefixRegex = Rx(
        "(?<!\(W))(?:\(PREP_EN)|\(PREP_TL)|\(PREP_CEB)|\(PREP_WAR))\\s+([^,.;:!?\\n]{1,60})"
    )

    private static let POST_HI =
        "(?:(?:ke|ki|ka)\\s+(?:paas|pass|pas|nazdeek|nazdik|najdik|pichhe|peeche|piche|saamne|samne|bagal|baju|aaspaas|aas\\s+paas)" +
            "|(?:के|की|का)\\s+(?:पास|नज़दीक|नजदीक|पीछे|सामने|बगल|आसपास|आस\\s*पास))"
    private static let POST_OR =
        "(?:(?:ra\\s+)?(?:pakhare|pakhe|pakhara|nikate|nikat|pachhare|pachhe|aagare)" +
            "|(?:ର\\s*)?(?:ପାଖରେ|ପାଖ|ନିକଟରେ|ନିକଟ|ପଛରେ|ଆଗରେ))"

    private static let postRegex = Rx(
        "(?<!\(W))((?:[\\p{L}\\p{M}\\p{N}']+\\s+){0,2}[\\p{L}\\p{M}\\p{N}']+)\\s+(?:\(POST_HI)|\(POST_OR))(?!\(W))"
    )


    private static let stop: Set<String> = Set([
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
    ])

    private static let locationSubject = Rx(
        "(?<!\(W))(?:we|i|they)(?:'re|'m|\\s+(?:are|am|were|was|live|stay|stand|reside))" +
            "(?:\\s+(?:all|still|now|here|just|last|staying|stuck|waiting|currently))*\\s*$"
    )


    private static let fillers: Set<String> = stop.union(["ke", "ki", "ka", "ra", "ର", "के", "की", "का", "the", "a", "an", "of"])

    private static let leadingArticles: Set<String> = ["the", "a", "an", "sa", "ng", "ha", "han", "hit", "ang"]

    private static let nonSpace = Rx("\\S+")

    /// Returns the normalised landmark ("" if none) and [norm] with the landmark phrase blanked out so
    /// that place names such as "hospital" do not create a need for a hospital.
    static func extract(_ norm: String) -> (String, String) {
        if let m = prefixRegex.find(norm), let grp = m.groupRange(1) {
            let grpValue = m.group(1)
            let tokens = nonSpace.findAll(grpValue)
            var kept: [RxMatch] = []
            for t in tokens {
                if kept.count >= 4 { break }
                if stop.contains(t.value) { break }
                kept.append(t)
            }
            // drop leading articles/linkers
            while let first = kept.first, leadingArticles.contains(first.value) { kept.removeFirst() }
            if let last = kept.last {
                let phrase = kept.map { $0.value }.joined(separator: " ")
                let end = grp.location + last.range.location + last.range.length
                let before = locationSubject.replace(norm.substr(0, m.range.location), with: "")
                let blanked = before + " " + norm.substr(from: end)
                return (normalizeLandmark(phrase, tailOnly: false), blanked)
            }
        }
        if let m = postRegex.find(norm), let grp = m.groupRange(1) {
            let phrase = m.group(1)
            let (lm, from) = tailLandmark(phrase)
            if !lm.isEmpty {
                // Blank only the landmark words: "hum paanch log gurudwara ke paas" must keep "paanch log".
                // A pronoun-only lead-in ("हम पेट्रोल पंप के पास हैं") is part of the location statement.
                let lead = phrase.substr(0, from).trimmingCharacters(in: .whitespacesAndNewlines)
                let keep = (lead.isEmpty || lead.split(separator: " ", omittingEmptySubsequences: false).allSatisfy { fillers.contains(String($0)) }) ? 0 : from
                let blanked = norm.substr(0, grp.location + keep) + " " + norm.substr(from: m.range.location + m.range.length)
                return (lm, blanked)
            }
        }
        return ("", norm)
    }

    /// Landmark of a "<place> ke paas" phrase and the offset in [phrase] where its words start.
    private static func tailLandmark(_ phrase: String) -> (String, Int) {
        for (name, lex) in canonical {
            if let m = lex.find(phrase) { return (name, m.range.location) }
        }
        let words = Array(nonSpace.findAll(phrase).filter { !fillers.contains($0.value) }.suffix(2))
        guard let first = words.first else { return ("", 0) }
        let joined = words.map { $0.value }.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines)
        return (Utf8.truncate(joined, 24), first.range.location)
    }

    private static func normalizeLandmark(_ phrase: String, tailOnly: Bool) -> String {
        for (name, lex) in canonical where lex.matches(phrase) { return name }
        var words = phrase.split(separator: " ").map(String.init).filter { !$0.isEmpty }
        if tailOnly {
            words = words.filter { !fillers.contains($0) }
            words = Array(words.suffix(2))
        } else {
            words = words.filter { !["the", "a", "an"].contains($0) }
        }
        return Utf8.truncate(words.joined(separator: " ").trimmingCharacters(in: .whitespacesAndNewlines), 24)
    }
}
