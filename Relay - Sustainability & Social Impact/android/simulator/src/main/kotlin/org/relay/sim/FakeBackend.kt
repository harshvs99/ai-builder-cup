package org.relay.sim

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.ids.Hex
import org.relay.core.ids.NodeId
import org.relay.core.payload.Ack
import org.relay.core.payload.AckCodec
import org.relay.core.payload.AckStatus
import org.relay.core.payload.LocHint
import org.relay.core.payload.LocHintCodec
import org.relay.core.payload.Need
import org.relay.core.payload.NeedCodec
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Minimal in-process stand-in for the Relay backend (SPEC section 7): `/v1/sync`,
 * `/v1/tickets/{n}/approve`, `/healthz` and `/v1/health`. One ticket per distinct msg_id. Used by the e2e unit
 * test and by `e2e --fake`; it is not a model of the real backend's clustering or scoring.
 */
class FakeBackend(port: Int = 0, private val relayKey: String = "dev-relay-key") : AutoCloseable {
    private val lock = Any()
    private val ackPayloads = ArrayList<ByteArray>() // ack id = index + 1
    private val ticketNeeds = LinkedHashMap<Long, Need>()
    private val ticketByMsg = HashMap<String, Long>()
    private val hintsByKey = LinkedHashMap<String, LocHint>()
    private var nextTicket = 1L
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    /** Needs received, in ticket order. */
    val needs: List<Need> get() = synchronized(lock) { ticketNeeds.values.toList() }
    /** LOC_HINTs received (first one per relay and NEED wins), in arrival order. The fake does not estimate anything. */
    val hints: List<LocHint> get() = synchronized(lock) { hintsByKey.values.toList() }
    @Volatile var syncCalls = 0
        private set
    @Volatile var failSync = false
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "fake-backend").also { it.isDaemon = true } }
        server.createContext("/healthz") { ex -> respond(ex, 200, buildJsonObject { put("ok", true) }) }
        server.createContext("/v1/health") { ex -> respond(ex, 200, buildJsonObject { put("ok", true) }) }
        server.createContext("/v1/sync") { ex -> safely(ex) { sync(ex) } }
        server.createContext("/v1/tickets/") { ex -> safely(ex) { tickets(ex) } }
        server.start()
    }

    private fun safely(ex: HttpExchange, block: () -> Unit) {
        try { block() } catch (e: Exception) {
            respond(ex, 500, buildJsonObject { put("error", e.toString()) })
        }
    }

    private fun sync(ex: HttpExchange) {
        syncCalls++
        if (failSync) return respond(ex, 500, buildJsonObject { put("error", "simulated outage") })
        if (ex.requestMethod != "POST") return respond(ex, 405, buildJsonObject { put("error", "method") })
        if (ex.requestHeaders.getFirst("X-Relay-Key") != relayKey) return respond(ex, 401, buildJsonObject { put("error", "bad key") })
        val body = Json.parseToJsonElement(String(ex.requestBody.readAllBytes(), Charsets.UTF_8)).jsonObject
        val frames = body["frames"]?.jsonArray ?: JsonArray(emptyList())
        val cursor = body["ack_cursor"]?.jsonPrimitive?.int ?: 0
        var accepted = 0; var duplicates = 0; var rejected = 0
        var sawHints = false; var hintsAccepted = 0; var hintsDuplicates = 0; var hintsRejected = 0
        val out = ArrayList<ByteArray>()
        var newCursor = cursor
        synchronized(lock) {
            for (f in frames) {
                val raw = try { Base64.getDecoder().decode(f.jsonPrimitive.content) } catch (e: IllegalArgumentException) { rejected++; continue }
                val frame = FrameCodec.decode(raw)
                if (frame != null && frame.type == FrameTypes.LOC_HINT) {
                    sawHints = true
                    val hint = LocHintCodec.decode(frame.payload)
                    if (hint == null || !hint.relayNode.contentEquals(frame.senderId)) { hintsRejected++; continue }
                    if (hintsByKey.putIfAbsent(hint.keyHex, hint) == null) hintsAccepted++ else hintsDuplicates++
                    continue
                }
                val need = if (frame != null && frame.type == FrameTypes.NEED) NeedCodec.decode(frame.payload) else null
                if (need == null) { rejected++; continue }
                val key = Hex.encode(need.msgId)
                if (ticketByMsg.containsKey(key)) { duplicates++; continue }
                val t = nextTicket++
                ticketByMsg[key] = t; ticketNeeds[t] = need
                queueAck(need, t, AckStatus.RECEIVED, "Request #$t received at control room")
                accepted++
            }
            for (id in cursor + 1..minOf(ackPayloads.size, cursor + 50)) { out += ackPayloads[id - 1]; newCursor = id }
        }
        respond(ex, 200, buildJsonObject {
            put("accepted", accepted); put("duplicates", duplicates); put("rejected", rejected)
            put("acks", buildJsonArray { out.forEach { add(JsonPrimitive(Base64.getEncoder().encodeToString(it))) } })
            put("ack_cursor", newCursor)
            if (sawHints) put("hints", buildJsonObject {
                put("accepted", hintsAccepted); put("duplicates", hintsDuplicates); put("rejected", hintsRejected)
            })
        })
    }

    private fun queueAck(need: Need, ticket: Long, status: AckStatus, text: String) {
        ackPayloads += AckCodec.encode(Ack(NodeId.random(), need.msgId, need.originNode, ticket, status, Ack.ETA_UNKNOWN, text))
    }

    private fun tickets(ex: HttpExchange) {
        val parts = ex.requestURI.path.trim('/').split('/') // v1, tickets, {n}, approve
        val n = parts.getOrNull(2)?.toLongOrNull()
        if (parts.size == 4 && parts[3] == "approve" && ex.requestMethod == "POST" && n != null) {
            val need = synchronized(lock) {
                val nd = ticketNeeds[n]
                if (nd != null) queueAck(nd, n, AckStatus.DISPATCHED, "Help is being dispatched for request #$n")
                nd
            } ?: return respond(ex, 404, buildJsonObject { put("error", "no such ticket") })
            return respond(ex, 200, buildJsonObject {
                put("ticket_no", n); put("status", "DISPATCHED"); put("category", need.category.name)
                put("item", need.item); put("landmark", need.landmark); put("summary", need.summary)
            })
        }
        respond(ex, 404, buildJsonObject { put("error", "not found") })
    }

    private fun respond(ex: HttpExchange, code: Int, json: JsonObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() { server.stop(0); (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow() }
}
