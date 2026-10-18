package org.relay.app.gemma

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono PCM16 <-> canonical 44-byte-header WAV (the form Google's Gallery passes to Content.AudioBytes). */
object Wav {
    const val SAMPLE_RATE = 16000
    const val MAX_SECONDS = 15

    fun pcmToWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE, channels: Int = 1): ByteArray {
        val bb = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        bb.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return bb.array()
    }

    class Info(val sampleRate: Int, val channels: Int, val bits: Int, val pcm: ByteArray) {
        val seconds: Double get() = pcm.size.toDouble() / (sampleRate * channels * (bits / 8))
    }

    /** Walks RIFF chunks (macOS afconvert inserts a FLLR pad chunk), so we never assume a 44-byte header. */
    fun parse(wav: ByteArray): Info {
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        require(wav.size > 12 && String(wav, 0, 4) == "RIFF" && String(wav, 8, 4) == "WAVE") { "not a RIFF/WAVE file" }
        var pos = 12
        var sr = 0; var ch = 0; var bits = 0
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4)
            val size = bb.getInt(pos + 4).let { if (it < 0) wav.size - pos - 8 else it }
            val body = pos + 8
            if (id == "fmt ") {
                require(bb.getShort(body).toInt() == 1) { "WAV is not PCM" }
                ch = bb.getShort(body + 2).toInt(); sr = bb.getInt(body + 4); bits = bb.getShort(body + 14).toInt()
            } else if (id == "data") {
                val end = minOf(wav.size, body + size)
                return Info(sr, ch, bits, wav.copyOfRange(body, end))
            }
            pos = body + size + (size and 1)
        }
        error("no data chunk")
    }

    /** Canonical WAV bytes for the model; also reports whether the input was 16 kHz mono 16-bit. */
    fun normalize(wav: ByteArray): Pair<ByteArray, Info> {
        val info = parse(wav)
        var pcm = info.pcm
        val maxBytes = MAX_SECONDS * info.sampleRate * info.channels * (info.bits / 8)
        if (pcm.size > maxBytes) pcm = pcm.copyOf(maxBytes)
        return pcmToWav(pcm, info.sampleRate, info.channels) to info
    }
}
