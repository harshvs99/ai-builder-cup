package org.relay.app.gateway

import org.json.JSONArray
import org.json.JSONObject
import org.relay.core.ids.Hex
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class SyncConfig(val baseUrl: String, val deviceKey: String)

/** The optional `hints` object of a sync response: counts for the LOC_HINT frames in the upload. */
data class HintCounts(val accepted: Int, val duplicates: Int, val rejected: Int)

data class SyncResponse(
    val accepted: Int,
    val duplicates: Int,
    val rejected: Int,
    /** ACK payloads (not frames), oldest first. */
    val acks: List<ByteArray>,
    val ackCursor: Long,
    /** BROADCAST payloads (SPEC 5.6) the control room has live now; every gateway originates them into the mesh. Absent = none. */
    val broadcasts: List<ByteArray> = emptyList(),
    /** Present only when the backend reports hints (it adds `hints` when the upload held any); null otherwise. */
    val hints: HintCounts? = null,
    /** ANNOUNCEMENT payloads (SPEC 5.7) for the district the request named; every gateway originates them into the mesh. Absent = none. */
    val announcements: List<ByteArray> = emptyList(),
    /** The cursor to send next time, or null when the response had none (an older backend). */
    val announcementCursor: Long? = null,
)

/** Result of a `GET /v1/health` reachability probe; [detail] is short, human-readable text. */
data class ProbeResult(val ok: Boolean, val detail: String, val latencyMs: Long)

class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** `POST {base}/v1/sync` (SPEC section 7) over plain [HttpURLConnection]. Blocking; call from a worker thread. */
class SyncClient(
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 10_000,
) {
    @Throws(SyncException::class)
    fun sync(
        cfg: SyncConfig, gatewayNode: ByteArray, frames: List<ByteArray>, ackCursor: Long,
        district: String? = null, announcementCursor: Long? = null,
    ): SyncResponse {
        val body = try {
            val arr = JSONArray()
            val enc = Base64.getEncoder() // standard alphabet, padded, no line wrapping
            frames.forEach { arr.put(enc.encodeToString(it)) }
            JSONObject()
                .put("gateway_node", Hex.encode(gatewayNode))
                .put("frames", arr)
                .put("ack_cursor", ackCursor)
                .also { o ->
                    // SPEC 5.7: either field asks the backend for ANNOUNCEMENT payloads; a gateway that sends neither gets neither.
                    if (district != null) o.put("district", district)
                    if (announcementCursor != null) o.put("announcement_cursor", announcementCursor)
                }
                .toString().toByteArray(Charsets.UTF_8)
        } catch (e: Exception) {
            throw SyncException("cannot build request: $e", e)
        }
        val url = try { URL(cfg.baseUrl.trim().trimEnd('/') + "/v1/sync") } catch (e: Exception) {
            throw SyncException("bad backend URL '${cfg.baseUrl}'", e)
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Relay-Key", cfg.deviceKey)
                setFixedLengthStreamingMode(body.size)
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: Exception) { null }
                throw SyncException("HTTP $code${if (err.isNullOrBlank()) "" else ": $err"}")
            }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(text)
        } catch (e: SyncException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("${e.javaClass.simpleName}: ${e.message}", e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /** `GET {base}/v1/health` with a [timeoutMs] connect+read timeout. Blocking; never throws. Not `/healthz`: Cloud Run reserves that path at its front end. */
    fun probe(baseUrl: String, timeoutMs: Int = 5_000): ProbeResult {
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started
        val url = try { URL(baseUrl.trim().trimEnd('/') + "/v1/health") } catch (_: Exception) {
            return ProbeResult(false, "bad backend URL", 0)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
            }
            val code = conn.responseCode
            if (code in 200..299) ProbeResult(true, "reachable (HTTP $code)", elapsed())
            else ProbeResult(false, "HTTP $code", elapsed())
        } catch (e: Exception) {
            ProbeResult(false, SyncHealth.shortError("${e.javaClass.simpleName}: ${e.message}"), elapsed())
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    internal fun parse(text: String): SyncResponse {
        try {
            val o = JSONObject(text)
            val acksJson = o.optJSONArray("acks") ?: JSONArray()
            val dec = Base64.getDecoder()
            val acks = ArrayList<ByteArray>(acksJson.length())
            for (i in 0 until acksJson.length()) {
                try { acks += dec.decode(acksJson.getString(i)) } catch (_: IllegalArgumentException) { /* skip bad entry */ }
            }
            val bJson = o.optJSONArray("broadcasts") ?: JSONArray()
            val broadcasts = ArrayList<ByteArray>(bJson.length())
            for (i in 0 until bJson.length()) {
                try { broadcasts += dec.decode(bJson.getString(i)) } catch (_: IllegalArgumentException) { /* skip bad entry */ }
            }
            val aJson = o.optJSONArray("announcements") ?: JSONArray()
            val announcements = ArrayList<ByteArray>(aJson.length())
            for (i in 0 until aJson.length()) {
                try { announcements += dec.decode(aJson.getString(i)) } catch (_: IllegalArgumentException) { /* skip bad entry */ }
            }
            return SyncResponse(
                broadcasts = broadcasts,
                announcements = announcements,
                announcementCursor = if (o.has("announcement_cursor") && !o.isNull("announcement_cursor")) o.optLong("announcement_cursor") else null,
                accepted = o.optInt("accepted", 0),
                duplicates = o.optInt("duplicates", 0),
                rejected = o.optInt("rejected", 0),
                acks = acks,
                ackCursor = o.getLong("ack_cursor"),
                hints = o.optJSONObject("hints")?.let { h ->
                    HintCounts(h.optInt("accepted", 0), h.optInt("duplicates", 0), h.optInt("rejected", 0))
                },
            )
        } catch (e: Exception) {
            throw SyncException("bad response body: ${e.message}", e)
        }
    }
}
