package org.relay.app

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.staticCompositionLocalOf
import org.relay.core.payload.Lang
import java.util.Locale

/** Speech rate for text-to-speech. */
fun VoiceSpeed.rate(): Float = when (this) {
    VoiceSpeed.SLOW -> 0.75f
    VoiceSpeed.NORMAL -> 1.0f
}

/** The TTS locale for an app language. Waray has no common voice, so it usually reports unavailable. */
internal fun Lang.ttsLocale(): Locale = when (this) {
    Lang.EN -> Locale.forLanguageTag("en-IN")
    Lang.HI -> Locale.forLanguageTag("hi-IN")
    Lang.OR -> Locale.forLanguageTag("or-IN")
    Lang.TL -> Locale.forLanguageTag("fil-PH")
    Lang.WAR -> Locale.forLanguageTag("war-PH")
    Lang.CEB -> Locale.forLanguageTag("ceb-PH")
}

/**
 * Thin wrapper over Android [TextToSpeech], created once (RelayRoot) and provided as [LocalSpeaker]. Speaking
 * before the engine has initialised is queued (latest request wins). Output only: recognition is [VoiceInput].
 */
class Speaker(context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var pending: Triple<String, Lang, VoiceSpeed>? = null

    /** Rate used by `speak` when none is given; RelayRoot keeps this equal to the Settings voice speed. */
    @Volatile var speed: VoiceSpeed = VoiceSpeed.NORMAL

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) synchronized(this) { pending?.let { (t, l, s) -> pending = null; speakNow(t, l, s) } }
        }
    }

    /** True when the installed engine has a voice for [lang] (so "Hear it" can work). False until initialised. */
    fun isVoiceAvailable(lang: Lang): Boolean {
        val t = tts ?: return false
        if (!ready) return false
        return try {
            t.isLanguageAvailable(lang.ttsLocale()) >= TextToSpeech.LANG_AVAILABLE
        } catch (_: Exception) {
            false
        }
    }

    /** Speaks [text] in [lang], replacing anything being spoken. Returns false when no voice is available. */
    fun speak(text: String, lang: Lang, speed: VoiceSpeed = this.speed): Boolean {
        if (text.isBlank()) return false
        synchronized(this) {
            if (!ready) { pending = Triple(text, lang, speed); return tts != null }
        }
        return speakNow(text, lang, speed)
    }

    private fun speakNow(text: String, lang: Lang, speed: VoiceSpeed): Boolean {
        val t = tts ?: return false
        return try {
            if (t.setLanguage(lang.ttsLocale()) < TextToSpeech.LANG_AVAILABLE) return false
            t.setSpeechRate(speed.rate())
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "relay-${System.nanoTime()}") == TextToSpeech.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }
}

/** Null in previews and tests; RelayRoot provides the real one. Call sites: `LocalSpeaker.current?.speak(...)`. */
val LocalSpeaker = staticCompositionLocalOf<Speaker?> { null }
