import Foundation

/// Script first (Odia / Devanagari), then keyword scoring for Latin-script text.
enum LangDetect {
    private static func set(_ w: String...) -> Set<String> { Set(w) }

    // Function words / markers. Words shared between languages are listed in each (weight 1);
    // distinctive markers get weight 2 via strong sets.
    private static let en: Set<String> = set("the", "my", "is", "are", "and", "with", "near", "need", "needs", "please", "we", "our", "has",
        "have", "of", "for", "from", "on", "in", "at", "am", "was", "there", "here", "his", "her", "this", "that",
        "not", "can't", "cant", "help", "stuck", "trapped", "people", "mother", "father", "to", "behind", "water",
        "food", "i", "me", "us", "it", "no", "children", "child", "baby", "house", "roof")
    private static let hi: Set<String> = set("hai", "hain", "ho", "ka", "ki", "ke", "ko", "se", "mein", "mai", "main", "hum", "ham", "mera",
        "meri", "mere", "hamare", "humare", "hamara", "chahiye", "chahie", "nahi", "nahin", "madad", "bachao", "paas",
        "pas", "aur", "bahut", "abhi", "jaldi", "kripya", "raha", "rahi", "rahe", "gaya", "gayi", "gaye", "hua", "hui",
        "fase", "phase", "phansa", "par", "pe", "wala", "wali", "ghar", "log", "saath", "bimar", "baadh", "pani",
        "dawai", "khana", "chhat", "bhookh", "maa", "bhai", "behen", "bachche", "bachcha")
    private static let orRoman: Set<String> = set("mo", "mora", "mu", "mun", "amara", "amaku", "mate", "mote", "darkar", "darakar", "achhi",
        "achhanti", "achhu", "pakhare", "nikate", "ku", "kana", "kemiti", "sahajya", "sahayya", "ausadha", "aushadha",
        "oushadha", "bhasi", "banya", "phasi", "phasichhanti", "phasichanti", "ghara", "gote", "duita", "jane", "pua",
        "jhia", "bapa", "bou", "aji", "bhoka", "khaibaku", "kheibaku", "paibaku", "diantu", "bachantu", "gala",
        "bhasigala", "pani", "ra", "nahi", "nahin", "bipad", "kichhi", "pina", "dina", "hela", "pilamane", "bhokila",
        "bhokilaa", "nikhoj", "upare", "aashray", "ashray", "chhata", "badhuchhi", "kamudichhi", "sapa", "pua", "phasiachhu")
    private static let tlStrong: Set<String> = set("po", "kailangan", "namin", "tulong", "malapit", "nasa", "hindi", "ako", "pakitulong", "naipit",
        "nakulong", "baha", "sugatan", "gamot", "pagkain", "kasi", "dito", "ng", "mga", "ay", "paki", "ang", "walang",
        "lola", "lolo", "nanay", "tatay", "bata", "buntis", "kami", "sa", "may", "na", "tubig", "gutom", "ospital",
        "eskwelahan", "nagugutom", "saklolo", "sugat")
    private static let cebStrong: Set<String> = set("kinahanglan", "palihug", "tabang", "duol", "naa", "dili", "wala", "gikinahanglan", "adunay",
        "kaon", "pagkaon", "nasamad", "nalumos", "nga", "tambal", "kay", "unsa", "mi", "og", "ug", "balay", "tigulang",
        "mga", "sa", "kami", "ako", "gutom", "tubig", "bata", "lola", "lolo", "atop", "ni", "unom", "duha", "tulo",
        "upat", "luwasa", "eskwelahan", "ospital", "gikan", "ta", "nag", "naay", "wala'y", "gipangita")
    private static let warStrong: Set<String> = set("han", "hin", "harani", "bulig", "waray", "diri", "dire", "amon", "ngan", "kun", "mayda",
        "nawara", "hiya", "akon", "ha", "an", "kami", "nga", "kinahanglan", "tambal", "tubig", "gutom", "bata",
        "lola", "lolo", "nanay", "balay", "atop", "eskwelahan", "namon", "nahulog", "pagkaon", "hi", "hit", "hira",
        "ginbaha", "nasagpan", "ospital")

    // Distinctive subset used to separate tl / ceb / war from each other (weight 2).
    private static let tlOnly: Set<String> = set("po", "kailangan", "namin", "tulong", "malapit", "nasa", "hindi", "pakitulong", "naipit",
        "nakulong", "sugatan", "gamot", "pagkain", "kasi", "dito", "ng", "ay", "paki", "walang", "nagugutom", "saklolo", "sugat")
    private static let cebOnly: Set<String> = set("kinahanglan", "palihug", "tabang", "duol", "naa", "dili", "wala", "gikinahanglan", "adunay",
        "kaon", "pagkaon", "nasamad", "nalumos", "unsa", "og", "ug", "tigulang", "naay", "luwasa", "gipangita", "kay")
    private static let warOnly: Set<String> = set("han", "hin", "harani", "bulig", "waray", "diri", "dire", "amon", "ngan", "kun", "mayda",
        "nawara", "hiya", "akon", "ha", "namon", "nasagpan", "ginbaha", "hira")

    private static let tokenRegex = Rx("[\\p{L}\\p{N}']+")

    static func detect(_ raw: String, _ norm: String, _ hint: Lang?) -> Lang {
        var odia = 0
        var deva = 0
        for u in raw.utf16 {
            switch u {
            case 0x0B00...0x0B7F: odia += 1
            case 0x0900...0x097F: deva += 1
            default: break
            }
        }
        if odia > 0 && odia >= deva { return .or }
        if deva > 0 { return .hi }

        let tokens = tokenRegex.findAll(norm).map { $0.value }
        func s(_ set: Set<String>, _ strong: Set<String> = []) -> Int {
            var v = 0
            for t in tokens {
                if strong.contains(t) { v += 2 } else if set.contains(t) { v += 1 }
            }
            return v
        }
        var score: [Lang: Int] = [:]
        score[.en] = s(en)
        score[.hi] = s(hi)
        score[.or] = s(orRoman)
        score[.tl] = s(tlStrong, tlOnly)
        score[.ceb] = s(cebStrong, cebOnly)
        score[.war] = s(warStrong, warOnly)

        let maxScore = score.values.max() ?? 0
        if maxScore == 0 { return hint ?? .en }
        if let hint, (score[hint] ?? 0) == maxScore { return hint }
        // Deterministic tie-break order: Cebuano before Waray (far more speakers), English first.
        let order: [Lang] = [.en, .hi, .or, .tl, .ceb, .war]
        return order.first { score[$0] == maxScore }!
    }
}
