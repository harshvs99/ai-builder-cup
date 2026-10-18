package org.relay.app.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.relay.app.store.AtomicFiles
import java.io.File

/**
 * Persistent gateway bookkeeping: which NEED msg_ids were already uploaded and the backend's ack_cursor.
 * [file] may be null for an in-memory instance. A corrupt file loads as empty state.
 */
class GatewayState(private val file: File?, private val maxRemembered: Int = 10_000) {
    private val uploaded = LinkedHashSet<String>()
    private var cursor: Long = 0
    private var announcementCursor: Long = 0

    init { load() }

    @Synchronized fun ackCursor(): Long = cursor
    /** The backend's `announcement_cursor` (SPEC 5.7): where this gateway's ANNOUNCEMENT feed stands. */
    @Synchronized fun announcementCursor(): Long = announcementCursor
    @Synchronized fun setAnnouncementCursor(value: Long) {
        if (value == announcementCursor) return
        announcementCursor = value
        save()
    }
    @Synchronized fun isUploaded(msgIdHex: String): Boolean = uploaded.contains(msgIdHex)
    @Synchronized fun uploadedCount(): Int = uploaded.size

    @Synchronized fun markUploaded(msgIds: Collection<String>) {
        uploaded.addAll(msgIds)
        while (uploaded.size > maxRemembered) uploaded.remove(uploaded.first())
        save()
    }

    @Synchronized fun setAckCursor(value: Long) {
        if (value == cursor) return
        cursor = value
        save()
    }

    /** Forget uploaded ids (keeps the cursor, so old ACKs are not replayed). */
    @Synchronized fun clearUploaded() { uploaded.clear(); save() }

    private fun save() {
        val f = file ?: return
        try {
            val arr = JSONArray()
            uploaded.forEach { arr.put(it) }
            val json = JSONObject().put("cursor", cursor).put("ann_cursor", announcementCursor).put("uploaded", arr)
            AtomicFiles.write(f, json.toString().toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {
            // retried on next change
        }
    }

    private fun load() {
        val f = file ?: return
        val bytes = AtomicFiles.readOrNull(f) ?: return
        try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            val arr = o.getJSONArray("uploaded")
            val ids = (0 until arr.length()).map { arr.getString(it) }
            cursor = o.getLong("cursor")
            announcementCursor = o.optLong("ann_cursor", 0) // files from before ANNOUNCEMENT have none
            uploaded.addAll(ids)
        } catch (_: Exception) {
            uploaded.clear(); cursor = 0; announcementCursor = 0
        }
    }
}
