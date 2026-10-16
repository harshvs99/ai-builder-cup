package org.relay.core.payload

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** UTF-8 helpers that never split a code point. */
object Utf8 {
    private fun cpBytes(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /**
     * UTF-8 bytes of [s], at most [maxBytes] long, cut on a code point boundary. A lone surrogate
     * is encoded as U+FFFD (3 bytes) so the result is always valid UTF-8.
     */
    fun truncateToBytes(s: String, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(s.length * 3, maxOf(maxBytes, 0)))
        var written = 0
        var i = 0
        while (i < s.length) {
            var cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0xD800..0xDFFF) cp = 0xFFFD
            val n = cpBytes(cp)
            if (written + n > maxBytes) break
            when (n) {
                1 -> out.write(cp)
                2 -> { out.write(0xC0 or (cp shr 6)); out.write(0x80 or (cp and 0x3F)) }
                3 -> {
                    out.write(0xE0 or (cp shr 12)); out.write(0x80 or ((cp shr 6) and 0x3F))
                    out.write(0x80 or (cp and 0x3F))
                }
                else -> {
                    out.write(0xF0 or (cp shr 18)); out.write(0x80 or ((cp shr 12) and 0x3F))
                    out.write(0x80 or ((cp shr 6) and 0x3F)); out.write(0x80 or (cp and 0x3F))
                }
            }
            written += n
        }
        return out.toByteArray()
    }

    /** [s] truncated so its UTF-8 encoding is at most [maxBytes], on a code point boundary. */
    fun truncate(s: String, maxBytes: Int): String =
        String(truncateToBytes(s, maxBytes), StandardCharsets.UTF_8)

    /** Strict decode: null on any malformed sequence. */
    fun decodeStrict(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String? {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
