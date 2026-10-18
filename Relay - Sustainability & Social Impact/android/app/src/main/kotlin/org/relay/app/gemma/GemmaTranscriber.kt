package org.relay.app.gemma

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Speech to text through Gemma's audio input, for a language the phone's recogniser cannot do. One utterance (up to
 * [Wav.MAX_SECONDS]): records until [Endpointer] says the person stopped, then asks the model for the transcript. Results come
 * back on the main thread. The model is loaded by [GemmaRunner] on first use; a phone that cannot hold it never gets here
 * (see `SpeechLogic.useGemmaForSpeech`).
 */
class GemmaTranscriber(private val runner: GemmaRunner) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var token = 0
    private var recorder: Recorder? = null
    private var endpointer: Endpointer? = null
    @Volatile private var finishing = false

    /**
     * @param onLevel loudness 0..1 for the meter, main thread
     * @param onResult the transcript, or null when nothing usable was heard / the model failed ([error] says why), main thread
     */
    fun start(langHint: String, endAfterSpeechMs: Long, onLevel: (Float) -> Unit, onResult: (text: String?, error: String?) -> Unit) {
        cancel()
        val my = ++token
        finishing = false
        val ep = Endpointer(endAfterSpeechMs)
        endpointer = ep
        val rec = Recorder(onAutoStop = { finish(my, langHint, onResult) }, onLevel = { lvl ->
            if (my == token && !finishing) {
                main.post { if (my == token) onLevel(lvl) }
                if (ep.update(lvl, SystemClock.elapsedRealtime()) != Endpointer.Verdict.LISTENING) finish(my, langHint, onResult)
            }
        })
        recorder = rec
        try { rec.start() } catch (t: Throwable) {
            recorder = null
            main.post { if (my == token) onResult(null, "microphone: ${t.message}") }
        }
    }

    /** The person pressed stop: transcribe what was recorded. */
    fun stopNow(langHint: String, onResult: (String?, String?) -> Unit) { if (recorder != null) finish(token, langHint, onResult) }

    fun cancel() {
        token++
        finishing = false
        val r = recorder
        recorder = null
        if (r != null) Thread { try { r.stop() } catch (_: Throwable) {} }.start()
    }

    private fun finish(my: Int, langHint: String, onResult: (String?, String?) -> Unit) {
        synchronized(this) {
            if (my != token || finishing) return
            finishing = true
        }
        val ep = endpointer
        Thread {
            val pcm = try { recorder?.stop() } catch (_: Throwable) { null }
            recorder = null
            if (my != token) return@Thread
            if (pcm == null || pcm.isEmpty() || ep?.heardSpeech == false) {
                main.post { if (my == token) onResult(null, null) }
                return@Thread
            }
            val run = runner.runAudio(Wav.pcmToWav(pcm), langHint)
            val text = when {
                run.error != null -> null
                run.parsed.jsonFound && run.parsed.transcript.isNotBlank() -> run.parsed.transcript.trim()
                !run.parsed.jsonFound -> run.raw.trim().takeIf { it.isNotEmpty() && !it.startsWith("{") }
                else -> null
            }
            main.post { if (my == token) onResult(text, run.error) }
        }.start()
    }
}
