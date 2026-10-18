package org.relay.app.ui.i18n

import org.relay.app.VillagerStatus
import org.relay.core.payload.Lang

/**
 * Strings for the Network tab and Settings. Owned by the Network/Settings agent. English is the source text
 * (design spec sections 7 and 8); hi, or, tl and war are drafts for native review.
 */
class NetworkSettingsStrings(private val lang: Lang) {
    private val common = CommonStrings(lang)

    // ---------------------------------------------------------------- Network
    val settingsTitle get() = tr(lang, "Settings", "सेटिंग्स", "ସେଟିଂସ", "Settings", "Settings") // needs native review
    val networkTitle get() = common.navNetwork

    val meshWord get() = tr(lang, "Bluetooth mesh", "ब्लूटूथ मेश", "ବ୍ଲୁଟୁଥ୍ ମେଶ୍", "Bluetooth mesh", "Bluetooth mesh") // needs native review
    val consoleDescription get() = tr(
        lang, "Developer console", "डेवलपर कंसोल", "ଡେଭଲପର୍ କନସୋଲ୍", "Developer console", "Developer console",
    ) // needs native review

    /** The sentence under the status title on the Network card. */
    fun statusSentence(s: VillagerStatus): String {
        val never = tr(
            lang, "Relay never shows who owns a phone.", "Relay कभी नहीं दिखाता कि फ़ोन किसका है।",
            "Relay କେବେ ଦେଖାଏ ନାହିଁ ଫୋନ୍ କାହାର।", "Hindi ipinapakita ng Relay kung kanino ang telepono.",
            "Diri pinakita han Relay kun kanay-o an telepono.",
        ) // needs native review
        return when (s.kind) {
            VillagerStatus.Kind.REACHABLE -> {
                val h = common.hops(s.hopsToGateway ?: 1)
                tr(
                    lang, "The nearest gateway phone is $h away. ", "सबसे पास का गेटवे फ़ोन $h दूर है। ",
                    "ସବୁଠାରୁ ପାଖ ଗେଟୱେ ଫୋନ୍ $h ଦୂରରେ ଅଛି। ", "Ang pinakamalapit na gateway na telepono ay $h ang layo. ",
                    "An pinakaharani nga gateway nga telepono $h an kalayo. ",
                ) + never // needs native review
            }
            VillagerStatus.Kind.THIS_PHONE_GATEWAY -> tr(
                lang, "This phone has internet and is passing messages on to the control room. ",
                "इस फ़ोन में इंटरनेट है और यह संदेश कंट्रोल रूम तक पहुँचा रहा है। ",
                "ଏହି ଫୋନରେ ଇଣ୍ଟରନେଟ୍ ଅଛି ଏବଂ ଏହା ସନ୍ଦେଶ କଣ୍ଟ୍ରୋଲ୍ ରୁମକୁ ପଠାଉଛି। ",
                "May internet ang teleponong ito at ipinapasa nito ang mga mensahe sa control room. ",
                "May internet ini nga telepono ngan ginpapasa niya an mga mensahe ngadto ha control room. ",
            ) + never // needs native review
            VillagerStatus.Kind.WAITING_FOR_GATEWAY -> tr(
                lang, "Phones are nearby but none has signal yet. Your messages wait safely and move on when one does.",
                "आस-पास फ़ोन हैं पर अभी किसी में सिग्नल नहीं है। आपके संदेश सुरक्षित रखे हैं और सिग्नल मिलते ही आगे जाएँगे।",
                "ପାଖରେ ଫୋନ୍ ଅଛି କିନ୍ତୁ ଏବେ କାହାରିର ସିଗ୍ନାଲ୍ ନାହିଁ। ଆପଣଙ୍କ ସନ୍ଦେଶ ସୁରକ୍ଷିତ ଅଛି ଏବଂ ସିଗ୍ନାଲ୍ ମିଳିଲେ ଆଗକୁ ଯିବ।",
                "May mga telepono sa malapit pero wala pang signal. Ligtas na naghihintay ang mga mensahe mo at magpapatuloy kapag may signal.",
                "May mga telepono nga harani pero waray pa signal. Ligtas nga naghuhulat an imo mga mensahe ngan magpapadayon kun may signal.",
            ) // needs native review
            VillagerStatus.Kind.SEARCHING -> tr(
                lang, "No phones in range yet. Keep Relay open and messages will move on when a phone comes near.",
                "अभी पहुँच में कोई फ़ोन नहीं है। Relay खुला रखें, कोई फ़ोन पास आते ही संदेश आगे जाएँगे।",
                "ଏବେ ପାଖରେ କୌଣସି ଫୋନ୍ ନାହିଁ। Relay ଖୋଲା ରଖନ୍ତୁ, ଫୋନ୍ ପାଖକୁ ଆସିଲେ ସନ୍ଦେଶ ଆଗକୁ ଯିବ।",
                "Wala pang telepono sa saklaw. Panatilihing bukas ang Relay at magpapatuloy ang mga mensahe kapag may lumapit na telepono.",
                "Waray pa telepono nga harani. Pabuksi an Relay ngan magpapadayon an mga mensahe kun may mag-abot nga telepono.",
            ) // needs native review
            VillagerStatus.Kind.BLUETOOTH_OFF -> tr(
                lang, "Bluetooth is off, so this phone cannot pass messages. Turn it on to join the mesh.",
                "ब्लूटूथ बंद है, इसलिए यह फ़ोन संदेश आगे नहीं भेज सकता। नेटवर्क से जुड़ने के लिए इसे चालू करें।",
                "ବ୍ଲୁଟୁଥ୍ ବନ୍ଦ ଅଛି, ତେଣୁ ଏହି ଫୋନ୍ ସନ୍ଦେଶ ପଠାଇପାରିବ ନାହିଁ। ନେଟୱର୍କରେ ଯୋଗ ଦେବାକୁ ଏହାକୁ ଚାଲୁ କରନ୍ତୁ।",
                "Naka-off ang Bluetooth kaya hindi makapagpasa ng mensahe ang teleponong ito. I-on ito para sumali sa mesh.",
                "Gin-off an Bluetooth salit diri makapasa hin mensahe ini nga telepono. Buksi ini basi makasulod ha mesh.",
            ) // needs native review
        }
    }

    // chain diagram
    val chainYou get() = tr(lang, "You", "आप", "ଆପଣ", "Ikaw", "Ikaw") // needs native review
    val chainThisPhone get() = tr(lang, "This phone", "यह फ़ोन", "ଏହି ଫୋନ୍", "Ang teleponong ito", "Ini nga telepono") // needs native review
    val chainGatewayItself get() = tr(
        lang, "Gateway · this phone", "गेटवे · यह फ़ोन", "ଗେଟୱେ · ଏହି ଫୋନ୍", "Gateway · ang teleponong ito", "Gateway · ini nga telepono",
    ) // needs native review
    fun chainHop(hops: Int): String = common.hops(hops)
    fun chainGateway(hops: Int): String = tr(
        lang, "Gateway · ${common.hops(hops)}", "गेटवे · ${common.hops(hops)}", "ଗେଟୱେ · ${common.hops(hops)}",
        "Gateway · ${common.hops(hops)}", "Gateway · ${common.hops(hops)}",
    ) // needs native review
    val chainUnknown get() = tr(
        lang, "No gateway known yet", "अभी कोई गेटवे नहीं मिला", "ଏପର୍ଯ୍ୟନ୍ତ କୌଣସି ଗେଟୱେ ମିଳିଲା ନାହିଁ", "Wala pang kilalang gateway", "Waray pa gateway nga kilala",
    ) // needs native review
    fun chainDescription(parts: List<String>): String = tr(
        lang, "Path to help: " + parts.joinToString(", then "), "मदद तक का रास्ता: " + parts.joinToString(", फिर "),
        "ସାହାଯ୍ୟ ପର୍ଯ୍ୟନ୍ତ ରାସ୍ତା: " + parts.joinToString(", ତା'ପରେ "), "Daan patungo sa tulong: " + parts.joinToString(", pagkatapos "),
        "Dalan paadto ha bulig: " + parts.joinToString(", dayon "),
    ) // needs native review

    // stat tiles
    val phonesNearby get() = tr(lang, "Phones nearby", "आस-पास के फ़ोन", "ପାଖ ଫୋନ୍", "Mga telepono sa malapit", "Mga telepono nga harani") // needs native review
    val carriedForNeighbours get() = tr(
        lang, "Carried for neighbours", "पड़ोसियों के लिए ढोए", "ପଡ଼ୋଶୀଙ୍କ ପାଇଁ ବହନ", "Dala para sa mga kapitbahay", "Gindara para ha mga kasikbit",
    ) // needs native review
    val waitingToPassOn get() = tr(
        lang, "Waiting to pass on", "आगे भेजने की प्रतीक्षा में", "ଆଗକୁ ପଠାଇବାକୁ ଅପେକ୍ଷାରେ", "Naghihintay maipasa", "Naghuhulat pa maipasa",
    ) // needs native review
    val meshHoursLeft get() = tr(lang, "Mesh hours left", "मेश के घंटे बाकी", "ମେଶ୍ ଘଣ୍ଟା ବାକି", "Oras ng mesh na natitira", "Oras han mesh nga nahibilin") // needs native review
    fun hoursValue(hours: Int): String = tr(lang, "about $hours h", "लगभग $hours घं", "ପ୍ରାୟ $hours ଘଂ", "mga $hours oras", "mga $hours oras") // needs native review
    val hoursUnder1 get() = tr(lang, "< 1 h", "< 1 घं", "< 1 ଘଂ", "< 1 oras", "< 1 oras") // needs native review
    val charging get() = tr(lang, "Charging", "चार्ज हो रहा है", "ଚାର୍ଜ ହେଉଛି", "Nagcha-charge", "Nagcha-charge") // needs native review
    val batteryLabel get() = tr(lang, "Battery", "बैटरी", "ବ୍ୟାଟେରୀ", "Baterya", "Baterya") // needs native review
    val unknownValue get() = "—"

    // toggles
    val gatewayToggle get() = tr(
        lang, "Act as a gateway when online", "ऑनलाइन होने पर गेटवे बनें", "ଅନଲାଇନ୍ ଥିଲେ ଗେଟୱେ ହୁଅନ୍ତୁ",
        "Maging gateway kapag online", "Mag-gateway kun online",
    ) // needs native review
    val gatewayToggleHelp get() = tr(
        lang, "Uploads everyone's requests the moment this phone gets signal.",
        "सिग्नल मिलते ही सबके अनुरोध अपलोड करता है।", "ସିଗ୍ନାଲ୍ ମିଳିଲେ ସମସ୍ତଙ୍କ ଅନୁରୋଧ ଅପଲୋଡ୍ କରେ।",
        "Ina-upload ang mga kahilingan ng lahat kapag may signal ang telepono.", "Ginau-upload an mga hangyo han ngatanan kun may signal an telepono.",
    ) // needs native review
    val carryToggle get() = tr(
        lang, "Carry messages in the background", "बैकग्राउंड में संदेश ढोएँ", "ବ୍ୟାକଗ୍ରାଉଣ୍ଡରେ ସନ୍ଦେଶ ବହନ କରନ୍ତୁ",
        "Magdala ng mensahe sa background", "Magdara hin mensahe ha background",
    ) // needs native review
    val carryToggleHelp get() = tr(
        lang, "Bluetooth only. Uses some battery.", "सिर्फ़ ब्लूटूथ। कुछ बैटरी लगती है।",
        "କେବଳ ବ୍ଲୁଟୁଥ୍। କିଛି ବ୍ୟାଟେରୀ ଲାଗେ।", "Bluetooth lang. Gumagamit ng kaunting baterya.", "Bluetooth la. Gumagamit hin gutiay nga baterya.",
    ) // needs native review

    // ---------------------------------------------------------------- Settings: groups
    val groupLanguageVoice get() = tr(lang, "Language and voice", "भाषा और आवाज़", "ଭାଷା ଓ ସ୍ୱର", "Wika at boses", "Pagsulti ngan tingog") // needs native review
    val groupScreenBattery get() = tr(lang, "Screen and battery", "स्क्रीन और बैटरी", "ସ୍କ୍ରିନ୍ ଓ ବ୍ୟାଟେରୀ", "Screen at baterya", "Screen ngan baterya") // needs native review
    val groupNetworkPermissions get() = tr(lang, "Network and permissions", "नेटवर्क और अनुमतियाँ", "ନେଟୱର୍କ ଓ ଅନୁମତି", "Network at mga pahintulot", "Network ngan mga pasubay") // needs native review
    val groupBeforeDisaster get() = tr(lang, "Before a disaster", "आपदा से पहले", "ବିପର୍ଯ୍ୟୟ ପୂର୍ବରୁ", "Bago ang sakuna", "Antes han katalagman") // needs native review

    // language and voice
    val language get() = tr(lang, "Language", "भाषा", "ଭାଷା", "Wika", "Pagsulti") // needs native review
    val chooseLanguage get() = tr(lang, "Choose your language", "अपनी भाषा चुनें", "ଆପଣଙ୍କ ଭାଷା ବାଛନ୍ତୁ", "Piliin ang wika mo", "Pilia an imo pagsulti") // needs native review
    val readAloud get() = tr(lang, "Read messages aloud", "संदेश बोलकर सुनाएँ", "ସନ୍ଦେଶ ଜୋରରେ ପଢ଼ନ୍ତୁ", "Basahin nang malakas ang mga mensahe", "Basaha nin kusog an mga mensahe") // needs native review
    val voiceSpeed get() = tr(lang, "Voice speed", "आवाज़ की गति", "ସ୍ୱରର ଗତି", "Bilis ng boses", "Kadagsa han tingog") // needs native review
    val speedSlow get() = tr(lang, "Slow", "धीमी", "ଧୀର", "Mabagal", "Hinay") // needs native review
    val speedNormal get() = tr(lang, "Normal", "सामान्य", "ସାଧାରଣ", "Normal", "Normal") // needs native review

    // screen and battery
    val theme get() = tr(lang, "Theme", "थीम", "ଥିମ୍", "Tema", "Tema") // needs native review
    val themeLight get() = tr(lang, "Light", "हल्की", "ହାଲୁକା", "Maliwanag", "Hayag") // needs native review
    val themeDark get() = tr(lang, "Dark", "गहरी", "ଗାଢ଼", "Madilim", "Maitom") // needs native review
    val themeAuto get() = tr(lang, "Auto", "ऑटो", "ଅଟୋ", "Auto", "Auto") // needs native review
    val largerText get() = tr(lang, "Larger text", "बड़े अक्षर", "ବଡ଼ ଅକ୍ଷର", "Mas malaking teksto", "Mas dako nga teksto") // needs native review
    val darkNote get() = tr(
        lang, "Dark appearance is used at night by default. It is easier on the eyes in a shelter and saves battery.",
        "रात में डिफ़ॉल्ट रूप से गहरी थीम चलती है। यह शेल्टर में आँखों को आराम देती है और बैटरी बचाती है।",
        "ରାତିରେ ଡିଫଲ୍ଟ ଭାବରେ ଗାଢ଼ ଥିମ୍ ବ୍ୟବହାର ହୁଏ। ଏହା ଆଶ୍ରୟସ୍ଥଳରେ ଆଖିକୁ ଆରାମ ଦିଏ ଓ ବ୍ୟାଟେରୀ ବଞ୍ଚାଏ।",
        "Madilim na tema ang gamit sa gabi bilang default. Mas magaan ito sa mata sa kanlungan at nakatitipid ng baterya.",
        "Maitom nga tema an gamit ha gabi. Mas gutiay an sakit ha mata ha kanlungan ngan nakakaipon hin baterya.",
    ) // needs native review
    val yourArea get() = tr(lang, "Your area", "आपका इलाका", "ଆପଣଙ୍କ ଅଞ୍ଚଳ", "Iyong lugar", "Imo lugar") // needs native review
    val areaNotSet get() = tr(lang, "Not set", "सेट नहीं है", "ସେଟ୍ ହୋଇନାହିଁ", "Hindi pa naka-set", "Waray pa gintakda") // needs native review
    val areaHelp get() = tr(
        lang, "Optional. Shown on Home, for example Ward 4, Puri district.",
        "वैकल्पिक। होम पर दिखता है, जैसे वार्ड 4, पुरी ज़िला।", "ଇଚ୍ଛାଧୀନ। ହୋମରେ ଦେଖାଯାଏ, ଯେପରି ୱାର୍ଡ 4, ପୁରୀ ଜିଲ୍ଲା।",
        "Opsyonal. Ipinapakita sa Home, halimbawa Ward 4, distrito ng Puri.", "Opsyonal. Ginpapakita ha Home, pananglitan Ward 4, distrito han Puri.",
    ) // needs native review
    val save get() = tr(lang, "Save", "सहेजें", "ସେଭ୍ କରନ୍ତୁ", "I-save", "Tipigi") // needs native review

    // network and permissions
    val gatewayMode get() = tr(lang, "Gateway mode", "गेटवे मोड", "ଗେଟୱେ ମୋଡ୍", "Gateway mode", "Gateway mode") // needs native review
    val gatewayAutomatic get() = tr(lang, "Automatic", "अपने-आप", "ସ୍ୱୟଂଚାଳିତ", "Awtomatiko", "Awtomatiko") // needs native review
    val gatewayNever get() = tr(lang, "Never", "कभी नहीं", "କେବେ ନୁହେଁ", "Hindi kailanman", "Diri gud") // needs native review
    val gatewayAutomaticHelp get() = tr(
        lang, "Whenever this phone has internet it uploads requests for the mesh.",
        "जब भी इस फ़ोन में इंटरनेट हो, यह मेश के अनुरोध अपलोड करता है।", "ଯେତେବେଳେ ଏହି ଫୋନରେ ଇଣ୍ଟରନେଟ୍ ଥାଏ, ଏହା ମେଶ୍ ପାଇଁ ଅନୁରୋଧ ଅପଲୋଡ୍ କରେ।",
        "Kapag may internet ang telepono, ina-upload nito ang mga kahilingan ng mesh.", "Kun may internet an telepono, ginau-upload niya an mga hangyo han mesh.",
    ) // needs native review
    val gatewayNeverHelp get() = tr(
        lang, "This phone never uploads. It still passes messages phone to phone.",
        "यह फ़ोन कभी अपलोड नहीं करता। फिर भी यह फ़ोन से फ़ोन संदेश पहुँचाता है।", "ଏହି ଫୋନ୍ କେବେ ଅପଲୋଡ୍ କରେ ନାହିଁ। ତଥାପି ଏହା ଫୋନ୍ ଠାରୁ ଫୋନ୍ କୁ ସନ୍ଦେଶ ପଠାଏ।",
        "Hindi nag-a-upload ang teleponong ito. Nagpapasa pa rin ito ng mensahe sa ibang telepono.", "Diri nag-a-upload ini nga telepono. Nagpapasa gihapon hin mensahe ha iba nga telepono.",
    ) // needs native review
    val permBluetooth get() = tr(lang, "Bluetooth", "ब्लूटूथ", "ବ୍ଲୁଟୁଥ୍", "Bluetooth", "Bluetooth") // needs native review
    val permLocation get() = tr(lang, "Location", "लोकेशन", "ଲୋକେସନ୍", "Lokasyon", "Lokasyon") // needs native review
    val permMicrophone get() = tr(lang, "Microphone", "माइक्रोफ़ोन", "ମାଇକ୍ରୋଫୋନ୍", "Mikropono", "Mikropono") // needs native review
    val permAllowed get() = tr(lang, "Allowed", "अनुमति है", "ଅନୁମତି ଅଛି", "Pinapayagan", "Gintutugot") // needs native review
    val permAsk get() = tr(lang, "Ask each time", "हर बार पूछें", "ପ୍ରତିଥର ପଚାରନ୍ତୁ", "Magtanong tuwing", "Pagpakiana kada beses") // needs native review
    val permNotAllowed get() = tr(lang, "Not allowed", "अनुमति नहीं", "ଅନୁମତି ନାହିଁ", "Hindi pinapayagan", "Diri gintutugot") // needs native review
    val permOpensSettings get() = tr(lang, "Opens system settings", "सिस्टम सेटिंग्स खुलती हैं", "ସିଷ୍ଟମ୍ ସେଟିଂସ ଖୋଲେ", "Bubuksan ang system settings", "Bubuksan an system settings") // needs native review

    // before a disaster
    val householdPlan get() = tr(lang, "Household plan", "घर की योजना", "ପରିବାର ଯୋଜନା", "Plano ng sambahayan", "Plano han panimalay") // needs native review
    val householdPlanHelp get() = tr(
        lang, "Shelter, checklist and a spoken plan, saved offline", "शेल्टर, चेकलिस्ट और बोली जाने वाली योजना, ऑफ़लाइन सहेजी",
        "ଆଶ୍ରୟ, ଚେକ୍ ଲିଷ୍ଟ ଓ କଥିତ ଯୋଜନା, ଅଫଲାଇନ୍ ସେଭ୍", "Kanlungan, checklist at planong naririnig, naka-save offline", "Kanlungan, checklist ngan plano nga naririnig, naka-save offline",
    ) // needs native review
    val setUp get() = tr(lang, "Set up", "शुरू करें", "ସେଟ୍ ଅପ୍", "I-set up", "Ayusa") // needs native review
    val comingSoonTitle get() = tr(lang, "Coming soon", "जल्द आ रहा है", "ଶୀଘ୍ର ଆସୁଛି", "Parating na", "Mag-abot na") // needs native review
    val comingSoonBody get() = tr(
        lang, "The household plan is not ready yet. For now, Relay passes your requests to help.",
        "घर की योजना अभी तैयार नहीं है। अभी Relay आपके अनुरोध मदद तक पहुँचाता है।",
        "ପରିବାର ଯୋଜନା ଏପର୍ଯ୍ୟନ୍ତ ପ୍ରସ୍ତୁତ ନୁହେଁ। ବର୍ତ୍ତମାନ Relay ଆପଣଙ୍କ ଅନୁରୋଧ ସାହାଯ୍ୟକୁ ପଠାଏ।",
        "Hindi pa handa ang plano ng sambahayan. Sa ngayon, ipinapasa ng Relay ang mga kahilingan mo sa tulong.",
        "Diri pa andam an plano han panimalay. Subong, ginpapasa han Relay an imo mga hangyo ngadto ha bulig.",
    ) // needs native review

    val developer get() = tr(lang, "Developer", "डेवलपर", "ଡେଭଲପର୍", "Developer", "Developer") // needs native review
}
