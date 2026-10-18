package org.relay.core.payload

import org.relay.core.ids.Hex
import org.relay.core.triage.DisasterType
import java.nio.ByteBuffer

/** What a [Broadcast] says. */
enum class BroadcastKind(val code: Int) {
    /** The control room declares a disaster in the area. */
    DISASTER(1),
    /** The control room says it is over; ends a declaration of the same disaster issued earlier. */
    DISASTER_CLEARED(2);

    companion object { fun fromCode(c: Int): BroadcastKind? = entries.firstOrNull { it.code == c } }
}

/** The wire code of a disaster in a [Broadcast] (SPEC 5.6). */
val DisasterType.code: Int get() = when (this) {
    DisasterType.FLOOD -> 0; DisasterType.CYCLONE -> 1; DisasterType.EARTHQUAKE -> 2; DisasterType.LANDSLIDE -> 3
}

fun disasterFromCode(c: Int): DisasterType? = DisasterType.entries.firstOrNull { it.code == c }

/**
 * BROADCAST payload (SPEC 5.6): the control room speaking to every phone. [radiusKm] 0 = the whole control-room area;
 * otherwise the declaration applies within that distance of ([latE5], [lonE5]).
 */
data class Broadcast(
    val id: ByteArray,
    val kind: BroadcastKind,
    val disaster: DisasterType,
    /** unix seconds, u32: when the control room issued it. Its 24 h life counts from here, not from when a phone got it. */
    val issuedAt: Long,
    val latE5: Int = 0,
    val lonE5: Int = 0,
    val radiusKm: Int = 0,
) {
    val idHex: String get() = Hex.encode(id)
    /** Dedupe key in the gossip store: the id itself. */
    val keyHex: String get() = idHex

    /** True while the broadcast is inside its life: not yet expired, and not stamped from the future beyond clock drift. */
    fun isLive(nowSec: Long): Boolean = nowSec - issuedAt in -BroadcastCodec.CLOCK_SKEW_SEC..BroadcastCodec.TTL_SEC

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Broadcast) return false
        return id.contentEquals(other.id) && kind == other.kind && disaster == other.disaster && issuedAt == other.issuedAt &&
            latE5 == other.latE5 && lonE5 == other.lonE5 && radiusKm == other.radiusKm
    }

    override fun hashCode(): Int {
        var h = id.contentHashCode()
        h = 31 * h + kind.hashCode()
        h = 31 * h + disaster.hashCode()
        h = 31 * h + issuedAt.hashCode()
        h = 31 * h + latE5
        h = 31 * h + lonE5
        h = 31 * h + radiusKm
        return h
    }
}

object BroadcastCodec {
    const val VERSION = 1
    const val SIZE = 25
    /** A broadcast lives 24 h from issue; phones drop it then and never relay it after. */
    const val TTL_SEC = 24L * 3600
    const val CLOCK_SKEW_SEC = 10L * 60

    fun encode(b: Broadcast): ByteArray {
        require(b.id.size == 8) { "bcast_id must be 8 bytes" }
        require(b.issuedAt in 0..0xFFFFFFFFL) { "issued_at out of u32 range" }
        require(b.radiusKm in 0..0xFFFF) { "radius_km out of u16 range" }
        require(coordsInRange(b.latE5, b.lonE5)) { "coordinates out of range" }
        val buf = ByteBuffer.allocate(SIZE)
        buf.put(VERSION.toByte())
        buf.put(b.id)
        buf.put(b.kind.code.toByte())
        buf.put(b.disaster.code.toByte())
        buf.putInt(b.issuedAt.toInt())
        buf.putInt(b.latE5)
        buf.putInt(b.lonE5)
        buf.putShort(b.radiusKm.toShort())
        return buf.array()
    }

    /** Null if invalid (SPEC 5.6 validation). Never throws. */
    fun decode(p: ByteArray): Broadcast? {
        try {
            if (p.size != SIZE || (p[0].toInt() and 0xFF) != VERSION) return null
            val buf = ByteBuffer.wrap(p)
            buf.position(1)
            val id = ByteArray(8).also { buf.get(it) }
            val kind = BroadcastKind.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val disaster = disasterFromCode(buf.get().toInt() and 0xFF) ?: return null
            val issued = buf.getInt().toLong() and 0xFFFFFFFFL
            val lat = buf.getInt()
            val lon = buf.getInt()
            val radius = buf.getShort().toInt() and 0xFFFF
            if (!coordsInRange(lat, lon)) return null
            return Broadcast(id, kind, disaster, issued, lat, lon, radius)
        } catch (e: RuntimeException) {
            return null
        }
    }

    private fun coordsInRange(latE5: Int, lonE5: Int) =
        latE5 in -9_000_000..9_000_000 && lonE5 in -18_000_000..18_000_000
}
