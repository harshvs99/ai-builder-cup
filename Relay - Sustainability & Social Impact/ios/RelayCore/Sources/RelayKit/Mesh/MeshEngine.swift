import Foundation
import RelayCore

/// The running mesh: owns the `GossipNode`, the gateway and the timers, and talks to the radio through a
/// `MeshTransport`. Everything the UI needs is published through `MeshRepository`.
///
/// Platform facts the engine cannot see are pushed in by the app: `setBluetoothOn`, `setForeground`, `setBattery`
/// and `setInternetAvailable` (an `NWPathMonitor`). Each is cheap and may be called from any thread, any number of
/// times.
///
/// Threading: no actors. `GossipNode` is lock-guarded and calls its sender and listener outside its lock; the engine's
/// own mutable fields sit behind one lock; timers run on a private serial queue; backend syncs run on a second serial
/// queue so a slow network never delays gossip. Callbacks into the repository may therefore arrive on any of those
/// queues, which is why the repository exposes `Broadcaster` streams instead of main-actor state.
///
/// A stopped engine cannot be restarted: make a new one (the repository and its stores are reusable).
public final class MeshEngine: @unchecked Sendable {
    /// SPEC section 6.6: re-send INVENTORY on every live link this often.
    public static let resyncIntervalMs = 30_000
    /// Packets older than 72 h are dropped this often.
    public static let expireIntervalMs = 3_600_000
    /// The status snapshot is rebuilt this often.
    public static let publishIntervalMs = 1_000

    public let node: GossipNode

    private let repo: MeshRepository
    private let transport: any MeshTransport
    private let store: FilePacketStore
    private let nodeId: [UInt8]
    private let recentPeers: RecentPeers
    private let syncer: GatewaySyncer
    /// Mesh-assisted location (SPEC 5.5). Created with the node, hence implicitly unwrapped.
    private var locHints: LocHintResponder!
    private let gateway: GatewayManager
    private let queue = DispatchQueue(label: "relay-engine")
    private let bridge: Bridge

    private struct State {
        var running = false
        var stopped = false
        var batteryPct = -1
        var charging = false
        var foreground = false
        var bluetoothOn = false
        var internet = false
        var lastSyncOkAt: Int64 = 0
        var lastSyncError: String?
        var syncHealth = SyncHealth()
        var lastBackendUrl = ""
        var settingsToken: Int?
        var timers: [DispatchSourceTimer] = []
    }
    private let lock = NSLock()
    private var st = State()

    /// Forwards gossip, transport and gateway callbacks to the engine without a strong reference, so the node (which
    /// holds its sender and listener for life) never keeps a stopped engine alive.
    private final class Bridge: LinkSender, GossipListener, MeshTransportEvents, @unchecked Sendable {
        weak var engine: MeshEngine?
        func send(linkId: String, frame: [UInt8]) { engine?.sendFrame(linkId, frame) }
        func onLinkUp(_ linkId: String) { engine?.linkUp(linkId) }
        func onLinkDown(_ linkId: String) { engine?.linkDown(linkId) }
        func onFrame(_ linkId: String, _ frame: [UInt8]) { engine?.node.onFrame(linkId, frame) }
        func onLog(_ msg: String) { engine?.repo.log(msg) }
        func onNeed(_ need: Need, frame: Frame, fromLink: String?) { engine?.gossipNeed(need, frame, fromLink) }
        func onAckForMe(_ ack: Ack, frame: Frame) { engine?.gossipAckForMe(ack) }
        func onLocHint(_ hint: LocHint, frame: Frame, fromLink: String?) { engine?.gossipLocHint(hint, frame, fromLink) }
        func onBroadcast(_ broadcast: Broadcast, frame: Frame, fromLink: String?) { engine?.gossipBroadcast(broadcast, frame, fromLink) }
        func onAnnouncement(_ announcement: Announcement, frame: Frame, fromLink: String?) { engine?.gossipAnnouncement(announcement, frame, fromLink) }
        func onPeerHello(linkId: String, hello: Hello) { engine?.gossipPeerHello(linkId, hello) }
        func gatewayChanged(_ internet: Bool, _ outcome: SyncOutcome?) { engine?.gatewayChanged(internet: internet, outcome: outcome) }
    }

    /// - Parameters:
    ///   - repository: stores, settings, observation hub.
    ///   - transport: the radio (CoreBluetooth in the app, a fake in tests); attached immediately.
    ///   - syncClient: backend client; pass one with a fake `HTTPTransport` in tests.
    ///   - hintFix: this phone's current position for LOC_HINT (SPEC 5.5), nil when it has none; read from the engine's
    ///     queue, so it must be thread-safe (see `LatestFix`). The default never has a fix, so no hint is ever sent.
    ///   - requestFix: asks the platform to start a short location listen when a hint is wanted and there is no fix.
    public init(repository: MeshRepository, transport: any MeshTransport, syncClient: SyncClient = SyncClient(),
                hintFix: @escaping @Sendable () -> RelayFix? = { nil }, requestFix: @escaping @Sendable () -> Void = {},
                district: @escaping @Sendable () -> String? = { nil }) {
        let bridge = Bridge()
        self.bridge = bridge
        self.repo = repository
        self.transport = transport
        self.store = repository.packetStore
        self.nodeId = repository.nodeId
        let settings = repository.settings
        let repo = repository
        self.recentPeers = RecentPeers(initial: settings.recentPeers) { settings.recentPeers = $0 }
        let queue = self.queue
        let node = GossipNode(
            nodeId: repository.nodeId, sender: bridge, clock: WallClock(),
            scheduler: DispatchQueueScheduler(queue: queue), store: repository.packetStore,
            listener: bridge, appVersion: 1, pushBudgetBytes: PushPlanner.defaultBudgetBytes)
        self.node = node
        // Re-checks the "Help locate neighbours" setting at the moment of emission, so turning it off stops hinting
        // immediately.
        self.locHints = LocHintResponder(
            node: node, clock: WallClock(), scheduler: DispatchQueueScheduler(queue: queue),
            enabled: { settings.current.helpLocateNeighbours },
            fix: hintFix,
            rssi: { [weak transport] linkId in transport?.rssiFor(linkId: linkId) },
            requestFix: requestFix,
            log: { repo.log($0) })
        let syncer = GatewaySyncer(
            node: node, store: repository.packetStore, state: repository.gatewayState, client: syncClient,
            config: { let s = settings.current; return SyncConfig(baseUrl: s.backendUrl, deviceKey: s.deviceKey) },
            log: { repo.log($0) }, district: district)
        self.syncer = syncer
        self.gateway = GatewayManager(
            node: node, syncer: syncer, scheduler: DispatchQueueScheduler(queue: DispatchQueue(label: "relay-gateway")),
            pretendOffline: { settings.current.pretendOffline },
            log: { repo.log($0) },
            onChange: { net, outcome in bridge.gatewayChanged(net, outcome) })
        bridge.engine = self
        st.lastBackendUrl = settings.current.backendUrl
        transport.attach(
            events: bridge,
            environment: MeshTransportEnvironment(
                myNodeId: repository.nodeId,
                allowlist: { settings.current.allowlist },
                dutyMode: { [weak self] in self?.dutyMode() ?? .normal },
                recentPeers: recentPeers))
    }

    public var isRunning: Bool { lock.lock(); defer { lock.unlock() }; return st.running }

    // ---------------------------------------------------------------- platform inputs

    /// Bluetooth radio state (`CBCentralManager.state == .poweredOn`). Turning it off stops the transport; turning it
    /// on restarts it, like Android's adapter-state receiver.
    public func setBluetoothOn(_ on: Bool) {
        lock.lock()
        let changed = st.bluetoothOn != on
        st.bluetoothOn = on
        let live = st.running
        lock.unlock()
        if changed && live {
            if on { repo.log("Bluetooth turned on; restarting transport"); transport.start() }
            else { repo.log("Bluetooth turned off; stopping transport"); transport.stop() }
        }
        publish()
    }

    /// The app is in the foreground (scene active): scans continuously instead of duty-cycling.
    public func setForeground(_ foreground: Bool) {
        lock.lock(); st.foreground = foreground; lock.unlock()
        publish()
    }

    /// Battery level 0...100 (-1 unknown) and whether the phone is charging; drives the scan duty mode.
    public func setBattery(percent: Int, charging: Bool) {
        lock.lock(); st.batteryPct = percent; st.charging = charging; lock.unlock()
        publish()
    }

    /// Validated internet path available (an `NWPathMonitor` with `.satisfied`). While true and "Pretend offline" is
    /// off, the node acts as a gateway.
    public func setInternetAvailable(_ available: Bool) {
        gateway.setInternetAvailable(available)
    }

    private func dutyMode() -> DutyCycle.Mode {
        lock.lock(); defer { lock.unlock() }
        return DutyCycle.choose(batteryPct: st.batteryPct, charging: st.charging, foreground: st.foreground)
    }

    // ---------------------------------------------------------------- lifecycle

    public func start() {
        lock.lock()
        if st.running || st.stopped { lock.unlock(); return }
        st.running = true
        lock.unlock()
        repo.log("engine: starting (node \(Hex.encode(nodeId)))")
        repo.engine = self
        let token = repo.settings.addListener { [weak self] s in self?.settingsChanged(s) }
        lock.lock()
        st.settingsToken = token
        st.lastBackendUrl = repo.settings.current.backendUrl
        lock.unlock()
        transport.start()
        gateway.start()
        let timers = [
            makeTimer(afterMs: MeshEngine.resyncIntervalMs, everyMs: MeshEngine.resyncIntervalMs) { [weak self] in self?.node.resync() },
            makeTimer(afterMs: MeshEngine.expireIntervalMs, everyMs: MeshEngine.expireIntervalMs) { [weak self] in self?.node.expire() },
            makeTimer(afterMs: MeshEngine.publishIntervalMs, everyMs: MeshEngine.publishIntervalMs) { [weak self] in self?.publish() },
        ]
        lock.lock(); st.timers = timers; lock.unlock()
        queue.async { [weak self] in self?.resendOwnNeeds() }
        // Broadcasts carried in the store from before this start still count (their 24 h life runs from issue).
        queue.async { [weak self] in
            guard let self else { return }
            for (b, _) in self.node.storedBroadcasts() { self.repo.disasters.apply(b) }
        }
        publish()
    }

    /// Re-checks Bluetooth / permissions and starts the transport if it was waiting (idempotent).
    public func ensureTransport() { transport.start() }

    public func stop() {
        lock.lock()
        if !st.running { lock.unlock(); return }
        st.running = false
        st.stopped = true
        let token = st.settingsToken
        st.settingsToken = nil
        let timers = st.timers
        st.timers = []
        lock.unlock()
        repo.log("engine: stopping")
        if let token { repo.settings.removeListener(token) }
        for t in timers { t.cancel() }
        gateway.stop()
        transport.shutdown()
        store.flush()
        if repo.engine === self { repo.engine = nil }
        var s = repo.status
        s.serviceRunning = false
        s.isGateway = false
        s.peers = []
        repo.publishStatus(s)
    }

    private func makeTimer(afterMs: Int, everyMs: Int, _ handler: @escaping @Sendable () -> Void) -> DispatchSourceTimer {
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .milliseconds(afterMs), repeating: .milliseconds(everyMs))
        t.setEventHandler(handler: handler)
        t.resume()
        return t
    }

    /// Own NEEDs that never made it into the store (the engine was down when they were created) go out now.
    private func resendOwnNeeds() {
        var toSend: [Need] = repo.drainOutbox()
        let now = currentTimeMs()
        for m in repo.myNeedsStore.list() {
            if m.stage != .queued || m.cancelled { continue }
            if now - m.createdAtMs > 72 * 3600 * 1000 { continue }
            guard let data = Data(base64Encoded: m.needPayloadB64), let need = NeedCodec.decode([UInt8](data)) else { continue }
            if !toSend.contains(where: { $0.msgIdHex == need.msgIdHex }) { toSend.append(need) }
        }
        for n in toSend { originate(n) }
    }

    /// Hands [need] to the gossip node (stores it and pushes it to every live link).
    public func originate(_ need: Need) {
        do {
            _ = try node.originateNeed(need)
            repo.log("originated NEED \(need.msgIdHex) cat=\(need.category.name) urgency=\(need.urgency) item=\"\(need.item)\"")
        } catch {
            repo.log("could not originate NEED \(need.msgIdHex): \(error)")
        }
        publish()
    }

    /// Developer/demo: this phone speaks as the control room. The broadcast goes through the same path as one from a gateway
    /// (stored, flooded, delivered to `gossipBroadcast`), so it also switches on the questions on this phone. Returns false
    /// when it was not originated.
    @discardableResult
    public func declareDisaster(_ type: DisasterType, cleared: Bool = false) -> Bool {
        let b = Broadcast(id: NodeId.random(), kind: cleared ? .disasterCleared : .disaster, disaster: type,
                          issuedAt: Int64(Date().timeIntervalSince1970))
        return ((try? node.originateBroadcast(b)) ?? nil) != nil
    }

    public func resyncNow() {
        repo.log("resync requested")
        node.resync()
        gateway.kick()
    }

    // ---------------------------------------------------------------- settings

    private func settingsChanged(_ s: Settings) {
        lock.lock()
        let backendChanged = s.backendUrl != st.lastBackendUrl
        if backendChanged {
            // New backend: the old outcome says nothing about it. Retry right away so Home shows the result.
            st.lastBackendUrl = s.backendUrl
            st.syncHealth = SyncHealth()
            st.lastSyncError = nil
        }
        lock.unlock()
        if backendChanged { gateway.kick() }
        gateway.refresh()
    }

    // ---------------------------------------------------------------- gossip -> radio

    fileprivate func sendFrame(_ linkId: String, _ frame: [UInt8]) {
        noteOwnNeedSent(frame)
        transport.send(linkId: linkId, frame: frame)
    }

    /// Mark "Sent to mesh" the first time one of our own NEED frames is handed to a link.
    private func noteOwnNeedSent(_ raw: [UInt8]) {
        if raw.count < 2 || Int(raw[1]) != FrameTypes.need { return }
        guard let f = FrameCodec.decode(raw), f.senderId == nodeId, let need = NeedCodec.decode(f.payload) else { return }
        if repo.myNeedsStore.update(need.msgIdHex, { $0.markSentToMesh() }) != nil { repo.refreshMyNeeds() }
    }

    // ---------------------------------------------------------------- radio -> gossip

    fileprivate func linkUp(_ linkId: String) {
        repo.log("linkUp \(linkId)")
        node.linkUp(linkId)
        publish()
    }

    fileprivate func linkDown(_ linkId: String) {
        repo.log("linkDown \(linkId)")
        node.linkDown(linkId)
        publish()
    }

    // ---------------------------------------------------------------- GossipListener

    fileprivate func gossipNeed(_ need: Need, _ frame: Frame, _ fromLink: String?) {
        locHints.onNeed(need, fromLink: fromLink)
        repo.log("NEED delivered \(need.msgIdHex) from=\(fromLink ?? "local") ttl=\(frame.ttl) cat=\(need.category.name) urgency=\(need.urgency) item=\"\(need.item)\"")
        gateway.kick()
        publish()
    }

    fileprivate func gossipLocHint(_ hint: LocHint, _ frame: Frame, _ fromLink: String?) {
        repo.log("LOC_HINT stored ref=\(hint.refMsgIdHex) relay=\(Hex.encode(hint.relayNode)) from=\(fromLink ?? "local") "
            + "acc<=\(hint.accuracyMaxM) m ttl=\(frame.ttl)")
        gateway.kick() // no-op unless this phone is a gateway; lets hints upload promptly
        publish()
    }

    fileprivate func gossipBroadcast(_ b: Broadcast, _ frame: Frame, _ fromLink: String?) {
        repo.log("BROADCAST \(b.idHex) \(b.kind) \(b.disaster.key) issued=\(b.issuedAt) from=\(fromLink ?? "local") ttl=\(frame.ttl)")
        repo.disasters.apply(b)
        publish()
    }

    /// Called (on the engine queue) when an ANNOUNCEMENT is stored for the first time; the app reloads its cards and notifies.
    public var onAnnouncement: (@Sendable (Announcement) -> Void)?

    fileprivate func gossipAnnouncement(_ a: Announcement, _ frame: Frame, _ fromLink: String?) {
        repo.log("ANNOUNCEMENT \(a.idHex) \(a.lang.tag) \(a.status) district=\(a.district) from=\(fromLink ?? "local") ttl=\(frame.ttl)")
        onAnnouncement?(a)
        publish()
    }

    fileprivate func gossipAckForMe(_ ack: Ack) {
        let id = Hex.encode(ack.refMsgId)
        repo.log("ACK for me ref=\(id) ticket=#\(ack.ticketNo) status=\(ack.status.name) text=\"\(ack.text)\"")
        let updated = repo.myNeedsStore.update(id) { $0.withAck(ack.status, ticket: ack.ticketNo, text: ack.text, eta: ack.etaMin) }
        if updated == nil { repo.log("ACK refers to an unknown own NEED \(id)") } else { repo.refreshMyNeeds() }
        publish()
    }

    fileprivate func gossipPeerHello(_ linkId: String, _ hello: Hello) {
        repo.log("HELLO from \(Hex.encode(hello.nodeId)) via \(linkId) gateway=\(hello.isGateway) hops=\(hello.hopsToGateway)")
        transport.onPeerHello(linkId: linkId, nodeId: hello.nodeId)
        publish()
    }

    // ---------------------------------------------------------------- gateway

    fileprivate func gatewayChanged(internet: Bool, outcome: SyncOutcome?) {
        lock.lock()
        st.internet = internet
        if let o = outcome {
            let now = currentTimeMs()
            if o.ok { st.lastSyncOkAt = now; st.lastSyncError = nil } else { st.lastSyncError = o.error }
            st.syncHealth = st.syncHealth.record(o.ok, o.error, now, repo.settings.current.backendUrl)
        }
        lock.unlock()
        if outcome?.ok == true { repo.noteUploads() }
        publish()
    }

    // ---------------------------------------------------------------- status snapshot for the UI

    /// Rebuilds the status from the node, store, transport and platform inputs and publishes it (no-op if unchanged).
    public func publish() {
        lock.lock()
        let s = st
        lock.unlock()
        if !s.running { return }
        let hellos = node.peers()
        let peers = node.linkIds().sorted().map { id -> PeerInfo in
            let h = hellos[id]
            return PeerInfo(linkId: id, nodeIdHex: h.map { Hex.encode($0.nodeId) }, isGateway: h?.isGateway == true,
                            hops: h?.hopsToGateway ?? HelloCodec.hopsUnknown)
        }
        let all = store.all()
        let mode = DutyCycle.choose(batteryPct: s.batteryPct, charging: s.charging, foreground: s.foreground)
        let gw = node.isGateway
        repo.publishStatus(MeshStatus(
            serviceRunning: true,
            bluetoothOn: s.bluetoothOn,
            peers: peers,
            isGateway: gw,
            hopsToGateway: node.hopsToGateway,
            storedNeeds: all.filter { $0.type == FrameTypes.need }.count,
            storedAcks: all.filter { $0.type == FrameTypes.ack }.count,
            storedHints: all.filter { $0.type == FrameTypes.locHint }.count,
            stats: node.stats(),
            transport: transport.info(),
            internetAvailable: s.internet,
            lastSyncOkAtMs: s.lastSyncOkAt,
            lastSyncError: s.lastSyncError,
            pendingUploads: gw ? syncer.pendingNeedKeys().count : 0,
            syncHealth: s.syncHealth,
            batteryPercent: s.batteryPct,
            charging: s.charging,
            scanMode: mode,
            meshHoursRemaining: MeshEnergy.hoursRemaining(batteryPct: s.batteryPct, charging: s.charging, mode: mode),
            recentPeerIds: recentPeers.all().map { $0.nodeIdHex }))
    }
}
