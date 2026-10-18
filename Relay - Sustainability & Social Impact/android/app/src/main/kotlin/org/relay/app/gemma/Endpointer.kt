package org.relay.app.gemma

/**
 * Decides when a recorded utterance is over, from loudness alone (no recogniser tells us: this is the path for languages
 * the phone cannot recognise). Learns the room's noise floor from the first [CALIBRATE_MS], then calls anything clearly
 * above it speech. Ends [endAfterSpeechMs] after the last speech, gives up on an empty recording after [noSpeechMs], and never
 * runs past [maxMs]. Pure: feed it (level, time) pairs.
 */
class Endpointer(
    private val endAfterSpeechMs: Long,
    private val noSpeechMs: Long = 8_000,
    private val maxMs: Long = Wav.MAX_SECONDS * 1000L - 300,
) {
    enum class Verdict { LISTENING, END_SPEECH, END_NOTHING, END_MAX }

    private var startMs = -1L
    private var floor = 0f
    private var floorN = 0
    private var lastSpeechMs = -1L

    val heardSpeech: Boolean get() = lastSpeechMs >= 0

    fun update(level: Float, nowMs: Long): Verdict {
        if (startMs < 0) startMs = nowMs
        val t = nowMs - startMs
        if (t < CALIBRATE_MS) {
            // A word spoken straight away must not become the "noise": only quiet buffers set the floor.
            if (level < MAX_FLOOR) { floor = (floor * floorN + level) / (floorN + 1); floorN++ }
        } else if (level > threshold()) {
            lastSpeechMs = nowMs
        }
        return when {
            t >= maxMs -> Verdict.END_MAX
            lastSpeechMs >= 0 && nowMs - lastSpeechMs >= endAfterSpeechMs -> Verdict.END_SPEECH
            lastSpeechMs < 0 && t >= noSpeechMs -> Verdict.END_NOTHING
            else -> Verdict.LISTENING
        }
    }

    fun threshold(): Float = maxOf(MIN_THRESHOLD, floor * 3f)

    companion object {
        const val CALIBRATE_MS = 400L
        const val MIN_THRESHOLD = 0.03f
        const val MAX_FLOOR = 0.2f
    }
}
