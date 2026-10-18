package org.relay.core.payload

import org.relay.core.ids.Hex
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * LOC_HINT payload (SPEC 5.5): a relay with a position fix vouches that it heard a no-fix NEED directly from its
 * requester. [acc] is the relay's own accuracy bucket (not the NEED loc_acc buckets): 1 <=10 m, 2 <=25 m, 3 <=50 m,
 * 4 <=100 m.
 */
data class LocHint(
    val refMsgId: ByteArray,
    /** Also the frame sender_id. */
    val relayNode: ByteArray,
    val latE5: Int,
    val lonE5: Int,
    val acc: Int,
    /** dBm of the relay's link to the requester, or [LocHintCodec.RSSI_UNKNOWN]. */
    val rssiDbm: Int,
    /** unix seconds, u32: when the relay received the NEED from the requester */
    val observedAt: Long,
) {
    val lat: Double get() = Geo.fromE5(latE5)
    val lon: Double get() = Geo.fromE5(lonE5)
    /** Upper bound of [acc] in metres. */
    val accuracyMaxM: Int get() = LocHintCodec.ACC_MAX_M[acc]
    /** Dedupe key in the gossip store (SPEC 5.5). */
    val keyHex: String get() = Hex.encode(LocHintCodec.key(refMsgId, relayNode))
    val refMsgIdHex: String get() = Hex.encode(refMsgId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LocHint) return false
        return refMsgId.contentEquals(other.refMsgId) && relayNode.contentEquals(other.relayNode) &&
            latE5 == other.latE5 && lonE5 == other.lonE5 && acc == other.acc && rssiDbm == other.rssiDbm &&
            observedAt == other.observedAt
    }

    override fun hashCode(): Int {
        var h = refMsgId.contentHashCode()
        h = 31 * h + relayNode.contentHashCode()
        h = 31 * h + latE5
        h = 31 * h + lonE5
        h = 31 * h + acc
        h = 31 * h + rssiDbm
        h = 31 * h + observedAt.hashCode()
        return h
    }
}

object LocHintCodec {
    const val VERSION = 1
    const val SIZE = 31
    const val RSSI_UNKNOWN = -128
    /** Upper bound in metres per accuracy bucket; index 0 is unused (0 is not a valid bucket). */
    val ACC_MAX_M = intArrayOf(0, 10, 25, 50, 100)
    /** A relay with a worse fix than this never emits a hint. */
    const val MAX_ACCURACY_M = 100f

    /** Accuracy bucket for a fix of [accuracyM] metres, or null when it is too coarse (or unknown) to hint with. */
    fun bucketFor(accuracyM: Float): Int? {
        if (accuracyM.isNaN() || accuracyM < 0f) return null
        for (b in 1..4) if (accuracyM <= ACC_MAX_M[b]) return b
        return null
    }

    /** First 8 bytes of SHA-256(0x74 || ref_msg_id || relay_node): one hint per relay per NEED. */
    fun key(refMsgId: ByteArray, relayNode: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(0x74.toByte())
        md.update(refMsgId)
        md.update(relayNode)
        return md.digest().copyOf(8)
    }

    fun encode(h: LocHint): ByteArray {
        require(h.refMsgId.size == 8) { "ref_msg_id must be 8 bytes" }
        require(h.relayNode.size == 8) { "relay_node must be 8 bytes" }
        require(h.acc in 1..4) { "acc out of range" }
        require(h.rssiDbm in -128..127) { "rssi_dbm out of i8 range" }
        require(h.observedAt in 0..0xFFFFFFFFL) { "observed_at out of u32 range" }
        require(coordsInRange(h.latE5, h.lonE5)) { "coordinates out of range" }
        val buf = ByteBuffer.allocate(SIZE)
        buf.put(VERSION.toByte())
        buf.put(h.refMsgId)
        buf.put(h.relayNode)
        buf.putInt(h.latE5)
        buf.putInt(h.lonE5)
        buf.put(h.acc.toByte())
        buf.put(h.rssiDbm.toByte())
        buf.putInt(h.observedAt.toInt())
        return buf.array()
    }

    /** Null if invalid (SPEC 5.5 validation). Never throws. */
    fun decode(p: ByteArray): LocHint? {
        try {
            if (p.size != SIZE || (p[0].toInt() and 0xFF) != VERSION) return null
            val buf = ByteBuffer.wrap(p)
            buf.position(1)
            val ref = ByteArray(8).also { buf.get(it) }
            val relay = ByteArray(8).also { buf.get(it) }
            val lat = buf.getInt()
            val lon = buf.getInt()
            val acc = buf.get().toInt() and 0xFF
            if (acc !in 1..4 || !coordsInRange(lat, lon)) return null
            val rssi = buf.get().toInt() // signed
            val observed = buf.getInt().toLong() and 0xFFFFFFFFL
            return LocHint(ref, relay, lat, lon, acc, rssi, observed)
        } catch (e: RuntimeException) {
            return null
        }
    }

    private fun coordsInRange(latE5: Int, lonE5: Int) =
        latE5 in -9_000_000..9_000_000 && lonE5 in -18_000_000..18_000_000
}
