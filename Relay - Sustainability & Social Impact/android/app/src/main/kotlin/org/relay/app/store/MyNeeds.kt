package org.relay.app.store

import org.json.JSONArray
import org.json.JSONObject
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import java.io.File

/** One NEED this phone originated, with how far it has got. */
data class MyNeed(
    val msgIdHex: String,
    val createdAtMs: Long,
    val category: Category,
    val urgency: Int,
    val people: Int,
    val item: String,
    val landmark: String,
    val summary: String,
    val lang: Lang,
    /** NeedCodec payload, base64, so a never-originated NEED can be re-sent after a restart. */
    val needPayloadB64: String,
    /** At least one frame of this NEED has been handed to a link. */
    val sentToMesh: Boolean = false,
    /** Highest [AckStatus] code received so far, -1 if none. */
    val ackStatus: Int = -1,
    val ticketNo: Long = -1,
    val ackText: String = "",
    val etaMin: Int = 0xFFFF,
    /** Text of the RECEIVED ack, kept even after a later DISPATCHED ack replaces [ackText]. */
    val receivedText: String = "",
) {
    enum class Stage { QUEUED, SENT, RECEIVED, DISPATCHED }

    val stage: Stage
        get() = when {
            ackStatus >= AckStatus.DISPATCHED.code -> Stage.DISPATCHED
            ackStatus == AckStatus.RECEIVED.code -> Stage.RECEIVED
            sentToMesh -> Stage.SENT
            else -> Stage.QUEUED
        }

    fun withAck(status: AckStatus, ticket: Long, text: String, eta: Int): MyNeed {
        val higher = status.code >= ackStatus
        return copy(
            ackStatus = maxOf(ackStatus, status.code),
            ticketNo = ticket,
            ackText = if (higher) text else ackText,
            etaMin = if (higher) eta else etaMin,
            receivedText = if (status == AckStatus.RECEIVED) text else receivedText,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", msgIdHex).put("at", createdAtMs).put("cat", category.code).put("urg", urgency)
        .put("ppl", people).put("item", item).put("lm", landmark).put("sum", summary).put("lang", lang.code)
        .put("need", needPayloadB64).put("sent", sentToMesh).put("ack", ackStatus).put("ticket", ticketNo)
        .put("atext", ackText).put("eta", etaMin).put("rtext", receivedText)

    companion object {
        fun fromJson(o: JSONObject): MyNeed? = try {
            MyNeed(
                msgIdHex = o.getString("id"),
                createdAtMs = o.getLong("at"),
                category = Category.fromCode(o.getInt("cat")) ?: Category.OTHER,
                urgency = o.getInt("urg"),
                people = o.getInt("ppl"),
                item = o.optString("item"),
                landmark = o.optString("lm"),
                summary = o.optString("sum"),
                lang = Lang.fromCode(o.optInt("lang", 0)) ?: Lang.EN,
                needPayloadB64 = o.optString("need"),
                sentToMesh = o.optBoolean("sent", false),
                ackStatus = o.optInt("ack", -1),
                ticketNo = o.optLong("ticket", -1),
                ackText = o.optString("atext"),
                etaMin = o.optInt("eta", 0xFFFF),
                receivedText = o.optString("rtext"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Persisted "my needs" list (newest first). Whole-file JSON, atomic rewrite; a corrupt file loads as empty. */
class MyNeedsStore(private val file: File) {
    private var items: List<MyNeed> = load()

    @Synchronized fun list(): List<MyNeed> = items

    @Synchronized fun add(need: MyNeed) {
        if (items.any { it.msgIdHex == need.msgIdHex }) return
        items = listOf(need) + items
        save()
    }

    /** Applies [f] to the need with this id; returns the updated need, or null if unknown. */
    @Synchronized fun update(msgIdHex: String, f: (MyNeed) -> MyNeed): MyNeed? {
        val cur = items.firstOrNull { it.msgIdHex == msgIdHex } ?: return null
        val next = f(cur)
        if (next == cur) return cur
        items = items.map { if (it.msgIdHex == msgIdHex) next else it }
        save()
        return next
    }

    @Synchronized fun clear() { items = emptyList(); save() }

    private fun save() {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(it.toJson()) }
            AtomicFiles.write(file, arr.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // keep the in-memory list; next mutation retries
        }
    }

    private fun load(): List<MyNeed> {
        val bytes = AtomicFiles.readOrNull(file) ?: return emptyList()
        return try {
            val arr = JSONArray(String(bytes, Charsets.UTF_8))
            (0 until arr.length()).mapNotNull { MyNeed.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
