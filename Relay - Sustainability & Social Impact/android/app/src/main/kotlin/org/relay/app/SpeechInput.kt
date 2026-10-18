package org.relay.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.relay.app.gemma.GemmaRunner
import org.relay.app.gemma.GemmaTranscriber
import org.relay.core.payload.Lang

/** What the voice panel shows. */
sealed interface VoiceState {
    data object Idle : VoiceState

    /** [level] is 0..1 mic loudness; [note] is an optional fallback-language hint. */
    data class Listening(val partial: String = "", val level: Float = 0f, val offline: Boolean = false, val note: String? = null) : VoiceState

    data class Problem(val text: String, val offerVoiceSettings: Boolean = false, val offerAppSettings: Boolean = false) : VoiceState
}

/**
 * Speech-to-text through the [SpeechRecognizer] API with our own UI (no dead-end system dialog) so real error
 * codes reach [SpeechLogic]. Offline mode is requested only without internet; an offline failure while online is
 * retried once online. Main-thread only.
 *
 * Dictation is continuous: the system recogniser ends an utterance after a second or two of quiet (and Google's offline
 * one ignores the silence extras), which cut people off mid-thought. A segment that ends while the person has not
 * pressed stop is kept and listening restarts, until [SpeechLogic.nextStep] says they are really done. [onText] gets the
 * whole utterance once.
 */
class VoiceInput(
    context: Context,
    /** How long a quiet spell after speech ends the utterance: long for a story, short for a yes/no answer. */
    private val endAfterSpeechMs: Long = SpeechLogic.END_AFTER_SPEECH_MS,
    private val onText: (String) -> Unit,
) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> get() = _state

    private var recognizer: SpeechRecognizer? = null
    private val gemmaRunner by lazy { GemmaRunner.get(ctx) }
    private val gemma by lazy { GemmaTranscriber(gemmaRunner) }
    private var gemmaActive = false
    private var lang: Lang = Lang.EN
    private var retried = false
    private var offlineAttempt = false
    private var lastLevelAt = 0L
    private var generation = 0

    // Continuous dictation: finished segments of this utterance, and the clocks SpeechLogic.nextStep decides on.
    private var committed = ""
    private var userStopped = false
    private var startedAt = 0L
    private var lastSpeechAt = 0L
    private var lastPartial = ""

    // The recogniser plays a start beep on every (re)start. Restarts are ours, not the person's, so they are silenced by
    // muting the streams the beep can use for a moment; the first start keeps its beep as the cue to speak.
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mutedStreams = ArrayList<Int>()
    private val unmute = Runnable { restoreSounds() }

    private fun muteBeep() {
        val am = audio ?: return
        for (st in intArrayOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                if (!am.isStreamMute(st)) { am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0); mutedStreams += st }
            } catch (_: Exception) { /* e.g. Do Not Disturb forbids touching the stream: the beep just plays */ }
        }
        main.removeCallbacks(unmute)
        main.postDelayed(unmute, BEEP_MUTE_MS) // never stay muted, whatever else happens
    }

    private fun restoreSounds() {
        main.removeCallbacks(unmute)
        val am = audio
        for (st in mutedStreams) try { am?.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        mutedStreams.clear()
    }

    // Ends the utterance exactly [endAfterSpeechMs] after the last word, instead of whenever the recogniser next errors.
    private val watchdog = object : Runnable {
        override fun run() {
            if (_state.value !is VoiceState.Listening) return
            val now = System.currentTimeMillis()
            val heard = committed.isNotBlank() || lastPartial.isNotBlank()
            val quietFor = now - lastSpeechAt
            if (!userStopped && ((heard && quietFor >= endAfterSpeechMs) || (!heard && now - startedAt >= SpeechLogic.WAIT_FOR_FIRST_WORD_MS))) stop()
            else main.postDelayed(this, 400)
        }
    }

    fun start(lang: Lang) {
        cancel()
        this.lang = lang
        retried = false
        committed = ""
        userStopped = false
        startedAt = System.currentTimeMillis()
        lastSpeechAt = startedAt
        lastPartial = ""
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 400)
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            MeshRepository.log("voice: no speech recogniser service on this device")
            _state.value = VoiceState.Problem("No speech recogniser on this phone — type your request instead.")
            return
        }
        attempt(SpeechLogic.preferOffline(isOnline(ctx)))
    }

    /** Stop recording and deliver what was heard so far. */
    fun stop() {
        if (gemmaActive) { gemma.stopNow(SpeechLogic.recognizerTag(lang)) { text, err -> gemmaDone(text, err, generation) }; return }
        userStopped = true
        val r = recognizer
        if (r != null) r.stopListening()
        else if (_state.value is VoiceState.Listening) finishUtterance() // between two segments: nothing to wait for
    }

    /** Abort without a result and return to idle. */
    fun cancel() {
        main.removeCallbacks(watchdog)
        restoreSounds()
        if (gemmaActive) { gemmaActive = false; gemma.cancel() }
        generation++
        release()
        _state.value = VoiceState.Idle
    }

    fun dismiss() { _state.value = VoiceState.Idle }

    /** Show a problem that happened before recording could start (e.g. the microphone permission was denied). */
    fun fail(text: String, offerAppSettings: Boolean = false) {
        cancel()
        _state.value = VoiceState.Problem(text, offerAppSettings = offerAppSettings)
    }

    fun destroy() { cancel() }

    private fun release() {
        recognizer?.let { r -> try { r.cancel() } catch (_: Exception) {}; try { r.destroy() } catch (_: Exception) {} }
        recognizer = null
    }

    private fun attempt(offline: Boolean) {
        release()
        offlineAttempt = offline
        val gen = ++generation
        val tag = SpeechLogic.recognizerTag(lang)
        MeshRepository.log("voice: start lang=$tag preferOffline=$offline online=${isOnline(ctx)} retried=$retried")
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (e: Exception) {
            MeshRepository.log("voice: createSpeechRecognizer failed: $e")
            _state.value = VoiceState.Problem("Voice input couldn't start — type your request instead.")
            return
        }
        recognizer = r
        _state.value = VoiceState.Listening(partial = committed, offline = offline, note = SpeechLogic.fallbackNote(lang))
        r.setRecognitionListener(object : RecognitionListener {
            private fun live() = gen == generation
            override fun onReadyForSpeech(params: Bundle?) { if (live()) MeshRepository.log("voice: ready for speech") }
            override fun onBeginningOfSpeech() {}
            override fun onEndOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                val cur = _state.value as? VoiceState.Listening ?: return
                if (!live()) return
                val now = System.currentTimeMillis()
                if (now - lastLevelAt < 80) return
                lastLevelAt = now
                _state.value = cur.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!live()) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val cur = _state.value as? VoiceState.Listening ?: return
                if (t.isNotBlank()) {
                    if (t != lastPartial) { lastPartial = t; lastSpeechAt = System.currentTimeMillis() }
                    _state.value = cur.copy(partial = SpeechLogic.join(committed, t))
                }
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                MeshRepository.log("voice: segment ${if (t == null) "empty" else "\"$t\""}")
                release()
                if (t != null) { committed = SpeechLogic.join(committed, t); lastPartial = ""; lastSpeechAt = System.currentTimeMillis() }
                afterSegment(gen)
            }

            override fun onError(error: Int) {
                if (!live()) return
                val online = isOnline(ctx)
                val d = SpeechLogic.onError(error, lang, offlineAttempt, online, retried)
                MeshRepository.log("voice: error $error offlineAttempt=$offlineAttempt online=$online -> ${d.kind}")
                release()
                when (d.kind) {
                    SpeechLogic.Kind.NO_SPEECH -> afterSegment(gen)
                    SpeechLogic.Kind.RETRY_ONLINE -> {
                        retried = true
                        // Post: the recogniser must not be re-created from inside its own callback.
                        main.post { if (gen == generation) attempt(false) }
                    }
                    SpeechLogic.Kind.PERMISSION -> _state.value = VoiceState.Problem(d.message.orEmpty(), offerAppSettings = true)
                    else -> if (SpeechLogic.useGemmaForSpeech(d.kind, gemmaUsable()) && startGemma(gen)) Unit
                    else _state.value = VoiceState.Problem(d.message.orEmpty(), offerVoiceSettings = d.offerVoiceSettings)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Honoured by some recognisers only; the restart loop in afterSegment is what actually allows pauses.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SpeechLogic.COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SpeechLogic.POSSIBLY_COMPLETE_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SpeechLogic.MINIMUM_LENGTH_MS)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { r.startListening(intent) } catch (e: Exception) {
            MeshRepository.log("voice: startListening failed: $e")
            release()
            _state.value = VoiceState.Problem("Voice input couldn't start — type your request instead.")
        }
    }

    /** The phone can hold the model now (Android 12+, file present, RAM): the recogniser's language gap can be filled by it. */
    private fun gemmaUsable(): Boolean = try { gemmaRunner.supported && gemmaRunner.blockedReason() == null } catch (_: Throwable) { false }

    /** The platform recogniser has no model for [lang]: listen with Gemma's audio input instead (one utterance of up to 15 s). */
    private fun startGemma(gen: Int): Boolean {
        if (gen != generation) return false
        MeshRepository.log("voice: no platform recogniser for ${lang.tag}; listening with Gemma audio")
        gemmaActive = true
        _state.value = VoiceState.Listening(offline = true, note = SpeechLogic.gemmaNote(lang))
        gemma.start(
            SpeechLogic.recognizerTag(lang), endAfterSpeechMs,
            onLevel = { lvl -> (_state.value as? VoiceState.Listening)?.let { _state.value = it.copy(level = lvl.coerceIn(0f, 1f)) } },
            onResult = { text, err -> gemmaDone(text, err, gen) },
        )
        return true
    }

    private fun gemmaDone(text: String?, err: String?, gen: Int) {
        if (!gemmaActive || gen != generation) return
        gemmaActive = false
        MeshRepository.log("voice: gemma ${if (text == null) "empty ${err.orEmpty()}" else "\"$text\""}")
        if (text.isNullOrBlank()) _state.value = VoiceState.Problem("Didn't catch that — tap the mic and try again, or type your request.")
        else { _state.value = VoiceState.Idle; onText(text) }
    }

    /** A segment ended (result, or silence with nothing heard): keep listening, deliver, or report "didn't catch that". */
    private fun afterSegment(gen: Int) {
        // The segment died without a final result (error after words were heard): its last partial is still what was said.
        if (lastPartial.isNotBlank()) { committed = SpeechLogic.join(committed, lastPartial); lastPartial = "" }
        val now = System.currentTimeMillis()
        when (SpeechLogic.nextStep(committed.isNotBlank(), userStopped, now - lastSpeechAt, now - startedAt, endAfterSpeechMs)) {
            SpeechLogic.Step.LISTEN_AGAIN -> {
                muteBeep()
                // Post: the recogniser must not be re-created from inside its own callback; a short gap lets it free up.
                main.postDelayed({ if (gen == generation) attempt(offlineAttempt) }, SpeechLogic.RESTART_GAP_MS)
            }
            SpeechLogic.Step.DELIVER -> finishUtterance()
            SpeechLogic.Step.NOTHING_HEARD ->
                _state.value = VoiceState.Problem("Didn't catch that — tap the mic and try again, or type your request.")
        }
    }

    private fun finishUtterance() {
        main.removeCallbacks(watchdog)
        restoreSounds()
        val text = committed.trim()
        generation++
        release()
        MeshRepository.log("voice: result \"$text\"")
        if (text.isEmpty()) _state.value = VoiceState.Problem("Didn't catch that — tap the mic and try again, or type your request.")
        else { _state.value = VoiceState.Idle; onText(text) }
    }

    companion object {
        /** How long the beep streams stay muted around a restart. */
        private const val BEEP_MUTE_MS = 1_000L

        /** True when the active network has validated internet. */
        fun isOnline(ctx: Context): Boolean {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null } ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        /** Opens the most specific voice settings screen that exists: system voice input, then the Google app's page. */
        fun openVoiceSettings(ctx: Context) {
            val candidates = listOf(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", "com.google.android.googlequicksearchbox", null)),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
            )
            for (i in candidates) {
                try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
            }
        }
    }
}
