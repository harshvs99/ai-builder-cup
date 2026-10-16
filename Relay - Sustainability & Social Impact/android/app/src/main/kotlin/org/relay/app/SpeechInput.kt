package org.relay.app

import android.content.Context
import android.content.Intent
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
 */
class VoiceInput(context: Context, private val onText: (String) -> Unit) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> get() = _state

    private var recognizer: SpeechRecognizer? = null
    private var lang: Lang = Lang.EN
    private var retried = false
    private var offlineAttempt = false
    private var lastLevelAt = 0L
    private var generation = 0

    fun start(lang: Lang) {
        cancel()
        this.lang = lang
        retried = false
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            MeshRepository.log("voice: no speech recogniser service on this device")
            _state.value = VoiceState.Problem("No speech recogniser on this phone — type your request instead.")
            return
        }
        attempt(SpeechLogic.preferOffline(isOnline(ctx)))
    }

    /** Stop recording and deliver what was heard so far. */
    fun stop() { recognizer?.stopListening() }

    /** Abort without a result and return to idle. */
    fun cancel() {
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
        _state.value = VoiceState.Listening(offline = offline, note = SpeechLogic.fallbackNote(lang))
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
                if (t.isNotBlank()) _state.value = cur.copy(partial = t)
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
                MeshRepository.log("voice: result ${if (t == null) "empty" else "\"$t\""}")
                release()
                if (t == null) {
                    _state.value = VoiceState.Problem("Didn't catch that — tap the mic and try again, or type your request.")
                } else {
                    _state.value = VoiceState.Idle
                    onText(t)
                }
            }

            override fun onError(error: Int) {
                if (!live()) return
                val online = isOnline(ctx)
                val d = SpeechLogic.onError(error, lang, offlineAttempt, online, retried)
                MeshRepository.log("voice: error $error offlineAttempt=$offlineAttempt online=$online -> ${d.kind}")
                release()
                when (d.kind) {
                    SpeechLogic.Kind.RETRY_ONLINE -> {
                        retried = true
                        // Post: the recogniser must not be re-created from inside its own callback.
                        main.post { if (gen == generation) attempt(false) }
                    }
                    SpeechLogic.Kind.PERMISSION -> _state.value = VoiceState.Problem(d.message.orEmpty(), offerAppSettings = true)
                    else -> _state.value = VoiceState.Problem(d.message.orEmpty(), offerVoiceSettings = d.offerVoiceSettings)
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
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

    companion object {
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
