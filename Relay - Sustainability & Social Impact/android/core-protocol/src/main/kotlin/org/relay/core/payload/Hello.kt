package org.relay.core.payload

import java.nio.ByteBuffer

/** HELLO payload (SPEC 5.3). */
data class Hello(
    val nodeId: ByteArray,
    val caps: Int,
    /** 0 if gateway, 0xFF unknown, else min(neighbour)+1 capped at 0xFE. */
    val hopsToGateway: Int,
    val appVersion: Int,
) {
    val isGateway: Boolean get() = caps and HelloCodec.CAP_GATEWAY != 0

    override fun equals(other: Any?): Boolean =
        other is Hello && nodeId.contentEquals(other.nodeId) && caps == other.caps &&
            hopsToGateway == other.hopsToGateway && appVersion == other.appVersion

    override fun hashCode(): Int {
        var h = nodeId.contentHashCode()
        h = 31 * h + caps
        h = 31 * h + hopsToGateway
        h = 31 * h + appVersion
        return h
    }
}

object HelloCodec {
    const val VERSION = 1
    const val SIZE = 13
    const val CAP_GATEWAY = 1
    const val HOPS_UNKNOWN = 0xFF
    const val HOPS_MAX = 0xFE

    fun encode(h: Hello): ByteArray {
        require(h.nodeId.size == 8) { "node_id must be 8 bytes" }
        require(h.caps in 0..255 && h.hopsToGateway in 0..255 && h.appVersion in 0..0xFFFF)
        val buf = ByteBuffer.allocate(SIZE)
        buf.put(VERSION.toByte())
        buf.put(h.nodeId)
        buf.put(h.caps.toByte())
        buf.put(h.hopsToGateway.toByte())
        buf.putShort(h.appVersion.toShort())
        return buf.array()
    }

    fun decode(p: ByteArray): Hello? {
        if (p.size != SIZE || (p[0].toInt() and 0xFF) != VERSION) return null
        val buf = ByteBuffer.wrap(p)
        buf.position(1)
        val id = ByteArray(8).also { buf.get(it) }
        val caps = buf.get().toInt() and 0xFF
        val hops = buf.get().toInt() and 0xFF
        val app = buf.getShort().toInt() and 0xFFFF
        return Hello(id, caps, hops, app)
    }
}
