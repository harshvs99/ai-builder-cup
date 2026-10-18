package org.relay.core.announce

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.PacketStore
import org.relay.core.payload.Announcement
import org.relay.core.payload.AnnouncementCodec
import org.relay.core.payload.AnnouncementDistricts
import org.relay.core.payload.AnnouncementStatus
import org.relay.core.payload.Lang
import org.relay.core.store.AtomicFiles
import java.io.File

/** An ANNOUNCEMENT frame the phone holds, with when it first stored it. */
data class HeldFrame(val announcement: Announcement, val receivedAtMs: Long)

/** One active announcement from `GET /v1/announcements/active`: the full text per approved language. */
data class HttpAnnouncement(val announcementId: String, val district: String, val approvedAt: Long, val texts: Map<Lang, String>, val fetchedAtMs: Long)

/** What the Home card shows: one announcement, in one language. [fromHttp] true = the full text rather than the mesh's short form. */
data class AnnouncementCard(val announcementId: String, val lang: Lang, val text: String, val approvedAt: Long, val fromHttp: Boolean)

/**
 * Which announcements a phone shows, and in which language (SPEC 5.7, contract v2 section 5.2). A phone relays every frame; it SHOWS one only when
 * its district is the phone's stored district (or the phone has none) and its language is the UI language, or the English one when no frame in the
 * UI language exists for that `announcement_id` after 10 minutes. WITHDRAWN removes the card and shows nothing. Mirrored in Swift.
 */
object AnnouncementBoard {
    /** How long the phone waits for the UI-language frame before it shows the English one. */
    const val FALLBACK_WAIT_MS = 10L * 60 * 1000
    const val MAX_CARDS = 3

    fun visible(
        held: List<HeldFrame>,
        http: List<HttpAnnouncement>,
        district: String?,
        uiLang: Lang,
        nowMs: Long,
        dismissed: Set<String> = emptySet(),
    ): List<AnnouncementCard> {
        val nowSec = nowMs / 1000
        class Entry(val text: String, val fromHttp: Boolean)
        class Group(val id: String) {
            var approvedAt = 0L
            var firstSeenMs = Long.MAX_VALUE
            val active = HashMap<Lang, Entry>()
            val present = HashSet<Lang>() // languages with any frame at all, active or withdrawn
            val withdrawn = HashSet<Lang>()
        }
        val groups = LinkedHashMap<String, Group>()
        fun group(id: String) = groups.getOrPut(id) { Group(id) }

        for (h in held) {
            val a = h.announcement
            if (!a.isLive(nowSec)) continue
            if (district != null && AnnouncementDistricts.idOf(a.district) != district) continue
            val g = group(a.idHex)
            g.approvedAt = maxOf(g.approvedAt, a.approvedAt)
            g.firstSeenMs = minOf(g.firstSeenMs, h.receivedAtMs)
            g.present += a.lang
            if (a.status == AnnouncementStatus.WITHDRAWN) g.withdrawn += a.lang else g.active.putIfAbsent(a.lang, Entry(a.text, false))
        }
        for (h in http) {
            if (nowSec - h.approvedAt > AnnouncementCodec.TTL_SEC) continue
            if (district != null && h.district != district) continue
            val g = group(h.announcementId)
            g.approvedAt = maxOf(g.approvedAt, h.approvedAt)
            g.firstSeenMs = minOf(g.firstSeenMs, h.fetchedAtMs)
            for ((lang, text) in h.texts) {
                g.present += lang
                g.active[lang] = Entry(text, true) // the full text wins over the mesh's short form
            }
        }

        val cards = ArrayList<AnnouncementCard>()
        for (g in groups.values) {
            if (g.id in dismissed) continue
            val lang: Lang
            if (uiLang in g.present) {
                lang = uiLang // the UI-language frame decides; if it was withdrawn nothing shows
            } else if (Lang.EN in g.present && nowMs - g.firstSeenMs >= FALLBACK_WAIT_MS) {
                lang = Lang.EN
            } else continue
            if (lang in g.withdrawn) continue
            val e = g.active[lang] ?: continue
            cards += AnnouncementCard(g.id, lang, e.text, g.approvedAt, e.fromHttp)
        }
        return cards.sortedWith(compareByDescending<AnnouncementCard> { it.approvedAt }.thenBy { it.announcementId }).take(MAX_CARDS)
    }

    /** The ANNOUNCEMENT frames in a packet store (the gossip store, persisted by the app), as the board takes them. Undecodable ones are skipped. */
    fun heldFrom(store: PacketStore): List<HeldFrame> = store.all().filter { it.type == FrameTypes.ANNOUNCEMENT }.mapNotNull { p ->
        val f = FrameCodec.decode(p.raw) ?: return@mapNotNull null
        AnnouncementCodec.decode(f.payload)?.let { HeldFrame(it, p.expiryBasisMs) }
    }

    /** The cards the person has not been told about yet: one notification per announcement, on first receipt. */
    fun toNotify(cards: List<AnnouncementCard>, notified: Set<String>): List<AnnouncementCard> = cards.filter { it.announcementId !in notified }
}

object AnnouncementJson {
    /** Parses `GET /v1/announcements/active`. An entry without an `announcement_id` (approved before mesh delivery existed) gets `http-<id>`. */
    fun parseActive(json: String?, fetchedAtMs: Long): List<HttpAnnouncement>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = JSONObject(json).getJSONArray("announcements")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val texts = o.optJSONObject("texts") ?: return@mapNotNull null
                val map = HashMap<Lang, String>()
                for (k in texts.keys()) {
                    val lang = Lang.fromTag(k) ?: continue
                    val t = texts.optString(k, "").trim()
                    if (t.isNotEmpty()) map[lang] = t
                }
                if (map.isEmpty()) return@mapNotNull null
                val id = o.optString("announcement_id", "").ifBlank { "http-" + o.optString("id") }
                HttpAnnouncement(id, o.optString("district", ""), o.optLong("approved_at", 0), map, fetchedAtMs)
            }
        } catch (e: JSONException) {
            null
        }
    }
}

/**
 * What the announcement cards need to remember between runs: the last HTTP list (the phone's own copy of what it fetched), the ids the person
 * dismissed, and the ids it already notified about. Whole-file atomic writes; a damaged file reads as empty.
 */
class AnnouncementStore(private val dir: File) {
    private val httpFile get() = File(dir, "announcements_http.json")
    private val stateFile get() = File(dir, "announcements_state.json")

    @Synchronized fun saveHttp(raw: String, fetchedAtMs: Long) {
        AtomicFiles.write(httpFile, JSONObject().put("fetched_at_ms", fetchedAtMs).put("body", raw).toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun http(): List<HttpAnnouncement> {
        val text = AtomicFiles.readOrNull(httpFile)?.toString(Charsets.UTF_8) ?: return emptyList()
        return try {
            val o = JSONObject(text)
            AnnouncementJson.parseActive(o.getString("body"), o.getLong("fetched_at_ms")) ?: emptyList()
        } catch (e: JSONException) {
            emptyList()
        }
    }

    @Synchronized fun lastHttpFetchMs(): Long {
        val text = AtomicFiles.readOrNull(httpFile)?.toString(Charsets.UTF_8) ?: return 0
        return try { JSONObject(text).optLong("fetched_at_ms", 0) } catch (e: JSONException) { 0 }
    }

    @Synchronized fun dismissed(): Set<String> = ids("dismissed")
    @Synchronized fun notified(): Set<String> = ids("notified")
    @Synchronized fun dismiss(id: String) = addId("dismissed", id)
    @Synchronized fun markNotified(id: String) = addId("notified", id)

    private fun state(): JSONObject = try {
        JSONObject(AtomicFiles.readOrNull(stateFile)?.toString(Charsets.UTF_8) ?: "{}")
    } catch (e: JSONException) {
        JSONObject()
    }

    private fun ids(key: String): Set<String> {
        val a = state().optJSONArray(key) ?: return emptySet()
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.toSet()
    }

    private fun addId(key: String, id: String) {
        val o = state()
        val list = (ids(key) + id).toList().takeLast(MAX_REMEMBERED) // old ids fall out; announcements die after 72 h anyway
        o.put(key, JSONArray(list))
        AtomicFiles.write(stateFile, o.toString().toByteArray(Charsets.UTF_8))
    }

    private companion object { const val MAX_REMEMBERED = 200 }
}
