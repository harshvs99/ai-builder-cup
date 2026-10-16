package org.relay.core.frame

import java.nio.ByteBuffer

/** Encoder/decoder for the bitchat-v1-compatible outer frame (SPEC section 3). */
object FrameCodec {
    const val HEADER_SIZE = 14
    const val ID_SIZE = 8
    const val SIGNATURE_SIZE = 64
    /** Smallest valid frame: header + sender id, empty payload. */
    const val MIN_SIZE = HEADER_SIZE + ID_SIZE

    const val FLAG_HAS_RECIPIENT = 0x01
    const val FLAG_HAS_SIGNATURE = 0x02
    const val FLAG_IS_COMPRESSED = 0x04
    const val FLAG_HAS_ROUTE = 0x08
    const val FLAG_IS_RSR = 0x10

    private const val TTL_OFFSET = 2
    private const val TS_OFFSET = 3

    /**
     * Encode per the Relay encoder rules: version 1, no padding, compressed/route/RSR flags 0.
     * Throws [IllegalArgumentException] for frames that cannot be represented (programmer error).
     */
    fun encode(frame: Frame): ByteArray {
        require(frame.version == 1) { "only version 1 is supported" }
        require(frame.type in 0..255) { "type out of range" }
        require(frame.ttl in 0..255) { "ttl out of range" }
        require(frame.senderId.size == ID_SIZE) { "sender_id must be 8 bytes" }
        val rec = frame.recipientId
        require(rec == null || rec.size == ID_SIZE) { "recipient_id must be 8 bytes" }
        val sig = frame.signature
        require(sig == null || sig.size == SIGNATURE_SIZE) { "signature must be 64 bytes" }
        require(frame.payload.size <= 0xFFFF) { "payload too large" }

        val flags = (if (rec != null) FLAG_HAS_RECIPIENT else 0) or (if (sig != null) FLAG_HAS_SIGNATURE else 0)
        val total = MIN_SIZE + (if (rec != null) ID_SIZE else 0) + frame.payload.size + (sig?.size ?: 0)
        val buf = ByteBuffer.allocate(total) // big-endian by default
        buf.put(1)
        buf.put(frame.type.toByte())
        buf.put(frame.ttl.toByte())
        buf.putLong(frame.timestampMs)
        buf.put(flags.toByte())
        buf.putShort(frame.payload.size.toShort())
        buf.put(frame.senderId)
        if (rec != null) buf.put(rec)
        buf.put(frame.payload)
        if (sig != null) buf.put(sig)
        return buf.array()
    }

    /**
     * Decode per the Relay decoder rules. Returns null (never throws) when the frame must be
     * rejected: wrong version, compressed or route flag, or a byte count that does not exactly
     * match the header. IS_RSR and unknown flag bits are ignored. A recipient of eight 0xFF bytes
     * is reported as broadcast (`recipientId == null`).
     */
    fun decode(bytes: ByteArray): Frame? {
        try {
            if (bytes.size < MIN_SIZE) return null
            val version = bytes[0].toInt() and 0xFF
            if (version != 1) return null
            val flags = bytes[11].toInt() and 0xFF
            if (flags and (FLAG_IS_COMPRESSED or FLAG_HAS_ROUTE) != 0) return null
            val payloadLen = ((bytes[12].toInt() and 0xFF) shl 8) or (bytes[13].toInt() and 0xFF)
            val hasRecipient = flags and FLAG_HAS_RECIPIENT != 0
            val hasSignature = flags and FLAG_HAS_SIGNATURE != 0
            val expected = MIN_SIZE + (if (hasRecipient) ID_SIZE else 0) + payloadLen +
                (if (hasSignature) SIGNATURE_SIZE else 0)
            if (bytes.size != expected) return null

            val buf = ByteBuffer.wrap(bytes)
            val type = bytes[1].toInt() and 0xFF
            val ttl = bytes[2].toInt() and 0xFF
            val ts = buf.getLong(TS_OFFSET)
            var pos = HEADER_SIZE
            val sender = bytes.copyOfRange(pos, pos + ID_SIZE)
            pos += ID_SIZE
            var recipient: ByteArray? = null
            if (hasRecipient) {
                recipient = bytes.copyOfRange(pos, pos + ID_SIZE)
                pos += ID_SIZE
                if (recipient.all { it == 0xFF.toByte() }) recipient = null
            }
            val payload = bytes.copyOfRange(pos, pos + payloadLen)
            pos += payloadLen
            val signature = if (hasSignature) bytes.copyOfRange(pos, pos + SIGNATURE_SIZE) else null
            return Frame(1, type, ttl, ts, sender, recipient, payload, signature)
        } catch (e: RuntimeException) {
            return null
        }
    }

    /** Copy of already-encoded [raw] with the ttl byte replaced (relay: everything else untouched). */
    fun withTtl(raw: ByteArray, ttl: Int): ByteArray =
        raw.copyOf().also { it[TTL_OFFSET] = ttl.toByte() }

    /** Copy of already-encoded [raw] with the timestamp replaced (inventory push re-stamp). */
    fun withTimestamp(raw: ByteArray, timestampMs: Long): ByteArray =
        raw.copyOf().also { ByteBuffer.wrap(it).putLong(TS_OFFSET, timestampMs) }
}
