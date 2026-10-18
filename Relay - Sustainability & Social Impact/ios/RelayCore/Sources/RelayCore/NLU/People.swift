import Foundation

/// Head-count extraction: digits or number words (en/hi/or/tl/ceb/war) next to a people noun.
enum People {
    private static let numberWords: [String: Int] = {
        var m: [String: Int] = [:]
        func add(_ v: Int, _ ws: String...) { for w in ws { m[w] = v } }
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
        add(30, "thirty", "tees", "तीस")
        add(40, "forty", "chalis", "chaalis", "चालीस")
        add(50, "fifty", "pachas", "pachaas", "पचास")
        add(60, "sixty", "साठ")
        add(70, "seventy", "sattar", "सत्तर")
        add(80, "eighty", "assi", "अस्सी")
        add(90, "ninety", "nabbe", "नब्बे")
        return m
    }()

    /// Alternation of every number word, longest first, for building regexes.
    static let numAlt: String = numberWords.keys
        .sorted { ($0.utf16.count, $1) > ($1.utf16.count, $0) }
        .map { Rx.escape($0) }.joined(separator: "|")

    /// Spelled-out decades (thirty..ninety): unambiguous enough to read as an age after "is".
    static let decadeAlt: String = numberWords.filter { (30...90).contains($0.value) }.keys
        .sorted { ($0.utf16.count, $1) > ($1.utf16.count, $0) }
        .map { Rx.escape($0) }.joined(separator: "|")

    /// Value of a digit string or number word; 0 if neither.
    static func valueOf(_ tok: String) -> Int { numberWords[tok] ?? Lexicons.digits(tok) }

    private static let groupUnits: Set<String> = Set([
        "adults", "adult", "children", "child", "kids", "kid", "men", "women", "babies", "elders",
        "bachche", "bachhe", "bacche", "bachcha", "बच्चे", "बच्चों", "bata", "anak",
    ])

    private static let units = "people|persons?|adults?|children|child|kids?|members?|men|women|babies|elders|souls|" +
        "log|logon|logo|लोग|लोगों|जन|आदमी|सदस्य|सदस्यों|bachche|bachhe|bacche|bachcha|बच्चे|बच्चों|" +
        "ଜଣ|ଲୋକ|jane|jana|lok|loka|pila|" +
        "tao|katao|tawo|bata|anak|miyembro|kabataan"

    private static let unitRegex = Rx(
        "(?<!\(W))(\\p{Nd}{1,3}|\(numAlt))(?:ng)?(?:\\s+na)?(?:\\s+ka)?(?:\\s+mga)?\\s+(?:more\\s+)?(\(units))(?!\(W))"
    )
    private static let familyOf = Rx("(?<!\(W))family\\s+of\\s+(\\p{Nd}{1,3}|\(numAlt))(?!\(W))")
    private static let weAre = Rx(
        "(?<!\(W))(?:we\\s+are|we're|there\\s+are|total|hum|ham|kami\\s+ay|kami|mi|amo|ame|amey)\\s+(\\p{Nd}{1,3}|\(numAlt))" +
            "(?!\\s*(?:years?|yrs?|saal|sal|din|days?|hours?|ghante|taon|anyos|tuig))(?!\(W))"
    )
    private static let ofUs = Rx(
        "(?<!\(W))(\\p{Nd}{1,3}|\(numAlt))\\s+(?:of\\s+us|kami|mi|sila|tayo|kita|ham|hum|ame|amey)(?!\(W))"
    )
    private static let plural = Lex(
        "we", "us", "our", "we're", "family", "families", "everyone", "all of us", "children", "kids", "mga bata",
        "hum", "ham", "hamare", "humare", "humko", "hamko", "hamara", "parivar*", "परिवार*", "परिजन*", "हम", "हमारे",
        "हमें", "हमको", "ଆମେ", "ଆମକୁ", "ଆମର", "ପରିବାର*", "kami", "namin", "amin", "amon", "pamilya*", "kita", "tayo",
        "mi", "among", "atong", "amo",
    )

    /// Number of people the text speaks about; 1 if unstated, 2 if only a plural subject is stated.
    static func count(_ text: String) -> Int {
        let unitMatches = unitRegex.findAll(text).map { (valueOf($0.group(1)), $0.group(2)) }
        var best = 0
        if unitMatches.count >= 2 && unitMatches.allSatisfy({ groupUnits.contains($0.1) }) {
            best = unitMatches.reduce(0) { $0 + $1.0 }
        } else {
            for (v, _) in unitMatches { best = max(best, v) }
        }
        for m in familyOf.findAll(text) { best = max(best, valueOf(m.group(1))) }
        for m in weAre.findAll(text) { best = max(best, valueOf(m.group(1))) }
        for m in ofUs.findAll(text) { best = max(best, valueOf(m.group(1))) }
        if (1...255).contains(best) { return best }
        if best > 255 { return 255 }
        return plural.matches(text) ? 2 : 1
    }
}
