import Foundation

// ====================================================================== disaster

/// The kinds of disaster the control room can declare for an area. Disaster-specific questions are keyed to these.
public enum DisasterType: String, CaseIterable, Sendable, Hashable {
    case flood, cyclone, earthquake, landslide

    public var key: String { rawValue }
    /// The wire code (SPEC 5.6).
    public var code: Int {
        switch self { case .flood: return 0; case .cyclone: return 1; case .earthquake: return 2; case .landslide: return 3 }
    }
    public static func fromCode(_ c: Int) -> DisasterType? { allCases.first { $0.code == c } }
    public static func fromKey(_ k: String?) -> DisasterType? {
        guard let k else { return nil }
        let t = k.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return allCases.first { $0.key == t }
    }
}

/// What the control room said is happening: `type`, and when it said so (`issuedAtSec`, unix seconds). The phone never
/// infers a disaster from what a person says; only a declaration like this switches disaster-specific questions on.
public struct DisasterContext: Equatable, Sendable {
    /// A disc: centre in degrees x 1e5 (as on the wire) and radius in km.
    public struct Area: Equatable, Sendable {
        public var latE5: Int
        public var lonE5: Int
        public var radiusKm: Int
        public init(latE5: Int, lonE5: Int, radiusKm: Int) { self.latE5 = latE5; self.lonE5 = lonE5; self.radiusKm = radiusKm }
    }

    public var type: DisasterType
    public var issuedAtSec: Int64
    /// Where it applies: nil = the whole control-room area.
    public var area: Area?

    public init(type: DisasterType, issuedAtSec: Int64, area: Area? = nil) {
        self.type = type; self.issuedAtSec = issuedAtSec; self.area = area
    }
}

/// The freshness rule: a declaration counts from the moment it is issued until `maxAgeSec` later. Past that, nothing
/// disaster-specific is asked: after a day the situation on the ground may have changed, and a wrong premise ("is the water
/// still rising?" in a place that has dried out) wastes the one minute a person has. Between `freshSec` and `maxAgeSec`
/// the declaration still counts but is marked `aging`, so the model and the questions can confirm before relying on it.
public enum DisasterGate {
    public static let freshSec: Int64 = 12 * 3600
    public static let maxAgeSec: Int64 = 24 * 3600
    /// A declaration stamped slightly in the future (clock drift between phones) still counts.
    public static let clockSkewSec: Int64 = 10 * 60

    public struct Active: Equatable, Sendable {
        public var types: Set<DisasterType>
        public var aging: Bool
        public init(types: Set<DisasterType>, aging: Bool) { self.types = types; self.aging = aging }
        public static let none = Active(types: [], aging: false)
    }

    /// `here` is the phone's last known position (degrees x 1e5) or nil. A declaration with an area is skipped only when
    /// the phone knows it is outside it: not knowing where it is never hides a declaration.
    public static func active(_ contexts: [DisasterContext], nowSec: Int64, here: (latE5: Int, lonE5: Int)? = nil) -> Active {
        let live = contexts.filter { c in
            let age = nowSec - c.issuedAtSec
            guard age >= -clockSkewSec && age <= maxAgeSec else { return false }
            guard let here, let a = c.area, a.radiusKm != 0 else { return true }
            return withinKm(a, here)
        }
        if live.isEmpty { return .none }
        // "Aging" only when every live declaration is past the fresh window: one fresh declaration vouches for the area.
        return Active(types: Set(live.map(\.type)), aging: live.allSatisfy { nowSec - $0.issuedAtSec > freshSec })
    }

    private static func withinKm(_ a: DisasterContext.Area, _ here: (latE5: Int, lonE5: Int)) -> Bool {
        func rad(_ d: Double) -> Double { d * Double.pi / 180 }
        let lat1 = rad(Double(a.latE5) / 1e5), lat2 = rad(Double(here.latE5) / 1e5)
        let dLat = lat2 - lat1, dLon = rad(Double(here.lonE5 - a.lonE5) / 1e5)
        let sLat = sin(dLat / 2), sLon = sin(dLon / 2)
        let h = sLat * sLat + cos(lat1) * cos(lat2) * sLon * sLon
        return 6371.0 * 2 * asin(min(max(h, 0), 1).squareRoot()) <= Double(a.radiusKm)
    }
}

// ====================================================================== facts and choices

/// Whether a fact is about one person ("is your mother awake") or the place they are in ("is the water rising").
public enum Scope: Sendable, Hashable { case person, scene }

/// The yes/no things triage can find out. `disasters` empty = asked in any situation; otherwise asked only while the control
/// room has declared one of those disasters (`DisasterGate`). `bankId` is the `QuestionBank` question the answer also goes
/// out on (the wire carries at most 3 answers, SPEC 5.1); the rest only steer the conversation and the advice.
public enum Fact: CaseIterable, Sendable, Hashable {
    case conscious, breathing, heavyBleeding, anyoneHurt, roadReachable, waterRising, liveWire, gasSmell, buildingUnsafe,
         stillShaking, shelterHolding, slopeMoving

    public var key: String {
        switch self {
        case .conscious: return "conscious"
        case .breathing: return "breathing"
        case .heavyBleeding: return "heavy_bleeding"
        case .anyoneHurt: return "anyone_hurt"
        case .roadReachable: return "road_reachable"
        case .waterRising: return "water_rising"
        case .liveWire: return "live_wire"
        case .gasSmell: return "gas_smell"
        case .buildingUnsafe: return "building_unsafe"
        case .stillShaking: return "still_shaking"
        case .shelterHolding: return "shelter_holding"
        case .slopeMoving: return "slope_moving"
        }
    }

    public var scope: Scope {
        switch self {
        case .conscious, .breathing, .heavyBleeding: return .person
        default: return .scene
        }
    }

    public var bankId: Int? {
        switch self {
        case .conscious: return 1
        case .roadReachable: return 2
        case .waterRising: return 3
        default: return nil
        }
    }

    public var disasters: Set<DisasterType> {
        switch self {
        case .waterRising: return [.flood]
        case .liveWire: return [.flood, .cyclone, .earthquake]
        case .gasSmell, .buildingUnsafe, .stillShaking: return [.earthquake]
        case .shelterHolding: return [.cyclone]
        case .slopeMoving: return [.landslide]
        default: return []
        }
    }

    /// Facts where "no" is the dangerous answer; a disagreement between rules and model resolves to the dangerous one.
    public var noIsDangerous: Bool { self == .conscious || self == .breathing || self == .shelterHolding }
    public var yesIsDangerous: Bool { !noIsDangerous && self != .roadReachable }

    public static func fromKey(_ k: String?) -> Fact? {
        guard let k else { return nil }
        let t = k.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return allCases.first { $0.key == t }
    }
}

/// A fact about one subject (0 for scene facts).
public struct FactKey: Hashable, Sendable {
    public let fact: Fact
    public let subject: Int

    public init(_ fact: Fact, _ subject: Int? = nil) {
        self.fact = fact
        self.subject = subject ?? (fact.scope == .scene ? 0 : 1)
    }

    /// The id used in the model's menu and replies: `conscious@2`, `water_rising`.
    public var id: String { fact.scope == .scene ? fact.key : "\(fact.key)@\(subject)" }

    public static func parse(_ id: String?) -> FactKey? {
        guard let id else { return nil }
        let raw = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let name = raw.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? raw
        guard let fact = Fact.fromKey(name) else { return nil }
        if fact.scope == .scene { return FactKey(fact) }
        var sub = 1
        if raw.contains("@") {
            guard let n = Int(raw.split(separator: "@", maxSplits: 1, omittingEmptySubsequences: false).last.map(String.init) ?? "") else { return nil }
            sub = n
        }
        return (1...Subjects.max).contains(sub) ? FactKey(fact, sub) : nil
    }
}

/// Questions with more than two answers. Options are chosen by the person; each has a stable `Option.key`.
public enum Choice: CaseIterable, Sendable, Hashable {
    case patients, location, floor, waterDepth

    public var key: String {
        switch self {
        case .patients: return "patients"
        case .location: return "location"
        case .floor: return "floor"
        case .waterDepth: return "water_depth"
        }
    }

    public var disasters: Set<DisasterType> { self == .waterDepth ? [.flood] : [] }

    public static func fromKey(_ k: String?) -> Choice? {
        guard let k else { return nil }
        let t = k.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return allCases.first { $0.key == t }
    }
}

extension Choice {
    /// True when `key` is one of this choice's answer keys.
    public func hasOption(_ key: String) -> Bool { Options.all(self).contains { $0.key == key } }
}

/// One answer to a `Choice`. `lex` recognises it in speech (any of the app's languages, code-mixed).
struct Option: Sendable {
    let key: String
    let lex: Lex
}

enum Options {
    private static func o(_ key: String, _ terms: String...) -> Option { Option(key: key, lex: Lex(terms: terms)) }

    // Order matters: the first option whose words appear wins, so the more specific ones come first.
    private static let location: [Option] = [
        o("rubble", "rubble", "debris", "buried", "collapsed", "malba*", "मलबा*", "मलबे*", "naipit*", "guho*"),
        o("basement", "basement", "cellar", "underground", "तहखाना", "तहख़ाना"),
        o("roof", "roof*", "terrace", "rooftop", "chhat*", "chat", "छत", "ଛାତ*", "bubong*"),
        o("ground_floor", "~ground\\s+floor", "downstairs", "inside the house", "inside house", "zameen*", "जमीन", "ज़मीन", "भूतल", "ଭୂମି*", "ଭୂତଳ*", "ibabang palapag", "sa lupa"),
        o("upper_floor", "~(?:upper|first|second|third|fourth|top|next)\\s+floor", "upstairs", "terrace floor", "~(?:1st|2nd|3rd|4th)\\s+floor", "मंज़िल", "मंजिल", "ମହଲା*", "itaas na palapag", "palapag"),
        o("vehicle", "car", "bus", "truck", "vehicle", "tractor", "boat", "gaadi*", "gadi*", "गाड़ी", "गाड़ी*", "naav*", "नाव", "ନାଆ", "ଗାଡ଼ି*", "sasakyan", "bangka"),
        o("outdoors", "outside", "outdoor*", "open", "field*", "road", "tree", "bahar", "khet*", "बाहर", "खेत*", "सड़क", "पेड़", "ବାହାର*", "ଖେତ*", "labas", "kalsada", "puno"),
    ]
    private static let floor: [Option] = [
        o("floor_3plus", "third", "3rd", "three", "fourth", "4th", "four", "higher", "top", "teesri", "तीसरी", "तीन", "ତିନି*", "pangatlo", "tatlo"),
        o("floor_2", "second", "2nd", "two", "doosri", "dusri", "दूसरी", "दो", "ଦ୍ୱିତୀୟ", "ଦୁଇ*", "pangalawa", "dalawa"),
        o("floor_1", "first", "1st", "one", "pehli", "पहली", "एक", "ପ୍ରଥମ", "ଏକ", "una", "isa"),
    ]
    private static let waterDepth: [Option] = [
        o("over_head", "~over\\s+(?:my\\s+)?head", "~above\\s+(?:my\\s+)?head", "head", "deep", "sir*", "सिर*", "ମୁଣ୍ଡ*", "ulo", "lubog"),
        o("chest", "chest", "neck", "seena", "seene", "सीने", "छाती", "ଛାତି*", "dibdib", "leeg"),
        o("waist", "waist", "kamar", "कमर", "ଅଣ୍ଟା*", "baywang"),
        o("knee", "knee*", "ghutn*", "घुटने", "घुटनों", "ଆଣ୍ଠୁ*", "tuhod"),
        o("ankle", "ankle*", "takhn*", "टखने", "ଗୋଡ଼*", "bukung-bukong", "paa"),
    ]
    private static let patients: [Option] = [
        o("three_plus", "three", "3", "four", "4", "many", "several", "everyone", "teen", "तीन", "ତିନି*", "tatlo", "marami"),
        o("two", "two", "2", "both", "do", "दो", "ଦୁଇ*", "dalawa"),
        o("one", "one", "1", "just", "only", "single", "ek", "एक", "ଏକ", "isa"),
    ]

    static func all(_ c: Choice) -> [Option] {
        switch c {
        case .location: return location
        case .floor: return floor
        case .waterDepth: return waterDepth
        case .patients: return patients
        }
    }

    /// The option of `c` that `text` names, restricted to `offered` keys; nil if none (or text is empty).
    static func detect(_ c: Choice, _ text: String, offered: [String]? = nil) -> String? {
        let t = normalizeText(text)
        if t.isEmpty { return nil }
        return all(c).first { (offered == nil || offered!.contains($0.key)) && $0.lex.matches(t) }?.key
    }
}

// ====================================================================== people

public enum Relation: CaseIterable, Sendable, Hashable {
    case mother, father, wife, husband, son, daughter, child, baby, grandmother, grandfather, brother, sister

    public var key: String {
        switch self {
        case .mother: return "mother"
        case .father: return "father"
        case .wife: return "wife"
        case .husband: return "husband"
        case .son: return "son"
        case .daughter: return "daughter"
        case .child: return "child"
        case .baby: return "baby"
        case .grandmother: return "grandmother"
        case .grandfather: return "grandfather"
        case .brother: return "brother"
        case .sister: return "sister"
        }
    }

    public static func fromKey(_ k: String?) -> Relation? {
        guard let k else { return nil }
        let t = k.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return allCases.first { $0.key == t }
    }
}

/// A person triage asks about. `relation` is how the speaker referred to them; nil = "the first person", "the second person".
public struct Subject: Equatable, Hashable, Sendable {
    public let id: Int
    public let relation: Relation?
    public init(_ id: Int, _ relation: Relation? = nil) { self.id = id; self.relation = relation }
}

public enum Subjects {
    /// Questioned one by one: beyond this, the rest are one group on the ticket (people count) and not questioned.
    public static let max = 3

    private static let lexicon: [(Relation, Lex)] = [
        (.mother, Lex("mother", "mom", "mum", "mummy", "maa", "माँ", "मां", "माता", "ମା", "ମାଆ", "ina", "nanay")),
        (.father, Lex("father", "dad", "daddy", "papa", "baap", "पिता", "पापा", "बाप", "ବାପା", "ବାପ", "ama", "tatay")),
        (.wife, Lex("wife", "biwi", "patni", "पत्नी", "बीवी", "ପତ୍ନୀ")),
        (.husband, Lex("husband", "pati", "पति", "ସ୍ୱାମୀ")),
        (.son, Lex("son", "beta", "बेटा", "ପୁଅ", "~anak\\s+na\\s+lalaki")),
        (.daughter, Lex("daughter", "beti", "बेटी", "ଝିଅ", "~anak\\s+na\\s+babae")),
        (.baby, Lex("baby", "infant", "newborn", "shishu", "शिशु", "ଶିଶୁ", "sanggol")),
        (.child, Lex("child", "kid", "bachcha", "bachha", "बच्चा", "ପିଲା", "anak", "bata")),
        (.grandmother, Lex("grandmother", "grandma", "dadi", "nani", "दादी", "नानी", "ଜେଜେମା", "lola")),
        (.grandfather, Lex("grandfather", "grandpa", "dada", "दादा", "नाना", "ଜେଜେବାପା", "lolo")),
        (.brother, Lex("brother", "bhai", "भाई", "ଭାଇ", "kuya")),
        (.sister, Lex("sister", "behen", "behan", "बहन", "ଭଉଣୀ")),
    ]

    /// Relations named in `text`, in the order spoken, each once.
    public static func relations(_ text: String) -> [Relation] {
        let t = normalizeText(text)
        return lexicon.compactMap { rel, lex in lex.find(t).map { (rel, $0.range.location) } }
            .sorted { $0.1 < $1.1 }.map { $0.0 }
    }

    /// Subjects for the relations spoken (at most `max`); none named = none (one unnamed patient is assumed until told otherwise).
    public static func fromText(_ text: String) -> [Subject] {
        relations(text).prefix(max).enumerated().map { Subject($0.offset + 1, $0.element) }
    }

    public static func ordinal(_ count: Int) -> [Subject] { (1...Swift.min(Swift.max(count, 1), max)).map { Subject($0) } }
}
