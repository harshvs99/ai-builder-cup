import XCTest
import RelayCore
@testable import RelayKit

/// A `MeshTransport` that records what the engine asks of it and lets the test play the radio.
final class FakeTransport: MeshTransport, @unchecked Sendable {
    private let lock = NSLock()
    private var _events: (any MeshTransportEvents)?
    private var _env: MeshTransportEnvironment?
    private var _sent: [(String, [UInt8])] = []
    private var _starts = 0, _stops = 0, _shutdowns = 0
    private var _hellos: [(String, [UInt8])] = []
    /// Where sent frames go: (linkId on this side, frame). Wired up by `connect`.
    var deliver: (@Sendable (String, [UInt8]) -> Void)?

    var events: (any MeshTransportEvents)? { lock.lock(); defer { lock.unlock() }; return _events }
    var environment: MeshTransportEnvironment? { lock.lock(); defer { lock.unlock() }; return _env }
    var sent: [(String, [UInt8])] { lock.lock(); defer { lock.unlock() }; return _sent }
    var starts: Int { lock.lock(); defer { lock.unlock() }; return _starts }
    var stops: Int { lock.lock(); defer { lock.unlock() }; return _stops }
    var shutdowns: Int { lock.lock(); defer { lock.unlock() }; return _shutdowns }
    var hellos: [(String, [UInt8])] { lock.lock(); defer { lock.unlock() }; return _hellos }

    func attach(events: any MeshTransportEvents, environment: MeshTransportEnvironment) {
        lock.lock(); _events = events; _env = environment; lock.unlock()
    }
    func start() { lock.lock(); _starts += 1; lock.unlock() }
    func stop() { lock.lock(); _stops += 1; lock.unlock() }
    func shutdown() { lock.lock(); _shutdowns += 1; lock.unlock() }
    func onPeerHello(linkId: String, nodeId: [UInt8]) { lock.lock(); _hellos.append((linkId, nodeId)); lock.unlock() }
    func info() -> MeshTransportInfo { MeshTransportInfo(running: true, scanning: true, advertising: true, centralLinks: 1, peripheralLinks: 0, dutyMode: environment?.dutyMode() ?? .normal) }
    func send(linkId: String, frame: [UInt8]) {
        lock.lock(); _sent.append((linkId, frame)); let d = deliver; lock.unlock()
        d?(linkId, frame)
    }
}

final class MeshEngineTests: TempDirTestCase {
    private func makeRepo(_ name: String, backend: String = "http://backend.test") -> MeshRepository {
        let settings = AppSettings(store: InMemoryKeyValueStore(), buildConfig: BuildConfig(defaultBackend: backend, defaultKey: "k", lockConnection: false))
        return MeshRepository(dataDirectory: tmp.appendingPathComponent(name), settings: settings, packetFlushDelayMs: 0)
    }

    private func newNeed(for repo: MeshRepository, item: String = "insulin") -> Need {
        Need(msgId: NodeId.random(), originNode: repo.nodeId, createdAt: currentTimeMs() / 1000, category: .medical, urgency: 3, people: 2,
             vulnFlags: VulnFlag.toMask([.elderly]), latE5: 2029612, lonE5: 8582451, locAcc: 2, lang: .en, item: item,
             landmark: "school", summary: "needs \(item)", answers: [NeedAnswer(3, 1)])
    }

    /// Joins two engines with an in-memory radio (frames hop through a queue, as BLE writes would).
    private func connect(_ a: FakeTransport, as linkA: String, _ b: FakeTransport, as linkB: String) {
        let q = DispatchQueue(label: "fake-radio")
        a.deliver = { _, frame in q.async { b.events?.onFrame(linkB, frame) } }
        b.deliver = { _, frame in q.async { a.events?.onFrame(linkA, frame) } }
        a.events?.onLinkUp(linkA)
        b.events?.onLinkUp(linkB)
    }

    func testLifecycleAttachesStartsAndShutsTheTransportDown() throws {
        let repo = makeRepo("a")
        let fake = FakeTransport()
        let engine = MeshEngine(repository: repo, transport: fake)
        XCTAssertNotNil(fake.events, "attached at init")
        XCTAssertEqual(repo.nodeId, fake.environment?.myNodeId)
        XCTAssertFalse(engine.isRunning)
        XCTAssertFalse(repo.status.serviceRunning)

        engine.setBluetoothOn(true)
        engine.start()
        engine.start() // idempotent
        XCTAssertTrue(engine.isRunning)
        XCTAssertEqual(1, fake.starts)
        XCTAssertTrue(repo.engine === engine)
        XCTAssertTrue(repo.status.serviceRunning)
        XCTAssertTrue(repo.status.bluetoothOn)
        XCTAssertEqual(true, repo.status.transport?.running)

        engine.stop()
        XCTAssertFalse(engine.isRunning)
        XCTAssertEqual(1, fake.shutdowns)
        XCTAssertNil(repo.engine)
        XCTAssertFalse(repo.status.serviceRunning)
        engine.start() // single use
        XCTAssertFalse(engine.isRunning)
    }

    func testBluetoothStateDrivesTheTransport() {
        let repo = makeRepo("a")
        let fake = FakeTransport()
        let engine = MeshEngine(repository: repo, transport: fake)
        engine.start()
        XCTAssertEqual(1, fake.starts)
        engine.setBluetoothOn(true)
        XCTAssertEqual(2, fake.starts, "turning Bluetooth on restarts the transport")
        engine.setBluetoothOn(true)
        XCTAssertEqual(2, fake.starts, "no change, no restart")
        engine.setBluetoothOn(false)
        XCTAssertEqual(1, fake.stops)
        XCTAssertFalse(repo.status.bluetoothOn)
        XCTAssertEqual(.bluetoothOff, VillagerStatus.from(repo.status, now: currentTimeMs()).kind)
        engine.ensureTransport()
        XCTAssertEqual(3, fake.starts)
        engine.stop()
    }

    func testPlatformInputsReachTheStatusAndTheTransportEnvironment() throws {
        let repo = makeRepo("a")
        let fake = FakeTransport()
        let engine = MeshEngine(repository: repo, transport: fake)
        engine.start()
        let env = try XCTUnwrap(fake.environment)
        XCTAssertEqual(.normal, env.dutyMode())

        engine.setForeground(true)
        XCTAssertEqual(.continuous, env.dutyMode())
        XCTAssertEqual(.continuous, repo.status.scanMode)
        engine.setBattery(percent: 15, charging: false)
        XCTAssertEqual(.lowBattery, env.dutyMode())
        XCTAssertEqual(15, repo.status.batteryPercent)
        XCTAssertNotNil(repo.status.meshHoursRemaining)
        engine.setBattery(percent: 15, charging: true)
        XCTAssertNil(repo.status.meshHoursRemaining)
        XCTAssertEqual(.continuous, env.dutyMode())

        repo.settings.update { var s = $0; s.allowlist = "a1b2"; return s }
        XCTAssertEqual("a1b2", env.allowlist(), "settings are read live")
        engine.stop()
    }

    func testSubmittedNeedIsStoredMarkedSentAndGossipedToTheLink() throws {
        let repo = makeRepo("a")
        let fake = FakeTransport()
        let engine = MeshEngine(repository: repo, transport: fake)
        engine.start()
        fake.events?.onLinkUp("c:peer")
        let need = newNeed(for: repo)
        try repo.submitNeed(need)

        let mine = try XCTUnwrap(repo.myNeeds.first)
        XCTAssertEqual(need.msgIdHex, mine.msgIdHex)
        XCTAssertEqual(need.createdAt * 1000, mine.createdAtMs)
        XCTAssertEqual([3: 1], mine.answers)
        XCTAssertEqual(VulnFlag.toMask([.elderly]), mine.vulnFlags)
        XCTAssertEqual(need, NeedCodec.decode([UInt8](try XCTUnwrap(Data(base64Encoded: mine.needPayloadB64)))), "payload round-trips")
        XCTAssertTrue(repo.packetStore.contains(need.msgIdHex))
        // The NEED frame was handed to the link: "Sent to mesh" flips on the first send.
        XCTAssertTrue(waitUntil { repo.myNeeds.first?.sentToMesh == true })
        XCTAssertNotEqual(0, repo.myNeeds.first?.sentToMeshAtMs)
        XCTAssertTrue(fake.sent.contains { $0.0 == "c:peer" && $0.1.count > 1 && Int($0.1[1]) == FrameTypes.need })
        XCTAssertEqual(1, repo.status.storedNeeds)
        engine.stop()
    }

    func testNeedSubmittedWhileStoppedIsQueuedAndOriginatedOnStart() throws {
        let repo = makeRepo("a")
        let need = newNeed(for: repo)
        try repo.submitNeed(need)
        XCTAssertFalse(repo.packetStore.contains(need.msgIdHex), "no engine yet")
        XCTAssertTrue(repo.logLines.contains { $0.contains("queued until it starts") })

        let engine = MeshEngine(repository: repo, transport: FakeTransport())
        engine.start()
        XCTAssertTrue(waitUntil { repo.packetStore.contains(need.msgIdHex) })
        XCTAssertTrue(repo.drainOutbox().isEmpty, "the engine drained the outbox")
        engine.stop()
    }

    func testQueuedNeedsFromAnEarlierRunAreResentAfterRestart() throws {
        let need: Need
        do {
            let repo = makeRepo("a")
            need = newNeed(for: repo)
            try repo.submitNeed(need) // never reached the store
            repo.packetStore.flush()
        }
        let repo2 = MeshRepository(dataDirectory: tmp.appendingPathComponent("a"), settings: AppSettings(store: InMemoryKeyValueStore()), packetFlushDelayMs: 0)
        XCTAssertEqual(1, repo2.myNeeds.count, "my_needs.json reloaded")
        XCTAssertEqual(0, repo2.drainOutbox().count, "the in-memory outbox is gone; the file is the source of truth")
        let engine = MeshEngine(repository: repo2, transport: FakeTransport())
        engine.start()
        XCTAssertTrue(waitUntil { repo2.packetStore.contains(need.msgIdHex) }, "QUEUED needs are decoded from needPayloadB64 and originated")
        engine.stop()
    }

    func testCancelStopsRelayingAndKeepsTheRecord() throws {
        let repo = makeRepo("a")
        let engine = MeshEngine(repository: repo, transport: FakeTransport())
        engine.start()
        let need = newNeed(for: repo)
        try repo.submitNeed(need)
        XCTAssertTrue(repo.packetStore.contains(need.msgIdHex))
        XCTAssertTrue(repo.cancel(need.msgIdHex))
        XCTAssertFalse(repo.packetStore.contains(need.msgIdHex))
        XCTAssertEqual(true, repo.myNeeds.first?.cancelled)
        XCTAssertFalse(repo.cancel("deadbeef"))
        engine.stop()
    }

    func testIdentityIsStableAcrossRestarts() {
        let a = makeRepo("a").nodeId
        XCTAssertEqual(8, a.count)
        XCTAssertEqual(a, makeRepo("a").nodeId)
        XCTAssertNotEqual(a, makeRepo("other").nodeId)
    }

    /// The whole loop, in process: A (villager) -> link -> B (gateway) -> fake backend -> ACK -> link -> A.
    func testNeedReachesGatewayIsUploadedAndTheAckComesBack() throws {
        let repoA = makeRepo("a"), repoB = makeRepo("b")
        let fakeA = FakeTransport(), fakeB = FakeTransport()
        let backend = FakeHTTP()
        let backendNeed = Box<Need?>(nil)
        backend.respond { body in
            guard let b64 = (body["frames"] as? [String])?.first, let raw = Data(base64Encoded: b64),
                  let frame = FrameCodec.decode([UInt8](raw)), let need = NeedCodec.decode(frame.payload) else {
                return ["accepted": 0, "duplicates": 0, "rejected": 0, "acks": [String](), "ack_cursor": 0]
            }
            backendNeed.value = need
            let ack = Ack(ackId: NodeId.random(), refMsgId: need.msgId, targetNode: need.originNode, ticketNo: 142, status: .received,
                          etaMin: Ack.etaUnknown, text: "Received. Ticket 142.")
            let payload = Data(try! AckCodec.encode(ack)).base64EncodedString()
            return ["accepted": 1, "duplicates": 0, "rejected": 0, "acks": [payload], "ack_cursor": 1]
        }
        let engineA = MeshEngine(repository: repoA, transport: fakeA)
        let engineB = MeshEngine(repository: repoB, transport: fakeB, syncClient: SyncClient(transport: backend))
        engineA.setBluetoothOn(true); engineB.setBluetoothOn(true)
        engineA.start(); engineB.start()
        defer { engineA.stop(); engineB.stop() }
        connect(fakeA, as: "c:B", fakeB, as: "p:A")

        // HELLOs cross; A learns B is not a gateway yet.
        XCTAssertTrue(waitUntil { repoA.status.peers.first?.nodeIdHex == Hex.encode(repoB.nodeId) })
        XCTAssertTrue(waitUntil { fakeA.hellos.contains { $0.0 == "c:B" && $0.1 == repoB.nodeId } }, "the transport is told about the peer's HELLO")
        XCTAssertEqual(.waitingForGateway, VillagerStatus.from(repoA.status, now: currentTimeMs()).kind)

        // B gets internet and becomes the gateway; A sees it reachable at one hop.
        engineB.setInternetAvailable(true)
        XCTAssertTrue(repoB.status.isGateway || waitUntil { repoB.status.isGateway })
        XCTAssertTrue(waitUntil { VillagerStatus.from(repoA.status, now: currentTimeMs()).kind == .reachable })
        XCTAssertEqual(1, VillagerStatus.from(repoA.status, now: currentTimeMs()).hopsToGateway)

        // A sends a NEED.
        let need = newNeed(for: repoA)
        try repoA.submitNeed(need)

        XCTAssertTrue(waitUntil(10) { repoB.packetStore.contains(need.msgIdHex) }, "NEED reached the gateway over the link")
        XCTAssertTrue(waitUntil(10) { repoB.gatewayState.isUploaded(need.msgIdHex) }, "gateway uploaded it")
        XCTAssertEqual(need.msgIdHex, backendNeed.value?.msgIdHex)
        let req = try XCTUnwrap(backend.requests.first { (($0.body?["frames"] as? [String])?.count ?? 0) > 0 })
        XCTAssertEqual("http://backend.test/v1/sync", req.url.absoluteString)
        XCTAssertEqual("k", req.headers["X-Relay-Key"])

        // The ACK returns through the mesh and lands on A's request.
        XCTAssertTrue(waitUntil(10) { repoA.myNeeds.first?.stage == .received }, "RECEIVED ack reached the originator")
        let mine = try XCTUnwrap(repoA.myNeeds.first)
        XCTAssertEqual(142, mine.ticketNo)
        XCTAssertEqual("Received. Ticket 142.", mine.receivedText)
        XCTAssertTrue(mine.sentToMesh)
        let steps = RequestTimeline.steps(mine, now: currentTimeMs()).map { $0.key }
        XCTAssertEqual([.saved, .passedToNeighbour, .reachedGateway, .seenByControlRoom], steps)

        // The gateway's own status shows a healthy sync.
        XCTAssertTrue(waitUntil { repoB.status.syncHealth.lastOk == true })
        XCTAssertEqual(.thisPhoneGateway, VillagerStatus.from(repoB.status, now: currentTimeMs()).kind)
        XCTAssertTrue(repoB.status.internetAvailable)
        XCTAssertEqual("backend.test", repoB.status.syncHealth.host)
    }

    func testGatewayStampsUploadedOnItsOwnNeeds() throws {
        let repo = makeRepo("gw")
        let backend = FakeHTTP()
        let engine = MeshEngine(repository: repo, transport: FakeTransport(), syncClient: SyncClient(transport: backend))
        engine.setBluetoothOn(true)
        engine.start()
        defer { engine.stop() }
        engine.setInternetAvailable(true)
        XCTAssertTrue(waitUntil { repo.status.isGateway })
        let need = newNeed(for: repo)
        try repo.submitNeed(need)
        XCTAssertTrue(waitUntil(10) { (repo.myNeeds.first?.uploadedAtMs ?? 0) > 0 }, "noteUploads after a successful sync")
        let steps = RequestTimeline.steps(try XCTUnwrap(repo.myNeeds.first), now: currentTimeMs()).map { $0.key }
        XCTAssertEqual([.saved, .reachedGateway], steps.filter { $0 != .passedToNeighbour })
    }

    func testSnapshotStreamDeliversStatusAndNeeds() async throws {
        let repo = makeRepo("a")
        let engine = MeshEngine(repository: repo, transport: FakeTransport())
        var it = repo.snapshots().makeAsyncIterator()
        let first = await it.next()
        XCTAssertEqual(false, first?.status.serviceRunning)
        engine.start()
        defer { engine.stop() }
        try repo.submitNeed(newNeed(for: repo))
        var sawNeed = false, sawRunning = false
        for _ in 0..<20 {
            guard let s = await it.next() else { break }
            sawRunning = sawRunning || s.status.serviceRunning
            sawNeed = sawNeed || !s.myNeeds.isEmpty
            if sawNeed && sawRunning { break }
        }
        XCTAssertTrue(sawNeed && sawRunning)
    }

    func testLogKeepsTheLastHundredLines() {
        let repo = makeRepo("a")
        for i in 0..<150 { repo.log("line \(i)") }
        XCTAssertEqual(MeshRepository.maxLog, repo.logLines.count)
        XCTAssertTrue(repo.logLines.last?.hasSuffix("line 149") == true)
    }
}
