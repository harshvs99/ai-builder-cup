package org.relay.app

import org.relay.core.payload.Lang

/**
 * Pure decision logic for voice input: which recogniser flags to use and what to do with an error code.
 * Error codes are the `SpeechRecognizer.ERROR_*` values, duplicated here so this stays JVM-testable.
 */
object SpeechLogic {
    const val ERROR_NETWORK_TIMEOUT = 1
    const val ERROR_NETWORK = 2
    const val ERROR_AUDIO = 3
    const val ERROR_SERVER = 4
    const val ERROR_CLIENT = 5
    const val ERROR_SPEECH_TIMEOUT = 6
    const val ERROR_NO_MATCH = 7
    const val ERROR_RECOGNIZER_BUSY = 8
    const val ERROR_INSUFFICIENT_PERMISSIONS = 9
    const val ERROR_TOO_MANY_REQUESTS = 10
    const val ERROR_SERVER_DISCONNECTED = 11
    const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    const val ERROR_LANGUAGE_UNAVAILABLE = 13
    const val ERROR_CANNOT_CHECK_SUPPORT = 14
    const val ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15

    /** Recogniser language tag for each Relay language; Waray and Cebuano fall back to Filipino. */
    fun recognizerTag(lang: Lang): String = when (lang) {
        Lang.EN -> "en-IN"
        Lang.HI -> "hi-IN"
        Lang.OR -> "or-IN"
        Lang.TL, Lang.WAR, Lang.CEB -> "fil-PH"
    }

    fun languageName(lang: Lang): String = when (lang) {
        Lang.EN -> "English"
        Lang.HI -> "Hindi"
        Lang.OR -> "Odia"
        Lang.TL -> "Tagalog"
        Lang.WAR -> "Waray"
        Lang.CEB -> "Cebuano"
    }

    /** Note shown while listening when the recogniser is not using the language itself. */
    fun fallbackNote(lang: Lang): String? = when (lang) {
        Lang.WAR, Lang.CEB -> "${languageName(lang)} voice uses the Filipino recogniser (fallback) — check the text before sending."
        else -> null
    }

    /** Offline mode is requested only when there is no validated internet; online it makes Google demand a pack. */
    fun preferOffline(online: Boolean): Boolean = !online

    enum class Kind { RETRY_ONLINE, LANGUAGE_UNAVAILABLE, NO_SPEECH, PERMISSION, BUSY, FAILED }

    data class Decision(val kind: Kind, val message: String? = null, val offerVoiceSettings: Boolean = false)

    private fun isLanguageOrNetworkError(code: Int) = code in setOf(
        ERROR_NETWORK_TIMEOUT, ERROR_NETWORK, ERROR_SERVER, ERROR_SERVER_DISCONNECTED,
        ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE, ERROR_CANNOT_CHECK_SUPPORT,
        ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
    )

    fun unavailableMessage(lang: Lang, offlineAttempt: Boolean): String {
        val n = languageName(lang)
        return if (offlineAttempt) {
            "Voice for $n isn't available on this phone offline — type your request, or download the $n voice pack (needs internet)."
        } else {
            "Voice for $n isn't available right now — type your request, or download the $n voice pack (needs internet)."
        }
    }

    /**
     * What to do after the recogniser reported [code].
     * @param offlineAttempt the failed attempt had EXTRA_PREFER_OFFLINE set
     * @param online the phone currently has validated internet
     * @param alreadyRetried an online retry was already made for this utterance
     */
    fun onError(code: Int, lang: Lang, offlineAttempt: Boolean, online: Boolean, alreadyRetried: Boolean): Decision = when {
        code == ERROR_NO_MATCH || code == ERROR_SPEECH_TIMEOUT ->
            Decision(Kind.NO_SPEECH, "Didn't catch that — tap the mic and try again, or type your request.")
        code == ERROR_INSUFFICIENT_PERMISSIONS ->
            Decision(Kind.PERMISSION, "Microphone permission is needed for voice. Type your request, or allow the microphone in App settings.")
        code == ERROR_RECOGNIZER_BUSY || code == ERROR_TOO_MANY_REQUESTS ->
            Decision(Kind.BUSY, "Voice recogniser is busy — try again in a moment, or type your request.")
        offlineAttempt && online && !alreadyRetried && isLanguageOrNetworkError(code) ->
            Decision(Kind.RETRY_ONLINE)
        isLanguageOrNetworkError(code) ->
            Decision(Kind.LANGUAGE_UNAVAILABLE, unavailableMessage(lang, offlineAttempt), offerVoiceSettings = true)
        code == ERROR_AUDIO -> Decision(Kind.FAILED, "Couldn't use the microphone. Type your request instead.")
        else -> Decision(Kind.FAILED, "Voice input failed (error $code). Type your request instead.")
    }
}
