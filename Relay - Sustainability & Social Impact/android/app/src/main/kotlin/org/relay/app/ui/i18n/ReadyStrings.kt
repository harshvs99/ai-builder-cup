package org.relay.app.ui.i18n

import org.relay.core.payload.Lang
import org.relay.core.ready.WarningLevel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Strings of the Ready screens (contract v2 section 3): the Home banner, "Get ready", "My household" and the notification.
 * English and Hindi only: Hindi is a draft for native review, and Odia, Filipino and Waray show English (they are hidden, see
 * [trEnHi]). Backend text (warning headline, plan steps, checklist) arrives already written and is not translated here.
 */
class ReadyStrings(private val lang: Lang) {
    private fun t(en: String, hi: String) = trEnHi(lang, en, hi) // hi: needs native review

    // ---- Home banner
    val getReady get() = t("Get ready", "तैयारी करें")
    fun landfallIn(hours: Int) = t("Landfall in about $hours h", "लगभग $hours घंटे में तूफ़ान तट से टकराएगा")
    fun levelWord(l: WarningLevel) = when (l) {
        WarningLevel.WATCH -> t("Watch", "निगरानी"); WarningLevel.WARNING -> t("Warning", "चेतावनी"); WarningLevel.ALERT -> t("Alert", "अलर्ट")
    }
    fun bannerDescription(level: WarningLevel, headline: String, hours: Int?) =
        "${levelWord(level)}. $headline." + (hours?.let { " ${landfallIn(it)}." } ?: "") + " $getReady"

    // ---- Ready screen
    val readyTitle get() = t("Get ready", "तैयारी करें")
    val warningTitle get() = t("Warning", "चेतावनी")
    fun issued(sec: Long) = t("Issued ${clock(sec)}", "जारी: ${clock(sec)}")
    fun landfallAt(sec: Long) = t("Expected landfall ${clock(sec)}", "तट से टकराने का अनुमान: ${clock(sec)}")
    fun source(source: String) = t("From $source", "स्रोत: $source")
    val yourPlan get() = t("Your plan", "आपकी योजना")
    val hearPlan get() = t("Hear the plan", "योजना सुनें")
    val stopPlan get() = t("Stop", "रुकें")
    val shownInEnglish get() = t("Shown in English", "अंग्रेज़ी में दिखाया गया")
    val noVoicePlan get() = t("No voice is installed for this language on this phone.", "इस फ़ोन में इस भाषा की आवाज़ इंस्टॉल नहीं है।")
    val nearestShelters get() = t("Nearest shelters", "सबसे पास के आश्रय")
    val noShelters get() = t("No verified shelter list for your district yet.", "आपके ज़िले की सत्यापित आश्रय सूची अभी नहीं है।")
    fun kmAway(km: Double) = t("${"%.1f".format(Locale.ROOT, km)} km", "${"%.1f".format(Locale.ROOT, km)} किमी")
    val allShelters get() = t("See all shelters", "सभी आश्रय देखें")
    val checklist get() = t("Checklist", "जाँच-सूची")
    fun updated(sec: Long) = t("Updated ${clock(sec)}", "अपडेट: ${clock(sec)}")
    fun verifiedOn(sec: Long?) = if (sec == null) t("Shelter list verified by the district", "आश्रय सूची ज़िले द्वारा सत्यापित") else
        t("Shelter list verified by the district on ${day(sec)}", "आश्रय सूची ज़िले द्वारा ${day(sec)} को सत्यापित")
    val demoList get() = t("Demo shelter list, not verified", "डेमो आश्रय सूची, सत्यापित नहीं")
    val refresh get() = t("Refresh", "ताज़ा करें")
    val refreshing get() = t("Updating…", "अपडेट हो रहा है…")
    val refreshFailed get() = t("Could not update. Showing the saved plan.", "अपडेट नहीं हो सका। सहेजी हुई योजना दिखाई जा रही है।")
    val noWarningNow get() = t("No warning for your district right now.", "अभी आपके ज़िले के लिए कोई चेतावनी नहीं है।")

    // ---- empty state and district
    val emptyTitle get() = t("Nothing downloaded yet", "अभी कुछ डाउनलोड नहीं हुआ")
    val emptyBody get() = t(
        "Relay downloads a plan when there is a warning and the phone has signal. Open the app when you have signal before a storm.",
        "जब चेतावनी होती है और फ़ोन में सिग्नल होता है, तब Relay एक योजना डाउनलोड करता है। तूफ़ान से पहले सिग्नल होने पर ऐप खोलें।",
    )
    val whichDistrict get() = t("Which district are you in?", "आप किस ज़िले में हैं?")
    fun districtName(id: String) = when (id) {
        "puri" -> t("Puri", "पुरी"); "delhi" -> t("Delhi", "दिल्ली"); else -> id.replaceFirstChar { it.uppercase() }
    }

    // ---- household
    val householdTitle get() = t("My household", "मेरा परिवार")
    val householdHelp get() = t(
        "This stays on your phone. Relay only sends yes or no answers, never names or numbers, to make your plan.",
        "यह आपके फ़ोन पर ही रहता है। योजना बनाने के लिए Relay सिर्फ़ हाँ या नहीं के जवाब भेजता है, नाम या नंबर कभी नहीं।",
    )
    val people get() = t("People in your home", "घर में लोग")
    fun peopleCount(n: Int) = t("$n", "$n")
    val morePeople get() = t("More people", "एक व्यक्ति और जोड़ें")
    val fewerPeople get() = t("Fewer people", "एक व्यक्ति कम करें")
    val elderly get() = t("Elderly person", "बुज़ुर्ग")
    val children get() = t("Children", "बच्चे")
    val infants get() = t("Baby under 1 year", "1 साल से छोटा शिशु")
    val pregnant get() = t("Pregnant woman", "गर्भवती महिला")
    val disability get() = t("Someone who needs help to walk or move", "कोई जिसे चलने में मदद चाहिए")
    val livestock get() = t("Animals to look after", "देखभाल के लिए पशु")
    val medical get() = t("Medicine or care needs", "दवा या देखभाल की ज़रूरत")
    val medicalHint get() = t("e.g. insulin, oxygen", "जैसे इंसुलिन, ऑक्सीजन")
    val saveHousehold get() = t("Save", "सहेजें")
    val savedHousehold get() = t("Saved. Your plan will update.", "सहेजा गया। आपकी योजना अपडेट होगी।")

    // ---- notification (a plain sentence; the district is named by its display name)
    fun notificationTitle(district: String) = t("Cyclone warning for $district", "$district के लिए चक्रवात की चेतावनी")
    val notificationText get() = t("Your plan is ready.", "आपकी योजना तैयार है।")
    val notificationChannel get() = t("Warnings and your plan", "चेतावनी और आपकी योजना")

    // ---- Settings row
    val settingsHousehold get() = t("My household", "मेरा परिवार")
    val settingsHouseholdHelp get() = t("Tell Relay who is at home so your evacuation plan fits.", "Relay को बताएँ कि घर में कौन है, ताकि निकलने की योजना सही बने।")
    val settingsReady get() = t("Get ready", "तैयारी करें")

    private fun clock(sec: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale()).format(Date(sec * 1000))
    private fun day(sec: Long) = DateFormat.getDateInstance(DateFormat.MEDIUM, locale()).format(Date(sec * 1000))
    private fun locale() = if (lang == Lang.HI) Locale.forLanguageTag("hi-IN") else Locale.getDefault()
}
