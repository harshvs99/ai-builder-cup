import Foundation
import RelayCore

/// Strings of the Ready screens (contract v2 section 3): the Home banner, "Get ready", "My household" and the notification.
/// English and Hindi only: Hindi is a draft for native review, and Odia, Filipino and Waray show English (they are hidden, see
/// `trEnHi`). Backend text (warning headline, plan steps, checklist) arrives already written and is not translated here.
/// Port of Kotlin `ReadyStrings`.
public struct ReadyStrings: Equatable, Sendable {
    private let lang: Lang
    private let timeZone: TimeZone
    public init(_ lang: Lang, timeZone: TimeZone = .autoupdatingCurrent) { self.lang = lang; self.timeZone = timeZone }

    private func t(_ en: String, _ hi: String) -> String { trEnHi(lang, en, hi) } // hi: needs native review

    // ---- Home banner
    public var getReady: String { t("Get ready", "तैयारी करें") }
    public func landfallIn(_ hours: Int) -> String { t("Landfall in about \(hours) h", "लगभग \(hours) घंटे में तूफ़ान तट से टकराएगा") }
    public func levelWord(_ l: WarningLevel) -> String {
        switch l {
        case .watch: return t("Watch", "निगरानी")
        case .warning: return t("Warning", "चेतावनी")
        case .alert: return t("Alert", "अलर्ट")
        }
    }
    public func bannerDescription(_ level: WarningLevel, headline: String, hours: Int?) -> String {
        "\(levelWord(level)). \(headline)." + (hours.map { " \(landfallIn($0))." } ?? "") + " \(getReady)"
    }

    // ---- Ready screen
    public var readyTitle: String { t("Get ready", "तैयारी करें") }
    public var warningTitle: String { t("Warning", "चेतावनी") }
    public func issued(_ sec: Int64) -> String { t("Issued \(clock(sec))", "जारी: \(clock(sec))") }
    public func landfallAt(_ sec: Int64) -> String { t("Expected landfall \(clock(sec))", "तट से टकराने का अनुमान: \(clock(sec))") }
    public func source(_ source: String) -> String { t("From \(source)", "स्रोत: \(source)") }
    public var yourPlan: String { t("Your plan", "आपकी योजना") }
    public var hearPlan: String { t("Hear the plan", "योजना सुनें") }
    public var stopPlan: String { t("Stop", "रुकें") }
    public var shownInEnglish: String { t("Shown in English", "अंग्रेज़ी में दिखाया गया") }
    public var noVoicePlan: String { t("No voice is installed for this language on this phone.", "इस फ़ोन में इस भाषा की आवाज़ इंस्टॉल नहीं है।") }
    public var nearestShelters: String { t("Nearest shelters", "सबसे पास के आश्रय") }
    public var noShelters: String { t("No verified shelter list for your district yet.", "आपके ज़िले की सत्यापित आश्रय सूची अभी नहीं है।") }
    public func kmAway(_ km: Double) -> String {
        let v = String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), km)
        return t("\(v) km", "\(v) किमी")
    }
    public var allShelters: String { t("See all shelters", "सभी आश्रय देखें") }
    public var checklist: String { t("Checklist", "जाँच-सूची") }
    public func updated(_ sec: Int64) -> String { t("Updated \(clock(sec))", "अपडेट: \(clock(sec))") }
    public func verifiedOn(_ sec: Int64?) -> String {
        guard let sec else { return t("Shelter list verified by the district", "आश्रय सूची ज़िले द्वारा सत्यापित") }
        return t("Shelter list verified by the district on \(day(sec))", "आश्रय सूची ज़िले द्वारा \(day(sec)) को सत्यापित")
    }
    public var demoList: String { t("Demo shelter list, not verified", "डेमो आश्रय सूची, सत्यापित नहीं") }
    public var refresh: String { t("Refresh", "ताज़ा करें") }
    public var refreshing: String { t("Updating…", "अपडेट हो रहा है…") }
    public var refreshFailed: String { t("Could not update. Showing the saved plan.", "अपडेट नहीं हो सका। सहेजी हुई योजना दिखाई जा रही है।") }
    public var noWarningNow: String { t("No warning for your district right now.", "अभी आपके ज़िले के लिए कोई चेतावनी नहीं है।") }

    // ---- empty state and district
    public var emptyTitle: String { t("Nothing downloaded yet", "अभी कुछ डाउनलोड नहीं हुआ") }
    public var emptyBody: String {
        t("Relay downloads a plan when there is a warning and the phone has signal. Open the app when you have signal before a storm.",
          "जब चेतावनी होती है और फ़ोन में सिग्नल होता है, तब Relay एक योजना डाउनलोड करता है। तूफ़ान से पहले सिग्नल होने पर ऐप खोलें।")
    }
    public var whichDistrict: String { t("Which district are you in?", "आप किस ज़िले में हैं?") }
    public func districtName(_ id: String) -> String {
        switch id {
        case "puri": return t("Puri", "पुरी")
        case "delhi": return t("Delhi", "दिल्ली")
        default: return id.prefix(1).uppercased() + id.dropFirst()
        }
    }

    // ---- household
    public var householdTitle: String { t("My household", "मेरा परिवार") }
    public var householdHelp: String {
        t("This stays on your phone. Relay only sends yes or no answers, never names or numbers, to make your plan.",
          "यह आपके फ़ोन पर ही रहता है। योजना बनाने के लिए Relay सिर्फ़ हाँ या नहीं के जवाब भेजता है, नाम या नंबर कभी नहीं।")
    }
    public var people: String { t("People in your home", "घर में लोग") }
    public var morePeople: String { t("More people", "एक व्यक्ति और जोड़ें") }
    public var fewerPeople: String { t("Fewer people", "एक व्यक्ति कम करें") }
    public var elderly: String { t("Elderly person", "बुज़ुर्ग") }
    public var children: String { t("Children", "बच्चे") }
    public var infants: String { t("Baby under 1 year", "1 साल से छोटा शिशु") }
    public var pregnant: String { t("Pregnant woman", "गर्भवती महिला") }
    public var disability: String { t("Someone who needs help to walk or move", "कोई जिसे चलने में मदद चाहिए") }
    public var livestock: String { t("Animals to look after", "देखभाल के लिए पशु") }
    public var medical: String { t("Medicine or care needs", "दवा या देखभाल की ज़रूरत") }
    public var medicalHint: String { t("e.g. insulin, oxygen", "जैसे इंसुलिन, ऑक्सीजन") }
    public var saveHousehold: String { t("Save", "सहेजें") }
    public var savedHousehold: String { t("Saved. Your plan will update.", "सहेजा गया। आपकी योजना अपडेट होगी।") }

    // ---- notification
    public func notificationTitle(_ district: String) -> String { t("Cyclone warning for \(district)", "\(district) के लिए चक्रवात की चेतावनी") }
    public var notificationText: String { t("Your plan is ready.", "आपकी योजना तैयार है।") }

    // ---- Settings rows
    public var settingsHousehold: String { t("My household", "मेरा परिवार") }
    public var settingsHouseholdHelp: String { t("Tell Relay who is at home so your evacuation plan fits.", "Relay को बताएँ कि घर में कौन है, ताकि निकलने की योजना सही बने।") }
    public var settingsReady: String { t("Get ready", "तैयारी करें") }

    // ---- shelters
    public var sheltersTitle: String { t("Shelters", "आश्रय") }
    public var sheltersRow: String { t("Shelters", "आश्रय") }
    public var sheltersRowHelp: String { t("Find the nearest safe place", "सबसे पास की सुरक्षित जगह खोजें") }
    public var demoBadge: String { t("Demo", "डेमो") }
    public var noSheltersList: String { t("No verified shelter list yet. Open Relay when you have signal and it will download one.", "अभी कोई सत्यापित आश्रय सूची नहीं है। सिग्नल होने पर Relay खोलें, वह एक डाउनलोड करेगा।") }
    public func capacity(_ n: Int) -> String { t("Holds about \(n) people", "लगभग \(n) लोगों की जगह") }
    public func kindName(_ kind: String) -> String {
        switch kind {
        case "cyclone_shelter": return t("Cyclone shelter", "चक्रवात आश्रय")
        case "school": return t("School", "स्कूल")
        case "relief_camp": return t("Relief camp", "राहत शिविर")
        case "hospital": return t("Hospital", "अस्पताल")
        default: return t("Shelter", "आश्रय")
        }
    }
    public func facilityName(_ f: String) -> String {
        switch f {
        case "water": return t("Water", "पानी")
        case "toilets": return t("Toilets", "शौचालय")
        case "generator": return t("Generator", "जनरेटर")
        case "first_aid": return t("First aid", "प्राथमिक उपचार")
        case "medical": return t("Medical care", "चिकित्सा")
        case "kitchen": return t("Kitchen", "रसोई")
        default: let x = f.replacingOccurrences(of: "_", with: " "); return x.prefix(1).uppercased() + x.dropFirst()
        }
    }
    public var facilities: String { t("Facilities", "सुविधाएँ") }
    public var address: String { t("Address", "पता") }
    public var coordinates: String { t("Coordinates", "निर्देशांक") }
    public var directions: String { t("Directions", "रास्ता") }
    public var copyCoordinates: String { t("Copy coordinates", "निर्देशांक कॉपी करें") }
    public var copied: String { t("Copied", "कॉपी हो गया") }
    public var speakShelter: String { t("Speak", "बोलकर सुनाएँ") }
    public var noMapsApp: String { t("No maps app found on this phone.", "इस फ़ोन में कोई मैप ऐप नहीं मिला।") }
    public var mapNote: String { t("Pins are placed from the shelter coordinates. Open Directions for a full map.", "पिन आश्रय के निर्देशांक से लगाए गए हैं। पूरा नक्शा देखने के लिए रास्ता दबाएँ।") }
    public var shelterNotFound: String { t("This shelter is not in the saved list any more.", "यह आश्रय सहेजी गई सूची में अब नहीं है।") }
    public func mapDescription(_ n: Int) -> String { t("Map with \(n) shelters", "\(n) आश्रयों वाला नक्शा") }

    private var locale: Locale { lang == .hi ? Locale(identifier: "hi_IN") : Locale.autoupdatingCurrent }
    private func clock(_ sec: Int64) -> String {
        let f = DateFormatter(); f.locale = locale; f.timeZone = timeZone; f.dateStyle = .medium; f.timeStyle = .short
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(sec)))
    }
    private func day(_ sec: Int64) -> String {
        let f = DateFormatter(); f.locale = locale; f.timeZone = timeZone; f.dateStyle = .medium; f.timeStyle = .none
        return f.string(from: Date(timeIntervalSince1970: TimeInterval(sec)))
    }
}
