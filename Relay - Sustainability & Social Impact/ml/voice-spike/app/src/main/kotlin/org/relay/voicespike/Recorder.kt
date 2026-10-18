package org.relay.voicespike

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/** Hold-to-record: 16 kHz mono PCM16 via AudioRecord, auto-stops at [Wav.MAX_SECONDS]. */
class Recorder(private val onAutoStop: () -> Unit) {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val out = ByteArrayOutputStream()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2,
        )
        check(r.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        out.reset(); rec = r; running = true
        r.startRecording()
        val limit = Wav.MAX_SECONDS * Wav.SAMPLE_RATE * 2
        thread = Thread {
            val buf = ByteArray(4096)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(out) { out.write(buf, 0, n) }
                if (synchronized(out) { out.size() } >= limit) { running = false; onAutoStop(); break }
            }
        }.also { it.start() }
    }

    /** Stops and returns the PCM captured (capped at the max duration). */
    fun stop(): ByteArray {
        running = false
        try { thread?.join(500) } catch (_: InterruptedException) {}
        try { rec?.stop() } catch (_: Throwable) {}
        rec?.release(); rec = null
        val all = synchronized(out) { out.toByteArray() }
        return all.copyOf(minOf(all.size, Wav.MAX_SECONDS * Wav.SAMPLE_RATE * 2))
    }
}
