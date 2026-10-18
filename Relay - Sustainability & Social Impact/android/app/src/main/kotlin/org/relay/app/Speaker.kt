package org.relay.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.staticCompositionLocalOf
import org.relay.core.payload.Lang
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    private val main = Handler(Looper.getMainLooper())
    private val whenDone = ConcurrentHashMap<String, () -> Unit>()

    /** Rate used by `speak` when none is given; RelayRoot keeps this equal to the Settings voice speed. */
    @Volatile var speed: VoiceSpeed = VoiceSpeed.NORMAL

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = finished(utteranceId)
            })
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

    private fun speakNow(text: String, lang: Lang, speed: VoiceSpeed, mode: Int = TextToSpeech.QUEUE_FLUSH, done: (() -> Unit)? = null): Boolean {
        val t = tts ?: return false
        return try {
            if (t.setLanguage(lang.ttsLocale()) < TextToSpeech.LANG_AVAILABLE) return false
            t.setSpeechRate(speed.rate())
            val id = "relay-${System.nanoTime()}"
            if (done != null) whenDone[id] = done
            val ok = t.speak(text, mode, null, id) == TextToSpeech.SUCCESS
            if (!ok) whenDone.remove(id)
            ok
        } catch (_: Exception) {
            false
        }
    }

    private fun finished(id: String?) {
        // stop() and QUEUE_FLUSH also report the interrupted utterance as done: callers treat done as "the voice is free".
        val cb = whenDone.remove(id ?: return) ?: return
        main.post { cb() }
    }

    /**
     * Speaks [lines] one after another in [lang], replacing anything being said, and calls [onDone] on the main thread when
     * the last one has finished. Returns false (and never calls [onDone]) when there is no voice for [lang] or the engine is
     * not ready; the caller then carries on without speech.
     */
    fun speakAll(lines: List<String>, lang: Lang, onDone: (() -> Unit)? = null): Boolean {
        val spoken = lines.filter { it.isNotBlank() }
        if (spoken.isEmpty() || !ready) return false
        whenDone.clear() // a new request replaces the old one; its callback must not start the microphone late
        spoken.forEachIndexed { i, line ->
            val last = i == spoken.lastIndex
            val ok = speakNow(
                line, lang, speed, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, if (last) onDone else null,
            )
            if (!ok) return false
        }
        return true
    }

    fun stop() { whenDone.clear(); try { tts?.stop() } catch (_: Exception) {} }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }
}

/** Null in previews and tests; RelayRoot provides the real one. Call sites: `LocalSpeaker.current?.speak(...)`. */
val LocalSpeaker = staticCompositionLocalOf<Speaker?> { null }
