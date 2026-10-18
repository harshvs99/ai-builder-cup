package org.relay.app.ui.i18n

import org.relay.app.VillagerStatus
import org.relay.app.store.RequestTimeline
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.VulnFlag
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Strings used by more than one screen: navigation, common actions, units, relative time, urgency, categories,
 * vulnerability flags, request status and timeline texts, and the villager status. English is the source text;
 * hi, or, tl and war are drafts: every non-English string here needs native review (// needs native review).
 */
class CommonStrings(private val lang: Lang) {
    val isEnglish get() = lang == Lang.EN
    // ---- navigation
    val navHome get() = tr(lang, "Home", "होम", "ହୋମ୍", "Home", "Balay") // needs native review
    val navRequests get() = tr(lang, "Requests", "अनुरोध", "ଅନୁରୋଧ", "Mga Kahilingan", "Mga Hangyo") // needs native review
    val navNetwork get() = tr(lang, "Network", "नेटवर्क", "ନେଟୱର୍କ", "Network", "Network") // needs native review
    val navSettings get() = tr(lang, "Settings", "सेटिंग्स", "ସେଟିଂସ", "Settings", "Settings") // needs native review

    // ---- common actions
    val change get() = tr(lang, "Change", "बदलें", "ବଦଳାନ୍ତୁ", "Baguhin", "Usba") // needs native review
    val continueAction get() = tr(lang, "Continue", "आगे बढ़ें", "ଜାରି ରଖନ୍ତୁ", "Magpatuloy", "Padayon") // needs native review
    val send get() = tr(lang, "Send", "भेजें", "ପଠାନ୍ତୁ", "Ipadala", "Ipadara") // needs native review
    val cancel get() = tr(lang, "Cancel", "रद्द करें", "ବାତିଲ୍", "Kanselahin", "Kanselaha") // needs native review
    val done get() = tr(lang, "Done", "हो गया", "ହୋଇଗଲା", "Tapos na", "Human na") // needs native review
    val back get() = tr(lang, "Back", "वापस", "ପଛକୁ", "Bumalik", "Balik") // needs native review
    val close get() = tr(lang, "Close", "बंद करें", "ବନ୍ଦ କରନ୍ତୁ", "Isara", "Sirhi") // needs native review
    val seeAll get() = tr(lang, "See all", "सभी देखें", "ସବୁ ଦେଖନ୍ତୁ", "Tingnan lahat", "Kitaa ngatanan") // needs native review
    val turnOn get() = tr(lang, "Turn on", "चालू करें", "ଚାଲୁ କରନ୍ତୁ", "I-on", "Buksi") // needs native review
    val somethingElse get() = tr(lang, "Something else", "कुछ और", "ଅନ୍ୟ କିଛି", "Iba pa", "Iba pa") // needs native review

    // ---- units
    fun people(n: Int): String = when (lang) {
        Lang.HI -> if (Plural.isOne(lang, n)) "$n व्यक्ति" else "$n लोग" // needs native review
        Lang.OR -> "$n ଜଣ" // needs native review
        Lang.TL -> "$n tao" // needs native review
        Lang.WAR -> "$n ka tawo" // needs native review
        else -> if (Plural.isOne(lang, n)) "$n person" else "$n people"
    }

    fun phones(n: Int): String = when (lang) {
        Lang.HI -> "$n फ़ोन" // needs native review
        Lang.OR -> "$n ଫୋନ୍" // needs native review
        Lang.TL -> "$n telepono" // needs native review
        Lang.WAR -> "$n ka telepono" // needs native review
        else -> if (Plural.isOne(lang, n)) "$n phone" else "$n phones"
    }

    fun hops(n: Int): String = when (lang) {
        Lang.HI -> "$n हॉप" // needs native review
        Lang.OR -> "$n ହପ୍" // needs native review
        Lang.TL -> "$n hop" // needs native review
        Lang.WAR -> "$n ka hop" // needs native review
        else -> if (Plural.isOne(lang, n)) "$n hop" else "$n hops"
    }

    // ---- time
    /** "just now", "2 min ago", "3 h ago", "yesterday", "4 days ago". */
    fun ago(thenMs: Long, nowMs: Long): String = when (val b = RelativeTime.bucket(thenMs, nowMs)) {
        RelativeTime.Bucket.JustNow -> tr(lang, "just now", "अभी-अभी", "ବର୍ତ୍ତମାନ", "ngayon lang", "yana la") // needs native review
        is RelativeTime.Bucket.Minutes -> tr(
            lang, "${b.n} min ago", "${b.n} मिनट पहले", "${b.n} ମିନିଟ୍ ପୂର୍ବେ", "${b.n} min ang nakalipas", "${b.n} minuto na an naglabay",
        ) // needs native review
        is RelativeTime.Bucket.Hours -> tr(
            lang, "${b.n} h ago", "${b.n} घंटे पहले", "${b.n} ଘଣ୍ଟା ପୂର୍ବେ", "${b.n} oras ang nakalipas", "${b.n} oras na an naglabay",
        ) // needs native review
        RelativeTime.Bucket.Yesterday -> tr(lang, "yesterday", "कल", "ଗତକାଲି", "kahapon", "kakulop") // needs native review
        is RelativeTime.Bucket.Days -> tr(
            lang, "${b.n} days ago", "${b.n} दिन पहले", "${b.n} ଦିନ ପୂର୍ବେ", "${b.n} araw ang nakalipas", "${b.n} adlaw na an naglabay",
        ) // needs native review
    }

    /** Short clock time in the app language's locale, e.g. "2:02 PM". */
    fun clock(ms: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT, Locale.forLanguageTag(lang.tag)).format(Date(ms))

    // ---- urgency (0 = can wait .. 3 = life at risk)
    fun urgencyWord(u: Int): String = when (u.coerceIn(0, 3)) {
        0 -> tr(lang, "Can wait", "रुक सकता है", "ଅପେକ୍ଷା କରିପାରିବ", "Puwedeng maghintay", "Mahuhulat") // needs native review
        1 -> tr(lang, "Soon", "जल्दी", "ଶୀଘ୍ର", "Maya-maya", "Madali") // needs native review
        2 -> tr(lang, "Urgent", "ज़रूरी", "ଜରୁରୀ", "Madalian", "Pagmadali") // needs native review
        else -> tr(lang, "Life at risk", "जान को ख़तरा", "ଜୀବନ ବିପଦରେ", "Buhay ang nasa panganib", "Ginhadlok an kinabuhi") // needs native review
    }

    /** The helper line under the urgency control, including the urgency word. */
    fun urgencyHelp(u: Int): String = when (u.coerceIn(0, 3)) {
        0 -> tr(
            lang, "Can wait: today or tomorrow is fine.", "रुक सकता है: आज या कल तक चलेगा।", "ଅପେକ୍ଷା କରିପାରିବ: ଆଜି କିମ୍ବା କାଲି ହେଲେ ଚଳିବ।",
            "Puwedeng maghintay: okay na ngayong araw o bukas.", "Mahuhulat: okay na yana nga adlaw o buwas.",
        ) // needs native review
        1 -> tr(
            lang, "Soon: needed today.", "जल्दी: आज चाहिए।", "ଶୀଘ୍ର: ଆଜି ଦରକାର।",
            "Maya-maya: kailangan ngayong araw.", "Madali: kinahanglan yana nga adlaw.",
        ) // needs native review
        2 -> tr(
            lang, "Urgent: needed in the next few hours.", "ज़रूरी: अगले कुछ घंटों में चाहिए।", "ଜରୁରୀ: ଆଗାମୀ କିଛି ଘଣ୍ଟାରେ ଦରକାର।",
            "Madalian: kailangan sa susunod na ilang oras.", "Pagmadali: kinahanglan ha sunod nga pira ka oras.",
        ) // needs native review
        else -> tr(
            lang, "Life at risk: someone may die without help now.", "जान को ख़तरा: अभी मदद न मिली तो किसी की जान जा सकती है।",
            "ଜୀବନ ବିପଦରେ: ଏବେ ସାହାଯ୍ୟ ନମିଳିଲେ କାହାର ମୃତ୍ୟୁ ହୋଇପାରେ।",
            "Buhay ang nasa panganib: maaaring may mamatay kung walang tulong ngayon.",
            "Ginhadlok an kinabuhi: may mamamatay kon waray bulig yana.",
        ) // needs native review
    }

    // ---- categories and vulnerability flags
    fun categoryName(c: Category): String = when (c) {
        Category.WATER -> tr(lang, "Water", "पानी", "ପାଣି", "Tubig", "Tubig")
        Category.FOOD -> tr(lang, "Food", "खाना", "ଖାଦ୍ୟ", "Pagkain", "Pagkaon")
        Category.MEDICAL -> tr(lang, "Medicine", "दवा", "ଔଷଧ", "Gamot", "Tambal")
        Category.RESCUE -> tr(lang, "Rescue", "बचाव", "ଉଦ୍ଧାର", "Pagsagip", "Pagluwas")
        Category.SHELTER -> tr(lang, "Shelter", "आश्रय", "ଆଶ୍ରୟ", "Matutuluyan", "Silungan")
        Category.MISSING_PERSON -> tr(lang, "Missing person", "लापता व्यक्ति", "ନିଖୋଜ ବ୍ୟକ୍ତି", "Nawawalang tao", "Nawawara nga tawo")
        Category.OTHER -> tr(lang, "Other", "अन्य", "ଅନ୍ୟ", "Iba pa", "Iba pa")
    } // needs native review (non-English)

    fun vulnFlag(f: VulnFlag): String = when (f) {
        VulnFlag.ELDERLY -> tr(lang, "elderly", "बुज़ुर्ग", "ବୃଦ୍ଧ", "matatanda", "tigurang")
        VulnFlag.CHILD -> tr(lang, "children", "बच्चे", "ପିଲା", "bata", "bata")
        VulnFlag.PREGNANT -> tr(lang, "pregnant", "गर्भवती", "ଗର୍ଭବତୀ", "buntis", "buntis")
        VulnFlag.INJURED -> tr(lang, "injured", "घायल", "ଆହତ", "sugatan", "nasamdan")
        VulnFlag.TRAPPED -> tr(lang, "trapped", "फँसे हुए", "ଫସିଯାଇଛନ୍ତି", "nakulong", "naipit")
    } // needs native review (non-English)

    /** "children, elderly" for a [VulnFlag] mask; empty when none. */
    fun vulnWords(mask: Int): String = VulnFlag.fromMask(mask).joinToString(", ") { vulnFlag(it) }

    // ---- request status
    val statusClosed get() = tr(lang, "Closed", "बंद", "ବନ୍ଦ", "Sarado", "Sirado") // needs native review
    val statusCancelled get() = tr(lang, "Cancelled", "रद्द", "ବାତିଲ୍", "Kinansela", "Gin-kansela") // needs native review
    val statusActive get() = tr(lang, "Active", "चालू", "ସକ୍ରିୟ", "Aktibo", "Aktibo") // needs native review

    // ---- timeline
    fun stepTitle(key: RequestTimeline.Key): String = when (key) {
        RequestTimeline.Key.SAVED -> tr(lang, "Saved on this phone", "इस फ़ोन में सहेजा गया", "ଏହି ଫୋନରେ ସେଭ୍ ହୋଇଛି", "Naka-save sa teleponong ito", "Gin-save ha ini nga telepono")
        RequestTimeline.Key.PASSED_TO_NEIGHBOUR -> tr(lang, "Passed to a neighbour's phone", "पड़ोसी के फ़ोन तक पहुँचा", "ପଡ଼ୋଶୀର ଫୋନକୁ ପଠାଯାଇଛି", "Naipasa sa telepono ng kapitbahay", "Gin-ipasa ha telepono han kaarabay")
        RequestTimeline.Key.REACHED_GATEWAY -> tr(lang, "Reached a gateway phone", "गेटवे फ़ोन तक पहुँचा", "ଗେଟୱେ ଫୋନରେ ପହଞ୍ଚିଛି", "Nakarating sa gateway phone", "Nakaabot ha gateway nga telepono")
        RequestTimeline.Key.SEEN_BY_CONTROL_ROOM -> tr(lang, "Seen by the control room", "कंट्रोल रूम ने देखा", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍ ଦେଖିଛି", "Nakita ng control room", "Nakita han control room")
        RequestTimeline.Key.HELP_COMING -> tr(lang, "Help is coming", "मदद आ रही है", "ସାହାଯ୍ୟ ଆସୁଛି", "Parating na ang tulong", "An bulig nag-abot na")
        RequestTimeline.Key.TEAM_ON_THE_WAY -> tr(lang, "Team on the way", "टीम रास्ते में है", "ଟିମ୍ ବାଟରେ ଅଛି", "Papunta na ang team", "Pakadto na an team")
        RequestTimeline.Key.DELIVERED -> tr(lang, "Delivered", "पहुँचा दिया गया", "ପହଞ୍ଚାଇ ଦିଆଗଲା", "Naihatid na", "Nahatod na")
    } // needs native review (non-English)

    val liveTag get() = tr(lang, "LIVE", "लाइव", "ଲାଇଭ୍", "LIVE", "LIVE") // needs native review

    private val uploadedNote get() = tr(
        lang, "uploaded to the control room", "कंट्रोल रूम में अपलोड हुआ", "କଣ୍ଟ୍ରୋଲ୍ ରୁମ୍‌କୁ ଅପଲୋଡ୍ ହୋଇଛି",
        "na-upload sa control room", "na-upload ha control room",
    ) // needs native review

    private fun markedUrgency(u: Int): String {
        val w = urgencyWord(u).lowercase()
        return tr(lang, "marked $w", "$w के रूप में चिह्नित", "$w ଭାବରେ ଚିହ୍ନିତ", "minarkahang $w", "gin-marka nga $w")
    } // needs native review

    fun expectedBy(ms: Long): String {
        val t = clock(ms)
        return tr(lang, "Expected by $t", "$t तक पहुँचने की उम्मीद", "$t ସୁଦ୍ଧା ଆସିବାର ଆଶା", "Inaasahan pagsapit ng $t", "Ginpapaabot ha $t")
    } // needs native review

    val runningLate get() = tr(lang, "Later than expected", "अपेक्षा से देर", "ଆଶାଠାରୁ ଡେରି", "Mas matagal kaysa inaasahan", "Mas mahuri kay han ginpaabot") // needs native review

    /** Second line of a timeline step: time, plus the spec's detail ("uploaded to the control room", ...). */
    fun stepSubtitle(step: RequestTimeline.Step): String {
        val parts = ArrayList<String>()
        if (step.atMs > 0) parts += clock(step.atMs)
        when (step.key) {
            RequestTimeline.Key.REACHED_GATEWAY -> parts += uploadedNote
            RequestTimeline.Key.SEEN_BY_CONTROL_ROOM -> step.urgency?.let { parts += markedUrgency(it) }
            RequestTimeline.Key.HELP_COMING, RequestTimeline.Key.TEAM_ON_THE_WAY ->
                if (step.etaAtMs > 0) parts += if (step.etaOverdue) runningLate else expectedBy(step.etaAtMs)
            else -> {}
        }
        return parts.joinToString(" · ")
    }

    /** Line two of a request row: "Reached a gateway phone · 2 min ago", "Delivered yesterday". */
    fun rowStatusLine(step: RequestTimeline.Step, now: Long): String {
        val title = stepTitle(step.key)
        if (step.atMs <= 0) return title
        val ago = ago(step.atMs, now)
        return if (step.key == RequestTimeline.Key.DELIVERED) "$title $ago" else "$title · $ago"
    }

    // ---- villager status (Home and Network)
    fun villagerTitle(s: VillagerStatus): String = when (s.kind) {
        VillagerStatus.Kind.REACHABLE -> tr(lang, "Your messages can reach help", "आपके संदेश मदद तक पहुँच सकते हैं", "ଆପଣଙ୍କ ସନ୍ଦେଶ ସାହାଯ୍ୟ ପାଖରେ ପହଞ୍ଚିପାରିବ", "Maaabot ng mga mensahe mo ang tulong", "Maabot han imo mga mensahe an bulig")
        VillagerStatus.Kind.THIS_PHONE_GATEWAY -> tr(lang, "This phone is sending messages to help", "यह फ़ोन मदद तक संदेश भेज रहा है", "ଏହି ଫୋନ ସାହାଯ୍ୟକୁ ସନ୍ଦେଶ ପଠାଉଛି", "Ang teleponong ito ay nagpapadala ng mensahe sa tulong", "Ini nga telepono nagpapadara hin mensahe ngadto ha bulig")
        VillagerStatus.Kind.WAITING_FOR_GATEWAY -> tr(lang, "Waiting for a phone with signal", "सिग्नल वाले फ़ोन का इंतज़ार", "ସିଗ୍ନାଲ ଥିବା ଫୋନ୍ ପାଇଁ ଅପେକ୍ଷା", "Naghihintay ng telepono na may signal", "Naghuhulat hin telepono nga may signal")
        VillagerStatus.Kind.SEARCHING -> tr(lang, "Looking for nearby phones", "आस-पास के फ़ोन खोज रहे हैं", "ପାଖ ଫୋନ୍ ଖୋଜୁଛି", "Naghahanap ng mga kalapit na telepono", "Naghahanap hin kalapit nga telepono")
        VillagerStatus.Kind.BLUETOOTH_OFF -> tr(lang, "Bluetooth is off", "ब्लूटूथ बंद है", "ବ୍ଲୁଟୁଥ୍ ବନ୍ଦ ଅଛି", "Naka-off ang Bluetooth", "Gin-off an Bluetooth")
    } // needs native review (non-English)

    /** One line under the title. [now] turns "synced X ago" durations into words. */
    fun villagerLine(s: VillagerStatus, now: Long): String = when (s.kind) {
        VillagerStatus.Kind.REACHABLE -> {
            val hops = s.hopsToGateway ?: 1
            val base = tr(
                lang, "Gateway ${hops(hops)} away", "गेटवे ${hops(hops)} दूर", "ଗେଟୱେ ${hops(hops)} ଦୂରରେ",
                "Gateway, ${hops(hops)} ang layo", "Gateway, ${hops(hops)} an kalayo",
            )
            base + agoSuffix(s.syncedAgoMs, now, "online")
        }
        VillagerStatus.Kind.THIS_PHONE_GATEWAY -> {
            val base = tr(lang, "Connected to the internet", "इंटरनेट से जुड़ा है", "ଇଣ୍ଟରନେଟ୍ ସହ ଯୋଡ଼ା ଅଛି", "Nakakonekta sa internet", "Nakakonekta ha internet")
            base + agoSuffix(s.syncedAgoMs, now, "synced")
        }
        VillagerStatus.Kind.WAITING_FOR_GATEWAY -> {
            val safe = tr(lang, "Your messages wait safely.", "आपके संदेश सुरक्षित रखे हैं।", "ଆପଣଙ୍କ ସନ୍ଦେଶ ସୁରକ୍ଷିତ ଅଛି।", "Ligtas na naghihintay ang mga mensahe mo.", "Ligtas nga naghuhulat an imo mga mensahe.")
            if (s.peers > 0) {
                val n = phones(s.peers)
                tr(lang, "Connected to $n nearby. ", "आस-पास $n से जुड़ा है। ", "ପାଖରେ $n ସହ ଯୋଡ଼ା ଅଛି। ", "Nakakonekta sa $n sa malapit. ", "Nakakonekta ha $n nga malapit. ") + safe
            } else safe
        }
        VillagerStatus.Kind.SEARCHING -> tr(
            lang, "Keep Relay open. Messages wait safely on this phone.", "Relay खुला रखें। संदेश इस फ़ोन में सुरक्षित रखे हैं।",
            "Relay ଖୋଲା ରଖନ୍ତୁ। ସନ୍ଦେଶ ଏହି ଫୋନରେ ସୁରକ୍ଷିତ ଅଛି।", "Panatilihing bukas ang Relay. Ligtas na naghihintay ang mga mensahe sa telepono.",
            "Pabuksi an Relay. Ligtas nga naghuhulat an mga mensahe hini nga telepono.",
        )
        VillagerStatus.Kind.BLUETOOTH_OFF -> tr(
            lang, "Turn it on so phones can pass your message", "इसे चालू करें ताकि फ़ोन आपका संदेश आगे पहुँचा सकें",
            "ଏହାକୁ ଚାଲୁ କରନ୍ତୁ, ଯେପରି ଫୋନ୍ ଆପଣଙ୍କ ସନ୍ଦେଶ ପଠାଇପାରିବ", "I-on ito para maipasa ng mga telepono ang mensahe mo",
            "Buksi ini basi maipasa han mga telepono an imo mensahe",
        )
    } // needs native review (non-English)

    private fun agoSuffix(durationMs: Long?, now: Long, word: String): String {
        if (durationMs == null) return ""
        val a = ago(now - durationMs, now)
        val w = when (word) {
            "online" -> tr(lang, "online $a", "$a ऑनलाइन", "$a ଅନଲାଇନ୍", "online $a", "online $a")
            else -> tr(lang, "synced $a", "$a सिंक हुआ", "$a ସିଙ୍କ୍ ହୋଇଛି", "na-sync $a", "na-sync $a")
        }
        return " · $w"
    }

    /** The status word beside the dot: "Reachable", "Waiting", "Searching", "Off". */
    fun villagerWord(s: VillagerStatus): String = when (s.kind) {
        VillagerStatus.Kind.REACHABLE, VillagerStatus.Kind.THIS_PHONE_GATEWAY ->
            tr(lang, "Reachable", "पहुँच में", "ପହଞ୍ଚିହେବ", "Maaabot", "Maabot")
        VillagerStatus.Kind.WAITING_FOR_GATEWAY -> tr(lang, "Waiting", "इंतज़ार", "ଅପେକ୍ଷା", "Naghihintay", "Naghuhulat")
        VillagerStatus.Kind.SEARCHING -> tr(lang, "Searching", "खोज जारी", "ଖୋଜୁଛି", "Naghahanap", "Naghahanap")
        VillagerStatus.Kind.BLUETOOTH_OFF -> tr(lang, "Off", "बंद", "ବନ୍ଦ", "Off", "Off")
    } // needs native review (non-English)
}
