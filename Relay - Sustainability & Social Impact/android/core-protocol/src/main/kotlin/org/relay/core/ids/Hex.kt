package org.relay.core.ids

import java.security.SecureRandom

/** Lowercase hex helpers. */
object Hex {
    private val DIGITS = "0123456789abcdef".toCharArray()

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = DIGITS[v ushr 4]
            out[i * 2 + 1] = DIGITS[v and 0x0F]
        }
        return String(out)
    }

    /** Returns null for odd length or non-hex characters. */
    fun decodeOrNull(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    fun decode(hex: String): ByteArray =
        decodeOrNull(hex) ?: throw IllegalArgumentException("invalid hex: $hex")
}

/** 8-byte identifiers (node ids, msg ids, ack ids). */
object NodeId {
    const val SIZE = 8
    private val rng: SecureRandom by lazy { SecureRandom() }

    fun random(): ByteArray = ByteArray(SIZE).also { rng.nextBytes(it) }

    fun toHex(id: ByteArray): String = Hex.encode(id)

    fun fromHex(hex: String): ByteArray {
        val b = Hex.decode(hex)
        require(b.size == SIZE) { "node id must be 8 bytes, got ${b.size}" }
        return b
    }

    /** Unsigned big-endian compare of two 8-byte ids. */
    fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** Short display form, e.g. "0011…". */
    fun short(id: ByteArray): String = Hex.encode(id.copyOf(minOf(2, id.size))) + "…"
}
