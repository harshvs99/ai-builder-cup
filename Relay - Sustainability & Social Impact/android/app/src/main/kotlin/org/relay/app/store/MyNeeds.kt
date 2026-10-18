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
    /** [org.relay.core.payload.VulnFlag] bitmask the NEED was sent with. */
    val vulnFlags: Int = 0,
    /** Intake answers, QuestionBank question id -> answer value (the NEED's answer bytes). */
    val answers: Map<Int, Int> = emptyMap(),
    // Per-step timestamps (epoch ms), 0 = not reached yet. Each is set the first time the step is reached.
    val savedAtMs: Long = createdAtMs,
    val sentToMeshAtMs: Long = 0,
    val uploadedAtMs: Long = 0,
    val receivedAtMs: Long = 0,
    val dispatchedAtMs: Long = 0,
    val onTheWayAtMs: Long = 0,
    val resolvedAtMs: Long = 0,
    /** Absolute expected arrival (epoch ms) from the latest ACK that carried an ETA; 0 = unknown. */
    val etaAtMs: Long = 0,
    /** The user stopped sharing this request from this phone (no cancel frame exists on the wire). */
    val cancelled: Boolean = false,
    val cancelledAtMs: Long = 0,
) {
    enum class Stage { QUEUED, SENT, RECEIVED, DISPATCHED, ON_THE_WAY, RESOLVED }

    /** Closed = resolved or cancelled. */
    val isClosed: Boolean get() = cancelled || ackStatus >= AckStatus.RESOLVED.code

    val stage: Stage
        get() = when {
            ackStatus >= AckStatus.RESOLVED.code -> Stage.RESOLVED
            ackStatus == AckStatus.ON_THE_WAY.code -> Stage.ON_THE_WAY
            ackStatus >= AckStatus.DISPATCHED.code -> Stage.DISPATCHED
            ackStatus == AckStatus.RECEIVED.code -> Stage.RECEIVED
            sentToMesh -> Stage.SENT
            else -> Stage.QUEUED
        }

    fun withAck(status: AckStatus, ticket: Long, text: String, eta: Int, nowMs: Long = System.currentTimeMillis()): MyNeed {
        val higher = status.code >= ackStatus
        fun first(cur: Long, reached: Boolean) = if (reached && cur == 0L) nowMs else cur
        return copy(
            receivedAtMs = first(receivedAtMs, status == AckStatus.RECEIVED),
            dispatchedAtMs = first(dispatchedAtMs, status == AckStatus.DISPATCHED),
            onTheWayAtMs = first(onTheWayAtMs, status == AckStatus.ON_THE_WAY),
            resolvedAtMs = first(resolvedAtMs, status == AckStatus.RESOLVED),
            etaAtMs = if (higher && eta != 0xFFFF) nowMs + eta * 60_000L else etaAtMs,
            ackStatus = maxOf(ackStatus, status.code),
            ticketNo = ticket,
            ackText = if (higher) text else ackText,
            etaMin = if (higher) eta else etaMin,
            receivedText = if (status == AckStatus.RECEIVED) text else receivedText,
        )
    }

    /** First NEED frame handed to a link; keeps an earlier timestamp. */
    fun markSentToMesh(nowMs: Long = System.currentTimeMillis()): MyNeed =
        if (sentToMesh && sentToMeshAtMs != 0L) this
        else copy(sentToMesh = true, sentToMeshAtMs = if (sentToMeshAtMs == 0L) nowMs else sentToMeshAtMs)

    /** A backend sync accepted this NEED (this phone was the gateway); keeps an earlier timestamp. */
    fun markUploaded(nowMs: Long = System.currentTimeMillis()): MyNeed =
        if (uploadedAtMs != 0L) this else copy(uploadedAtMs = nowMs)

    fun cancel(nowMs: Long = System.currentTimeMillis()): MyNeed =
        if (cancelled) this else copy(cancelled = true, cancelledAtMs = nowMs)

    fun toJson(): JSONObject = JSONObject()
        .put("id", msgIdHex).put("at", createdAtMs).put("cat", category.code).put("urg", urgency)
        .put("ppl", people).put("item", item).put("lm", landmark).put("sum", summary).put("lang", lang.code)
        .put("need", needPayloadB64).put("sent", sentToMesh).put("ack", ackStatus).put("ticket", ticketNo)
        .put("atext", ackText).put("eta", etaMin).put("rtext", receivedText)
        .put("vuln", vulnFlags).put("answers", JSONObject().also { o -> answers.forEach { (q, v) -> o.put(q.toString(), v) } })
        .put("t_saved", savedAtMs).put("t_sent", sentToMeshAtMs).put("t_up", uploadedAtMs).put("t_recv", receivedAtMs)
        .put("t_disp", dispatchedAtMs).put("t_otw", onTheWayAtMs).put("t_done", resolvedAtMs).put("eta_at", etaAtMs)
        .put("cancelled", cancelled).put("t_cancel", cancelledAtMs)

    companion object {
        fun fromJson(o: JSONObject): MyNeed? = try {
            val created = o.getLong("at")
            MyNeed(
                msgIdHex = o.getString("id"),
                createdAtMs = created,
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
                // Files written before the redesign lack everything below: defaults apply.
                vulnFlags = o.optInt("vuln", 0),
                answers = o.optJSONObject("answers")?.let { a ->
                    a.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to a.optInt(k) } }.toMap()
                } ?: emptyMap(),
                savedAtMs = o.optLong("t_saved", created),
                sentToMeshAtMs = o.optLong("t_sent", 0),
                uploadedAtMs = o.optLong("t_up", 0),
                receivedAtMs = o.optLong("t_recv", 0),
                dispatchedAtMs = o.optLong("t_disp", 0),
                onTheWayAtMs = o.optLong("t_otw", 0),
                resolvedAtMs = o.optLong("t_done", 0),
                etaAtMs = o.optLong("eta_at", 0),
                cancelled = o.optBoolean("cancelled", false),
                cancelledAtMs = o.optLong("t_cancel", 0),
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
