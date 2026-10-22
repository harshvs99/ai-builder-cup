package org.relay.app.ui.ready

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays the stored spoken plan: a WAV of PCM16 mono (the backend's 24 kHz), read from app files, so it works offline.
 * Returns false from [play] when the file is not a WAV this player understands; the caller then speaks the steps with
 * on-device speech instead.
 */
class PlanPlayer {
    private var track: AudioTrack? = null

    /** Starts playback; [onDone] runs when it ends on its own (not after [stop]). */
    fun play(file: File, onDone: () -> Unit): Boolean {
        stop()
        val wav = try { parse(file.readBytes()) } catch (e: Exception) { null } ?: return false
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(wav.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(wav.pcm.size).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(wav.pcm, 0, wav.pcm.size)
            t.setNotificationMarkerPosition(wav.pcm.size / 2)
            t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(track: AudioTrack?) { if (this@PlanPlayer.track === t) { stop(); onDone() } }
                override fun onPeriodicNotification(track: AudioTrack?) {}
            })
            track = t
            t.play()
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        try { t.stop() } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
    }

    class Wav(val rate: Int, val pcm: ByteArray)

    companion object {
        /** Reads a RIFF/WAVE file of 16-bit mono PCM; null for anything else. */
        fun parse(b: ByteArray): Wav? {
            if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF" || String(b, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 12
            var rate = 0
            var channels = 0
            var bits = 0
            while (pos + 8 <= b.size) {
                val id = String(b, pos, 4, Charsets.US_ASCII)
                val size = buf.getInt(pos + 4)
                val body = pos + 8
                if (id == "fmt " && body + 16 <= b.size) {
                    if (buf.getShort(body).toInt() != 1) return null // PCM only
                    channels = buf.getShort(body + 2).toInt(); rate = buf.getInt(body + 4); bits = buf.getShort(body + 14).toInt()
                } else if (id == "data") {
                    if (rate <= 0 || channels != 1 || bits != 16) return null
                    val len = if (size < 0 || body + size > b.size) b.size - body else size
                    return if (len > 0) Wav(rate, b.copyOfRange(body, body + len)) else null
                }
                if (size < 0) return null
                pos = body + size + (size and 1)
            }
            return null
        }
    }
}
