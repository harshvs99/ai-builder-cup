import Foundation
import RelayCore


/// Strings for the Network tab and Settings. Owned by the Network/Settings agent. English is the source text
/// (design spec sections 7 and 8); hi, or, tl and war are drafts for native review.
public struct NetworkSettingsStrings: Equatable, Sendable {
    public let lang: Lang
    public let timeZone: TimeZone
    public init(_ lang: Lang, timeZone: TimeZone) { self.lang = lang; self.timeZone = timeZone }
    private var common: CommonStrings { CommonStrings(lang, timeZone: timeZone) }

    // ---------------------------------------------------------------- Network
    public var settingsTitle: String { tr(lang, "Settings", "सेटिंग्स", "ସେଟିଂସ", "Settings", "Settings") } // needs native review
    public var networkTitle: String { common.navNetwork }

    public var meshWord: String { tr(lang, "Bluetooth mesh", "ब्लूटूथ मेश", "ବ୍ଲୁଟୁଥ୍ ମେଶ୍", "Bluetooth mesh", "Bluetooth mesh") } // needs native review
    public var consoleDescription: String { tr(
        lang, "Developer console", "डेवलपर कंसोल", "ଡେଭଲପର୍ କନସୋଲ୍", "Developer console", "Developer console",
    ) } // needs native review

    /// The sentence under the status title on the Network card.
    public func statusSentence(_ s: VillagerStatus) -> String {
        let never = tr(
            lang, "Relay never shows who owns a phone.", "Relay कभी नहीं दिखाता कि फ़ोन किसका है।",
            "Relay କେବେ ଦେଖାଏ ନାହିଁ ଫୋନ୍ କାହାର।", "Hindi ipinapakita ng Relay kung kanino ang telepono.",
            "Diri pinakita han Relay kun kanay-o an telepono.",
        ) // needs native review
        switch s.kind {
        case .reachable:
            let h = common.hops(s.hopsToGateway ?? 1)
            return tr(
                lang, "The nearest gateway phone is \(h) away. ", "सबसे पास का गेटवे फ़ोन \(h) दूर है। ",
                "ସବୁଠାରୁ ପାଖ ଗେଟୱେ ଫୋନ୍ \(h) ଦୂରରେ ଅଛି। ", "Ang pinakamalapit na gateway na telepono ay \(h) ang layo. ",
                "An pinakaharani nga gateway nga telepono \(h) an kalayo. ",
            ) + never // needs native review
        case .thisPhoneGateway:
            return tr(
                lang, "This phone has internet and is passing messages on to the control room. ",
                "इस फ़ोन में इंटरनेट है और यह संदेश कंट्रोल रूम तक पहुँचा रहा है। ",
                "ଏହି ଫୋନରେ ଇଣ୍ଟରନେଟ୍ ଅଛି ଏବଂ ଏହା ସନ୍ଦେଶ କଣ୍ଟ୍ରୋଲ୍ ରୁମକୁ ପଠାଉଛି। ",
                "May internet ang teleponong ito at ipinapasa nito ang mga mensahe sa control room. ",
                "May internet ini nga telepono ngan ginpapasa niya an mga mensahe ngadto ha control room. ",
            ) + never // needs native review
        case .waitingForGateway:
            return tr(
                lang, "Phones are nearby but none has signal yet. Your messages wait safely and move on when one does.",
                "आस-पास फ़ोन हैं पर अभी किसी में सिग्नल नहीं है। आपके संदेश सुरक्षित रखे हैं और सिग्नल मिलते ही आगे जाएँगे।",
                "ପାଖରେ ଫୋନ୍ ଅଛି କିନ୍ତୁ ଏବେ କାହାରିର ସିଗ୍ନାଲ୍ ନାହିଁ। ଆପଣଙ୍କ ସନ୍ଦେଶ ସୁରକ୍ଷିତ ଅଛି ଏବଂ ସିଗ୍ନାଲ୍ ମିଳିଲେ ଆଗକୁ ଯିବ।",
                "May mga telepono sa malapit pero wala pang signal. Ligtas na naghihintay ang mga mensahe mo at magpapatuloy kapag may signal.",
                "May mga telepono nga harani pero waray pa signal. Ligtas nga naghuhulat an imo mga mensahe ngan magpapadayon kun may signal.",
            ) // needs native review
        case .searching:
            return tr(
                lang, "No phones in range yet. Keep Relay open and messages will move on when a phone comes near.",
                "अभी पहुँच में कोई फ़ोन नहीं है। Relay खुला रखें, कोई फ़ोन पास आते ही संदेश आगे जाएँगे।",
                "ଏବେ ପାଖରେ କୌଣସି ଫୋନ୍ ନାହିଁ। Relay ଖୋଲା ରଖନ୍ତୁ, ଫୋନ୍ ପାଖକୁ ଆସିଲେ ସନ୍ଦେଶ ଆଗକୁ ଯିବ।",
                "Wala pang telepono sa saklaw. Panatilihing bukas ang Relay at magpapatuloy ang mga mensahe kapag may lumapit na telepono.",
                "Waray pa telepono nga harani. Pabuksi an Relay ngan magpapadayon an mga mensahe kun may mag-abot nga telepono.",
            ) // needs native review
        case .bluetoothOff:
            return tr(
                lang, "Bluetooth is off, so this phone cannot pass messages. Turn it on to join the mesh.",
                "ब्लूटूथ बंद है, इसलिए यह फ़ोन संदेश आगे नहीं भेज सकता। नेटवर्क से जुड़ने के लिए इसे चालू करें।",
                "ବ୍ଲୁଟୁଥ୍ ବନ୍ଦ ଅଛି, ତେଣୁ ଏହି ଫୋନ୍ ସନ୍ଦେଶ ପଠାଇପାରିବ ନାହିଁ। ନେଟୱର୍କରେ ଯୋଗ ଦେବାକୁ ଏହାକୁ ଚାଲୁ କରନ୍ତୁ।",
                "Naka-off ang Bluetooth kaya hindi makapagpasa ng mensahe ang teleponong ito. I-on ito para sumali sa mesh.",
                "Gin-off an Bluetooth salit diri makapasa hin mensahe ini nga telepono. Buksi ini basi makasulod ha mesh.",
            ) // needs native review
        }
    }

    // chain diagram
    public var chainYou: String { tr(lang, "You", "आप", "ଆପଣ", "Ikaw", "Ikaw") } // needs native review
    public var chainThisPhone: String { tr(lang, "This phone", "यह फ़ोन", "ଏହି ଫୋନ୍", "Ang teleponong ito", "Ini nga telepono") } // needs native review
    public var chainGatewayItself: String { tr(
        lang, "Gateway · this phone", "गेटवे · यह फ़ोन", "ଗେଟୱେ · ଏହି ଫୋନ୍", "Gateway · ang teleponong ito", "Gateway · ini nga telepono",
    ) } // needs native review
    public func chainHop(_ hops: Int) -> String { common.hops(hops) }
    public func chainGateway(_ hops: Int) -> String { tr(
        lang, "Gateway · \(common.hops(hops))", "गेटवे · \(common.hops(hops))", "ଗେଟୱେ · \(common.hops(hops))",
        "Gateway · \(common.hops(hops))", "Gateway · \(common.hops(hops))",
    ) } // needs native review
    public var chainUnknown: String { tr(
        lang, "No gateway known yet", "अभी कोई गेटवे नहीं मिला", "ଏପର୍ଯ୍ୟନ୍ତ କୌଣସି ଗେଟୱେ ମିଳିଲା ନାହିଁ", "Wala pang kilalang gateway", "Waray pa gateway nga kilala",
    ) } // needs native review
    public func chainDescription(_ parts: [String]) -> String { tr(
        lang, "Path to help: " + parts.joined(separator: ", then "), "मदद तक का रास्ता: " + parts.joined(separator: ", फिर "),
        "ସାହାଯ୍ୟ ପର୍ଯ୍ୟନ୍ତ ରାସ୍ତା: " + parts.joined(separator: ", ତା'ପରେ "), "Daan patungo sa tulong: " + parts.joined(separator: ", pagkatapos "),
        "Dalan paadto ha bulig: " + parts.joined(separator: ", dayon "),
    ) } // needs native review

    // stat tiles
    public var phonesNearby: String { tr(lang, "Phones nearby", "आस-पास के फ़ोन", "ପାଖ ଫୋନ୍", "Mga telepono sa malapit", "Mga telepono nga harani") } // needs native review
    public var carriedForNeighbours: String { tr(
        lang, "Carried for neighbours", "पड़ोसियों के लिए ढोए", "ପଡ଼ୋଶୀଙ୍କ ପାଇଁ ବହନ", "Dala para sa mga kapitbahay", "Gindara para ha mga kasikbit",
    ) } // needs native review
    public var waitingToPassOn: String { tr(
        lang, "Waiting to pass on", "आगे भेजने की प्रतीक्षा में", "ଆଗକୁ ପଠାଇବାକୁ ଅପେକ୍ଷାରେ", "Naghihintay maipasa", "Naghuhulat pa maipasa",
    ) } // needs native review
    public var meshHoursLeft: String { tr(lang, "Mesh hours left", "मेश के घंटे बाकी", "ମେଶ୍ ଘଣ୍ଟା ବାକି", "Oras ng mesh na natitira", "Oras han mesh nga nahibilin") } // needs native review
    public func hoursValue(_ hours: Int) -> String { tr(lang, "about \(hours) h", "लगभग \(hours) घं", "ପ୍ରାୟ \(hours) ଘଂ", "mga \(hours) oras", "mga \(hours) oras") } // needs native review
    public var hoursUnder1: String { tr(lang, "< 1 h", "< 1 घं", "< 1 ଘଂ", "< 1 oras", "< 1 oras") } // needs native review
    public var charging: String { tr(lang, "Charging", "चार्ज हो रहा है", "ଚାର୍ଜ ହେଉଛି", "Nagcha-charge", "Nagcha-charge") } // needs native review
    public var batteryLabel: String { tr(lang, "Battery", "बैटरी", "ବ୍ୟାଟେରୀ", "Baterya", "Baterya") } // needs native review
    public var unknownValue: String { "—" }

    // toggles
    public var gatewayToggle: String { tr(
        lang, "Act as a gateway when online", "ऑनलाइन होने पर गेटवे बनें", "ଅନଲାଇନ୍ ଥିଲେ ଗେଟୱେ ହୁଅନ୍ତୁ",
        "Maging gateway kapag online", "Mag-gateway kun online",
    ) } // needs native review
    public var gatewayToggleHelp: String { tr(
        lang, "Uploads everyone's requests the moment this phone gets signal.",
        "सिग्नल मिलते ही सबके अनुरोध अपलोड करता है।", "ସିଗ୍ନାଲ୍ ମିଳିଲେ ସମସ୍ତଙ୍କ ଅନୁରୋଧ ଅପଲୋଡ୍ କରେ।",
        "Ina-upload ang mga kahilingan ng lahat kapag may signal ang telepono.", "Ginau-upload an mga hangyo han ngatanan kun may signal an telepono.",
    ) } // needs native review
    public var carryToggle: String { tr(
        lang, "Carry messages in the background", "बैकग्राउंड में संदेश ढोएँ", "ବ୍ୟାକଗ୍ରାଉଣ୍ଡରେ ସନ୍ଦେଶ ବହନ କରନ୍ତୁ",
        "Magdala ng mensahe sa background", "Magdara hin mensahe ha background",
    ) } // needs native review
    public var carryToggleHelp: String { tr(
        lang, "Bluetooth only. Uses some battery.", "सिर्फ़ ब्लूटूथ। कुछ बैटरी लगती है।",
        "କେବଳ ବ୍ଲୁଟୁଥ୍। କିଛି ବ୍ୟାଟେରୀ ଲାଗେ।", "Bluetooth lang. Gumagamit ng kaunting baterya.", "Bluetooth la. Gumagamit hin gutiay nga baterya.",
    ) } // needs native review

    // ---------------------------------------------------------------- Settings: groups
    public var groupLanguageVoice: String { tr(lang, "Language and voice", "भाषा और आवाज़", "ଭାଷା ଓ ସ୍ୱର", "Wika at boses", "Pagsulti ngan tingog") } // needs native review
    public var groupScreenBattery: String { tr(lang, "Screen and battery", "स्क्रीन और बैटरी", "ସ୍କ୍ରିନ୍ ଓ ବ୍ୟାଟେରୀ", "Screen at baterya", "Screen ngan baterya") } // needs native review
    public var groupNetworkPermissions: String { tr(lang, "Network and permissions", "नेटवर्क और अनुमतियाँ", "ନେଟୱର୍କ ଓ ଅନୁମତି", "Network at mga pahintulot", "Network ngan mga pasubay") } // needs native review
    public var groupBeforeDisaster: String { tr(lang, "Before a disaster", "आपदा से पहले", "ବିପର୍ଯ୍ୟୟ ପୂର୍ବରୁ", "Bago ang sakuna", "Antes han katalagman") } // needs native review

    // language and voice
    public var language: String { tr(lang, "Language", "भाषा", "ଭାଷା", "Wika", "Pagsulti") } // needs native review
    public var chooseLanguage: String { tr(lang, "Choose your language", "अपनी भाषा चुनें", "ଆପଣଙ୍କ ଭାଷା ବାଛନ୍ତୁ", "Piliin ang wika mo", "Pilia an imo pagsulti") } // needs native review
    public var readAloud: String { tr(lang, "Read messages aloud", "संदेश बोलकर सुनाएँ", "ସନ୍ଦେଶ ଜୋରରେ ପଢ଼ନ୍ତୁ", "Basahin nang malakas ang mga mensahe", "Basaha nin kusog an mga mensahe") } // needs native review
    public var voiceSpeed: String { tr(lang, "Voice speed", "आवाज़ की गति", "ସ୍ୱରର ଗତି", "Bilis ng boses", "Kadagsa han tingog") } // needs native review
    public var speedSlow: String { tr(lang, "Slow", "धीमी", "ଧୀର", "Mabagal", "Hinay") } // needs native review
    public var speedNormal: String { tr(lang, "Normal", "सामान्य", "ସାଧାରଣ", "Normal", "Normal") } // needs native review

    // screen and battery
    public var theme: String { tr(lang, "Theme", "थीम", "ଥିମ୍", "Tema", "Tema") } // needs native review
    public var themeLight: String { tr(lang, "Light", "हल्की", "ହାଲୁକା", "Maliwanag", "Hayag") } // needs native review
    public var themeDark: String { tr(lang, "Dark", "गहरी", "ଗାଢ଼", "Madilim", "Maitom") } // needs native review
    public var themeAuto: String { tr(lang, "Auto", "ऑटो", "ଅଟୋ", "Auto", "Auto") } // needs native review
    public var largerText: String { tr(lang, "Larger text", "बड़े अक्षर", "ବଡ଼ ଅକ୍ଷର", "Mas malaking teksto", "Mas dako nga teksto") } // needs native review
    public var darkNote: String { tr(
        lang, "Dark appearance is used at night by default. It is easier on the eyes in a shelter and saves battery.",
        "रात में डिफ़ॉल्ट रूप से गहरी थीम चलती है। यह शेल्टर में आँखों को आराम देती है और बैटरी बचाती है।",
        "ରାତିରେ ଡିଫଲ୍ଟ ଭାବରେ ଗାଢ଼ ଥିମ୍ ବ୍ୟବହାର ହୁଏ। ଏହା ଆଶ୍ରୟସ୍ଥଳରେ ଆଖିକୁ ଆରାମ ଦିଏ ଓ ବ୍ୟାଟେରୀ ବଞ୍ଚାଏ।",
        "Madilim na tema ang gamit sa gabi bilang default. Mas magaan ito sa mata sa kanlungan at nakatitipid ng baterya.",
        "Maitom nga tema an gamit ha gabi. Mas gutiay an sakit ha mata ha kanlungan ngan nakakaipon hin baterya.",
    ) } // needs native review
    public var yourArea: String { tr(lang, "Your area", "आपका इलाका", "ଆପଣଙ୍କ ଅଞ୍ଚଳ", "Iyong lugar", "Imo lugar") } // needs native review
    public var areaNotSet: String { tr(lang, "Not set", "सेट नहीं है", "ସେଟ୍ ହୋଇନାହିଁ", "Hindi pa naka-set", "Waray pa gintakda") } // needs native review
    public var areaHelp: String { tr(
        lang, "Optional. Shown on Home, for example Ward 4, Puri district.",
        "वैकल्पिक। होम पर दिखता है, जैसे वार्ड 4, पुरी ज़िला।", "ଇଚ୍ଛାଧୀନ। ହୋମରେ ଦେଖାଯାଏ, ଯେପରି ୱାର୍ଡ 4, ପୁରୀ ଜିଲ୍ଲା।",
        "Opsyonal. Ipinapakita sa Home, halimbawa Ward 4, distrito ng Puri.", "Opsyonal. Ginpapakita ha Home, pananglitan Ward 4, distrito han Puri.",
    ) } // needs native review
    public var save: String { tr(lang, "Save", "सहेजें", "ସେଭ୍ କରନ୍ତୁ", "I-save", "Tipigi") } // needs native review

    // network and permissions
    public var gatewayMode: String { tr(lang, "Gateway mode", "गेटवे मोड", "ଗେଟୱେ ମୋଡ୍", "Gateway mode", "Gateway mode") } // needs native review
    public var gatewayAutomatic: String { tr(lang, "Automatic", "अपने-आप", "ସ୍ୱୟଂଚାଳିତ", "Awtomatiko", "Awtomatiko") } // needs native review
    public var gatewayNever: String { tr(lang, "Never", "कभी नहीं", "କେବେ ନୁହେଁ", "Hindi kailanman", "Diri gud") } // needs native review
    public var gatewayAutomaticHelp: String { tr(
        lang, "Whenever this phone has internet it uploads requests for the mesh.",
        "जब भी इस फ़ोन में इंटरनेट हो, यह मेश के अनुरोध अपलोड करता है।", "ଯେତେବେଳେ ଏହି ଫୋନରେ ଇଣ୍ଟରନେଟ୍ ଥାଏ, ଏହା ମେଶ୍ ପାଇଁ ଅନୁରୋଧ ଅପଲୋଡ୍ କରେ।",
        "Kapag may internet ang telepono, ina-upload nito ang mga kahilingan ng mesh.", "Kun may internet an telepono, ginau-upload niya an mga hangyo han mesh.",
    ) } // needs native review
    public var gatewayNeverHelp: String { tr(
        lang, "This phone never uploads. It still passes messages phone to phone.",
        "यह फ़ोन कभी अपलोड नहीं करता। फिर भी यह फ़ोन से फ़ोन संदेश पहुँचाता है।", "ଏହି ଫୋନ୍ କେବେ ଅପଲୋଡ୍ କରେ ନାହିଁ। ତଥାପି ଏହା ଫୋନ୍ ଠାରୁ ଫୋନ୍ କୁ ସନ୍ଦେଶ ପଠାଏ।",
        "Hindi nag-a-upload ang teleponong ito. Nagpapasa pa rin ito ng mensahe sa ibang telepono.", "Diri nag-a-upload ini nga telepono. Nagpapasa gihapon hin mensahe ha iba nga telepono.",
    ) } // needs native review
    public var permBluetooth: String { tr(lang, "Bluetooth", "ब्लूटूथ", "ବ୍ଲୁଟୁଥ୍", "Bluetooth", "Bluetooth") } // needs native review
    public var permLocation: String { tr(lang, "Location", "लोकेशन", "ଲୋକେସନ୍", "Lokasyon", "Lokasyon") } // needs native review
    public var permMicrophone: String { tr(lang, "Microphone", "माइक्रोफ़ोन", "ମାଇକ୍ରୋଫୋନ୍", "Mikropono", "Mikropono") } // needs native review
    public var permAllowed: String { tr(lang, "Allowed", "अनुमति है", "ଅନୁମତି ଅଛି", "Pinapayagan", "Gintutugot") } // needs native review
    public var permAsk: String { tr(lang, "Ask each time", "हर बार पूछें", "ପ୍ରତିଥର ପଚାରନ୍ତୁ", "Magtanong tuwing", "Pagpakiana kada beses") } // needs native review
    public var permNotAllowed: String { tr(lang, "Not allowed", "अनुमति नहीं", "ଅନୁମତି ନାହିଁ", "Hindi pinapayagan", "Diri gintutugot") } // needs native review
    public var permOpensSettings: String { tr(lang, "Opens system settings", "सिस्टम सेटिंग्स खुलती हैं", "ସିଷ୍ଟମ୍ ସେଟିଂସ ଖୋଲେ", "Bubuksan ang system settings", "Bubuksan an system settings") } // needs native review

    // before a disaster
    public var householdPlan: String { tr(lang, "Household plan", "घर की योजना", "ପରିବାର ଯୋଜନା", "Plano ng sambahayan", "Plano han panimalay") } // needs native review
    public var householdPlanHelp: String { tr(
        lang, "Shelter, checklist and a spoken plan, saved offline", "शेल्टर, चेकलिस्ट और बोली जाने वाली योजना, ऑफ़लाइन सहेजी",
        "ଆଶ୍ରୟ, ଚେକ୍ ଲିଷ୍ଟ ଓ କଥିତ ଯୋଜନା, ଅଫଲାଇନ୍ ସେଭ୍", "Kanlungan, checklist at planong naririnig, naka-save offline", "Kanlungan, checklist ngan plano nga naririnig, naka-save offline",
    ) } // needs native review
    public var setUp: String { tr(lang, "Set up", "शुरू करें", "ସେଟ୍ ଅପ୍", "I-set up", "Ayusa") } // needs native review
    public var comingSoonTitle: String { tr(lang, "Coming soon", "जल्द आ रहा है", "ଶୀଘ୍ର ଆସୁଛି", "Parating na", "Mag-abot na") } // needs native review
    public var comingSoonBody: String { tr(
        lang, "The household plan is not ready yet. For now, Relay passes your requests to help.",
        "घर की योजना अभी तैयार नहीं है। अभी Relay आपके अनुरोध मदद तक पहुँचाता है।",
        "ପରିବାର ଯୋଜନା ଏପର୍ଯ୍ୟନ୍ତ ପ୍ରସ୍ତୁତ ନୁହେଁ। ବର୍ତ୍ତମାନ Relay ଆପଣଙ୍କ ଅନୁରୋଧ ସାହାଯ୍ୟକୁ ପଠାଏ।",
        "Hindi pa handa ang plano ng sambahayan. Sa ngayon, ipinapasa ng Relay ang mga kahilingan mo sa tulong.",
        "Diri pa andam an plano han panimalay. Subong, ginpapasa han Relay an imo mga hangyo ngadto ha bulig.",
    ) } // needs native review

    // ---- iPhone copy (docs/design/ios-build-plan.md). Android's `carryToggleHelp`, `permAsk` etc. stay for parity.
    /// Help line under "Carry messages in the background" on iPhone: honest about the background limits.
    public var carryToggleHelpIOS: String { tr(
        lang, "Bluetooth only. Slower while the app is closed.", "सिर्फ़ ब्लूटूथ। ऐप बंद होने पर धीमा चलता है।",
        "କେବଳ ବ୍ଲୁଟୁଥ୍। ଆପ୍ ବନ୍ଦ ଥିଲେ ଧୀର ଚାଲେ।", "Bluetooth lang. Mas mabagal kapag sarado ang app.", "Bluetooth la. Mas hinay kon sarado an app.",
    ) } // needs native review
    /// A plain note (Network and Settings) that an iPhone carries messages best with Relay open.
    public var iPhoneBestOpenNote: String { tr(
        lang, "An iPhone carries messages best with Relay open.", "iPhone संदेश तब सबसे अच्छी तरह पहुँचाता है जब Relay खुला हो।",
        "Relay ଖୋଲା ଥିଲେ iPhone ସନ୍ଦେଶ ସବୁଠାରୁ ଭଲ ଭାବେ ବହନ କରେ।", "Pinakamahusay magdala ng mensahe ang iPhone kapag bukas ang Relay.",
        "Pinakamaupay magdara hin mensahe an iPhone kon bukas an Relay.",
    ) } // needs native review
    /// Location row value on iPhone when the permission is "While Using the App".
    public var permWhileUsing: String { tr(lang, "While using", "इस्तेमाल के दौरान", "ବ୍ୟବହାର ସମୟରେ", "Habang ginagamit", "Samtang ginagamit") } // needs native review
    /// Short status for the "Set up" household-plan button's result: the same words as `comingSoonTitle`.
    public var comingSoon: String { comingSoonTitle }

    public var developer: String { tr(lang, "Developer", "डेवलपर", "ଡେଭଲପର୍", "Developer", "Developer") } // needs native review
}

