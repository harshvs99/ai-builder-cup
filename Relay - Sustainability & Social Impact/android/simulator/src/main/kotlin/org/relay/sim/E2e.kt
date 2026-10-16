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
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.ExecutorScheduler
import org.relay.core.gossip.GossipListener
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.InMemoryPacketStore
import org.relay.core.gossip.LinkSender
import org.relay.core.gossip.WallClock
import org.relay.core.ids.Hex
import org.relay.core.ids.NodeId
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Ack
import org.relay.core.payload.AckCodec
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Geo
import org.relay.core.payload.Lang
import org.relay.core.payload.Need
import org.relay.core.payload.NeedCodec
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class E2eConfig(
    val backend: String,
    val approve: Boolean = false,
    val text: String = "my mother needs insulin, near the school",
    val lang: Lang? = null,
    val timeoutMs: Long = 30_000,
    val pollMs: Long = 1_000,
    val relayKey: String = "dev-relay-key",
    val out: PrintStream = System.out,
)

data class E2eResult(
    val success: Boolean,
    val reason: String,
    val ticketNo: Long?,
    val receivedText: String?,
    val dispatchedText: String?,
)

/**
 * Real-time end-to-end run: A -- B -- C -- D(gateway) over in-process links, the gateway talking to
 * a real HTTP backend per SPEC section 7. Uses wall-clock time and real jitter.
 */
object E2e {
    private val names = listOf("A", "B", "C", "D")

    fun run(cfg: E2eConfig): E2eResult {
        val t0 = System.currentTimeMillis()
        fun log(msg: String) = synchronized(cfg.out) {
            cfg.out.println(String.format(java.util.Locale.ROOT, "[+%5.2fs] %s", (System.currentTimeMillis() - t0) / 1000.0, msg))
        }

        val executor = ScheduledThreadPoolExecutor(6) { r -> Thread(r, "e2e").also { it.isDaemon = true } }
        executor.removeOnCancelPolicy = true
        val scheduler = ExecutorScheduler(executor)
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        val base = cfg.backend.trimEnd('/')
        val rnd = Random()

        // ---- state shared with the result
        val needAtGateway = AtomicBoolean(false)
        val syncOk = AtomicInteger(0)
        val lastSyncError = AtomicReference<String?>(null)
        val receivedAck = AtomicReference<Ack?>(null)
        val dispatchedAck = AtomicReference<Ack?>(null)
        val approveError = AtomicReference<String?>(null)
        val approveCalled = AtomicBoolean(false)
        val done = CountDownLatch(1)

        // ---- the four nodes (in-process links with 20 ms latency)
        val registry = java.util.concurrent.ConcurrentHashMap<String, GossipNode>()
        class Peer(val name: String) : LinkSender, GossipListener {
            val id = NodeId.random()
            lateinit var node: GossipNode
            override fun send(linkId: String, frame: ByteArray) {
                describeOutgoing(name, id, frame)?.let { log(it) }
                val peer = registry[linkId] ?: return
                executor.schedule({ peer.onFrame(name, frame) }, 20, TimeUnit.MILLISECONDS)
            }
            override fun onLog(msg: String) {}
        }
        val peersMap = LinkedHashMap<String, Peer>()
        for (n in names) peersMap[n] = Peer(n)

        val a = peersMap.getValue("A"); val d = peersMap.getValue("D")
        val pending = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        var cursor = 0

        val gatewayListener = object : GossipListener {
            override fun onNeed(need: Need, frame: Frame, fromLink: String?) {
                log("[D] gateway received NEED ${NodeId.short(need.msgId)} (ttl on arrival=${frame.ttl}, hops=${7 - frame.ttl}); queued for upload")
                needAtGateway.set(true)
                pending.add(FrameCodec.encode(frame))
            }
        }
        val originListener = object : GossipListener {
            override fun onAckForMe(ack: Ack, frame: Frame) {
                log("[A] ACK for me: ticket #${ack.ticketNo} status=${ack.status} text=\"${ack.text}\"")
                when (ack.status) {
                    AckStatus.RECEIVED -> if (receivedAck.compareAndSet(null, ack)) {
                        if (cfg.approve) {
                            if (approveCalled.compareAndSet(false, true)) executor.execute { approve(http, base, ack.ticketNo, approveError, ::log) }
                        } else done.countDown()
                    }
                    AckStatus.DISPATCHED -> if (dispatchedAck.compareAndSet(null, ack) && cfg.approve) done.countDown()
                    else -> {}
                }
            }
        }
        for ((name, p) in peersMap) {
            val listener: GossipListener = when (name) { "A" -> originListener; "D" -> gatewayListener; else -> p }
            p.node = GossipNode(p.id, p, WallClock, scheduler, InMemoryPacketStore(), Random(rnd.nextLong()), listener)
            registry[name] = p.node
        }
        d.node.isGateway = true
        for (i in 0 until names.size - 1) { // A-B, B-C, C-D
            peersMap.getValue(names[i]).node.linkUp(names[i + 1])
            peersMap.getValue(names[i + 1]).node.linkUp(names[i])
        }

        // ---- gateway sync loop
        val syncTask = Runnable {
            val batch = ArrayList<ByteArray>()
            while (true) batch.add(pending.poll() ?: break)
            try {
                val reqBody = buildJsonObject {
                    put("gateway_node", Hex.encode(d.id))
                    put("frames", buildJsonArray { batch.forEach { add(JsonPrimitive(Base64.getEncoder().encodeToString(it))) } })
                    put("ack_cursor", cursor)
                }.toString()
                val req = HttpRequest.newBuilder(URI.create("$base/v1/sync")).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json").header("X-Relay-Key", cfg.relayKey)
                    .POST(HttpRequest.BodyPublishers.ofString(reqBody)).build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) throw IllegalStateException("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
                val json = Json.parseToJsonElement(resp.body()).jsonObject
                val acks = json["acks"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                cursor = json["ack_cursor"]?.jsonPrimitive?.int ?: cursor
                syncOk.incrementAndGet(); lastSyncError.set(null)
                if (batch.isNotEmpty() || acks.isNotEmpty()) {
                    log("[D] POST /v1/sync frames=${batch.size} -> accepted=${json["accepted"]} duplicates=${json["duplicates"]} " +
                        "rejected=${json["rejected"]} acks=${acks.size} ack_cursor=$cursor")
                }
                for (e in acks) {
                    val payload = Base64.getDecoder().decode(e.jsonPrimitive.content)
                    val ack = AckCodec.decode(payload)
                    if (ack == null) { log("[D] backend returned an undecodable ACK payload; skipped"); continue }
                    log("[D] originating ACK ${NodeId.short(ack.ackId)} status=${ack.status} ticket=#${ack.ticketNo} -> node ${NodeId.short(ack.targetNode)}")
                    d.node.originateAck(ack)
                }
            } catch (e: Exception) {
                batch.forEach { pending.add(it) } // retry next poll
                val msg = e.javaClass.simpleName + ": " + (e.message ?: e.cause?.message ?: "connection failed")
                if (lastSyncError.getAndSet(msg) != msg) log("[D] sync failed: $msg (will retry)")
            }
        }
        executor.scheduleWithFixedDelay({ try { syncTask.run() } catch (_: Throwable) {} }, cfg.pollMs, cfg.pollMs, TimeUnit.MILLISECONDS)

        // ---- A builds and originates the NEED
        val draft = RuleExtractor.extract(cfg.text, cfg.lang)
        log("[A] text: \"${cfg.text}\"")
        log("[A] extracted: category=${draft.category} urgency=${draft.urgency} people=${draft.people} item=\"${draft.item}\" " +
            "landmark=\"${draft.landmark}\" flags=${draft.vulnFlags} lang=${draft.lang.tag} summary=\"${draft.summary}\"")
        val need = draft.toNeed(NodeId.random(), a.id, System.currentTimeMillis() / 1000, Geo.toE5(20.2961), Geo.toE5(85.8245), 1,
            cfg.lang ?: draft.lang)
        log("[A] originated NEED ${NodeId.short(need.msgId)} lang=${need.lang.tag} at (20.2961, 85.8245)")
        a.node.originateNeed(need)

        // ---- wait
        val finished = done.await(cfg.timeoutMs, TimeUnit.MILLISECONDS)
        val received = receivedAck.get(); val dispatched = dispatchedAck.get()
        val result: E2eResult = when {
            finished && received != null && (!cfg.approve || dispatched != null) -> {
                log("[A] DONE: ticket_no=${received.ticketNo}; RECEIVED text=\"${received.text}\"" +
                    (if (cfg.approve) "; DISPATCHED text=\"${dispatched?.text}\"" else ""))
                E2eResult(true, "ok", received.ticketNo, received.text, dispatched?.text)
            }
            else -> {
                val reason = when {
                    !needAtGateway.get() -> "timeout: the NEED never reached the gateway node D over the mesh within ${cfg.timeoutMs / 1000} s"
                    syncOk.get() == 0 -> "timeout: gateway D could not reach the backend at $base (last error: ${lastSyncError.get()})"
                    received == null -> "timeout: backend synced OK but returned no RECEIVED ACK for this NEED within ${cfg.timeoutMs / 1000} s (ack_cursor=$cursor)"
                    approveError.get() != null -> "timeout: approving ticket #${received.ticketNo} failed: ${approveError.get()}"
                    else -> "timeout: ticket #${received.ticketNo} approved but no DISPATCHED ACK reached node A within ${cfg.timeoutMs / 1000} s"
                }
                log("[A] FAILED: $reason")
                E2eResult(false, reason, received?.ticketNo, received?.text, dispatched?.text)
            }
        }
        executor.shutdownNow()
        return result
    }

    private fun approve(http: HttpClient, base: String, ticket: Long, err: AtomicReference<String?>, log: (String) -> Unit) {
        try {
            val req = HttpRequest.newBuilder(URI.create("$base/v1/tickets/$ticket/approve")).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody()).build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) throw IllegalStateException("HTTP ${resp.statusCode()}: ${resp.body().take(200)}")
            log("[dashboard] POST /v1/tickets/$ticket/approve -> HTTP ${resp.statusCode()}")
        } catch (e: Exception) {
            err.set(e.toString()); log("[dashboard] approve failed: $e")
        }
    }

    /** Human-readable line for NEED/ACK frames leaving a node; null for HELLO/INVENTORY chatter. */
    private fun describeOutgoing(node: String, nodeId: ByteArray, raw: ByteArray): String? {
        val f = FrameCodec.decode(raw) ?: return null
        return when (f.type) {
            FrameTypes.NEED -> {
                val n = NeedCodec.decode(f.payload) ?: return null
                val verb = if (f.senderId.contentEquals(nodeId)) "sent" else "relayed"
                "[$node] $verb NEED ${NodeId.short(n.msgId)} ttl=${f.ttl}"
            }
            FrameTypes.ACK -> {
                val a = AckCodec.decode(f.payload) ?: return null
                val verb = if (f.senderId.contentEquals(nodeId)) "sent" else "relayed"
                "[$node] $verb ACK ${NodeId.short(a.ackId)} ticket=#${a.ticketNo} ttl=${f.ttl}"
            }
            else -> null
        }
    }
}
