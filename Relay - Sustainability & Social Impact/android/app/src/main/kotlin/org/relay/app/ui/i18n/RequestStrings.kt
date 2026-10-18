package org.relay.app.ui.i18n

import org.relay.core.payload.Lang

/** Strings for Request detail (timeline is in [CommonStrings]). Owned by the Request agent. */
class RequestStrings(private val lang: Lang) {
    val title get() = tr(lang, "Request", "अनुरोध", "ଅନୁରୋଧ", "Kahilingan", "Hangyo") // needs native review

    val share get() = tr(lang, "Share", "साझा करें", "ଶେୟାର୍ କରନ୍ତୁ", "Ibahagi", "Ibahin") // needs native review

    /** "R-7F3A · sent 2:02 PM · Ward 4" (area omitted when blank). */
    fun referenceLine(ref: String, sentTime: String, area: String): String {
        val sent = tr(lang, "sent $sentTime", "$sentTime पर भेजा", "$sentTime ରେ ପଠାଯାଇଛି", "ipinadala $sentTime", "gin-ipadara $sentTime") // needs native review
        return listOf(ref, sent, area).filter { it.isNotBlank() }.joinToString(" · ")
    }

    val fromControlRoom get() = tr(lang, "From the control room", "कंट्रोल रूम से", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍ ରୁ", "Mula sa control room", "Tikang ha control room") // needs native review

    fun hearItIn(language: String) = tr(
        lang, "Hear it in $language", "$language में सुनें", "$language ରେ ଶୁଣନ୍ତୁ", "Pakinggan sa $language", "Pamati ha $language",
    ) // needs native review

    val noVoice get() = tr(
        lang, "No voice for this language is installed on this phone.", "इस फ़ोन में इस भाषा की आवाज़ इंस्टॉल नहीं है।",
        "ଏହି ଫୋନରେ ଏହି ଭାଷାର ସ୍ୱର ଇନ୍‌ଷ୍ଟଲ୍ ହୋଇନାହିଁ।", "Walang naka-install na boses para sa wikang ito sa teleponong ito.",
        "Waray naka-install nga tingog para hini nga pagsasaramong hini nga telepono.",
    ) // needs native review

    val cancelRequest get() = tr(lang, "Cancel request", "अनुरोध रद्द करें", "ଅନୁରୋଧ ବାତିଲ୍ କରନ୍ତୁ", "Kanselahin ang kahilingan", "Kanselaha an hangyo") // needs native review
    val addToRequest get() = tr(lang, "+ Add to request", "+ अनुरोध में जोड़ें", "+ ଅନୁରୋଧରେ ଯୋଡ଼ନ୍ତୁ", "+ Magdagdag sa kahilingan", "+ Dugangi an hangyo") // needs native review

    val cancelDialogTitle get() = tr(lang, "Stop sharing this request?", "इस अनुरोध को साझा करना बंद करें?", "ଏହି ଅନୁରୋଧ ସେୟାର୍ କରିବା ବନ୍ଦ କରିବେ?", "Ihinto ang pagbabahagi ng kahilingang ito?", "Hunongon an pagbahin hini nga hangyo?") // needs native review

    val cancelDialogBody get() = tr(
        lang,
        "Phones nearby will stop passing it on from this phone. If the control room already has it, they may still come.",
        "आस-पास के फ़ोन इस फ़ोन से इसे आगे नहीं बढ़ाएँगे। अगर कंट्रोल रूम के पास यह पहले से है, तो वे फिर भी आ सकते हैं।",
        "ପାଖ ଫୋନ୍‌ଗୁଡ଼ିକ ଏହି ଫୋନରୁ ଏହାକୁ ଆଗକୁ ପଠାଇବା ବନ୍ଦ କରିବେ। କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍ ପାଖରେ ଆଗରୁ ଥିଲେ, ସେମାନେ ତଥାପି ଆସିପାରନ୍ତି।",
        "Titigil ang mga kalapit na telepono sa pagpasa nito mula sa teleponong ito. Kung hawak na ito ng control room, maaari pa rin silang dumating.",
        "Hihinunong han kalapit nga mga telepono an pagpasa hini tikang hini nga telepono. Kon hinuuna na ito han control room, mahimo gihapon sira umabot.",
    ) // needs native review

    val cancelConfirm get() = tr(lang, "Stop sharing", "साझा करना बंद करें", "ସେୟାର୍ ବନ୍ଦ କରନ୍ତୁ", "Ihinto", "Hunong") // needs native review
    val cancelKeep get() = tr(lang, "Keep sharing", "साझा करते रहें", "ସେୟାର୍ ଜାରି ରଖନ୍ତୁ", "Ituloy ang pagbabahagi", "Padayon pagbahin") // needs native review

    val gone get() = tr(
        lang, "This request is no longer on this phone.", "यह अनुरोध अब इस फ़ोन में नहीं है।", "ଏହି ଅନୁରୋଧ ଆଉ ଏହି ଫୋନରେ ନାହିଁ।",
        "Wala na ang kahilingang ito sa teleponong ito.", "Waray na ini nga hangyo hini nga telepono.",
    ) // needs native review

    // ---- share text
    fun shareHeader(ref: String) = tr(lang, "Relay request $ref", "Relay अनुरोध $ref", "Relay ଅନୁରୋଧ $ref", "Kahilingan sa Relay $ref", "Hangyo ha Relay $ref") // needs native review
    fun shareStatus(status: String) = tr(lang, "Status: $status", "स्थिति: $status", "ସ୍ଥିତି: $status", "Katayuan: $status", "Kahimtang: $status") // needs native review
    fun shareNear(landmark: String) = tr(lang, "Near $landmark", "$landmark के पास", "$landmark ପାଖରେ", "Malapit sa $landmark", "Ha kadul han $landmark") // needs native review
    fun shareMessage(text: String) = tr(lang, "Control room: $text", "कंट्रोल रूम: $text", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍: $text", "Control room: $text", "Control room: $text") // needs native review
    val shareChooser get() = tr(lang, "Share request", "अनुरोध साझा करें", "ଅନୁରୋଧ ଶେୟାର୍ କରନ୍ତୁ", "Ibahagi ang kahilingan", "Ibahin an hangyo") // needs native review
}
