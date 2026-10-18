import Foundation

/// Transport-agnostic gossip engine (SPEC section 6).
///
/// Threading: every mutable field is guarded by one (recursive) lock. `LinkSender`, `Scheduler` and
/// `GossipListener` are always called after the lock is released, so they may re-enter the node. Frames from a
/// link that is not up are processed if they are NEED/ACK/opaque (never lost) but HELLO/INVENTORY from an unknown
/// link are ignored: call `linkUp(_:)` before feeding `onFrame(_:_:)`.
///
/// The node is `@unchecked Sendable`: its own state is lock-guarded, but the injected sender, clock, scheduler,
/// store and listener must themselves be safe to call from whatever threads drive the node.
public final class GossipNode: @unchecked Sendable {
    public static let seenCapacity = 10_000
    public static let expiryMs: Int64 = 72 * 3600 * 1000
    /// A BROADCAST lives 24 h from issue (SPEC 5.6), not the 72 h of the other types.
    public static let broadcastTtlMs: Int64 = BroadcastCodec.ttlSec * 1000
    public static let futureLimitMs: Int64 = 24 * 3600 * 1000
    public static let jitterMinMs = 10
    public static let jitterMaxMs = 220
    public static let initialTtl = 7

    public let nodeId: [UInt8]

    private let sender: any LinkSender
    private let clock: any Clock
    private let scheduler: any Scheduler
    private let store: any PacketStore
    private let listener: any GossipListener
    private let appVersion: Int
    /// Max raw bytes pushed per inventory diff (see `PushPlanner`); the remainder follows after [pushContinueMs].
    private let pushBudgetBytes: Int
    private let pushContinueMs: Int64

    private final class LinkState: @unchecked Sendable {
        var hello: Hello?
        /// keys the peer reported in its last complete INVENTORY, plus keys it has since sent us
        var peerKeys = Set<String>()
        var accChunkCount = -1
        var accReceived = Set<Int>()
        var accIds = Set<String>()
    }

    private let lock = NSRecursiveLock()
    private var links: [String: LinkState] = [:]
    private var linkOrder: [String] = []
    private var seen = LruSet(capacity: GossipNode.seenCapacity)
    private var rng: AnyRandom
    private var gateway = false
    private var announcedHops = HelloCodec.hopsUnknown
    private var announcedGateway = false
    private var statsValue = GossipStats()

    /// Deferred side effects, run in order once the lock is released.
    private final class Out { var actions: [() -> Void] = [] }

    public init(
        nodeId: [UInt8], sender: any LinkSender, clock: any Clock, scheduler: any Scheduler,
        store: any PacketStore = InMemoryPacketStore(),
        random: any RandomNumberGenerator = SystemRandomNumberGenerator(),
        listener: any GossipListener, appVersion: Int = 1,
        pushBudgetBytes: Int = Int.max, pushContinueMs: Int64 = 2_000
    ) {
        precondition(nodeId.count == 8, "node id must be 8 bytes")
        self.nodeId = nodeId
        self.sender = sender; self.clock = clock; self.scheduler = scheduler; self.store = store
        self.rng = AnyRandom(random)
        self.listener = listener; self.appVersion = appVersion
        self.pushBudgetBytes = pushBudgetBytes; self.pushContinueMs = pushContinueMs
    }

    private func locked<T>(_ block: (Out) -> T) -> T {
        let out = Out()
        lock.lock()
        let result = block(out)
        lock.unlock()
        for a in out.actions { a() }
        return result
    }

    private func guarded<T>(_ block: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return block()
    }

    private func safeLog(_ msg: String) { listener.onLog(msg) }
    private func log(_ out: Out, _ msg: String) { out.actions.append { [self] in safeLog(msg) } }
    private func send(_ out: Out, _ linkId: String, _ raw: [UInt8]) {
        statsValue.framesSent += 1
        out.actions.append { [self] in sender.send(linkId: linkId, frame: raw) }
    }

    // ---------------------------------------------------------------- public API

    /// True while this node acts as an internet gateway. Changing it re-announces HELLO.
    public var isGateway: Bool {
        get { guarded { gateway } }
        set {
            locked { out in
                if gateway != newValue {
                    gateway = newValue
                    maybeAnnounce(out)
                }
            }
        }
    }

    public var hopsToGateway: Int { guarded { computeHops() } }

    /// HELLOs received on the currently-up links, by link id.
    public func peers() -> [String: Hello] {
        guarded {
            var m: [String: Hello] = [:]
            for id in linkOrder { if let h = links[id]?.hello { m[id] = h } }
            return m
        }
    }

    public func linkIds() -> Set<String> { guarded { Set(linkOrder) } }

    public func stats() -> GossipStats { guarded { statsValue } }

    public func storedNeeds() -> [(Need, Frame)] {
        guarded {
            store.all().filter { $0.type == FrameTypes.need }.compactMap { p in
                guard let f = FrameCodec.decode(p.raw), let n = NeedCodec.decode(f.payload) else { return nil }
                return (n, f)
            }
        }
    }

    public func storedLocHints() -> [(LocHint, Frame)] {
        guarded {
            store.all().filter { $0.type == FrameTypes.locHint }.compactMap { p in
                guard let f = FrameCodec.decode(p.raw), let h = LocHintCodec.decode(f.payload) else { return nil }
                return (h, f)
            }
        }
    }

    /// Control-room broadcasts still inside their 24 h life, as stored (carried) by this node.
    public func storedBroadcasts() -> [(Broadcast, Frame)] {
        guarded {
            let nowSec = clock.nowMs() / 1000
            return store.all().filter { $0.type == FrameTypes.broadcast }.compactMap { p in
                guard let f = FrameCodec.decode(p.raw), let b = BroadcastCodec.decode(f.payload), b.isLive(nowSec: nowSec) else { return nil }
                return (b, f)
            }
        }
    }

    public func storedAcks() -> [(Ack, Frame)] {
        guarded {
            store.all().filter { $0.type == FrameTypes.ack }.compactMap { p in
                guard let f = FrameCodec.decode(p.raw), let a = AckCodec.decode(f.payload) else { return nil }
                return (a, f)
            }
        }
    }

    /// Link established: HELLO, then the full INVENTORY. Calling it again for a live link restarts the handshake.
    public func linkUp(_ linkId: String) {
        locked { out in
            if links[linkId] == nil { linkOrder.append(linkId) }
            links[linkId] = LinkState()
            log(out, "link up \(linkId)")
            sendHello(out, linkId)
            sendInventory(out, linkId)
            maybeAnnounce(out) // gateway value may change as peers say hello; nothing yet, but cheap
        }
    }

    public func linkDown(_ linkId: String) {
        locked { out in
            if links.removeValue(forKey: linkId) != nil {
                linkOrder.removeAll { $0 == linkId }
                log(out, "link down \(linkId)")
                maybeAnnounce(out)
            }
        }
    }

    /// Re-send our INVENTORY on one link (or all links). Peers answer with whatever we lack, so this
    /// advances TTL-exhausted packets one more hop and repairs losses. Each resync also re-sends our HELLO, so a
    /// lost HELLO heals and neighbours keep a fresh gateway distance (or learn it is gone). The app should call
    /// `resync` periodically (suggested: every 30 s) and when a screen of needs is opened.
    public func resync(_ linkId: String? = nil) {
        locked { out in
            expireLocked(out)
            if let linkId {
                if links[linkId] != nil { sendHello(out, linkId); sendInventory(out, linkId) }
            } else {
                for id in linkOrder { sendHello(out, id); sendInventory(out, id) }
            }
        }
    }

    /// Drop NEED/ACK/LOC_HINT frames older than 72 h from the store. Seen-set keys stay until LRU eviction.
    public func expire() { locked { out in expireLocked(out) } }

    @discardableResult
    public func originateNeed(_ need: Need) throws -> Frame {
        let payload = try NeedCodec.encode(need)
        let frame = Frame(type: FrameTypes.need, ttl: Self.initialTtl, timestampMs: clock.nowMs(),
                          senderId: nodeId, payload: payload)
        let raw = try FrameCodec.encode(frame)
        locked { out in acceptNeed(out, need, frame, raw, fromLink: nil) }
        return frame
    }

    @discardableResult
    public func originateAck(_ ack: Ack) throws -> Frame {
        let payload = try AckCodec.encode(ack)
        let frame = Frame(type: FrameTypes.ack, ttl: Self.initialTtl, timestampMs: clock.nowMs(),
                          senderId: nodeId, recipientId: ack.targetNode, payload: payload)
        let raw = try FrameCodec.encode(frame)
        locked { out in acceptAck(out, ack, frame, raw, fromLink: nil) }
        return frame
    }

    /// Originate a LOC_HINT (SPEC 5.5) with this node as relay: [hint]'s relay_node must be this node. Returns nil when
    /// this node already put a hint for that NEED into the mesh (the derived key is in the seen-set), so a relay never
    /// sends two.
    @discardableResult
    public func originateLocHint(_ hint: LocHint) throws -> Frame? {
        guard hint.relayNode == nodeId else { throw IllegalArgumentError("relay_node must be this node") }
        let frame = Frame(type: FrameTypes.locHint, ttl: Self.initialTtl, timestampMs: clock.nowMs(),
                          senderId: nodeId, payload: try LocHintCodec.encode(hint))
        let raw = try FrameCodec.encode(frame)
        var fresh = false
        locked { out in
            fresh = !isKnown(hint.keyHex)
            acceptLocHint(out, hint, frame, raw, fromLink: nil)
        }
        return fresh ? frame : nil
    }

    /// Originate a BROADCAST (SPEC 5.6) into the mesh: a gateway does this for every broadcast the backend returns, so every
    /// gateway puts the control room's word into the phones around it. Returns nil when this node already holds it (the id is
    /// in the seen-set) or it is outside its 24 h life, so calling it with the same list every sync is free.
    @discardableResult
    public func originateBroadcast(_ b: Broadcast) throws -> Frame? {
        if !b.isLive(nowSec: clock.nowMs() / 1000) { return nil }
        let frame = Frame(type: FrameTypes.broadcast, ttl: Self.initialTtl, timestampMs: clock.nowMs(),
                          senderId: nodeId, payload: try BroadcastCodec.encode(b))
        let raw = try FrameCodec.encode(frame)
        var fresh = false
        locked { out in
            fresh = !isKnown(b.keyHex)
            acceptBroadcast(out, b, frame, raw, fromLink: nil)
        }
        return fresh ? frame : nil
    }

    /// Entry point for every whole frame received on [linkId]. Never fails on malformed input.
    public func onFrame(_ linkId: String, _ bytes: [UInt8]) {
        handleFrame(linkId, bytes)
    }

    // ---------------------------------------------------------------- inbound

    private func handleFrame(_ linkId: String, _ bytes: [UInt8]) {
        locked { out in
            statsValue.framesReceived += 1
            guard let frame = FrameCodec.decode(bytes) else {
                statsValue.framesRejected += 1
                return
            }
            let now = clock.nowMs()
            if frame.timestampMs < 0 || frame.timestampMs > now + Self.futureLimitMs {
                statsValue.framesRejected += 1
                log(out, "dropped frame >24h in the future from \(linkId)")
                return
            }
            let payloadVersion = frame.payload.isEmpty ? -1 : Int(frame.payload[0])
            let knownVersion = payloadVersion == 1
            switch frame.type {
            case FrameTypes.need:
                // v2 = v1 plus intake answers (SPEC 5.1); an older build treats it as opaque and only relays it
                if !knownVersion && payloadVersion != NeedCodec.versionWithAnswers {
                    return acceptOpaque(out, frame, bytes, linkId)
                }
                guard let need = NeedCodec.decode(frame.payload) else { reject(); return }
                acceptNeed(out, need, frame, bytes, fromLink: linkId)
            case FrameTypes.ack:
                if !knownVersion { return acceptOpaque(out, frame, bytes, linkId) }
                guard let ack = AckCodec.decode(frame.payload) else { reject(); return }
                acceptAck(out, ack, frame, bytes, fromLink: linkId)
            case FrameTypes.locHint:
                if !knownVersion { return acceptOpaque(out, frame, bytes, linkId) }
                // like HELLO: the relay named in the payload must be the node that sent the frame
                guard let hint = LocHintCodec.decode(frame.payload), hint.relayNode == frame.senderId else { reject(); return }
                acceptLocHint(out, hint, frame, bytes, fromLink: linkId)
            case FrameTypes.broadcast:
                if !knownVersion { return acceptOpaque(out, frame, bytes, linkId) }
                guard let b = BroadcastCodec.decode(frame.payload) else { reject(); return }
                // Outside its life: neither stored nor relayed, so a stale declaration cannot circulate for ever.
                if !b.isLive(nowSec: now / 1000) { log(out, "dropped expired broadcast \(b.idHex)"); return }
                acceptBroadcast(out, b, frame, bytes, fromLink: linkId)
            case FrameTypes.hello:
                guard let hello = HelloCodec.decode(frame.payload), hello.nodeId == frame.senderId else { reject(); return }
                onHello(out, linkId, hello)
            case FrameTypes.inventory:
                guard let inv = InventoryCodec.decode(frame.payload) else { reject(); return }
                onInventory(out, linkId, inv)
            default:
                acceptOpaque(out, frame, bytes, linkId)
            }
        }
    }

    private func reject() { statsValue.framesRejected += 1 }

    private func isKnown(_ key: String) -> Bool {
        if seen.touch(key) { return true } // access-order lookup refreshes the LRU position
        return store.contains(key)
    }

    private func markSeen(_ key: String) { seen.put(key) }

    private func acceptNeed(_ out: Out, _ need: Need, _ frame: Frame, _ raw: [UInt8], fromLink: String?) {
        let key = need.msgIdHex
        if let fromLink { links[fromLink]?.peerKeys.insert(key) }
        if isKnown(key) { statsValue.duplicates += 1; return }
        markSeen(key)
        store.put(StoredPacket(key: key, type: FrameTypes.need, raw: raw, expiryBasisMs: need.createdAt * 1000))
        statsValue.needsDelivered += 1
        out.actions.append { [listener] in listener.onNeed(need, frame: frame, fromLink: fromLink) }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    private func acceptAck(_ out: Out, _ ack: Ack, _ frame: Frame, _ raw: [UInt8], fromLink: String?) {
        let key = ack.ackIdHex
        if let fromLink { links[fromLink]?.peerKeys.insert(key) }
        if isKnown(key) { statsValue.duplicates += 1; return }
        markSeen(key)
        store.put(StoredPacket(key: key, type: FrameTypes.ack, raw: raw, expiryBasisMs: clock.nowMs()))
        statsValue.acksDelivered += 1
        out.actions.append { [listener] in listener.onAckSeen(ack, frame: frame) }
        if ack.targetNode == nodeId { out.actions.append { [listener] in listener.onAckForMe(ack, frame: frame) } }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    private func acceptLocHint(_ out: Out, _ hint: LocHint, _ frame: Frame, _ raw: [UInt8], fromLink: String?) {
        let key = hint.keyHex
        if let fromLink { links[fromLink]?.peerKeys.insert(key) }
        if isKnown(key) { statsValue.duplicates += 1; return }
        markSeen(key)
        store.put(StoredPacket(key: key, type: FrameTypes.locHint, raw: raw, expiryBasisMs: clock.nowMs()))
        statsValue.locHintsDelivered += 1
        out.actions.append { [listener] in listener.onLocHint(hint, frame: frame, fromLink: fromLink) }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    private func acceptBroadcast(_ out: Out, _ b: Broadcast, _ frame: Frame, _ raw: [UInt8], fromLink: String?) {
        let key = b.keyHex
        if let fromLink { links[fromLink]?.peerKeys.insert(key) }
        if isKnown(key) { statsValue.duplicates += 1; return }
        markSeen(key)
        // The shared 72 h expiry (`basis + expiryMs`) lands exactly 24 h after issue when the basis is issued_at minus 48 h.
        store.put(StoredPacket(key: key, type: FrameTypes.broadcast, raw: raw,
                               expiryBasisMs: b.issuedAt * 1000 - (Self.expiryMs - Self.broadcastTtlMs)))
        statsValue.broadcastsDelivered += 1
        out.actions.append { [listener] in listener.onBroadcast(b, frame: frame, fromLink: fromLink) }
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    /// Unknown type or payload version: relay once, never store, never deliver.
    private func acceptOpaque(_ out: Out, _ frame: Frame, _ raw: [UInt8], _ fromLink: String) {
        let key = "o" + Hex.encode(OpaqueKey.of(frame))
        if isKnown(key) { statsValue.duplicates += 1; return }
        markSeen(key)
        scheduleFlood(out, frame, raw, key, fromLink)
    }

    /// Forward after jitter to every link except [fromLink]. Originated frames (fromLink == nil)
    /// keep their initial TTL; relayed frames go out with ttl - 1, and only if ttl > 1 on arrival.
    private func scheduleFlood(_ out: Out, _ frame: Frame, _ raw: [UInt8], _ key: String, _ fromLink: String?) {
        let outRaw: [UInt8]
        if fromLink == nil {
            outRaw = raw
        } else {
            if frame.ttl <= 1 { return }
            outRaw = FrameCodec.withTtl(raw, frame.ttl - 1)
        }
        let delay = Int64(Self.jitterMinMs + Int.random(in: 0...(Self.jitterMaxMs - Self.jitterMinMs), using: &rng))
        out.actions.append { [self, scheduler] in
            scheduler.schedule(delayMs: delay) { self.floodNow(key, outRaw, exclude: fromLink) }
        }
    }

    private func floodNow(_ key: String, _ raw: [UInt8], exclude: String?) {
        locked { out in
            for id in linkOrder {
                if id == exclude { continue }
                guard let st = links[id] else { continue }
                if st.peerKeys.contains(key) { continue } // peer already told us it has it
                send(out, id, raw)
            }
        }
    }

    // ---------------------------------------------------------------- HELLO / INVENTORY

    private func onHello(_ out: Out, _ linkId: String, _ hello: Hello) {
        guard let st = links[linkId] else { return }
        st.hello = hello
        out.actions.append { [listener] in listener.onPeerHello(linkId: linkId, hello: hello) }
        maybeAnnounce(out)
    }

    private func onInventory(_ out: Out, _ linkId: String, _ inv: Inventory) {
        guard let st = links[linkId] else { return }
        if inv.chunkIndex == 0 || st.accChunkCount != inv.chunkCount {
            st.accChunkCount = inv.chunkCount
            st.accReceived = []
            st.accIds = []
        }
        st.accReceived.insert(inv.chunkIndex)
        for id in inv.ids { st.accIds.insert(Hex.encode(id)) }
        if st.accReceived.count < st.accChunkCount { return }

        // Complete: the peer's set is authoritative as of now.
        st.peerKeys = st.accIds
        st.accChunkCount = -1
        st.accReceived = []
        st.accIds = []
        pushBatch(out, linkId, st)
    }

    /// Push the next capped batch of what the peer lacks (ACKs, then NEEDs newest first, then LOC_HINTs). Pushed keys are
    /// recorded in `LinkState.peerKeys` so flooding does not repeat them. A remainder is continued after
    /// [pushContinueMs] on the same link, without waiting for the next 30 s inventory exchange.
    private func pushBatch(_ out: Out, _ linkId: String, _ st: LinkState) {
        let candidates = store.all().filter {
            ($0.type == FrameTypes.need || $0.type == FrameTypes.ack || $0.type == FrameTypes.locHint || $0.type == FrameTypes.broadcast)
                && !st.peerKeys.contains($0.key)
        }
        if candidates.isEmpty { return }
        let plan = PushPlanner.plan(candidates, peerNodeId: st.hello?.nodeId, budgetBytes: pushBudgetBytes)
        let now = clock.nowMs()
        for p in plan.batch {
            send(out, linkId, FrameCodec.withTimestamp(p.raw, now))
            st.peerKeys.insert(p.key)
        }
        log(out, "inventory diff: pushed \(plan.batch.count) frame(s) to \(linkId)"
            + (plan.rest.isEmpty ? "" : ", \(plan.rest.count) deferred"))
        if !plan.rest.isEmpty {
            out.actions.append { [self, scheduler, pushContinueMs] in
                scheduler.schedule(delayMs: pushContinueMs) {
                    self.locked { o in
                        if let cur = self.links[linkId], cur === st { self.pushBatch(o, linkId, st) }
                    }
                }
            }
        }
    }

    private func sendHello(_ out: Out, _ linkId: String) {
        let hello = Hello(nodeId: nodeId, caps: gateway ? HelloCodec.capGateway : 0, hopsToGateway: computeHops(),
                          appVersion: appVersion)
        guard let payload = try? HelloCodec.encode(hello) else { return }
        let frame = Frame(type: FrameTypes.hello, ttl: 1, timestampMs: clock.nowMs(), senderId: nodeId, payload: payload)
        if let raw = try? FrameCodec.encode(frame) { send(out, linkId, raw) }
    }

    private func sendInventory(_ out: Out, _ linkId: String) {
        let keys = store.allKeys().filter { !$0.hasPrefix("o") }
        let limit = InventoryCodec.maxChunks * InventoryCodec.maxIdsPerChunk
        // Newest first if we must cut: recent needs matter most.
        let chosen = keys.count > limit ? Array(keys[(keys.count - limit)...]) : keys
        let chunks = InventoryCodec.chunk(chosen.compactMap { Hex.decodeOrNull($0) })
        for c in chunks {
            guard let payload = try? InventoryCodec.encode(c) else { continue }
            let frame = Frame(type: FrameTypes.inventory, ttl: 1, timestampMs: clock.nowMs(), senderId: nodeId,
                              payload: payload)
            if let raw = try? FrameCodec.encode(frame) { send(out, linkId, raw) }
        }
    }

    /// hops_to_gateway per SPEC 5.3 over currently-up links.
    private func computeHops() -> Int {
        if gateway { return 0 }
        var best = HelloCodec.hopsUnknown
        for id in linkOrder {
            guard let h = links[id]?.hello?.hopsToGateway else { continue }
            // A neighbour at the cap is a stale loop (phones feeding each other's value + 1), not a real route.
            if h >= HelloCodec.hopsMax { continue }
            let cand = h + 1
            if best == HelloCodec.hopsUnknown || cand < best { best = cand }
        }
        return best
    }

    /// Re-announce HELLO to every link when caps or hops_to_gateway changed.
    private func maybeAnnounce(_ out: Out) {
        let hops = computeHops()
        if hops == announcedHops && gateway == announcedGateway { return }
        announcedHops = hops
        announcedGateway = gateway
        for id in linkOrder { sendHello(out, id) }
    }

    private func expireLocked(_ out: Out) {
        let dead = store.expire(clock.nowMs() - Self.expiryMs)
        if !dead.isEmpty { log(out, "expired \(dead.count) packet(s)") }
    }
}

/// Type-erased `RandomNumberGenerator` so the node can hold an injected generator.
struct AnyRandom: RandomNumberGenerator {
    private var base: any RandomNumberGenerator
    init(_ base: any RandomNumberGenerator) { self.base = base }
    mutating func next() -> UInt64 { base.next() }
}

/// Bounded set with least-recently-used eviction (the seen-set: Kotlin used an access-ordered `LinkedHashMap`).
struct LruSet {
    private let capacity: Int
    private var tick: [String: Int] = [:]
    private var queue: [(key: String, tick: Int)] = []
    private var head = 0
    private var counter = 0

    init(capacity: Int) { self.capacity = capacity }

    /// True if [key] is present; refreshes its recency.
    mutating func touch(_ key: String) -> Bool {
        guard tick[key] != nil else { return false }
        bump(key)
        return true
    }

    /// Insert or refresh [key], evicting the least recently used entries beyond capacity.
    mutating func put(_ key: String) {
        bump(key)
        while tick.count > capacity { evictOldest() }
    }

    private mutating func bump(_ key: String) {
        counter += 1
        tick[key] = counter
        queue.append((key, counter))
        if queue.count - head > 4 * max(tick.count, 16) { compact() }
    }

    private mutating func evictOldest() {
        while head < queue.count {
            let e = queue[head]
            head += 1
            if tick[e.key] == e.tick { tick.removeValue(forKey: e.key); return }
        }
    }

    private mutating func compact() {
        queue = Array(queue[head...]).filter { tick[$0.key] == $0.tick }
        head = 0
    }
}
