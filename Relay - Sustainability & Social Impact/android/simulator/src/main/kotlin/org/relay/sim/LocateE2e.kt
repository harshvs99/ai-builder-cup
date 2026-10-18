package org.relay.sim

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.gossip.ExecutorScheduler
import org.relay.core.gossip.GossipListener
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.InMemoryPacketStore
import org.relay.core.gossip.LinkSender
import org.relay.core.gossip.LocHintResponder
import org.relay.core.gossip.RelayFix
import org.relay.core.gossip.WallClock
import org.relay.core.ids.Hex
import org.relay.core.ids.NodeId
import org.relay.core.payload.Ack
import org.relay.core.payload.AckCodec
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.LocHint
import org.relay.core.payload.Need
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Where a simulated phone is and how well it knows it. */
data class SimFix(val lat: Double, val lon: Double, val accuracyM: Float)

data class LocateConfig(
    val backend: String,
    val relayKey: String = "dev-relay-key",
    /** The "Help locate neighbours" switch on every relay phone. */
    val helpLocate: Boolean = true,
    /** Poll the backend for the ticket's mesh estimate (needs the real backend; the fake one estimates nothing). */
    val expectEstimate: Boolean = true,
    val timeoutMs: Long = 30_000,
    val pollMs: Long = 300,
    val out: PrintStream = System.out,
) {
    companion object {
        /** Where the requester really is (known only to this simulation). */
        val TRUTH = SimFix(20.29625, 85.82455, 0f)
    }
}

data class LocateResult(
    val success: Boolean,
    val reason: String,
    val ticketNo: Long?,
    /** Relays that put a LOC_HINT into the mesh. */
    val hintRelays: List<String>,
    /** The ticket's `geo` once the backend produced a mesh estimate. */
    val geoLat: Double?, val geoLon: Double?, val radiusM: Int?, val hintCount: Int?, val geoSource: String?,
    /** Metres between the estimate and where the requester really was. */
    val errorM: Double?,
)

/**
 * Real-time end-to-end run of mesh-assisted location against a backend:
 *
 *            +-- B1 (fix 20 m) --+
 *     A -----+                   +-- C (fix, NOT next to A) -- D (gateway) --> backend
 *  (no fix)  +-- B2 (fix 30 m) --+
 *
 * A sends a no-fix NEED. B1 and B2 hear it directly from A and each put one LOC_HINT into the mesh; C only relays
 * the NEED, so it must not hint even though it has a fix. D uploads the NEED and the hints, and the backend answers
 * with a ticket whose `geo` is a "mesh" estimate near where A really is.
 */
object LocateE2e {
    private val FIXES = mapOf(
        "B1" to SimFix(20.29610, 85.82440, 20f),
        "B2" to SimFix(20.29640, 85.82470, 30f),
        "C" to SimFix(20.29700, 85.82600, 10f),
    )

    fun run(cfg: LocateConfig): LocateResult {
        val t0 = System.currentTimeMillis()
        fun log(msg: String) = synchronized(cfg.out) {
            cfg.out.println(String.format(java.util.Locale.ROOT, "[+%5.2fs] %s", (System.currentTimeMillis() - t0) / 1000.0, msg))
        }
        val executor = ScheduledThreadPoolExecutor(6) { r -> Thread(r, "locate-e2e").also { it.isDaemon = true } }
        executor.removeOnCancelPolicy = true
        val scheduler = ExecutorScheduler(executor)
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val base = cfg.backend.trimEnd('/')
        val rnd = Random()

        val registry = java.util.concurrent.ConcurrentHashMap<String, GossipNode>()
        val pending = ConcurrentLinkedQueue<ByteArray>()
        val syncOk = AtomicInteger(0)
        val lastSyncError = AtomicReference<String?>(null)
        val hintRelays = java.util.concurrent.CopyOnWriteArrayList<String>()
        var cursor = 0

        class Phone(val name: String, val fix: SimFix?) : LinkSender, GossipListener {
            val id = NodeId.random()
            lateinit var node: GossipNode
            var responder: LocHintResponder? = null
            override fun send(linkId: String, frame: ByteArray) {
                val peer = registry[linkId] ?: return
                executor.schedule({ peer.onFrame(name, frame) }, 20, TimeUnit.MILLISECONDS)
            }
            override fun onNeed(need: Need, frame: Frame, fromLink: String?) {
                if (name == "D") { log("[D] gateway received NEED ${NodeId.short(need.msgId)}"); pending.add(FrameCodec.encode(frame)) }
                responder?.onNeed(need, fromLink)
            }
            override fun onLocHint(hint: LocHint, frame: Frame, fromLink: String?) {
                if (fromLink == null) { hintRelays.add(name); log("[$name] put a LOC_HINT for ${NodeId.short(hint.refMsgId)} into the mesh (acc<=${hint.accuracyMaxM} m, rssi ${hint.rssiDbm})") }
                if (name == "D") pending.add(FrameCodec.encode(frame)) // the gateway uploads hints with the NEEDs
            }
            override fun onAckForMe(ack: Ack, frame: Frame) { log("[$name] ACK for me: ticket #${ack.ticketNo}") }
        }

        val phones = listOf("A", "B1", "B2", "C", "D").associateWith { Phone(it, FIXES[it]) }
        for ((name, p) in phones) {
            p.node = GossipNode(p.id, p, WallClock, scheduler, InMemoryPacketStore(), Random(rnd.nextLong()), p)
            registry[name] = p.node
            val fix = p.fix
            if (fix != null) {
                p.responder = LocHintResponder(
                    node = p.node, clock = WallClock, scheduler = scheduler,
                    enabled = { cfg.helpLocate },
                    fix = { RelayFix(fix.lat, fix.lon, fix.accuracyM, System.currentTimeMillis()) },
                    rssi = { link -> if (link == "A") -60 else null },
                    log = { log("[$name] $it") },
                )
            }
        }
        phones.getValue("D").node.isGateway = true
        for ((x, y) in listOf("A" to "B1", "A" to "B2", "B1" to "C", "B2" to "C", "C" to "D")) {
            phones.getValue(x).node.linkUp(y); phones.getValue(y).node.linkUp(x)
        }
        Thread.sleep(300) // let the HELLOs land: a relay must know who is on the other end of a link

        val sync = Runnable {
            val batch = ArrayList<ByteArray>()
            while (true) batch.add(pending.poll() ?: break)
            try {
                val body = buildJsonObject {
                    put("gateway_node", Hex.encode(phones.getValue("D").id))
                    put("frames", buildJsonArray { batch.forEach { add(JsonPrimitive(Base64.getEncoder().encodeToString(it))) } })
                    put("ack_cursor", cursor)
                }.toString()
                val req = HttpRequest.newBuilder(URI.create("$base/v1/sync")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").header("X-Relay-Key", cfg.relayKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) throw IllegalStateException("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                cursor = json["ack_cursor"]?.jsonPrimitive?.int ?: cursor
                syncOk.incrementAndGet(); lastSyncError.set(null)
                if (batch.isNotEmpty()) log("[D] POST /v1/sync frames=${batch.size} -> accepted=${json["accepted"]} hints=${json["hints"] ?: "-"}")
                for (e in json["acks"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())) {
                    AckCodec.decode(Base64.getDecoder().decode(e.jsonPrimitive.content))?.let { phones.getValue("D").node.originateAck(it) }
                }
            } catch (e: Exception) {
                batch.forEach { pending.add(it) }
                lastSyncError.set(e.javaClass.simpleName + ": " + (e.message ?: "connection failed"))
            }
        }
        executor.scheduleWithFixedDelay({ try { sync.run() } catch (_: Throwable) {} }, cfg.pollMs, cfg.pollMs, TimeUnit.MILLISECONDS)

        // A has no GPS fix: loc_acc 0, coordinates 0,0, only a landmark
        val a = phones.getValue("A")
        val need = Need(NodeId.random(), a.id, System.currentTimeMillis() / 1000, Category.WATER, 2, 4, 0, 0, 0, 0, Lang.EN,
            "drinking water", "", "no drinking water, no GPS")
        log("[A] no GPS fix; originated NEED ${NodeId.short(need.msgId)} with loc_acc 0 (really at ${LocateConfig.TRUTH.lat}, ${LocateConfig.TRUTH.lon})")
        a.node.originateNeed(need)

        // wait for the backend to know the ticket (and, if asked, its mesh estimate)
        val msgHex = Hex.encode(need.msgId)
        val deadline = System.currentTimeMillis() + cfg.timeoutMs
        var ticketNo: Long? = null
        var geo: kotlinx.serialization.json.JsonObject? = null
        val wantHints = if (cfg.helpLocate) 2 else 0
        val dNode = phones.getValue("D").node
        val waitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(cfg.pollMs)
            if (!cfg.expectEstimate) { // fake backend: just let the hints reach D and be uploaded, or let a silent switch-off show
                if (cfg.helpLocate && dNode.storedLocHints().size >= wantHints && pending.isEmpty()) { Thread.sleep(2 * cfg.pollMs); break }
                if (!cfg.helpLocate && System.currentTimeMillis() - waitStart > 3_000) break
                continue
            }
            try {
                val resp = http.send(HttpRequest.newBuilder(URI.create("$base/v1/tickets")).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() != 200) continue
                val t = Json.parseToJsonElement(resp.body()).jsonObject["tickets"]!!.jsonArray
                    .map { it.jsonObject }.firstOrNull { o -> o["msg_ids"]!!.jsonArray.any { it.jsonPrimitive.content == msgHex } } ?: continue
                ticketNo = t["ticket_no"]!!.jsonPrimitive.content.toLong()
                val g = t["geo"]?.takeIf { it is kotlinx.serialization.json.JsonObject }?.jsonObject
                if (g != null && g["source"]?.jsonPrimitive?.content == "mesh" && (g["hint_count"]?.jsonPrimitive?.int ?: 0) >= wantHints) { geo = g; break }
                if (!cfg.helpLocate && System.currentTimeMillis() - waitStart > 3_000) { geo = g; break } // switch off: no hint ever came
            } catch (_: Exception) { /* backend not up yet */ }
        }
        executor.shutdownNow()

        val relays = hintRelays.toList()
        val lat = geo?.get("lat")?.jsonPrimitive?.content?.toDouble()
        val lon = geo?.get("lon")?.jsonPrimitive?.content?.toDouble()
        val err = if (lat != null && lon != null) distanceM(lat, lon, LocateConfig.TRUTH.lat, LocateConfig.TRUTH.lon) else null
        val radius = geo?.get("radius_m")?.jsonPrimitive?.int
        val count = geo?.get("hint_count")?.jsonPrimitive?.int
        val source = geo?.get("source")?.jsonPrimitive?.content
        val reason = when {
            syncOk.get() == 0 -> "the gateway could not reach the backend at $base (last error: ${lastSyncError.get()})"
            cfg.helpLocate && relays.sorted() != listOf("B1", "B2") ->
                "expected exactly B1 and B2 (next to A) to hint, but hints came from $relays"
            !cfg.helpLocate && relays.isNotEmpty() -> "the switch was off but $relays emitted hints"
            !cfg.helpLocate -> "ok"
            !cfg.expectEstimate -> "ok"
            geo == null -> "the backend produced no mesh estimate for the ticket within ${cfg.timeoutMs / 1000} s (ticket $ticketNo)"
            err != null && radius != null && err > radius -> "the estimate is ${"%.0f".format(err)} m from the truth, outside its own ${radius} m radius"
            else -> "ok"
        }
        val ok = reason == "ok"
        log(if (ok) "DONE: relays=$relays geo=$source ($lat, $lon) radius=${radius} m hints=$count error=${err?.let { "%.1f".format(it) }} m"
            else "FAILED: $reason")
        return LocateResult(ok, reason, ticketNo, relays, lat, lon, radius, count, source, err)
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * Math.cos(Math.toRadians(lat1))
        return Math.hypot(dx, dy)
    }
}
