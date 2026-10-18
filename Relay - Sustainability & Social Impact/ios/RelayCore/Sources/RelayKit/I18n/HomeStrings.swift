import Foundation
import RelayCore


/// Strings for the language picker, Home and the Requests tab. Owned by the Home agent.
public struct HomeStrings: Equatable, Sendable {
    public let lang: Lang
    public init(_ lang: Lang) { self.lang = lang }
    public var title: String { tr(lang, "Relay", "Relay", "Relay", "Relay", "Relay") }

    // ---- Choose your language
    public var chooseTitle: String { tr(
        lang, "Choose your language", "अपनी भाषा चुनें", "ଆପଣଙ୍କ ଭାଷା ବାଛନ୍ତୁ", "Piliin ang iyong wika", "Pilia an imo pinulongan",
    ) } // needs native review
    public var chooseSubtitle: String { tr(
        lang,
        "Tap the speaker to hear it. You can change this later in Settings.",
        "सुनने के लिए स्पीकर दबाएँ। आप इसे बाद में सेटिंग्स में बदल सकते हैं।",
        "ଶୁଣିବାକୁ ସ୍ପିକର ଦବାନ୍ତୁ। ଆପଣ ଏହାକୁ ପରେ ସେଟିଂସରେ ବଦଳାଇପାରିବେ।",
        "I-tap ang speaker para marinig ito. Puwede mo itong palitan mamaya sa Settings.",
        "Pidlita an speaker basi mapamati ini. Mababag-o mo ini ha ulahi ha Settings.",
    ) } // needs native review
    public var addLanguage: String { tr(
        lang, "Add a language", "भाषा जोड़ें", "ଭାଷା ଯୋଡ଼ନ୍ତୁ", "Magdagdag ng wika", "Pagdugang hin pinulongan",
    ) } // needs native review
    /// Shown once when the stored language has been switched off (contract v2 section 1). `name` is its English name.
    public func languageUnavailable(_ name: String) -> String { trEnHi(
        lang, "\(name) is not available yet; showing English.", "\(name) अभी उपलब्ध नहीं है; अंग्रेज़ी दिखाई जा रही है।",
    ) } // needs native review
    public func speakLanguage(_ name: String) -> String { tr(
        lang, "Hear \(name)", "\(name) सुनें", "\(name) ଶୁଣନ୍ତୁ", "Pakinggan ang \(name)", "Pamati an \(name)",
    ) } // needs native review
    public func noVoice(_ name: String) -> String { tr(
        lang,
        "No \(name) voice is installed on this phone.",
        "इस फ़ोन में \(name) की आवाज़ इंस्टॉल नहीं है।",
        "ଏହି ଫୋନରେ \(name) ସ୍ୱର ଇନଷ୍ଟଲ୍ ହୋଇନାହିଁ।",
        "Walang naka-install na boses para sa \(name) sa teleponong ito.",
        "Waray naka-install nga tingog para ha \(name) hini nga telepono.",
    ) } // needs native review

    /// What the speaker button says for [target]: its name and a short greeting, in that language (not the UI language).
    public func greeting(_ target: Lang) -> String {
        switch target {
        case .hi: return "हिन्दी। नमस्ते, यह रिले है।" // needs native review
        case .or: return "ଓଡ଼ିଆ। ନମସ୍କାର, ଏହା ରିଲେ।" // needs native review
        case .tl: return "Tagalog. Kumusta, ito ang Relay." // needs native review
        case .war: return "Waray. Maupay, ini an Relay." // needs native review
        default: return "English. Hello, this is Relay."
        }
    }

    // ---- Home
    public var helpLabel: String { tr(lang, "How Relay works", "Relay कैसे काम करता है", "Relay କିପରି କାମ କରେ", "Paano gumagana ang Relay", "Paonan nag-obra an Relay") } // needs native review
    public var helpLine1: String { tr(
        lang,
        "Press the yellow button and say what you need.",
        "पीला बटन दबाएँ और बताएँ कि आपको क्या चाहिए।",
        "ହଳଦିଆ ବଟନ୍ ଦବାନ୍ତୁ ଏବଂ ଆପଣଙ୍କୁ କ'ଣ ଦରକାର କୁହନ୍ତୁ।",
        "Pindutin ang dilaw na button at sabihin ang kailangan mo.",
        "Pidlita an dilaw nga button ngan igsiring an imo kinahanglan.",
    ) } // needs native review
    public var helpLine2: String { tr(
        lang,
        "Your phone passes the message from phone to phone, with no network needed, until it reaches a phone with signal.",
        "आपका फ़ोन संदेश को एक फ़ोन से दूसरे फ़ोन तक पहुँचाता है, बिना नेटवर्क के, जब तक वह सिग्नल वाले फ़ोन तक न पहुँच जाए।",
        "ଆପଣଙ୍କ ଫୋନ୍ ନେଟୱର୍କ ବିନା ସନ୍ଦେଶକୁ ଫୋନରୁ ଫୋନକୁ ପଠାଏ, ସିଗ୍ନାଲ ଥିବା ଫୋନରେ ପହଞ୍ଚିବା ପର୍ଯ୍ୟନ୍ତ।",
        "Ipinapasa ng telepono mo ang mensahe mula telepono hanggang telepono, kahit walang network, hanggang umabot sa teleponong may signal.",
        "An imo telepono nagpapasa han mensahe tikang ha telepono pakadto ha telepono, bisan waray network, tubtob makaabot ha telepono nga may signal.",
    ) } // needs native review
    public var helpLine3: String { tr(
        lang,
        "The control room sends help, and the answer comes back to this phone.",
        "कंट्रोल रूम मदद भेजता है, और जवाब इसी फ़ोन पर लौट आता है।",
        "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍ ସାହାଯ୍ୟ ପଠାଏ, ଏବଂ ଉତ୍ତର ଏହି ଫୋନକୁ ଫେରିଆସେ।",
        "Magpapadala ng tulong ang control room, at babalik ang sagot sa teleponong ito.",
        "An control room nagpapadara hin bulig, ngan mabalik an sagot hini nga telepono.",
    ) } // needs native review
    public var statusOpensNetwork: String { tr(lang, "Opens Network", "नेटवर्क खुलेगा", "ନେଟୱର୍କ ଖୋଲିବ", "Bubuksan ang Network", "Bubuksan an Network") } // needs native review
    public var pressAndSay: String { tr(
        lang, "Press and say what you need", "दबाएँ और बताएँ कि आपको क्या चाहिए", "ଦବାନ୍ତୁ ଏବଂ ଆପଣଙ୍କୁ କ'ଣ ଦରକାର କୁହନ୍ତୁ",
        "Pindutin at sabihin ang kailangan mo", "Pidlita ngan igsiring an imo kinahanglan",
    ) } // needs native review
    /// "Water · food · medicine · rescue · shelter" from the category names.
    public var categoriesLine: String {
        let c = CommonStrings(lang, timeZone: .autoupdatingCurrent)
        let cats: [NeedCategory] = [.water, .food, .medical, .rescue, .shelter]
        return cats.enumerated().map { i, cat in
            let n = c.categoryName(cat)
            return i == 0 ? n : n.lowercased()
        }.joined(separator: " · ")
    }
    public var typeInstead: String { tr(lang, "Type instead", "इसके बजाय टाइप करें", "ବଦଳରେ ଟାଇପ୍ କରନ୍ତୁ", "Mag-type na lang", "Magtayp na la") } // needs native review
    public var yourRequests: String { tr(lang, "Your requests", "आपके अनुरोध", "ଆପଣଙ୍କ ଅନୁରୋଧ", "Iyong mga kahilingan", "Imo mga hangyo") } // needs native review
    public var noRequestsShort: String { tr(lang, "No requests yet", "अभी कोई अनुरोध नहीं", "ଏପର୍ଯ୍ୟନ୍ତ କୌଣସି ଅନୁରୋଧ ନାହିଁ", "Wala pang kahilingan", "Waray pa hangyo") } // needs native review
    public var bluetoothWhy: String { tr(
        lang,
        "So your phone can pass messages to neighbours",
        "ताकि आपका फ़ोन पड़ोसियों तक संदेश पहुँचा सके",
        "ଯେପରି ଆପଣଙ୍କ ଫୋନ୍ ପଡ଼ୋଶୀମାନଙ୍କୁ ସନ୍ଦେଶ ପଠାଇପାରିବ",
        "Para maipasa ng telepono mo ang mga mensahe sa mga kapitbahay",
        "Basi maipasa han imo telepono an mga mensahe ha mga kasumpay",
    ) } // needs native review

    // ---- Requests tab
    public var requestsTitle: String { yourRequests }
    public var emptyRequests: String { tr(
        lang,
        "No requests yet. Press the yellow button on Home to ask for help.",
        "अभी कोई अनुरोध नहीं। मदद माँगने के लिए होम पर पीला बटन दबाएँ।",
        "ଏପର୍ଯ୍ୟନ୍ତ କୌଣସି ଅନୁରୋଧ ନାହିଁ। ସାହାଯ୍ୟ ମାଗିବାକୁ ହୋମରେ ହଳଦିଆ ବଟନ୍ ଦବାନ୍ତୁ।",
        "Wala pang kahilingan. Pindutin ang dilaw na button sa Home para humingi ng tulong.",
        "Waray pa hangyo. Pidlita an dilaw nga button ha Balay basi mangayo hin bulig.",
    ) } // needs native review
}

