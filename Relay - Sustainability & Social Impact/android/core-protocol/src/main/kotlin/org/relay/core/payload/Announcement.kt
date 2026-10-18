package org.relay.core.payload

import org.relay.core.ids.Hex
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Whether an [Announcement] frame shows a message or takes it back (SPEC 5.7). */
enum class AnnouncementStatus(val code: Int) {
    ACTIVE(0), WITHDRAWN(1);

    companion object { fun fromCode(c: Int): AnnouncementStatus? = entries.firstOrNull { it.code == c } }
}

/** The wire index of a district (SPEC 5.7). Append only; the backend's `codec.DISTRICT_CODES` and the golden vectors pin it. */
object AnnouncementDistricts {
    val IDS: List<String> = listOf("puri", "delhi")

    fun indexOf(id: String?): Int? = IDS.indexOf(id?.trim()?.lowercase()).takeIf { it >= 0 }
    /** The district id for a wire index, or null for one this build does not know (it still relays such a frame). */
    fun idOf(index: Int): String? = IDS.getOrNull(index)
}

/**
 * ANNOUNCEMENT payload (SPEC 5.7): a duty officer's words, translated and approved per language, to every phone in a
 * district. NOT the disaster declaration of SPEC 5.6 ([Broadcast]). [district] is the wire index; a decoder keeps any
 * value, a phone only shows the frames of its own district.
 */
data class Announcement(
    val announcementId: ByteArray,
    val lang: Lang,
    val district: Int,
    val status: AnnouncementStatus,
    /** unix seconds, u32 */
    val approvedAt: Long,
    /** unix seconds, u32; 0 = none */
    val expiresAt: Long,
    /** <= 139 bytes of UTF-8, already in [lang]. */
    val text: String,
) {
    val idHex: String get() = Hex.encode(announcementId)
    /** Dedupe key in the gossip store: SHA-256(announcement_id || lang || status), first 8 bytes. A WITHDRAWN frame is a new frame. */
    val keyHex: String get() = Hex.encode(AnnouncementCodec.key(announcementId, lang, status))

    /** Inside its life: not past [expiresAt] and not older than 72 h. A phone's clock may be wrong, so a future [approvedAt] is not refused. */
    fun isLive(nowSec: Long): Boolean =
        (expiresAt == 0L || nowSec < expiresAt) && nowSec - approvedAt <= AnnouncementCodec.TTL_SEC

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Announcement) return false
        return announcementId.contentEquals(other.announcementId) && lang == other.lang && district == other.district &&
            status == other.status && approvedAt == other.approvedAt && expiresAt == other.expiresAt && text == other.text
    }

    override fun hashCode(): Int {
        var h = announcementId.contentHashCode()
        h = 31 * h + lang.hashCode()
        h = 31 * h + district
        h = 31 * h + status.hashCode()
        h = 31 * h + approvedAt.hashCode()
        h = 31 * h + expiresAt.hashCode()
        h = 31 * h + text.hashCode()
        return h
    }
}

object AnnouncementCodec {
    const val VERSION = 1
    const val MAX_TEXT = 139
    const val FIXED_SIZE = 20
    /** 20 + 1 + 139 = 160 bytes, so the frame (22 + 160) is exactly the 182-byte single-write limit. */
    const val MAX_PAYLOAD = FIXED_SIZE + 1 + MAX_TEXT
    /** Stored like an ACK: dropped 72 h after approval (and 72 h after first storage by the gossip store). */
    const val TTL_SEC = 72L * 3600

    fun key(announcementId: ByteArray, lang: Lang, status: AnnouncementStatus): ByteArray {
        require(announcementId.size == 8) { "announcement_id must be 8 bytes" }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(announcementId)
        md.update(lang.code.toByte())
        md.update(status.code.toByte())
        return md.digest().copyOf(8)
    }

    fun encode(a: Announcement): ByteArray {
        require(a.announcementId.size == 8) { "announcement_id must be 8 bytes" }
        require(a.district in 0..0xFF) { "district out of u8 range" }
        require(a.approvedAt in 0..0xFFFFFFFFL) { "approved_at out of u32 range" }
        require(a.expiresAt in 0..0xFFFFFFFFL) { "expires_at out of u32 range" }
        val text = Utf8.truncateToBytes(a.text, MAX_TEXT)
        val buf = ByteBuffer.allocate(FIXED_SIZE + 1 + text.size)
        buf.put(VERSION.toByte())
        buf.put(a.announcementId)
        buf.put(a.lang.code.toByte())
        buf.put(a.district.toByte())
        buf.put(a.status.code.toByte())
        buf.putInt(a.approvedAt.toInt())
        buf.putInt(a.expiresAt.toInt())
        buf.put(text.size.toByte())
        buf.put(text)
        return buf.array()
    }

    /** Null if invalid (SPEC 5.7 validation). Never throws. */
    fun decode(p: ByteArray): Announcement? {
        try {
            if (p.size < FIXED_SIZE + 1 || p.size > MAX_PAYLOAD) return null
            if ((p[0].toInt() and 0xFF) != VERSION) return null
            val buf = ByteBuffer.wrap(p)
            buf.position(1)
            val id = ByteArray(8).also { buf.get(it) }
            val lang = Lang.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val district = buf.get().toInt() and 0xFF
            val status = AnnouncementStatus.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val approvedAt = buf.getInt().toLong() and 0xFFFFFFFFL
            val expiresAt = buf.getInt().toLong() and 0xFFFFFFFFL
            val text = NeedCodec.readString(p, buf, MAX_TEXT) ?: return null
            if (buf.hasRemaining()) return null
            return Announcement(id, lang, district, status, approvedAt, expiresAt, text)
        } catch (e: RuntimeException) {
            return null
        }
    }
}
