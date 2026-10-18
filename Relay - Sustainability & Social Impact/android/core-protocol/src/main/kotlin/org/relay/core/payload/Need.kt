package org.relay.core.payload

import org.relay.core.ids.Hex
import org.relay.core.intake.QuestionBank
import java.nio.ByteBuffer

/** One answered intake question (SPEC 5.1): [questionId] and [value] are the [QuestionBank] wire values. */
data class NeedAnswer(val questionId: Int, val value: Int) {
    /** Wire byte: question id in the high 5 bits, answer value in the low 3. Never 0 for a valid answer. */
    val wireByte: Int get() = (questionId shl 3) or value
}

/**
 * The photo tag table (SPEC 5.1): bit -> JSON key. A photo is classified on the phone into these and only the
 * 16-bit mask travels, never pixels. Bits are never renumbered; bit 15 is reserved and must be 0. Checked against
 * `photo_tag_bank` in protocol/vectors.json.
 */
object PhotoTags {
    val KEYS: List<String> = listOf(
        "floodwater", "house_damaged", "house_collapsed", "roof_damaged", "person_injured", "people_stranded",
        "road_blocked", "tree_fallen", "power_line_down", "boat_damaged", "crops_damaged", "livestock_lost",
        "vehicle_damaged", "fire", "landslide",
    )
    /** Every defined bit; bit 15 is reserved. */
    const val ALL_MASK = 0x7FFF

    /** Tag keys of [mask] in bit order, e.g. 0x21 -> floodwater, people_stranded. */
    fun toKeys(mask: Int): List<String> = KEYS.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }

    /** Inverse of [toKeys]; null if a key is not in the table. */
    fun fromKeys(keys: List<String>): Int? {
        var mask = 0
        for (k in keys) {
            val bit = KEYS.indexOf(k)
            if (bit < 0) return null
            mask = mask or (1 shl bit)
        }
        return mask
    }
}

/** NEED payload (SPEC 5.1). Encoded as payload_version 1, 2 when [answers] is not empty, or 3 when [photoTags] is not 0. */
data class Need(
    val msgId: ByteArray,
    val originNode: ByteArray,
    /** unix seconds, u32 */
    val createdAt: Long,
    val category: Category,
    val urgency: Int,
    /** 1..255, 0 = unknown */
    val people: Int,
    /** bitmask of [VulnFlag] bits */
    val vulnFlags: Int,
    val latE5: Int,
    val lonE5: Int,
    /** 0 no fix, 1 <10 m, 2 <50 m, 3 <200 m, 4 <1 km, 5 >=1 km */
    val locAcc: Int,
    val lang: Lang,
    val item: String,
    val landmark: String,
    val summary: String,
    /** At most [QuestionBank.MAX_ANSWERS], distinct question ids; empty = a v1 payload on the wire. */
    val answers: List<NeedAnswer> = emptyList(),
    /** Bitmask over [PhotoTags.KEYS], 0..[PhotoTags.ALL_MASK]; 0 = no photo (a v1 or v2 payload on the wire). */
    val photoTags: Int = 0,
) {
    val lat: Double get() = Geo.fromE5(latE5)
    val lon: Double get() = Geo.fromE5(lonE5)
    val vulnSet: Set<VulnFlag> get() = VulnFlag.fromMask(vulnFlags)
    val msgIdHex: String get() = Hex.encode(msgId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Need) return false
        return msgId.contentEquals(other.msgId) && originNode.contentEquals(other.originNode) &&
            createdAt == other.createdAt && category == other.category && urgency == other.urgency &&
            people == other.people && vulnFlags == other.vulnFlags && latE5 == other.latE5 &&
            lonE5 == other.lonE5 && locAcc == other.locAcc && lang == other.lang &&
            item == other.item && landmark == other.landmark && summary == other.summary && answers == other.answers &&
            photoTags == other.photoTags
    }

    override fun hashCode(): Int {
        var h = msgId.contentHashCode()
        h = 31 * h + originNode.contentHashCode()
        h = 31 * h + createdAt.hashCode()
        h = 31 * h + category.hashCode()
        h = 31 * h + urgency
        h = 31 * h + people
        h = 31 * h + vulnFlags
        h = 31 * h + latE5
        h = 31 * h + lonE5
        h = 31 * h + locAcc
        h = 31 * h + lang.hashCode()
        h = 31 * h + item.hashCode()
        h = 31 * h + landmark.hashCode()
        h = 31 * h + summary.hashCode()
        h = 31 * h + answers.hashCode()
        h = 31 * h + photoTags
        return h
    }
}

object NeedCodec {
    const val VERSION = 1
    /** v1 layout followed by exactly [ANSWER_BYTES] answer bytes. */
    const val VERSION_WITH_ANSWERS = 2
    const val ANSWER_BYTES = 3
    /** v2 layout followed by [PHOTO_TAG_BYTES] (a u16 big-endian bitmask, never 0, bit 15 clear). */
    const val VERSION_WITH_PHOTO_TAGS = 3
    const val PHOTO_TAG_BYTES = 2
    const val MAX_ITEM = 16
    const val MAX_LANDMARK = 24
    const val MAX_SUMMARY = 40
    const val FIXED_SIZE = 35
    /** Fixed part + 3 length bytes + 80 string bytes. */
    const val MAX_PAYLOAD = FIXED_SIZE + 3 + MAX_ITEM + MAX_LANDMARK + MAX_SUMMARY
    const val MAX_PAYLOAD_V2 = MAX_PAYLOAD + ANSWER_BYTES
    const val MAX_PAYLOAD_V3 = MAX_PAYLOAD_V2 + PHOTO_TAG_BYTES

    /**
     * Encode a NEED. Strings are truncated on a code point boundary to their limits. Throws
     * [IllegalArgumentException] if a numeric field is out of range or an id has the wrong length.
     */
    fun encode(need: Need): ByteArray {
        require(need.msgId.size == 8) { "msg_id must be 8 bytes" }
        require(need.originNode.size == 8) { "origin_node must be 8 bytes" }
        require(need.createdAt in 0..0xFFFFFFFFL) { "created_at out of u32 range" }
        require(need.urgency in 0..3) { "urgency out of range" }
        require(need.people in 0..255) { "people out of range" }
        require(need.vulnFlags in 0..255) { "vuln_flags out of range" }
        require(need.locAcc in 0..5) { "loc_acc out of range" }
        require(need.answers.size <= ANSWER_BYTES) { "at most $ANSWER_BYTES answers" }
        require(need.answers.all { QuestionBank.isValid(it.questionId, it.value) }) { "unknown question id or answer value" }
        require(need.answers.map { it.questionId }.toSet().size == need.answers.size) { "duplicate question id" }
        require(need.photoTags in 0..PhotoTags.ALL_MASK) { "photo_tags out of range or uses the reserved bit" }
        val v3 = need.photoTags != 0
        val v2 = v3 || need.answers.isNotEmpty() // v3 carries the 3 answer slots too (all empty is fine)
        val item = Utf8.truncateToBytes(need.item, MAX_ITEM)
        val landmark = Utf8.truncateToBytes(need.landmark, MAX_LANDMARK)
        val summary = Utf8.truncateToBytes(need.summary, MAX_SUMMARY)
        val buf = ByteBuffer.allocate(FIXED_SIZE + 3 + item.size + landmark.size + summary.size +
            (if (v2) ANSWER_BYTES else 0) + (if (v3) PHOTO_TAG_BYTES else 0))
        buf.put((if (v3) VERSION_WITH_PHOTO_TAGS else if (v2) VERSION_WITH_ANSWERS else VERSION).toByte())
        buf.put(need.msgId)
        buf.put(need.originNode)
        buf.putInt(need.createdAt.toInt())
        buf.put(need.category.code.toByte())
        buf.put(need.urgency.toByte())
        buf.put(need.people.toByte())
        buf.put(need.vulnFlags.toByte())
        buf.putInt(need.latE5)
        buf.putInt(need.lonE5)
        buf.put(need.locAcc.toByte())
        buf.put(need.lang.code.toByte())
        buf.put(item.size.toByte()); buf.put(item)
        buf.put(landmark.size.toByte()); buf.put(landmark)
        buf.put(summary.size.toByte()); buf.put(summary)
        if (v2) for (i in 0 until ANSWER_BYTES) buf.put((need.answers.getOrNull(i)?.wireByte ?: 0).toByte())
        if (v3) buf.putShort(need.photoTags.toShort())
        return buf.array()
    }

    /** Null if invalid (SPEC 5.1 validation) or `payload_version` is not 1, 2 or 3. Never throws. */
    fun decode(p: ByteArray): Need? {
        try {
            if (p.size < FIXED_SIZE + 3 || p.size > MAX_PAYLOAD_V3) return null
            val version = p[0].toInt() and 0xFF
            if (version != VERSION && version != VERSION_WITH_ANSWERS && version != VERSION_WITH_PHOTO_TAGS) return null
            val buf = ByteBuffer.wrap(p)
            buf.position(1)
            val msgId = ByteArray(8).also { buf.get(it) }
            val origin = ByteArray(8).also { buf.get(it) }
            val createdAt = buf.getInt().toLong() and 0xFFFFFFFFL
            val category = Category.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val urgency = buf.get().toInt() and 0xFF
            if (urgency > 3) return null
            val people = buf.get().toInt() and 0xFF
            val vuln = buf.get().toInt() and 0xFF
            val lat = buf.getInt()
            val lon = buf.getInt()
            val locAcc = buf.get().toInt() and 0xFF
            if (locAcc > 5) return null
            val lang = Lang.fromCode(buf.get().toInt() and 0xFF) ?: return null
            val item = readString(p, buf, MAX_ITEM) ?: return null
            val landmark = readString(p, buf, MAX_LANDMARK) ?: return null
            val summary = readString(p, buf, MAX_SUMMARY) ?: return null
            val answers = ArrayList<NeedAnswer>()
            var photoTags = 0
            if (version != VERSION) {
                val tail = ANSWER_BYTES + (if (version == VERSION_WITH_PHOTO_TAGS) PHOTO_TAG_BYTES else 0)
                if (buf.remaining() != tail) return null
                var empty = false
                for (i in 0 until ANSWER_BYTES) {
                    val b = buf.get().toInt() and 0xFF
                    if (b == 0) { empty = true; continue }
                    if (empty) return null // occupied slot after an empty one: not canonical
                    val a = NeedAnswer(b ushr 3, b and 7)
                    if (!QuestionBank.isValid(a.questionId, a.value) || answers.any { it.questionId == a.questionId }) return null
                    answers += a
                }
                if (answers.isEmpty() && version == VERSION_WITH_ANSWERS) return null // v2 means "has answers"; encoders emit v1 otherwise
                if (version == VERSION_WITH_PHOTO_TAGS) {
                    photoTags = buf.getShort().toInt() and 0xFFFF
                    if (photoTags == 0 || photoTags and PhotoTags.ALL_MASK.inv() != 0) return null // v3 means "has tags", bit 15 reserved
                }
            }
            if (buf.hasRemaining()) return null
            return Need(msgId, origin, createdAt, category, urgency, people, vuln, lat, lon, locAcc,
                lang, item, landmark, summary, answers, photoTags)
        } catch (e: RuntimeException) {
            return null
        }
    }

    internal fun readString(p: ByteArray, buf: ByteBuffer, max: Int): String? {
        if (!buf.hasRemaining()) return null
        val len = buf.get().toInt() and 0xFF
        if (len > max || buf.remaining() < len) return null
        val s = Utf8.decodeStrict(p, buf.position(), len) ?: return null
        buf.position(buf.position() + len)
        return s
    }
}
