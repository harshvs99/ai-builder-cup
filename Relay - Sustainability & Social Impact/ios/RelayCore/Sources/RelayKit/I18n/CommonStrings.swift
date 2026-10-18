import Foundation
import RelayCore


/// Strings used by more than one screen: navigation, common actions, units, relative time, urgency, categories,
/// vulnerability flags, request status and timeline texts, and the villager status. English is the source text;
/// hi, or, tl and war are drafts: every non-English string here needs native review (// needs native review).
public struct CommonStrings: Equatable, Sendable {
    public let lang: Lang
    public let timeZone: TimeZone
    public init(_ lang: Lang, timeZone: TimeZone) { self.lang = lang; self.timeZone = timeZone }
    public var isEnglish: Bool { lang == .en }
    // ---- navigation
    public var navHome: String { tr(lang, "Home", "होम", "ହୋମ୍", "Home", "Balay") } // needs native review
    public var navRequests: String { tr(lang, "Requests", "अनुरोध", "ଅନୁରୋଧ", "Mga Kahilingan", "Mga Hangyo") } // needs native review
    public var navNetwork: String { tr(lang, "Network", "नेटवर्क", "ନେଟୱର୍କ", "Network", "Network") } // needs native review
    public var navSettings: String { tr(lang, "Settings", "सेटिंग्स", "ସେଟିଂସ", "Settings", "Settings") } // needs native review

    // ---- common actions
    public var change: String { tr(lang, "Change", "बदलें", "ବଦଳାନ୍ତୁ", "Baguhin", "Usba") } // needs native review
    public var continueAction: String { tr(lang, "Continue", "आगे बढ़ें", "ଜାରି ରଖନ୍ତୁ", "Magpatuloy", "Padayon") } // needs native review
    public var send: String { tr(lang, "Send", "भेजें", "ପଠାନ୍ତୁ", "Ipadala", "Ipadara") } // needs native review
    public var cancel: String { tr(lang, "Cancel", "रद्द करें", "ବାତିଲ୍", "Kanselahin", "Kanselaha") } // needs native review
    public var done: String { tr(lang, "Done", "हो गया", "ହୋଇଗଲା", "Tapos na", "Human na") } // needs native review
    public var back: String { tr(lang, "Back", "वापस", "ପଛକୁ", "Bumalik", "Balik") } // needs native review
    public var close: String { tr(lang, "Close", "बंद करें", "ବନ୍ଦ କରନ୍ତୁ", "Isara", "Sirhi") } // needs native review
    public var delete: String { tr(lang, "Delete", "हटाएं", "ବିଲୋପ କରନ୍ତୁ", "Burahin", "Papason") } // needs native review
    public var seeAll: String { tr(lang, "See all", "सभी देखें", "ସବୁ ଦେଖନ୍ତୁ", "Tingnan lahat", "Kitaa ngatanan") } // needs native review
    public var turnOn: String { tr(lang, "Turn on", "चालू करें", "ଚାଲୁ କରନ୍ତୁ", "I-on", "Buksi") } // needs native review
    public var somethingElse: String { tr(lang, "Something else", "कुछ और", "ଅନ୍ୟ କିଛି", "Iba pa", "Iba pa") } // needs native review

    // ---- units
    public func people(_ n: Int) -> String {
        switch lang {
        case .hi: return Plural.isOne(lang, n) ? "\(n) व्यक्ति" : "\(n) लोग" // needs native review
        case .or: return "\(n) ଜଣ" // needs native review
        case .tl: return "\(n) tao" // needs native review
        case .war: return "\(n) ka tawo" // needs native review
        default: return Plural.isOne(lang, n) ? "\(n) person" : "\(n) people"
        }
    }

    public func phones(_ n: Int) -> String {
        switch lang {
        case .hi: return "\(n) फ़ोन" // needs native review
        case .or: return "\(n) ଫୋନ୍" // needs native review
        case .tl: return "\(n) telepono" // needs native review
        case .war: return "\(n) ka telepono" // needs native review
        default: return Plural.isOne(lang, n) ? "\(n) phone" : "\(n) phones"
        }
    }

    public func hops(_ n: Int) -> String {
        switch lang {
        case .hi: return "\(n) हॉप" // needs native review
        case .or: return "\(n) ହପ୍" // needs native review
        case .tl: return "\(n) hop" // needs native review
        case .war: return "\(n) ka hop" // needs native review
        default: return Plural.isOne(lang, n) ? "\(n) hop" : "\(n) hops"
        }
    }

    // ---- time
    /// "just now", "2 min ago", "3 h ago", "yesterday", "4 days ago".
    public func ago(_ thenMs: Int64, _ nowMs: Int64) -> String {
        switch RelativeTime.bucket(thenMs, nowMs, zone: timeZone) {
        case .justNow: return tr(lang, "just now", "अभी-अभी", "ବର୍ତ୍ତମାନ", "ngayon lang", "yana la") // needs native review
        case .minutes(let n): return tr(
            lang, "\(n) min ago", "\(n) मिनट पहले", "\(n) ମିନିଟ୍ ପୂର୍ବେ", "\(n) min ang nakalipas", "\(n) minuto na an naglabay",
        ) // needs native review
        case .hours(let n): return tr(
            lang, "\(n) h ago", "\(n) घंटे पहले", "\(n) ଘଣ୍ଟା ପୂର୍ବେ", "\(n) oras ang nakalipas", "\(n) oras na an naglabay",
        ) // needs native review
        case .yesterday: return tr(lang, "yesterday", "कल", "ଗତକାଲି", "kahapon", "kakulop") // needs native review
        case .days(let n): return tr(
            lang, "\(n) days ago", "\(n) दिन पहले", "\(n) ଦିନ ପୂର୍ବେ", "\(n) araw ang nakalipas", "\(n) adlaw na an naglabay",
        ) // needs native review
        }
    }

    /// Short clock time in the app language's locale and the strings' time zone, e.g. "2:02 PM".
    public func clock(_ ms: Int64) -> String { ClockFormat.short(ms, lang: lang, zone: timeZone) }

    // ---- urgency (0 = can wait .. 3 = life at risk)
    public func urgencyWord(_ u: Int) -> String {
        switch min(max(u, 0), 3) {
        case 0: return tr(lang, "Can wait", "रुक सकता है", "ଅପେକ୍ଷା କରିପାରିବ", "Puwedeng maghintay", "Mahuhulat") // needs native review
        case 1: return tr(lang, "Soon", "जल्दी", "ଶୀଘ୍ର", "Maya-maya", "Madali") // needs native review
        case 2: return tr(lang, "Urgent", "ज़रूरी", "ଜରୁରୀ", "Madalian", "Pagmadali") // needs native review
        default: return tr(lang, "Life at risk", "जान को ख़तरा", "ଜୀବନ ବିପଦରେ", "Buhay ang nasa panganib", "Ginhadlok an kinabuhi") // needs native review
        }
    }

    /// The helper line under the urgency control, including the urgency word.
    public func urgencyHelp(_ u: Int) -> String {
        switch min(max(u, 0), 3) {
        case 0: return tr(
            lang, "Can wait: today or tomorrow is fine.", "रुक सकता है: आज या कल तक चलेगा।", "ଅପେକ୍ଷା କରିପାରିବ: ଆଜି କିମ୍ବା କାଲି ହେଲେ ଚଳିବ।",
            "Puwedeng maghintay: okay na ngayong araw o bukas.", "Mahuhulat: okay na yana nga adlaw o buwas.",
        ) // needs native review
        case 1: return tr(
            lang, "Soon: needed today.", "जल्दी: आज चाहिए।", "ଶୀଘ୍ର: ଆଜି ଦରକାର।",
            "Maya-maya: kailangan ngayong araw.", "Madali: kinahanglan yana nga adlaw.",
        ) // needs native review
        case 2: return tr(
            lang, "Urgent: needed in the next few hours.", "ज़रूरी: अगले कुछ घंटों में चाहिए।", "ଜରୁରୀ: ଆଗାମୀ କିଛି ଘଣ୍ଟାରେ ଦରକାର।",
            "Madalian: kailangan sa susunod na ilang oras.", "Pagmadali: kinahanglan ha sunod nga pira ka oras.",
        ) // needs native review
        default: return tr(
            lang, "Life at risk: someone may die without help now.", "जान को ख़तरा: अभी मदद न मिली तो किसी की जान जा सकती है।",
            "ଜୀବନ ବିପଦରେ: ଏବେ ସାହାଯ୍ୟ ନମିଳିଲେ କାହାର ମୃତ୍ୟୁ ହୋଇପାରେ।",
            "Buhay ang nasa panganib: maaaring may mamatay kung walang tulong ngayon.",
            "Ginhadlok an kinabuhi: may mamamatay kon waray bulig yana.",
        ) // needs native review
        }
    }

    // ---- categories and vulnerability flags
    public func categoryName(_ c: NeedCategory) -> String {
        switch c {
        case .water: return tr(lang, "Water", "पानी", "ପାଣି", "Tubig", "Tubig")
        case .food: return tr(lang, "Food", "खाना", "ଖାଦ୍ୟ", "Pagkain", "Pagkaon")
        case .medical: return tr(lang, "Medicine", "दवा", "ଔଷଧ", "Gamot", "Tambal")
        case .rescue: return tr(lang, "Rescue", "बचाव", "ଉଦ୍ଧାର", "Pagsagip", "Pagluwas")
        case .shelter: return tr(lang, "Shelter", "आश्रय", "ଆଶ୍ରୟ", "Matutuluyan", "Silungan")
        case .missingPerson: return tr(lang, "Missing person", "लापता व्यक्ति", "ନିଖୋଜ ବ୍ୟକ୍ତି", "Nawawalang tao", "Nawawara nga tawo")
        case .other: return tr(lang, "Other", "अन्य", "ଅନ୍ୟ", "Iba pa", "Iba pa")
        }
    } // needs native review (non-English)

    /// The "get me out of here" chip: a vehicle to leave the area. Goes out as a RESCUE ticket, item "evacuation".
    public var transportOut: String { tr(lang, "Transport out", "बाहर जाने का साधन", "ବାହାରକୁ ଯିବା ଯାନ", "Sasakyan palabas", "Sakyanan paggawas") } // needs native review (non-English)

    public func vulnFlag(_ f: VulnFlag) -> String {
        switch f {
        case .elderly: return tr(lang, "elderly", "बुज़ुर्ग", "ବୃଦ୍ଧ", "matatanda", "tigurang")
        case .child: return tr(lang, "children", "बच्चे", "ପିଲା", "bata", "bata")
        case .pregnant: return tr(lang, "pregnant", "गर्भवती", "ଗର୍ଭବତୀ", "buntis", "buntis")
        case .injured: return tr(lang, "injured", "घायल", "ଆହତ", "sugatan", "nasamdan")
        case .trapped: return tr(lang, "trapped", "फँसे हुए", "ଫସିଯାଇଛନ୍ତି", "nakulong", "naipit")
        }
    } // needs native review (non-English)

    /// "children, elderly" for a [VulnFlag] mask; empty when none.
    public func vulnWords(_ mask: Int) -> String {
        VulnFlag.fromMask(mask).sorted().map { vulnFlag($0) }.joined(separator: ", ")
    }

    // ---- request status
    public var statusClosed: String { tr(lang, "Closed", "बंद", "ବନ୍ଦ", "Sarado", "Sirado") } // needs native review
    public var statusCancelled: String { tr(lang, "Cancelled", "रद्द", "ବାତିଲ୍", "Kinansela", "Gin-kansela") } // needs native review
    public var statusActive: String { tr(lang, "Active", "चालू", "ସକ୍ରିୟ", "Aktibo", "Aktibo") } // needs native review

    // ---- timeline
    public func stepTitle(_ key: RequestTimeline.Key) -> String {
        switch key {
        case .saved: return tr(lang, "Saved on this phone", "इस फ़ोन में सहेजा गया", "ଏହି ଫୋନରେ ସେଭ୍ ହୋଇଛି", "Naka-save sa teleponong ito", "Gin-save ha ini nga telepono")
        case .passedToNeighbour: return tr(lang, "Passed to a neighbour's phone", "पड़ोसी के फ़ोन तक पहुँचा", "ପଡ଼ୋଶୀର ଫୋନକୁ ପଠାଯାଇଛି", "Naipasa sa telepono ng kapitbahay", "Gin-ipasa ha telepono han kaarabay")
        case .reachedGateway: return tr(lang, "Reached a gateway phone", "गेटवे फ़ोन तक पहुँचा", "ଗେଟୱେ ଫୋନରେ ପହଞ୍ଚିଛି", "Nakarating sa gateway phone", "Nakaabot ha gateway nga telepono")
        case .seenByControlRoom: return tr(lang, "Seen by the control room", "कंट्रोल रूम ने देखा", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍ ଦେଖିଛି", "Nakita ng control room", "Nakita han control room")
        case .helpComing: return tr(lang, "Help is coming", "मदद आ रही है", "ସାହାଯ୍ୟ ଆସୁଛି", "Parating na ang tulong", "An bulig nag-abot na")
        case .teamOnTheWay: return tr(lang, "Team on the way", "टीम रास्ते में है", "ଟିମ୍ ବାଟରେ ଅଛି", "Papunta na ang team", "Pakadto na an team")
        case .delivered: return tr(lang, "Delivered", "पहुँचा दिया गया", "ପହଞ୍ଚାଇ ଦିଆଗଲା", "Naihatid na", "Nahatod na")
        }
    } // needs native review (non-English)

    public var liveTag: String { tr(lang, "LIVE", "लाइव", "ଲାଇଭ୍", "LIVE", "LIVE") } // needs native review

    private var uploadedNote: String { tr(
        lang, "uploaded to the control room", "कंट्रोल रूम में अपलोड हुआ", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍‌କୁ ଅପଲୋଡ୍ ହୋଇଛି",
        "na-upload sa control room", "na-upload ha control room",
    ) } // needs native review

    private func markedUrgency(_ u: Int) -> String {
        let w = urgencyWord(u).lowercased()
        return tr(lang, "marked \(w)", "\(w) के रूप में चिह्नित", "\(w) ଭାବରେ ଚିହ୍ନିତ", "minarkahang \(w)", "gin-marka nga \(w)")
    } // needs native review

    public func expectedBy(_ ms: Int64) -> String {
        let t = clock(ms)
        return tr(lang, "Expected by \(t)", "\(t) तक पहुँचने की उम्मीद", "\(t) ସୁଦ୍ଧା ଆସିବାର ଆଶା", "Inaasahan pagsapit ng \(t)", "Ginpapaabot ha \(t)")
    } // needs native review

    public var runningLate: String { tr(lang, "Later than expected", "अपेक्षा से देर", "ଆଶାଠାରୁ ଡେରି", "Mas matagal kaysa inaasahan", "Mas mahuri kay han ginpaabot") } // needs native review

    /// Second line of a timeline step: time, plus the spec's detail ("uploaded to the control room", ...).
    public func stepSubtitle(_ step: RequestTimeline.Step) -> String {
        var parts: [String] = []
        if step.atMs > 0 { parts.append(clock(step.atMs)) }
        switch step.key {
        case .reachedGateway:
            parts.append(uploadedNote)
        case .seenByControlRoom:
            if let u = step.urgency { parts.append(markedUrgency(u)) }
        case .helpComing, .teamOnTheWay:
            if step.etaAtMs > 0 { parts.append(step.etaOverdue ? runningLate : expectedBy(step.etaAtMs)) }
        default:
            break
        }
        return parts.joined(separator: " · ")
    }

    /// Line two of a request row: "Reached a gateway phone · 2 min ago", "Delivered yesterday".
    public func rowStatusLine(_ step: RequestTimeline.Step, _ now: Int64) -> String {
        let title = stepTitle(step.key)
        if step.atMs <= 0 { return title }
        let when = ago(step.atMs, now)
        return step.key == .delivered ? "\(title) \(when)" : "\(title) · \(when)"
    }

    // ---- villager status (Home and Network)
    public func villagerTitle(_ s: VillagerStatus) -> String {
        switch s.kind {
        case .reachable: return tr(lang, "Your messages can reach help", "आपके संदेश मदद तक पहुँच सकते हैं", "ଆପଣଙ୍କ ସନ୍ଦେଶ ସାହାଯ୍ୟ ପାଖରେ ପହଞ୍ଚିପାରିବ", "Maaabot ng mga mensahe mo ang tulong", "Maabot han imo mga mensahe an bulig")
        case .thisPhoneGateway: return tr(lang, "This phone is sending messages to help", "यह फ़ोन मदद तक संदेश भेज रहा है", "ଏହି ଫୋନ ସାହାଯ୍ୟକୁ ସନ୍ଦେଶ ପଠାଉଛି", "Ang teleponong ito ay nagpapadala ng mensahe sa tulong", "Ini nga telepono nagpapadara hin mensahe ngadto ha bulig")
        case .waitingForGateway: return tr(lang, "Waiting for a phone with signal", "सिग्नल वाले फ़ोन का इंतज़ार", "ସିଗ୍ନାଲ ଥିବା ଫୋନ୍ ପାଇଁ ଅପେକ୍ଷା", "Naghihintay ng telepono na may signal", "Naghuhulat hin telepono nga may signal")
        case .searching: return tr(lang, "Looking for nearby phones", "आस-पास के फ़ोन खोज रहे हैं", "ପାଖ ଫୋନ୍ ଖୋଜୁଛି", "Naghahanap ng mga kalapit na telepono", "Naghahanap hin kalapit nga telepono")
        case .bluetoothOff: return tr(lang, "Bluetooth is off", "ब्लूटूथ बंद है", "ବ୍ଲୁଟୁଥ୍ ବନ୍ଦ ଅଛି", "Naka-off ang Bluetooth", "Gin-off an Bluetooth")
        }
    } // needs native review (non-English)

    /// One line under the title. [now] turns "synced X ago" durations into words.
    public func villagerLine(_ s: VillagerStatus, _ now: Int64) -> String {
        switch s.kind {
        case .reachable:
            let away = hops(s.hopsToGateway ?? 1)
            let base = tr(
                lang, "Gateway \(away) away", "गेटवे \(away) दूर", "ଗେଟୱେ \(away) ଦୂରରେ",
                "Gateway, \(away) ang layo", "Gateway, \(away) an kalayo",
            )
            return base + agoSuffix(s.syncedAgoMs, now, "online")
        case .thisPhoneGateway:
            let base = tr(lang, "Connected to the internet", "इंटरनेट से जुड़ा है", "ଇଣ୍ଟରନେଟ୍ ସହ ଯୋଡ଼ା ଅଛି", "Nakakonekta sa internet", "Nakakonekta ha internet")
            return base + agoSuffix(s.syncedAgoMs, now, "synced")
        case .waitingForGateway:
            let safe = tr(lang, "Your messages wait safely.", "आपके संदेश सुरक्षित रखे हैं।", "ଆପଣଙ୍କ ସନ୍ଦେଶ ସୁରକ୍ଷିତ ଅଛି।", "Ligtas na naghihintay ang mga mensahe mo.", "Ligtas nga naghuhulat an imo mga mensahe.")
            if s.peers > 0 {
                let n = phones(s.peers)
                return tr(lang, "Connected to \(n) nearby. ", "आस-पास \(n) से जुड़ा है। ", "ପାଖରେ \(n) ସହ ଯୋଡ଼ା ଅଛି। ", "Nakakonekta sa \(n) sa malapit. ", "Nakakonekta ha \(n) nga malapit. ") + safe
            }
            return safe
        case .searching: return tr(
            lang, "Keep Relay open. Messages wait safely on this phone.", "Relay खुला रखें। संदेश इस फ़ोन में सुरक्षित रखे हैं।",
            "Relay ଖୋଲା ରଖନ୍ତୁ। ସନ୍ଦେଶ ଏହି ଫୋନରେ ସୁରକ୍ଷିତ ଅଛି।", "Panatilihing bukas ang Relay. Ligtas na naghihintay ang mga mensahe sa telepono.",
            "Pabuksi an Relay. Ligtas nga naghuhulat an mga mensahe hini nga telepono.",
        )
        case .bluetoothOff: return tr(
            lang, "Turn it on so phones can pass your message", "इसे चालू करें ताकि फ़ोन आपका संदेश आगे पहुँचा सकें",
            "ଏହାକୁ ଚାଲୁ କରନ୍ତୁ, ଯେପରି ଫୋନ୍ ଆପଣଙ୍କ ସନ୍ଦେଶ ପଠାଇପାରିବ", "I-on ito para maipasa ng mga telepono ang mensahe mo",
            "Buksi ini basi maipasa han mga telepono an imo mensahe",
        )
        }
    } // needs native review (non-English)

    private func agoSuffix(_ durationMs: Int64?, _ now: Int64, _ word: String) -> String {
        guard let durationMs else { return "" }
        let a = ago(now - durationMs, now)
        let w: String
        switch word {
        case "online": w = tr(lang, "online \(a)", "\(a) ऑनलाइन", "\(a) ଅନଲାଇନ୍", "online \(a)", "online \(a)")
        default: w = tr(lang, "synced \(a)", "\(a) सिंक हुआ", "\(a) ସିଙ୍କ୍ ହୋଇଛି", "na-sync \(a)", "na-sync \(a)")
        }
        return " · \(w)"
    } // needs native review

    /// The status word beside the dot: "Reachable", "Waiting", "Searching", "Off".
    public func villagerWord(_ s: VillagerStatus) -> String {
        switch s.kind {
        case .reachable, .thisPhoneGateway:
            return tr(lang, "Reachable", "पहुँच में", "ପହଞ୍ଚିହେବ", "Maaabot", "Maabot")
        case .waitingForGateway: return tr(lang, "Waiting", "इंतज़ार", "ଅପେକ୍ଷା", "Naghihintay", "Naghuhulat")
        case .searching: return tr(lang, "Searching", "खोज जारी", "ଖୋଜୁଛି", "Naghahanap", "Naghahanap")
        case .bluetoothOff: return tr(lang, "Off", "बंद", "ବନ୍ଦ", "Off", "Off")
        }
    } // needs native review (non-English)
}

