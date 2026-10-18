import Foundation

/// Dangers a requester can mention in passing. They pick which advisories apply and whether the request needs triage at
/// all. Detection is rules only and errs towards "yes": a false hazard costs one extra safety line, a missed one costs more.
/// Port of the Android `Hazards.kt`.
public enum Hazard: CaseIterable, Sendable, Hashable {
    case notBreathing, unconscious, bleeding, snake, labour, electric, flood, trapped, cyclone, landslide

    /// The Kotlin constant name (`NOT_BREATHING`), as the model is asked to write it.
    public var name: String {
        switch self {
        case .notBreathing: return "NOT_BREATHING"
        case .unconscious: return "UNCONSCIOUS"
        case .bleeding: return "BLEEDING"
        case .snake: return "SNAKE"
        case .labour: return "LABOUR"
        case .electric: return "ELECTRIC"
        case .flood: return "FLOOD"
        case .trapped: return "TRAPPED"
        case .cyclone: return "CYCLONE"
        case .landslide: return "LANDSLIDE"
        }
    }

    /// Dangers that route a request to triage even if the extractor called it something simpler.
    public var lifeThreatening: Bool { self != .flood && self != .cyclone }
}

public enum HazardLexicon {
    private static let lexicon: [(Hazard, Lex)] = [
        (.notBreathing, Lex("~not\\s+breathing", "~(?:can'?t|cannot|unable\\s+to)\\s+breathe", "stopped breathing", "no breath", "gasping", "~s(?:aa|a)ns\\s+(?:nahi|nahin|ruk\\w*|band)", "~(?:सांस|साँस)\\s+(?:नहीं|रुक\\w*|बंद)", "~ଶ୍[ୱବ]ାସ\\s+(?:ନାହିଁ|ବନ୍ଦ)", "~hindi\\s+(?:maka-?hinga|humi-?hinga)")),
        (.unconscious, Lex("unconscious*", "passed out", "fainted", "not responding", "unresponsive", "behosh*", "behoosh*", "बेहोश*", "ବେହୋସ*", "ବେହୁସ*", "nahimatay*", "walang malay", "nawalan ng malay")),
        (.bleeding, Lex("bleed*", "blood*", "khoon*", "खून*", "रक्त*", "ରକ୍ତ*", "rakt*", "dugo*", "nagdudugo*", "nagdugo*")),
        (.snake, Lex("snake*", "saanp*", "sanp*", "सांप*", "साँप*", "सर्प*", "ସାପ*", "ahas*", "bitin*", "tuklaw*", "natuklaw*")),
        (.labour, Lex("labour*", "labor", "~in\\s+labor", "delivery pain*", "prasav*", "prasab*", "प्रसव*", "ପ୍ରସବ*", "manganak*", "nanganak*", "panganganak*", "contraction*")),
        (.electric, Lex("electric*", "~live\\s+wire", "~(?:power|electric|light)\\s*(?:line|pole|post|wire|cable)s?", "pole", "poles", "wire", "wires", "short circuit", "bijli*", "bijali*", "बिजली*", "करंट*", "खंभा", "खम्भा", "तार", "ବିଜୁଳି*", "କରେଣ୍ଟ*", "ଖୁଣ୍ଟ*", "kuryente*", "poste*", "kawad*")),
        (.flood, Lex("flood*", "water rising", "~water\\s+(?:is\\s+)?(?:rising|coming|entering)", "~rising\\s+water", "submerged", "inundat*", "baadh*", "badh", "बाढ़*", "बाढ*", "पानी बढ़*", "पानी भर*", "ବନ୍ୟା*", "ପାଣି ବଢ*", "baha", "bumabaha", "binaha", "tubig baha")),
        (.trapped, Lex("trapped", "buried", "under debris", "under rubble", "under the debris", "under the rubble", "malba*", "मलबा*", "मलबे*", "दबे", "दबी", "दबा", "naipit*", "nakulong*")),
        (.cyclone, Lex("cyclone*", "storm*", "hurricane*", "typhoon*", "tufan*", "toofan*", "तूफ़ान*", "तूफान*", "चक्रवात*", "ଘୂର୍ଣ୍ଣିବାତ୍ୟା*", "ବାତ୍ୟା*", "bagyo*", "unos")),
        (.landslide, Lex("landslide*", "mudslide*", "land slide", "~hill\\s*(?:slide|collapse|fell)", "~bhu-?skhalan", "भूस्खलन*", "पहाड़ गिर*", "pahad gir*", "ଭୂସ୍ଖଳନ*", "guho*", "pagguho*")),
    ]

    /// Every hazard the text mentions. Never throws.
    public static func detect(_ text: String) -> Set<Hazard> {
        let t = normalizeText(text)
        if t.isEmpty { return [] }
        return Set(lexicon.filter { $0.1.matches(t) }.map { $0.0 })
    }
}
