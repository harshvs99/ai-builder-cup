package org.relay.core.frame

/** Packet type bytes (SPEC section 4). */
object FrameTypes {
    const val NEED = 0x70
    const val ACK = 0x71
    const val HELLO = 0x72
    const val INVENTORY = 0x73
    const val LOC_HINT = 0x74
    const val BROADCAST = 0x75
}

/** Outer frame (SPEC section 3). Arrays are compared by content. */
data class Frame(
    val version: Int = 1,
    val type: Int,
    val ttl: Int,
    val timestampMs: Long,
    val senderId: ByteArray,
    /** null = broadcast (also used when the wire recipient was eight 0xFF bytes). */
    val recipientId: ByteArray? = null,
    val payload: ByteArray,
    val signature: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return version == other.version && type == other.type && ttl == other.ttl &&
            timestampMs == other.timestampMs && senderId.contentEquals(other.senderId) &&
            recipientId.contentEqualsNullable(other.recipientId) &&
            payload.contentEquals(other.payload) && signature.contentEqualsNullable(other.signature)
    }

    override fun hashCode(): Int {
        var h = version
        h = 31 * h + type
        h = 31 * h + ttl
        h = 31 * h + timestampMs.hashCode()
        h = 31 * h + senderId.contentHashCode()
        h = 31 * h + (recipientId?.contentHashCode() ?: 0)
        h = 31 * h + payload.contentHashCode()
        h = 31 * h + (signature?.contentHashCode() ?: 0)
        return h
    }

    override fun toString(): String =
        "Frame(type=0x${type.toString(16)}, ttl=$ttl, ts=$timestampMs, payload=${payload.size}B)"
}

private fun ByteArray?.contentEqualsNullable(o: ByteArray?): Boolean =
    if (this == null || o == null) this == null && o == null else this.contentEquals(o)
