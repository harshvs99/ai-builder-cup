package org.relay.core.gossip

import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.ids.Hex
import org.relay.core.payload.Ack
import org.relay.core.payload.AckCodec
import org.relay.core.payload.Broadcast
import org.relay.core.payload.BroadcastCodec
import org.relay.core.payload.Hello
import org.relay.core.payload.HelloCodec
import org.relay.core.payload.Inventory
import org.relay.core.payload.LocHint
import org.relay.core.payload.LocHintCodec
import org.relay.core.payload.InventoryCodec
import org.relay.core.payload.Need
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.OpaqueKey
import java.util.Random

/**
 * Transport-agnostic gossip engine (SPEC section 6).
 *
 * Threading: every mutable field is guarded by one lock. [LinkSender], [Scheduler] and
 * [GossipListener] are always called after the lock is released, so they may re-enter the node.
 * Frames from a link that is not up are processed if they are NEED/ACK/opaque (never lost) but
 * HELLO/INVENTORY from an unknown link are ignored: call [linkUp] before feeding [onFrame].
 */
class GossipNode(
    val nodeId: ByteArray,
    private val sender: LinkSender,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val store: PacketStore = InMemoryPacketStore(),
    private val random: Random = Random(),
    private val listener: GossipListener,
    private val appVersion: Int = 1,
    /** Max raw bytes pushed per inventory diff (see [PushPlanner]); the remainder follows after [pushContinueMs]. */
    private val pushBudgetBytes: Int = Int.MAX_VALUE,
    private val pushContinueMs: Long = 2_000,
) {
    companion object {
        const val SEEN_CAPACITY = 10_000
        const val EXPIRY_MS = 72L * 3600 * 1000
        /** A BROADCAST lives 24 h from issue (SPEC 5.6), not the 72 h of the other types. */
        const val BROADCAST_TTL_MS = BroadcastCodec.TTL_SEC * 1000
        const val FUTURE_LIMIT_MS = 24L * 3600 * 1000
        const val JITTER_MIN_MS = 10
        const val JITTER_MAX_MS = 220
        const val INITIAL_TTL = 7
    }

    init { require(nodeId.size == 8) { "node id must be 8 bytes" } }

    private class LinkState {
        var hello: Hello? = null
        /** keys the peer reported in its last complete INVENTORY, plus keys it has since sent us */
        var peerKeys: MutableSet<String> = HashSet()
        var accChunkCount = -1
        var accReceived = HashSet<Int>()
        var accIds = HashSet<String>()
    }

    private val lock = Any()
    private val links = LinkedHashMap<String, LinkState>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > SEEN_CAPACITY
    }
    private var gateway = false
    private var announcedHops = HelloCodec.HOPS_UNKNOWN
    private var announcedGateway = false
    private var stats = GossipStats()

    /** Deferred side effects, run in order once the lock is released. */
    private class Out { val actions = ArrayList<() -> Unit>() }

    private inline fun <T> locked(block: (Out) -> T): T {
        val out = Out()
        val result = synchronized(lock) { block(out) }
        for (a in out.actions) {
            try { a() } catch (e: Exception) { safeLog("deferred action failed: $e") }
        }
        return result
    }

    private fun safeLog(msg: String) { try { listener.onLog(msg) } catch (_: Exception) {} }
    private fun Out.log(msg: String) { actions += { safeLog(msg) } }
    private fun Out.send(linkId: String, raw: ByteArray) {
        stats = stats.copy(framesSent = stats.framesSent + 1)
        actions += { sender.send(linkId, raw.copyOf()) }
    }

    // ---------------------------------------------------------------- public API

    /** True while this node acts as an internet gateway. Changing it re-announces HELLO. */
    var isGateway: Boolean
        get() = synchronized(lock) { gateway }
        set(value) = locked { out ->
            if (gateway != value) {
                gateway = value
                maybeAnnounce(out)
            }
        }

    val hopsToGateway: Int get() = synchronized(lock) { computeHops() }

    fun peers(): Map<String, Hello> = synchronized(lock) {
        val m = LinkedHashMap<String, Hello>()
        for ((id, st) in links) st.hello?.let { m[id] = it }
        m
    }

    fun linkIds(): Set<String> = synchronized(lock) { LinkedHashSet(links.keys) }

    fun stats(): GossipStats = synchronized(lock) { stats }

    fun storedNeeds(): List<Pair<Need, Frame>> = synchronized(lock) {
        store.all().filter { it.type == FrameTypes.NEED }.mapNotNull { p ->
            val f = FrameCodec.decode(p.raw) ?: return@mapNotNull null
            val n = NeedCodec.decode(f.payload) ?: return@mapNotNull null
            n to f
        }
    }

    fun storedLocHints(): List<Pair<LocHint, Frame>> = synchronized(lock) {
        store.all().filter { it.type == FrameTypes.LOC_HINT }.mapNotNull { p ->
            val f = FrameCodec.decode(p.raw) ?: return@mapNotNull null
            val h = LocHintCodec.decode(f.payload) ?: return@mapNotNull null
            h to f
        }
    }

    /** Control-room broadcasts still inside their 24 h life, as stored (carried) by this node. */
    fun storedBroadcasts(): List<Pair<Broadcast, Frame>> = synchronized(lock) {
        val nowSec = clock.nowMs() / 1000
        store.all().filter { it.type == FrameTypes.BROADCAST }.mapNotNull { p ->
            val f = FrameCodec.decode(p.raw) ?: return@mapNotNull null
            val b = BroadcastCodec.decode(f.payload) ?: return@mapNotNull null
            if (b.isLive(nowSec)) b to f else null
        }
    }

    fun storedAcks(): List<Pair<Ack, Frame>> = synchronized(lock) {
        store.all().filter { it.type == FrameTypes.ACK }.mapNotNull { p ->
            val f = FrameCodec.decode(p.raw) ?: return@mapNotNull null
            val a = AckCodec.decode(f.payload) ?: return@mapNotNull null
            a to f
        }
    }

    /** Link established: HELLO, then the full INVENTORY. Calling it again for a live link restarts the handshake. */
    fun linkUp(linkId: String) = locked { out ->
        links[linkId] = LinkState()
        out.log("link up $linkId")
        sendHello(out, linkId)
        sendInventory(out, linkId)
        maybeAnnounce(out) // gateway value may change as peers say hello; nothing yet, but cheap
    }

    fun linkDown(linkId: String) = locked { out ->
        if (links.remove(linkId) != null) {
            out.log("link down $linkId")
            maybeAnnounce(out)
        }
    }

    /**
     * Re-send our INVENTORY on one link (or all links). Peers answer with whatever we lack, so this
     * advances TTL-exhausted packets one more hop and repairs losses. Each resync also re-sends our HELLO, so a
     * lost HELLO heals and neighbours keep a fresh gateway distance (or learn it is gone). The Android app should
     * call [resync] periodically (suggested: every 30 s) and when a screen of needs is opened.
     */
    fun resync(linkId: String? = null) = locked { out ->
        expireLocked(out)
        if (linkId != null) {
            if (links.containsKey(linkId)) { sendHello(out, linkId); sendInventory(out, linkId) }
        } else {
            for (id in links.keys.toList()) { sendHello(out, id); sendInventory(out, id) }
        }
    }

    /** Drop NEED/ACK/LOC_HINT frames older than 72 h and BROADCAST frames older than 24 h from the store. Seen-set keys stay until LRU eviction. */
    fun expire() = locked { out -> expireLocked(out) }

    fun originateNeed(need: Need): Frame {
        val payload = NeedCodec.encode(need)
        val frame = Frame(
            type = FrameTypes.NEED, ttl = INITIAL_TTL, timestampMs = clock.nowMs(),
            senderId = nodeId, payload = payload,
        )
        val raw = FrameCodec.encode(frame)
        locked { out ->
            acceptNeed(out, need, frame, raw, fromLink = null)
        }
        return frame
    }

    fun originateAck(ack: Ack): Frame {
        val payload = AckCodec.encode(ack)
        val frame = Frame(
            type = FrameTypes.ACK, ttl = INITIAL_TTL, timestampMs = clock.nowMs(),
            senderId = nodeId, recipientId = ack.targetNode, payload = payload,
        )
        val raw = FrameCodec.encode(frame)
        locked { out ->
            acceptAck(out, ack, frame, raw, fromLink = null)
        }
        return frame
    }

    /**
     * Originate a LOC_HINT (SPEC 5.5) with this node as relay: [hint]'s relay_node must be this node. Returns null when
     * this node already put a hint for that NEED into the mesh (the derived key is in the seen-set), so a relay never
     * sends two.
     */
    fun originateLocHint(hint: LocHint): Frame? {
        require(hint.relayNode.contentEquals(nodeId)) { "relay_node must be this node" }
        val frame = Frame(
            type = FrameTypes.LOC_HINT, ttl = INITIAL_TTL, timestampMs = clock.nowMs(),
            senderId = nodeId, payload = LocHintCodec.encode(hint),
        )
        val raw = FrameCodec.encode(frame)
        var fresh = false
        locked { out ->
            fresh = !isKnown(hint.keyHex)
            acceptLocHint(out, hint, frame, raw, fromLink = null)
        }
        return if (fresh) frame else null
    }

    /**
     * Originate a BROADCAST (SPEC 5.6) into the mesh: a gateway does this for every broadcast the backend returns, so every
     * gateway puts the control room's word into the phones around it. Returns null when this node already holds it (the id is
     * in the seen-set) or it is outside its 24 h life, so calling it with the same list every sync is free.
     */
    fun originateBroadcast(b: Broadcast): Frame? {
        if (!b.isLive(clock.nowMs() / 1000)) return null
        val frame = Frame(
            type = FrameTypes.BROADCAST, ttl = INITIAL_TTL, timestampMs = clock.nowMs(),
            senderId = nodeId, payload = BroadcastCodec.encode(b),
        )
        val raw = FrameCodec.encode(frame)
        var fresh = false
        locked { out ->
            fresh = !isKnown(b.keyHex)
            acceptBroadcast(out, b, frame, raw, fromLink = null)
        }
        return if (fresh) frame else null
    }

    /** Entry point for every whole frame received on [linkId]. Never throws on malformed input. */
    fun onFrame(linkId: String, bytes: ByteArray) {
        try {
            handleFrame(linkId, bytes)
        } catch (e: Exception) {
            safeLog("onFrame failed: $e")
        }
    }

    // ---------------------------------------------------------------- inbound

    private fun handleFrame(linkId: String, bytes: ByteArray) = locked { out ->
        stats = stats.copy(framesReceived = stats.framesReceived + 1)
        val frame = FrameCodec.decode(bytes)
        if (frame == null) {
            stats = stats.copy(framesRejected = stats.framesRejected + 1)
            return@locked
        }
        val now = clock.nowMs()
        if (frame.timestampMs < 0 || frame.timestampMs > now + FUTURE_LIMIT_MS) {
            stats = stats.copy(framesRejected = stats.framesRejected + 1)
            out.log("dropped frame >24h in the future from $linkId")
            return@locked
        }
        val payloadVersion = if (frame.payload.isNotEmpty()) frame.payload[0].toInt() and 0xFF else -1
        val knownVersion = payloadVersion == 1
        when (frame.type) {
            FrameTypes.NEED -> {
                // v2 = v1 plus intake answers (SPEC 5.1); an older build treats it as opaque and only relays it
                if (!knownVersion && payloadVersion != NeedCodec.VERSION_WITH_ANSWERS) return@locked acceptOpaque(out, frame, bytes, linkId)
                val need = NeedCodec.decode(frame.payload)
                if (need == null) { reject(); return@locked }
                acceptNeed(out, need, frame, bytes, linkId)
            }
            FrameTypes.ACK -> {
                if (!knownVersion) return@locked acceptOpaque(out, frame, bytes, linkId)
                val ack = AckCodec.decode(frame.payload)
                if (ack == null) { reject(); return@locked }
                acceptAck(out, ack, frame, bytes, linkId)
            }
            FrameTypes.LOC_HINT -> {
                if (!knownVersion) return@locked acceptOpaque(out, frame, bytes, linkId)
                val hint = LocHintCodec.decode(frame.payload)
                // like HELLO: the relay named in the payload must be the node that sent the frame
                if (hint == null || !hint.relayNode.contentEquals(frame.senderId)) { reject(); return@locked }
                acceptLocHint(out, hint, frame, bytes, linkId)
            }
            FrameTypes.BROADCAST -> {
                if (!knownVersion) return@locked acceptOpaque(out, frame, bytes, linkId)
                val b = BroadcastCodec.decode(frame.payload)
                if (b == null) { reject(); return@locked }
                // Outside its life: neither stored nor relayed, so a stale declaration cannot circulate for ever.
                if (!b.isLive(now / 1000)) { out.log("dropped expired broadcast ${b.idHex}"); return@locked }
                acceptBroadcast(out, b, frame, bytes, linkId)
            }
            FrameTypes.HELLO -> {
                val hello = HelloCodec.decode(frame.payload)
                if (hello == null || !hello.nodeId.contentEquals(frame.senderId)) { reject(); return@locked }
                onHello(out, linkId, hello)
            }
            FrameTypes.INVENTORY -> {
                val inv = InventoryCodec.decode(frame.payload)
                if (inv == null) { reject(); return@locked }
                onInventory(out, linkId, inv)
            }
            else -> acceptOpaque(out, frame, bytes, linkId)
        }
    }

    private fun reject() { stats = stats.copy(framesRejected = stats.framesRejected + 1) }

    private fun isKnown(key: String): Boolean {
        if (seen[key] != null) return true // access-order get() refreshes LRU position
        return store.contains(key)
    }

    private fun markSeen(key: String) { seen[key] = true }

    private fun acceptNeed(out: Out, need: Need, frame: Frame, raw: ByteArray, fromLink: String?) {
        val key = need.msgIdHex
        fromLink?.let { links[it]?.peerKeys?.add(key) }
        if (isKnown(key)) { stats = stats.copy(duplicates = stats.duplicates + 1); return }
        markSeen(key)
        store.put(StoredPacket(key, FrameTypes.NEED, raw, need.createdAt * 1000L))
        stats = stats.copy(needsDelivered = stats.needsDelivered + 1)
        out.actions += { listener.onNeed(need, frame, fromLink) }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    private fun acceptAck(out: Out, ack: Ack, frame: Frame, raw: ByteArray, fromLink: String?) {
        val key = ack.ackIdHex
        fromLink?.let { links[it]?.peerKeys?.add(key) }
        if (isKnown(key)) { stats = stats.copy(duplicates = stats.duplicates + 1); return }
        markSeen(key)
        store.put(StoredPacket(key, FrameTypes.ACK, raw, clock.nowMs()))
        stats = stats.copy(acksDelivered = stats.acksDelivered + 1)
        out.actions += { listener.onAckSeen(ack, frame) }
        if (ack.targetNode.contentEquals(nodeId)) out.actions += { listener.onAckForMe(ack, frame) }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    private fun acceptLocHint(out: Out, hint: LocHint, frame: Frame, raw: ByteArray, fromLink: String?) {
        val key = hint.keyHex
        fromLink?.let { links[it]?.peerKeys?.add(key) }
        if (isKnown(key)) { stats = stats.copy(duplicates = stats.duplicates + 1); return }
        markSeen(key)
        store.put(StoredPacket(key, FrameTypes.LOC_HINT, raw, clock.nowMs()))
        stats = stats.copy(locHintsDelivered = stats.locHintsDelivered + 1)
        out.actions += { listener.onLocHint(hint, frame, fromLink) }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    private fun acceptBroadcast(out: Out, b: Broadcast, frame: Frame, raw: ByteArray, fromLink: String?) {
        val key = b.keyHex
        fromLink?.let { links[it]?.peerKeys?.add(key) }
        if (isKnown(key)) { stats = stats.copy(duplicates = stats.duplicates + 1); return }
        markSeen(key)
        // The shared 72 h expiry (`basis + EXPIRY_MS`) lands exactly 24 h after issue when the basis is issued_at minus 48 h.
        store.put(StoredPacket(key, FrameTypes.BROADCAST, raw, b.issuedAt * 1000L - (EXPIRY_MS - BROADCAST_TTL_MS)))
        stats = stats.copy(broadcastsDelivered = stats.broadcastsDelivered + 1)
        out.actions += { listener.onBroadcast(b, frame, fromLink) }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    /** Unknown type or payload version: relay once, never store, never deliver. */
    private fun acceptOpaque(out: Out, frame: Frame, raw: ByteArray, fromLink: String) {
        val key = "o" + Hex.encode(OpaqueKey.of(frame))
        if (isKnown(key)) { stats = stats.copy(duplicates = stats.duplicates + 1); return }
        markSeen(key)
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    /**
     * Forward after jitter to every link except [fromLink]. Originated frames (fromLink == null)
     * keep their initial TTL; relayed frames go out with ttl - 1, and only if ttl > 1 on arrival.
     */
    private fun scheduleFlood(out: Out, frame: Frame, raw: ByteArray, key: String, fromLink: String?) {
        val outRaw: ByteArray = if (fromLink == null) {
            raw
        } else {
            if (frame.ttl <= 1) return
            FrameCodec.withTtl(raw, frame.ttl - 1)
        }
        val delay = (JITTER_MIN_MS + random.nextInt(JITTER_MAX_MS - JITTER_MIN_MS + 1)).toLong()
        out.actions += { scheduler.schedule(delay) { floodNow(key, outRaw, fromLink) } }
    }

    private fun floodNow(key: String, raw: ByteArray, exclude: String?) = locked { out ->
        for ((id, st) in links) {
            if (id == exclude) continue
            if (st.peerKeys.contains(key)) continue // peer already told us it has it
            out.send(id, raw)
        }
    }

    // ---------------------------------------------------------------- HELLO / INVENTORY

    private fun onHello(out: Out, linkId: String, hello: Hello) {
        val st = links[linkId] ?: return
        st.hello = hello
        out.actions += { listener.onPeerHello(linkId, hello) }
        maybeAnnounce(out)
    }

    private fun onInventory(out: Out, linkId: String, inv: Inventory) {
        val st = links[linkId] ?: return
        if (inv.chunkIndex == 0 || st.accChunkCount != inv.chunkCount) {
            st.accChunkCount = inv.chunkCount
            st.accReceived = HashSet()
            st.accIds = HashSet()
        }
        st.accReceived.add(inv.chunkIndex)
        for (id in inv.ids) st.accIds.add(Hex.encode(id))
        if (st.accReceived.size < st.accChunkCount) return

        // Complete: the peer's set is authoritative as of now.
        val peerSet = st.accIds
        st.peerKeys = HashSet(peerSet)
        st.accChunkCount = -1
        st.accReceived = HashSet()
        st.accIds = HashSet()
        pushBatch(out, linkId, st)
    }

    /**
     * Push the next capped batch of what the peer lacks (ACKs for the peer, broadcasts, other ACKs, NEEDs newest first, then LOC_HINTs). Pushed keys are
     * recorded in [LinkState.peerKeys] so flooding does not repeat them. A remainder is continued after
     * [pushContinueMs] on the same link, without waiting for the next 30 s inventory exchange.
     */
    private fun pushBatch(out: Out, linkId: String, st: LinkState) {
        val candidates = store.all().filter {
            (it.type == FrameTypes.NEED || it.type == FrameTypes.ACK || it.type == FrameTypes.LOC_HINT || it.type == FrameTypes.BROADCAST) &&
                !st.peerKeys.contains(it.key)
        }
        if (candidates.isEmpty()) return
        val plan = PushPlanner.plan(candidates, st.hello?.nodeId, pushBudgetBytes)
        val now = clock.nowMs()
        for (p in plan.batch) {
            out.send(linkId, FrameCodec.withTimestamp(p.raw, now))
            st.peerKeys.add(p.key)
        }
        out.log("inventory diff: pushed ${plan.batch.size} frame(s) to $linkId" +
            if (plan.rest.isNotEmpty()) ", ${plan.rest.size} deferred" else "")
        if (plan.rest.isNotEmpty()) {
            out.actions += {
                scheduler.schedule(pushContinueMs) {
                    locked { o -> if (links[linkId] === st) pushBatch(o, linkId, st) }
                }
            }
        }
    }

    private fun sendHello(out: Out, linkId: String) {
        val hello = Hello(nodeId, if (gateway) HelloCodec.CAP_GATEWAY else 0, computeHops(), appVersion)
        val frame = Frame(type = FrameTypes.HELLO, ttl = 1, timestampMs = clock.nowMs(),
            senderId = nodeId, payload = HelloCodec.encode(hello))
        out.send(linkId, FrameCodec.encode(frame))
    }

    private fun sendInventory(out: Out, linkId: String) {
        val keys = store.allKeys().filter { !it.startsWith("o") }
        val limit = InventoryCodec.MAX_CHUNKS * InventoryCodec.MAX_IDS_PER_CHUNK
        // Newest first if we must cut: recent needs matter most.
        val chosen = if (keys.size > limit) keys.subList(keys.size - limit, keys.size) else keys
        val chunks = InventoryCodec.chunk(chosen.map { Hex.decode(it) })
        for (c in chunks) {
            val frame = Frame(type = FrameTypes.INVENTORY, ttl = 1, timestampMs = clock.nowMs(),
                senderId = nodeId, payload = InventoryCodec.encode(c))
            out.send(linkId, FrameCodec.encode(frame))
        }
    }

    /** hops_to_gateway per SPEC 5.3 over currently-up links. */
    private fun computeHops(): Int {
        if (gateway) return 0
        var best = HelloCodec.HOPS_UNKNOWN
        for (st in links.values) {
            val h = st.hello?.hopsToGateway ?: continue
            // A neighbour at the cap is a stale loop (phones feeding each other's value + 1), not a real route.
            if (h >= HelloCodec.HOPS_MAX) continue
            val cand = h + 1
            if (best == HelloCodec.HOPS_UNKNOWN || cand < best) best = cand
        }
        return best
    }

    /** Re-announce HELLO to every link when caps or hops_to_gateway changed. */
    private fun maybeAnnounce(out: Out) {
        val hops = computeHops()
        if (hops == announcedHops && gateway == announcedGateway) return
        announcedHops = hops
        announcedGateway = gateway
        for (id in links.keys) sendHello(out, id)
    }

    private fun expireLocked(out: Out) {
        val dead = store.expire(clock.nowMs() - EXPIRY_MS)
        if (dead.isNotEmpty()) out.log("expired ${dead.size} packet(s)")
    }
}
