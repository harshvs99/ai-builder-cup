package org.relay.app.ui.i18n

import org.relay.core.payload.Lang

/** Strings for New request (voice/text) and Check and send. Owned by the Ask agent. */
class AskStrings(private val lang: Lang) {
    // Pattern: a `val` for fixed text, a `fun` for text with parameters; mark non-English `// needs native review`.
    val newRequestTitle get() = tr(lang, "New request", "नया अनुरोध", "ନୂଆ ଅନୁରୋଧ", "Bagong kahilingan", "Bag-o nga hangyo") // needs native review
    fun stepCounter(step: Int, total: Int) = tr(lang, "$step of $total", "$total में से $step", "$total ରୁ $step", "$step sa $total", "$step han $total") // needs native review

    // ---- New request
    val headline get() = tr(
        lang, "What do you need most right now?", "अभी आपको सबसे ज़्यादा किस चीज़ की ज़रूरत है?",
        "ଏବେ ଆପଣଙ୍କୁ ସବୁଠାରୁ ଅଧିକ କ'ଣ ଦରକାର?", "Ano ang pinakakailangan ninyo ngayon?", "Ano an pinakakinahanglan niyo yana?",
    ) // needs native review
    val headlineHint get() = tr(
        lang, "Say what you need and how many people are with you.", "बताइए कि क्या चाहिए और आपके साथ कितने लोग हैं।",
        "କ'ଣ ଦରକାର ଓ ଆପଣଙ୍କ ସାଙ୍ଗରେ କେତେ ଜଣ ଅଛନ୍ତି କୁହନ୍ତୁ।", "Sabihin kung ano ang kailangan at ilan kayo.", "Hunga kon ano an kinahanglan ngan pira kamo.",
    ) // needs native review
    val howManyPeople get() = tr(lang, "How many people need this?", "यह कितने लोगों के लिए चाहिए?", "ଏହା କେତେ ଜଣଙ୍କ ପାଇଁ ଦରକାର?", "Ilang tao ang nangangailangan nito?", "Pira nga tawo an nangangaro hini?") // needs native review
    val hearItAgain get() = tr(lang, "Hear it again", "फिर से सुनें", "ପୁଣି ଶୁଣନ୍ତୁ", "Pakinggan ulit", "Pamatii liwat") // needs native review
    val noVoiceInstalled get() = tr(
        lang, "This phone has no voice installed for this language.", "इस फ़ोन में इस भाषा की आवाज़ नहीं है।",
        "ଏହି ଫୋନରେ ଏହି ଭାଷାର ସ୍ୱର ନାହିଁ।", "Walang boses para sa wikang ito sa teleponong ito.", "Waray boses para hini nga yinaknan ha telepono.",
    ) // needs native review
    val listening get() = tr(lang, "Listening", "सुन रहा है", "ଶୁଣୁଛି", "Nakikinig", "Nagpapamati") // needs native review
    val paused get() = tr(lang, "Paused", "रुका हुआ", "ବିରତି", "Naka-pause", "Gintigil") // needs native review
    val sayItPrompt get() = tr(
        lang, "Say it in your own words. For example: \"6 of us need drinking water, near the school.\"",
        "अपने शब्दों में बोलिए। जैसे: \"हम 6 लोगों को पीने का पानी चाहिए, स्कूल के पास।\"",
        "ନିଜ ଶବ୍ଦରେ କୁହନ୍ତୁ। ଯେପରି: \"ଆମ 6 ଜଣଙ୍କୁ ପିଇବା ପାଣି ଦରକାର, ସ୍କୁଲ ପାଖରେ।\"",
        "Sabihin sa sarili ninyong salita. Halimbawa: \"6 kami na kailangan ng inuming tubig, malapit sa paaralan.\"",
        "Hunga ha iyo kalugaringon nga pulong. Pananglitan: \"6 kami nga kinahanglan hin tubig nga inumon, harapit ha eskwelahan.\"",
    ) // needs native review
    val understoodSoFar get() = tr(lang, "Understood so far", "अब तक समझा", "ଏପର୍ଯ୍ୟନ୍ତ ବୁଝିଲି", "Naintindihan na", "Naintindihan na") // needs native review
    val nothingYet get() = tr(lang, "Nothing yet", "अभी कुछ नहीं", "ଏପର୍ଯ୍ୟନ୍ତ କିଛି ନାହିଁ", "Wala pa", "Waray pa") // needs native review
    fun nearLandmark(place: String) = tr(lang, "Near $place", "$place के पास", "$place ପାଖରେ", "Malapit sa $place", "Harapit ha $place") // needs native review
    val orTapWhat get() = tr(lang, "Or tap what you need", "या चुनें कि आपको क्या चाहिए", "କିମ୍ବା ଦରକାର ଜିନିଷ ଛୁଅନ୍ତୁ", "O pindutin ang kailangan ninyo", "O pinduta an kinahanglan niyo") // needs native review
    val stopListening get() = tr(lang, "Stop listening", "सुनना बंद करें", "ଶୁଣିବା ବନ୍ଦ କରନ୍ତୁ", "Itigil ang pakikinig", "Undanga an pagpamati") // needs native review
    val startListening get() = tr(lang, "Start listening", "सुनना शुरू करें", "ଶୁଣିବା ଆରମ୍ଭ କରନ୍ତୁ", "Simulan ang pakikinig", "Sugdi an pagpamati") // needs native review
    val doneCheckIt get() = tr(lang, "Done, check it", "हो गया, जाँचें", "ହୋଇଗଲା, ଯାଞ୍ଚ କରନ୍ତୁ", "Tapos na, suriin", "Human na, usisaa") // needs native review
    val typeInstead get() = tr(lang, "Type instead", "लिखकर बताएँ", "ଲେଖି କୁହନ୍ତୁ", "Mag-type na lang", "Pagsurat la") // needs native review
    val useVoiceInstead get() = tr(lang, "Use voice instead", "बोलकर बताएँ", "କହି କୁହନ୍ତୁ", "Gamitin ang boses", "Gamita an boses") // needs native review
    val typeHint get() = tr(lang, "Type what you need", "लिखिए कि आपको क्या चाहिए", "ଲେଖନ୍ତୁ ଆପଣଙ୍କୁ କ'ଣ ଦରକାର", "I-type ang kailangan ninyo", "Isurat an kinahanglan niyo") // needs native review
    val typePlaceholder get() = tr(
        lang, "e.g. 6 of us need drinking water, near the school", "जैसे: हम 6 लोगों को पीने का पानी चाहिए, स्कूल के पास",
        "ଯେପରି: ଆମ 6 ଜଣଙ୍କୁ ପିଇବା ପାଣି ଦରକାର, ସ୍କୁଲ ପାଖରେ", "hal. 6 kami na kailangan ng inuming tubig, malapit sa paaralan",
        "pananglitan: 6 kami nga kinahanglan hin tubig nga inumon, harapit ha eskwelahan",
    ) // needs native review

    // Voice problems, in plain words (never an error code or developer text).
    fun voicePackMissing(language: String) = tr(
        lang, "Voice for $language isn't on this phone without internet. Please type what you need.",
        "$language की आवाज़ पहचान इस फ़ोन पर बिना इंटरनेट के नहीं है। कृपया लिखकर बताएँ।",
        "$language ଭାଷାର ସ୍ୱର ଚିହ୍ନଟ ଇଣ୍ଟରନେଟ ବିନା ଏହି ଫୋନରେ ନାହିଁ। ଦୟାକରି ଲେଖି କୁହନ୍ତୁ।",
        "Wala sa teleponong ito ang boses para sa $language kapag walang internet. Paki-type na lang ang kailangan ninyo.",
        "Waray ha telepono ini an boses para ha $language kon waray internet. Pagsurat la han kinahanglan niyo.",
    ) // needs native review
    val micDenied get() = tr(
        lang, "The microphone is off for Relay. Please type, or allow the microphone in Settings.",
        "Relay के लिए माइक्रोफ़ोन बंद है। कृपया लिखें, या सेटिंग्स में माइक्रोफ़ोन की अनुमति दें।",
        "Relay ପାଇଁ ମାଇକ୍ରୋଫୋନ ବନ୍ଦ ଅଛି। ଦୟାକରି ଲେଖନ୍ତୁ, କିମ୍ବା ସେଟିଂସରେ ମାଇକ୍ରୋଫୋନ ଅନୁମତି ଦିଅନ୍ତୁ।",
        "Nakapatay ang mikropono para sa Relay. Mag-type na lang, o payagan ang mikropono sa Settings.",
        "Patay an mikropono para ha Relay. Pagsurat la, o tugti an mikropono ha Settings.",
    ) // needs native review
    val didntCatch get() = tr(
        lang, "Didn't catch that. Tap the microphone to try again, or type.", "सुनाई नहीं दिया। फिर कोशिश करने के लिए माइक दबाएँ, या लिखें।",
        "ଶୁଣାଗଲା ନାହିଁ। ପୁଣି ଚେଷ୍ଟା ପାଇଁ ମାଇକ ଛୁଅନ୍ତୁ, କିମ୍ବା ଲେଖନ୍ତୁ।", "Hindi narinig. Pindutin ang mikropono para subukan ulit, o mag-type.",
        "Waray mabati. Pinduta an mikropono para pagsari liwat, o pagsurat.",
    ) // needs native review
    val voiceUnavailable get() = tr(
        lang, "Voice isn't working right now. Please type what you need.", "अभी आवाज़ काम नहीं कर रही। कृपया लिखकर बताएँ।",
        "ଏବେ ସ୍ୱର କାମ କରୁନାହିଁ। ଦୟାକରି ଲେଖି କୁହନ୍ତୁ।", "Hindi gumagana ang boses ngayon. Paki-type ang kailangan ninyo.",
        "Diri nagagana an boses yana. Pagsurat han kinahanglan niyo.",
    ) // needs native review
    val openSettings get() = tr(lang, "Open settings", "सेटिंग्स खोलें", "ସେଟିଂସ ଖୋଲନ୍ତୁ", "Buksan ang Settings", "Abri an Settings") // needs native review

    // ---- Check and send
    val checkTitle get() = tr(lang, "Check and send", "जाँचें और भेजें", "ଯାଞ୍ଚ କରି ପଠାନ୍ତୁ", "Suriin at ipadala", "Usisaa ngan ipadara") // needs native review
    val isThisRight get() = tr(lang, "Is this right?", "क्या यह सही है?", "ଏହା ଠିକ୍ ଅଛି କି?", "Tama ba ito?", "Husto ba ini?") // needs native review
    val rowNeed get() = tr(lang, "Need", "ज़रूरत", "ଦରକାର", "Kailangan", "Kinahanglan") // needs native review
    val rowPeople get() = tr(lang, "People", "लोग", "ଲୋକ", "Mga tao", "Mga tawo") // needs native review
    val rowWhere get() = tr(lang, "Where", "कहाँ", "କେଉଁଠି", "Saan", "Diin") // needs native review
    val rowNote get() = tr(lang, "Note", "टिप्पणी", "ଟିପ୍ପଣୀ", "Tala", "Sumat") // needs native review
    val nothingChosen get() = tr(lang, "Not chosen yet", "अभी चुना नहीं", "ଏପର୍ଯ୍ୟନ୍ତ ବାଛି ନାହିଁ", "Wala pang napili", "Waray pa napili") // needs native review
    val noLandmark get() = tr(lang, "No landmark yet", "अभी कोई पहचान की जगह नहीं", "ଏପର୍ଯ୍ୟନ୍ତ ଚିହ୍ନ ସ୍ଥାନ ନାହିଁ", "Wala pang palatandaan", "Waray pa landmark") // needs native review
    val noNote get() = tr(lang, "No note", "कोई टिप्पणी नहीं", "କୌଣସି ଟିପ୍ପଣୀ ନାହିଁ", "Walang tala", "Waray sumat") // needs native review
    fun locationAccuracy(meters: Int) = tr(
        lang, "GPS · about $meters m accuracy", "GPS · लगभग $meters मी की सटीकता", "GPS · ପ୍ରାୟ $meters ମି ସଠିକତା",
        "GPS · humigit-kumulang $meters m ang katumpakan", "GPS · mga $meters m an kaparehas",
    ) // needs native review
    val locationAcquiring get() = tr(lang, "Getting location…", "लोकेशन मिल रही है…", "ଲୋକେସନ ମିଳୁଛି…", "Kinukuha ang lokasyon…", "Ginkukuha an lokasyon…") // needs native review
    val locationOff get() = tr(
        lang, "Location is off — rescuers will rely on the landmark", "लोकेशन बंद है — बचावकर्ता पहचान की जगह पर निर्भर रहेंगे",
        "ଲୋକେସନ ବନ୍ଦ ଅଛି — ଉଦ୍ଧାରକାରୀ ଚିହ୍ନ ସ୍ଥାନ ଉପରେ ନିର୍ଭର କରିବେ", "Naka-off ang lokasyon — aasa ang mga tagapagligtas sa palatandaan",
        "Patay an lokasyon — an mga manluluwas magsarig ha landmark",
    ) // needs native review
    // ---- no-location warning (Where row) and the dialog shown on Send without a fix
    val whereReasonPermissionOff get() = tr(
        lang, "Location permission is off", "लोकेशन की अनुमति बंद है", "ଲୋକେସନ ଅନୁମତି ବନ୍ଦ ଅଛି", "Naka-off ang pahintulot sa lokasyon", "Patay an pagtugot ha lokasyon",
    ) // needs native review
    val whereReasonServicesOff get() = tr(
        lang, "Location is turned off on this phone", "इस फ़ोन में लोकेशन बंद है", "ଏହି ଫୋନରେ ଲୋକେସନ ବନ୍ଦ ଅଛି", "Naka-off ang lokasyon sa teleponong ito", "Patay an lokasyon hini nga telepono",
    ) // needs native review
    val whereReasonFinding get() = tr(
        lang, "Still finding your location…", "आपकी लोकेशन अभी खोज रहे हैं…", "ଆପଣଙ୍କ ଲୋକେସନ ଏବେ ବି ଖୋଜୁଛି…", "Hinahanap pa ang inyong lokasyon…", "Gin-aagi pa an pagpangita han iyo lokasyon…",
    ) // needs native review
    val noLocationWarningA11y get() = tr(lang, "Warning", "चेतावनी", "ସତର୍କତା", "Babala", "Pasidaan") // needs native review
    val noLocationTitle get() = tr(
        lang, "Rescuers can't see where you are", "बचावकर्ता नहीं देख सकते कि आप कहाँ हैं", "ଉଦ୍ଧାରକାରୀ ଦେଖି ପାରୁନାହାଁନ୍ତି ଆପଣ କେଉଁଠି ଅଛନ୍ତି",
        "Hindi nakikita ng mga tagapagligtas kung nasaan kayo", "Diri makita han mga manluluwas kon hain kamo",
    ) // needs native review
    fun noLocationBodyWithLandmark(landmark: String) = tr(
        lang,
        "They will rely on '$landmark'. Adding your location helps them find you faster.",
        "वे '$landmark' पर निर्भर रहेंगे। लोकेशन जोड़ने से वे आपको जल्दी ढूँढ पाएँगे।",
        "ସେମାନେ '$landmark' ଉପରେ ନିର୍ଭର କରିବେ। ଲୋକେସନ ଯୋଡ଼ିଲେ ସେମାନେ ଆପଣଙ୍କୁ ଶୀଘ୍ର ଖୋଜି ପାରିବେ।",
        "Aasa sila sa '$landmark'. Makakatulong ang pagdagdag ng lokasyon para mas mabilis nila kayong mahanap.",
        "Magsarig hira ha '$landmark'. An pagdugang han lokasyon makabulig para mas dagmit hira makaagi ha iyo.",
    ) // needs native review
    val noLocationBodyNoLandmark get() = tr(
        lang,
        "Without your location or a nearby landmark, the control room may not be able to find you.",
        "आपकी लोकेशन या पास की किसी पहचान की जगह के बिना, कंट्रोल रूम आपको ढूँढ नहीं पाएगा।",
        "ଆପଣଙ୍କ ଲୋକେସନ କିମ୍ବା ପାଖର ଚିହ୍ନ ସ୍ଥାନ ବିନା, କଣ୍ଟ୍ରୋଲ ରୁମ ଆପଣଙ୍କୁ ଖୋଜି ନପାରିପାରେ।",
        "Kung walang lokasyon o malapit na palatandaan, maaaring hindi kayo mahanap ng control room.",
        "Kon waray lokasyon o harapit nga landmark, basin diri kamo makita han control room.",
    ) // needs native review
    val turnOnLocation get() = tr(lang, "Turn on location", "लोकेशन चालू करें", "ଲୋକେସନ ଚାଲୁ କରନ୍ତୁ", "I-on ang lokasyon", "Buksi an lokasyon") // needs native review
    val addLandmark get() = tr(lang, "Add a landmark", "पहचान की जगह जोड़ें", "ଚିହ୍ନ ସ୍ଥାନ ଯୋଡ଼ନ୍ତୁ", "Magdagdag ng palatandaan", "Pagdugang hin landmark") // needs native review
    val changeLandmark get() = tr(lang, "Change landmark", "पहचान की जगह बदलें", "ଚିହ୍ନ ସ୍ଥାନ ବଦଳାନ୍ତୁ", "Palitan ang palatandaan", "Usba an landmark") // needs native review
    val sendWithoutLocation get() = tr(lang, "Send without location", "बिना लोकेशन के भेजें", "ଲୋକେସନ ବିନା ପଠାନ୍ତୁ", "Ipadala nang walang lokasyon", "Ipadara nga waray lokasyon") // needs native review
    val sendAnywayConfirm get() = tr(
        lang, "Send anyway — the control room may not find you", "फिर भी भेजें — कंट्रोल रूम आपको ढूँढ नहीं पाएगा",
        "ତଥାପି ପଠାନ୍ତୁ — କଣ୍ଟ୍ରୋଲ ରୁମ ଆପଣଙ୍କୁ ଖୋଜି ନପାରିପାରେ", "Ipadala pa rin — maaaring hindi kayo mahanap ng control room",
        "Ipadara gihapon — basin diri kamo makita han control room",
    ) // needs native review
    val quickQuestions get() = tr(lang, "A few quick questions", "कुछ छोटे सवाल", "କିଛି ଛୋଟ ପ୍ରଶ୍ନ", "Ilang mabilis na tanong", "Pira nga dagmit nga pakiana") // needs native review
    fun hearQuestion(question: String) = tr(lang, "Hear the question: $question", "सवाल सुनें: $question", "ପ୍ରଶ୍ନ ଶୁଣନ୍ତୁ: $question", "Pakinggan ang tanong: $question", "Pamatii an pakiana: $question") // needs native review
    val howUrgent get() = tr(lang, "How urgent is it?", "यह कितना ज़रूरी है?", "ଏହା କେତେ ଜରୁରୀ?", "Gaano ito kaapura?", "Hin-o kapagmadali ini?") // needs native review
    val changeSomething get() = tr(lang, "Change something", "कुछ बदलें", "କିଛି ବଦଳାନ୍ତୁ", "Baguhin ang ilan", "May usbon") // needs native review
    val footnote get() = tr(
        lang, "Goes phone to phone until it reaches a gateway. No network needed.",
        "फ़ोन से फ़ोन होते हुए गेटवे तक पहुँचता है। नेटवर्क की ज़रूरत नहीं।",
        "ଫୋନରୁ ଫୋନକୁ ଯାଇ ଗେଟୱେରେ ପହଞ୍ଚେ। ନେଟୱର୍କ ଦରକାର ନାହିଁ।",
        "Lilipat-lipat sa telepono hanggang makarating sa gateway. Hindi kailangan ng network.",
        "Nagbabalhin-balhin ha telepono tubtob makaabot ha gateway. Diri kinahanglan an network.",
    ) // needs native review
    val sending get() = tr(lang, "Sending…", "भेज रहे हैं…", "ପଠାଉଛି…", "Ipinapadala…", "Ginpapadara…") // needs native review
    val sendingGettingLocation get() = tr(lang, "Getting your location…", "आपकी लोकेशन ले रहे हैं…", "ଆପଣଙ୍କ ଲୋକେସନ ନେଉଛି…", "Kinukuha ang inyong lokasyon…", "Ginkukuha an iyo lokasyon…") // needs native review

    // ---- editors (bottom sheets)
    val editNeedTitle get() = tr(lang, "What do you need?", "आपको क्या चाहिए?", "ଆପଣଙ୍କୁ କ'ଣ ଦରକାର?", "Ano ang kailangan ninyo?", "Ano an kinahanglan niyo?") // needs native review
    val itemLabel get() = tr(lang, "What exactly? (optional)", "ठीक-ठीक क्या? (वैकल्पिक)", "ଠିକ୍ କ'ଣ? (ଇଚ୍ଛାଧୀନ)", "Ano mismo? (opsyonal)", "Ano gud? (opsyonal)") // needs native review
    val editPeopleTitle get() = tr(lang, "How many people?", "कितने लोग?", "କେତେ ଜଣ?", "Ilang tao?", "Pira nga tawo?") // needs native review
    val morePeople get() = tr(lang, "More people", "और लोग", "ଅଧିକ ଲୋକ", "Dagdagan ang tao", "Dugangi an tawo") // needs native review
    val fewerPeople get() = tr(lang, "Fewer people", "कम लोग", "କମ୍ ଲୋକ", "Bawasan ang tao", "Pakadiyuta an tawo") // needs native review
    val whoIsWithYou get() = tr(lang, "Who is with you?", "आपके साथ कौन है?", "ଆପଣଙ୍କ ସହିତ କିଏ ଅଛି?", "Sino ang kasama ninyo?", "Hin-o an kaupod niyo?") // needs native review
    val editWhereTitle get() = tr(lang, "Where are you?", "आप कहाँ हैं?", "ଆପଣ କେଉଁଠି ଅଛନ୍ତି?", "Nasaan kayo?", "Hain kamo?") // needs native review
    val landmarkLabel get() = tr(lang, "A place nearby (school, temple, shop)", "पास की कोई जगह (स्कूल, मंदिर, दुकान)", "ପାଖର ଏକ ସ୍ଥାନ (ସ୍କୁଲ, ମନ୍ଦିର, ଦୋକାନ)", "Isang lugar na malapit (paaralan, templo, tindahan)", "Usa nga lugar nga harapit (eskwelahan, templo, tindahan)") // needs native review
    val editNoteTitle get() = tr(lang, "Add a short note", "छोटी टिप्पणी जोड़ें", "ଏକ ଛୋଟ ଟିପ୍ପଣୀ ଯୋଗ କରନ୍ତୁ", "Magdagdag ng maikling tala", "Pagdugang hin mubo nga sumat") // needs native review
    val sentInEnglish get() = tr(lang, "Sent to the control room in English", "कंट्रोल रूम को अंग्रेज़ी में भेजा जाता है", "କଣ୍ଟ୍ରୋଲ ରୁମକୁ ଇଂରାଜୀରେ ପଠାଯାଏ", "Ipapadala sa control room sa English", "Ipadadara ha control room ha English") // needs native review
    val noteSentInEnglish get() = tr(
        lang, "This note is sent to the control room in English, as written here.",
        "यह टिप्पणी कंट्रोल रूम को अंग्रेज़ी में, जैसी यहाँ लिखी है वैसी ही भेजी जाती है।",
        "ଏହି ଟିପ୍ପଣୀ ଏଠାରେ ଯେପରି ଲେଖା ଅଛି ସେହିପରି ଇଂରାଜୀରେ କଣ୍ଟ୍ରୋଲ ରୁମକୁ ପଠାଯାଏ।",
        "Ipapadala ang tala na ito sa control room sa English, ayon sa pagkakasulat dito.",
        "Ipadadara ini nga sumat ha control room ha English, sugad han pagkasulat dinhi.",
    ) // needs native review
    val noteLabel get() = tr(lang, "Note", "टिप्पणी", "ଟିପ୍ପଣୀ", "Tala", "Sumat") // needs native review
    fun charsLeft(n: Int) = tr(lang, "$n characters left", "$n अक्षर बाकी", "$n ଅକ୍ଷର ବାକି", "$n karakter ang natitira", "$n ka karakter an nahibilin") // needs native review

    // ---- Triage (medical and rescue requests: a short back-and-forth before Check and send)
    val triageTitle get() = tr(lang, "A few quick questions", "कुछ छोटे सवाल", "କିଛି ଛୋଟ ପ୍ରଶ୍ନ", "Ilang mabilis na tanong", "Pipira nga mabinit nga pangutana") // needs native review
    val triageAnswerHint get() = tr(
        lang, "Answer out loud, or tap a button.", "बोलकर जवाब दें, या बटन दबाएँ।", "କହି ଉତ୍ତର ଦିଅନ୍ତୁ, କିମ୍ବା ବଟନ ଛୁଅନ୍ତୁ।",
        "Sumagot nang malakas, o pindutin ang isang button.", "Sumagot nga mabaskog, o pinduta an button.",
    ) // needs native review
    val triageUnclear get() = tr(
        lang, "Please say yes or no, or tap a button.", "कृपया हाँ या नहीं कहें, या बटन दबाएँ।", "ଦୟାକରି ହଁ ବା ନା କୁହନ୍ତୁ, କିମ୍ବା ବଟନ ଛୁଅନ୍ତୁ।",
        "Pakisabi ang oo o hindi, o pindutin ang isang button.", "Pakisiring oo o diri, o pinduta an button.",
    ) // needs native review
    val triageYes get() = tr(lang, "Yes", "हाँ", "ହଁ", "Oo", "Oo") // needs native review
    val triageNo get() = tr(lang, "No", "नहीं", "ନା", "Hindi", "Diri") // needs native review
    val triageDontKnow get() = tr(lang, "Don't know", "पता नहीं", "ଜଣା ନାହିଁ", "Hindi alam", "Diri aram") // needs native review
    val triageThinking get() = tr(lang, "Thinking…", "सोच रहा है…", "ଭାବୁଛି…", "Nag-iisip…", "Nagpapahunahuna…") // needs native review
    val triageSkip get() = tr(lang, "Skip questions", "सवाल छोड़ें", "ପ୍ରଶ୍ନ ଛାଡ଼ନ୍ତୁ", "Laktawan ang mga tanong", "Laktawi an mga pangutana") // needs native review
    val triageCritical get() = tr(
        lang, "This sounds life-threatening. It will be sent as urgent.", "यह जानलेवा लग रहा है। इसे अत्यंत ज़रूरी के रूप में भेजा जाएगा।",
        "ଏହା ଜୀବନ ପ୍ରତି ବିପଦଜନକ ଲାଗୁଛି। ଏହାକୁ ଜରୁରୀ ଭାବେ ପଠାଯିବ।", "Mukhang nakamamatay ito. Ipapadala ito bilang agaran.",
        "Nakakamatay ini. Ipadadara ini sugad nga dagmitan.",
    ) // needs native review
    val triageAdviceTitle get() = tr(lang, "What to do now", "अभी क्या करें", "ବର୍ତ୍ତମାନ କ'ଣ କରିବେ", "Ano ang gagawin ngayon", "Ano an buhaton yana") // needs native review
    val triageReview get() = tr(lang, "Review and send", "जाँचें और भेजें", "ଯାଞ୍ଚ କରି ପଠାନ୍ତୁ", "Suriin at ipadala", "Usisaa ngan ipadara") // needs native review
    val triageModelReady get() = tr(lang, "Assistant on", "सहायक चालू", "ସହାୟକ ଚାଲୁ", "Naka-on ang katulong", "Nakabukas an katabang") // needs native review
    val triageModelLoading get() = tr(lang, "Assistant starting…", "सहायक शुरू हो रहा है…", "ସହାୟକ ଆରମ୍ଭ ହେଉଛି…", "Sinisimulan ang katulong…", "Gin-uumpisahan an katabang…") // needs native review
    val triageModelOff get() = tr(lang, "Basic questions only", "सिर्फ़ सामान्य सवाल", "କେବଳ ସାଧାରଣ ପ୍ରଶ୍ନ", "Pangunahing tanong lang", "Pangunahon nga pangutana la") // needs native review
    fun triageDeclared(kinds: String) = tr(
        lang, "Control room alert: $kinds", "कंट्रोल रूम की चेतावनी: $kinds", "କଣ୍ଟ୍ରୋଲ ରୁମ ସତର୍କତା: $kinds",
        "Babala ng control room: $kinds", "Pasidaan han control room: $kinds",
    ) // needs native review
    val triageYouSaid get() = tr(lang, "You", "आप", "ଆପଣ", "Kayo", "Ikaw") // needs native review
    val triageStartMic get() = tr(lang, "Answer by voice", "बोलकर जवाब दें", "କହି ଉତ୍ତର ଦିଅନ୍ତୁ", "Sumagot gamit ang boses", "Sumagot gamit an boses") // needs native review
}
