package org.relay.core.payload

import org.relay.core.ids.Hex
import java.nio.ByteBuffer

/** ACK payload (SPEC 5.2). */
data class Ack(
    val ackId: ByteArray,
    val refMsgId: ByteArray,
    /** = origin_node of the NEED; also the frame recipient. */
    val targetNode: ByteArray,
    /** u32 */
    val ticketNo: Long,
    val status: AckStatus,
    /** u16, 0xFFFF = unknown */
    val etaMin: Int,
    /** <= 112 bytes of UTF-8, already in the requester's language. */
    val text: String,
) {
    val ackIdHex: String get() = Hex.encode(ackId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ack) return false
        return ackId.contentEquals(other.ackId) && refMsgId.contentEquals(other.refMsgId) &&
            targetNode.contentEquals(other.targetNode) && ticketNo == other.ticketNo &&
            status == other.status && etaMin == other.etaMin && text == other.text
    }

    override fun hashCode(): Int {
        var h = ackId.contentHashCode()
        h = 31 * h + refMsgId.contentHashCode()
        h = 31 * h + targetNode.contentHashCode()
        h = 31 * h + ticketNo.hashCode()
        h = 31 * h + status.hashCode()
        h = 31 * h + etaMin
        h = 31 * h + text.hashCode()
        return h
    }

    companion object {
        const val ETA_UNKNOWN = 0xFFFF
    }
}

object AckCodec {
    const val VERSION = 1
    const val MAX_TEXT = 112
    const val FIXED_SIZE = 32
    const val MAX_PAYLOAD = FIXED_SIZE + 1 + MAX_TEXT

    fun encode(ack: Ack): ByteArray {
        require(ack.ackId.size == 8) { "ack_id must be 8 bytes" }
        require(ack.refMsgId.size == 8) { "ref_msg_id must be 8 bytes" }
        require(ack.targetNode.size == 8) { "target_node must be 8 bytes" }
        require(ack.ticketNo in 0..0xFFFFFFFFL) { "ticket_no out of u32 range" }
        require(ack.etaMin in 0..0xFFFF) { "eta_min out of u16 range" }
        val text = Utf8.truncateToBytes(ack.text, MAX_TEXT)
        val buf = ByteBuffer.allocate(FIXED_SIZE + 1 + text.size)
        buf.put(VERSION.toByte())
        buf.put(ack.ackId)
        buf.put(ack.refMsgId)
        buf.put(ack.targetNode)
        buf.putInt(ack.ticketNo.toInt())
        buf.put(ack.status.code.toByte())
        buf.putShort(ack.etaMin.toShort())
        buf.put(text.size.toByte())
        buf.put(text)
        return buf.array()
    }

    fun decode(p: ByteArray): Ack? {
        try {
            if (p.size < FIXED_SIZE + 1 || p.size > MAX_PAYLOAD) return null
            if ((p[0].toInt() and 0xFF) != VERSION) return null
            val buf = ByteBuffer.wrap(p)
            buf.position(1)
            val ackId = ByteArray(8).also { buf.get(it) }
            val ref = ByteArray(8).also { buf.get(it) }
            val target = ByteArray(8).also { buf.get(it) }
            val ticket = buf.getInt().toLong() and 0xFFFFFFFFL
            val status = AckStatus.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val eta = buf.getShort().toInt() and 0xFFFF
            val text = NeedCodec.readString(p, buf, MAX_TEXT) ?: return null
            if (buf.hasRemaining()) return null
            return Ack(ackId, ref, target, ticket, status, eta, text)
        } catch (e: RuntimeException) {
            return null
        }
    }
}
