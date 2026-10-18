import Foundation

/// The fixed follow-up questions a dispatcher would ask, as plain data. Neither the rules nor a model ever writes a
/// question: they only choose ids from this bank, and the app shows the text below in the requester's language.
///
/// Ids and answer values are wire values (SPEC 5.1 answer bytes) and must never be renumbered. Answer value 0 is
/// always "unknown". "How many people" is not a question here: it fills the NEED's `people` field.
///
/// Non-English text is pending native-speaker review (like the ACK templates). Cebuano has no text yet and falls back
/// to English.
public struct AnswerOption: Equatable, Hashable, Sendable {
    public let value: Int
    public let key: String
    private let labels: [Lang: String]

    public init(_ value: Int, _ key: String, _ labels: [Lang: String]) {
        self.value = value; self.key = key; self.labels = labels
    }

    public func label(_ lang: Lang) -> String { labels[lang] ?? labels[.en]! }
}

public struct Question: Equatable, Hashable, Sendable {
    public let id: Int
    /// stable name used in the backend / dashboard JSON (`answers: {"conscious": "no"}`)
    public let key: String
    public let categories: Set<Category>
    public let options: [AnswerOption]
    private let texts: [Lang: String]

    public init(_ id: Int, _ key: String, _ categories: Set<Category>, _ options: [AnswerOption], _ texts: [Lang: String]) {
        self.id = id; self.key = key; self.categories = categories; self.options = options; self.texts = texts
    }

    public func text(_ lang: Lang) -> String { texts[lang] ?? texts[.en]! }
    public func option(_ value: Int) -> AnswerOption? { options.first { $0.value == value } }
    public func optionByKey(_ key: String) -> AnswerOption? { options.first { $0.key == key } }
}

public enum QuestionBank {
    /// At most this many answers fit in a NEED (SPEC 5.1).
    public static let maxAnswers = 3

    /// Largest id the 5-bit wire field can carry.
    public static let maxId = 31

    private static func labels(_ en: String, _ hi: String, _ or: String, _ tl: String, _ war: String) -> [Lang: String] {
        [.en: en, .hi: hi, .or: or, .tl: tl, .war: war]
    }

    private static let unknown = AnswerOption(0, "unknown", labels("Don't know", "पता नहीं", "ଜଣା ନାହିଁ", "Hindi alam", "Diri aram"))
    private static let yes = AnswerOption(1, "yes", labels("Yes", "हाँ", "ହଁ", "Oo", "Oo"))
    private static let no = AnswerOption(2, "no", labels("No", "नहीं", "ନା", "Hindi", "Diri"))
    private static let yesNoUnknown: [AnswerOption] = [unknown, yes, no]

    private static let roof = AnswerOption(1, "roof", labels("Roof", "छत", "ଛାତ", "Bubong", "Atop"))
    private static let ground = AnswerOption(2, "ground", labels("Ground", "ज़मीन", "ଭୂମି", "Lupa", "Tuna"))

    private static let howLong: [AnswerOption] = [
        unknown,
        AnswerOption(1, "today", labels("Since today", "आज से", "ଆଜିଠାରୁ", "Mula ngayon", "Tikang yana")),
        AnswerOption(2, "1-2 days", labels("1-2 days", "1-2 दिन", "1-2 ଦିନ", "1-2 araw", "1-2 ka adlaw")),
        AnswerOption(3, "3+ days", labels("3+ days", "3+ दिन", "3+ ଦିନ", "3+ araw", "3+ ka adlaw")),
    ]

    public static let all: [Question] = [
        Question(1, "conscious", [.medical], yesNoUnknown, labels(
            "Is the person awake and responding?", "क्या व्यक्ति होश में है और जवाब दे रहा है?",
            "ବ୍ୟକ୍ତି ହୋସରେ ଅଛନ୍ତି କି ଓ ଉତ୍ତର ଦେଉଛନ୍ତି କି?", "Gising ba at sumasagot ang tao?",
            "Nagmamata ba ngan nasagot an tawo?")),
        Question(2, "road_reachable", [.medical], yesNoUnknown, labels(
            "Can a vehicle reach you by road?", "क्या गाड़ी सड़क से आप तक पहुँच सकती है?",
            "ରାସ୍ତାରେ ଗାଡ଼ି ଆପଣଙ୍କ ପାଖରେ ପହଞ୍ଚିପାରିବ କି?", "Maaabot ba kayo ng sasakyan sa kalsada?",
            "Maabot ba kamo han sakayan ha dalan?")),
        Question(3, "water_rising", [.rescue], yesNoUnknown, labels(
            "Is the water still rising?", "क्या पानी अभी भी बढ़ रहा है?", "ପାଣି ଏବେ ବି ବଢୁଛି କି?",
            "Tumataas pa ba ang tubig?", "Nagtataas pa ba an tubig?")),
        Question(4, "roof_or_ground", [.rescue], [unknown, roof, ground], labels(
            "Are you on a roof or on the ground?", "आप छत पर हैं या ज़मीन पर?",
            "ଆପଣ ଛାତ ଉପରେ ଅଛନ୍ତି ନା ଭୂମିରେ?", "Nasa bubong ba kayo o nasa lupa?", "Ha atop ba kamo o ha tuna?")),
        Question(5, "children_or_elderly", [.water, .food], yesNoUnknown, labels(
            "Are there children or elderly people with you?", "क्या आपके साथ बच्चे या बुज़ुर्ग हैं?",
            "ଆପଣଙ୍କ ସାଙ୍ଗରେ ପିଲା କିମ୍ବା ବୃଦ୍ଧ ଲୋକ ଅଛନ୍ତି କି?", "May kasama ba kayong bata o matanda?",
            "May kaupod ba kamo nga bata o tigurang?")),
        Question(6, "how_long", [.water, .food], howLong, labels(
            "How long have you been without?", "आपको कितने समय से नहीं मिला है?", "କେତେ ଦିନ ହେଲା ପାଇ ନାହାନ୍ତି?",
            "Gaano na katagal kayong walang nakukuha?", "Pira na ka adlaw nga waray kamo nakakarawat?")),
        Question(7, "house_damaged", [.shelter], yesNoUnknown, labels(
            "Is your house damaged?", "क्या आपका घर क्षतिग्रस्त है?", "ଆପଣଙ୍କ ଘର କ୍ଷତିଗ୍ରସ୍ତ ହୋଇଛି କି?",
            "Nasira ba ang inyong bahay?", "Naguba ba an iyo balay?")),
        Question(8, "last_seen_today", [.missingPerson], yesNoUnknown, labels(
            "Was the person seen today?", "क्या व्यक्ति आज दिखाई दिया था?", "ବ୍ୟକ୍ତିଙ୍କୁ ଆଜି ଦେଖାଯାଇଥିଲା କି?",
            "Nakita ba ang tao ngayong araw?", "Nakita ba an tawo yana nga adlaw?")),
    ]

    private static let byIdTable: [Int: Question] = Dictionary(uniqueKeysWithValues: all.map { ($0.id, $0) })
    private static let byKeyTable: [String: Question] = Dictionary(uniqueKeysWithValues: all.map { ($0.key, $0) })

    public static func byId(_ id: Int) -> Question? { byIdTable[id] }
    public static func byKey(_ key: String) -> Question? { byKeyTable[key] }

    /// The category's questions in asking order. OTHER has none.
    public static func forCategory(_ category: Category) -> [Question] { all.filter { $0.categories.contains(category) } }

    /// True if [id] is a bank question and [value] one of its answers.
    public static func isValid(_ id: Int, _ value: Int) -> Bool { byIdTable[id]?.option(value) != nil }

    /// Up to `maxAnswers` question ids for a request of [category], in asking order. Rules only: skips a question the
    /// text already answered (children or elderly flagged by the parser).
    public static func select(_ category: Category, _ vulnFlags: Set<VulnFlag>) -> [Int] {
        Array(
            forCategory(category)
                .filter { !($0.key == "children_or_elderly" && (vulnFlags.contains(.child) || vulnFlags.contains(.elderly))) }
                .prefix(maxAnswers)
                .map { $0.id }
        )
    }
}
