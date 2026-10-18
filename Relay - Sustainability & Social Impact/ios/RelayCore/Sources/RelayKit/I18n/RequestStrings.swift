import Foundation
import RelayCore


/// Strings for Request detail (timeline is in [CommonStrings]). Owned by the Request agent.
public struct RequestStrings: Equatable, Sendable {
    public let lang: Lang
    public init(_ lang: Lang) { self.lang = lang }
    public var title: String { tr(lang, "Request", "अनुरोध", "ଅନୁରୋଧ", "Kahilingan", "Hangyo") } // needs native review

    public var share: String { tr(lang, "Share", "साझा करें", "ଶେୟାର୍ କରନ୍ତୁ", "Ibahagi", "Ibahin") } // needs native review

    /// "R-7F3A · sent 2:02 PM · Ward 4" (area omitted when blank).
    public func referenceLine(_ ref: String, _ sentTime: String, _ area: String) -> String {
        let sent = tr(lang, "sent \(sentTime)", "\(sentTime) पर भेजा", "\(sentTime) ରେ ପଠାଯାଇଛି", "ipinadala \(sentTime)", "gin-ipadara \(sentTime)") // needs native review
        return [ref, sent, area].filter { !$0.trimmed.isEmpty }.joined(separator: " · ")
    }

    public var fromControlRoom: String { tr(lang, "From the control room", "कंट्रोल रूम से", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍ ରୁ", "Mula sa control room", "Tikang ha control room") } // needs native review

    public func hearItIn(_ language: String) -> String { tr(
        lang, "Hear it in \(language)", "\(language) में सुनें", "\(language) ରେ ଶୁଣନ୍ତୁ", "Pakinggan sa \(language)", "Pamati ha \(language)",
    ) } // needs native review

    public var noVoice: String { tr(
        lang, "No voice for this language is installed on this phone.", "इस फ़ोन में इस भाषा की आवाज़ इंस्टॉल नहीं है।",
        "ଏହି ଫୋନରେ ଏହି ଭାଷାର ସ୍ୱର ଇନ୍‌ଷ୍ଟଲ୍ ହୋଇନାହିଁ।", "Walang naka-install na boses para sa wikang ito sa teleponong ito.",
        "Waray naka-install nga tingog para hini nga pagsasaramong hini nga telepono.",
    ) } // needs native review

    public var cancelRequest: String { tr(lang, "Cancel request", "अनुरोध रद्द करें", "ଅନୁରୋଧ ବାତିଲ୍ କରନ୍ତୁ", "Kanselahin ang kahilingan", "Kanselaha an hangyo") } // needs native review
    public var addToRequest: String { tr(lang, "+ Add to request", "+ अनुरोध में जोड़ें", "+ ଅନୁରୋଧରେ ଯୋଡ଼ନ୍ତୁ", "+ Magdagdag sa kahilingan", "+ Dugangi an hangyo") } // needs native review
    /// iPhone half-width action capsules on Request detail ("+ Add" and "× Cancel").
    public var addShort: String { tr(lang, "+ Add", "+ जोड़ें", "+ ଯୋଡ଼ନ୍ତୁ", "+ Magdagdag", "+ Dugangi") } // needs native review
    public var cancelShort: String { tr(lang, "× Cancel", "× रद्द करें", "× ବାତିଲ୍", "× Kanselahin", "× Kanselaha") } // needs native review

    public var cancelDialogTitle: String { tr(lang, "Stop sharing this request?", "इस अनुरोध को साझा करना बंद करें?", "ଏହି ଅନୁରୋଧ ସେୟାର୍ କରିବା ବନ୍ଦ କରିବେ?", "Ihinto ang pagbabahagi ng kahilingang ito?", "Hunongon an pagbahin hini nga hangyo?") } // needs native review

    public var cancelDialogBody: String { tr(
        lang,
        "Phones nearby will stop passing it on from this phone. If the control room already has it, they may still come.",
        "आस-पास के फ़ोन इस फ़ोन से इसे आगे नहीं बढ़ाएँगे। अगर कंट्रोल रूम के पास यह पहले से है, तो वे फिर भी आ सकते हैं।",
        "ପାଖ ଫୋନ୍‌ଗୁଡ଼ିକ ଏହି ଫୋନରୁ ଏହାକୁ ଆଗକୁ ପଠାଇବା ବନ୍ଦ କରିବେ। କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍ ପାଖରେ ଆଗରୁ ଥିଲେ, ସେମାନେ ତଥାପି ଆସିପାରନ୍ତି।",
        "Titigil ang mga kalapit na telepono sa pagpasa nito mula sa teleponong ito. Kung hawak na ito ng control room, maaari pa rin silang dumating.",
        "Hihinunong han kalapit nga mga telepono an pagpasa hini tikang hini nga telepono. Kon hinuuna na ito han control room, mahimo gihapon sira umabot.",
    ) } // needs native review

    public var cancelConfirm: String { tr(lang, "Stop sharing", "साझा करना बंद करें", "ସେୟାର୍ ବନ୍ଦ କରନ୍ତୁ", "Ihinto", "Hunong") } // needs native review
    public var cancelKeep: String { tr(lang, "Keep sharing", "साझा करते रहें", "ସେୟାର୍ ଜାରି ରଖନ୍ତୁ", "Ituloy ang pagbabahagi", "Padayon pagbahin") } // needs native review

    public var gone: String { tr(
        lang, "This request is no longer on this phone.", "यह अनुरोध अब इस फ़ोन में नहीं है।", "ଏହି ଅନୁରୋଧ ଆଉ ଏହି ଫୋନରେ ନାହିଁ।",
        "Wala na ang kahilingang ito sa teleponong ito.", "Waray na ini nga hangyo hini nga telepono.",
    ) } // needs native review

    // ---- share text
    public func shareHeader(_ ref: String) -> String { tr(lang, "Relay request \(ref)", "Relay अनुरोध \(ref)", "Relay ଅନୁରୋଧ \(ref)", "Kahilingan sa Relay \(ref)", "Hangyo ha Relay \(ref)") } // needs native review
    public func shareStatus(_ status: String) -> String { tr(lang, "Status: \(status)", "स्थिति: \(status)", "ସ୍ଥିତି: \(status)", "Katayuan: \(status)", "Kahimtang: \(status)") } // needs native review
    public func shareNear(_ landmark: String) -> String { tr(lang, "Near \(landmark)", "\(landmark) के पास", "\(landmark) ପାଖରେ", "Malapit sa \(landmark)", "Ha kadul han \(landmark)") } // needs native review
    public func shareMessage(_ text: String) -> String { tr(lang, "Control room: \(text)", "कंट्रोल रूम: \(text)", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍: \(text)", "Control room: \(text)", "Control room: \(text)") } // needs native review
    public var shareChooser: String { tr(lang, "Share request", "अनुरोध साझा करें", "ଅନୁରୋଧ ଶେୟାର୍ କରନ୍ତୁ", "Ibahagi ang kahilingan", "Ibahin an hangyo") } // needs native review
}

