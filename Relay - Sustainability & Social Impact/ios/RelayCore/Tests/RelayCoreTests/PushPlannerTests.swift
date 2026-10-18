import XCTest
@testable import RelayCore

final class PushPlannerTests: XCTestCase {
    private let peer = NodeId.random()
    private let other = NodeId.random()

    private func needPkt(_ createdAtSec: Int64) -> StoredPacket {
        let n = sampleNeed(originNode: NodeId.random(), createdAtSec: createdAtSec)
        return StoredPacket(key: n.msgIdHex, type: FrameTypes.need, raw: needFrameBytes(n, ts: T0), expiryBasisMs: n.createdAt * 1000)
    }

    private func ackPkt(_ target: [UInt8], _ firstStoredMs: Int64) -> StoredPacket {
        let n = sampleNeed(originNode: target)
        let a = sampleAck(n)
        let f = Frame(type: FrameTypes.ack, ttl: 7, timestampMs: T0, senderId: NodeId.random(), recipientId: target, payload: encodeAck(a))
        return StoredPacket(key: a.ackIdHex, type: FrameTypes.ack, raw: encodeFrame(f), expiryBasisMs: firstStoredMs)
    }

    func testAcksFirstThenNeedsNewestFirst() {
        let oldNeed = needPkt(1_000), newNeed = needPkt(2_000)
        let ackOther = ackPkt(other, T0), ackPeer = ackPkt(peer, T0 - 10_000)
        let order = PushPlanner.order([oldNeed, ackOther, newNeed, ackPeer], peerNodeId: peer)
        XCTAssertEqual([ackPeer.key, ackOther.key, newNeed.key, oldNeed.key], order.map { $0.key })
    }

    func testNewestAckFirstWithinTheAckGroup() {
        let a1 = ackPkt(other, T0), a2 = ackPkt(other, T0 + 5_000)
        XCTAssertEqual([a2.key, a1.key], PushPlanner.order([a1, a2], peerNodeId: nil).map { $0.key })
    }

    func testBatchIsCutAtTheByteBudgetAndKeepsOrder() {
        let pkts = (1...10).map { needPkt(1_000 + Int64($0)) }
        let size = pkts[0].raw.count
        let plan = PushPlanner.plan(pkts, peerNodeId: peer, budgetBytes: size * 3 + 1)
        XCTAssertEqual(3, plan.batch.count)
        XCTAssertEqual(7, plan.rest.count)
        XCTAssertEqual(pkts.last!.key, plan.batch.first!.key, "newest first")
        XCTAssertEqual(Set(pkts.map { $0.key }), Set((plan.batch + plan.rest).map { $0.key }))
    }

    func testAlwaysPushesAtLeastOneFrame() {
        let plan = PushPlanner.plan([needPkt(1), needPkt(2)], peerNodeId: peer, budgetBytes: 1)
        XCTAssertEqual(1, plan.batch.count); XCTAssertEqual(1, plan.rest.count)
    }

    func testDefaultBudgetIsAboutTwoMtus() {
        XCTAssertEqual(1028, PushPlanner.defaultBudgetBytes)
        let plan = PushPlanner.plan((1...30).map { needPkt(1_000 + Int64($0)) }, peerNodeId: peer)
        let bytes = plan.batch.reduce(0) { $0 + $1.raw.count }
        XCTAssertLessThanOrEqual(bytes, PushPlanner.defaultBudgetBytes, "batch of \(bytes) B within budget")
        XCTAssertGreaterThanOrEqual(plan.batch.count, 5)
    }

    // ------------------------------------------------------------ through GossipNode

    private final class Rig: LinkSender, Clock, Scheduler, GossipListener {
        var now = T0
        var sent: [(String, [UInt8])] = []
        var tasks: [(Int64, @Sendable () -> Void)] = []
        var node: GossipNode!

        init(budget: Int) {
            node = GossipNode(nodeId: NodeId.random(), sender: self, clock: self, scheduler: self,
                              store: InMemoryPacketStore(), random: SeededRandom(seed: 3), listener: self,
                              pushBudgetBytes: budget, pushContinueMs: 2_000)
        }
        func send(linkId: String, frame: [UInt8]) { sent.append((linkId, frame)) }
        func nowMs() -> Int64 { now }
        func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void) { tasks.append((delayMs, task)) }

        func pushedNeeds(_ link: String) -> [Need] {
            sent.filter { $0.0 == link }.compactMap { FrameCodec.decode($0.1) }.filter { $0.type == FrameTypes.need }
                .compactMap { NeedCodec.decode($0.payload) }
        }
        func runTasks() { let t = tasks; tasks.removeAll(); t.forEach { $0.1() } }
        func peerInventory(_ link: String, _ ids: [[UInt8]]) {
            let inv = Frame(type: FrameTypes.inventory, ttl: 1, timestampMs: now, senderId: NodeId.random(),
                            payload: encodeInventory(Inventory(0, 1, ids)))
            node.onFrame(link, encodeFrame(inv))
        }
    }

    func testCappedDiffPushesNewestFirstThenContinuesWithoutWaitingForResync() {
        let rig = Rig(budget: 2 * needFrameBytes(sampleNeed(originNode: NodeId.random())).count + 1)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        let needs = (1...6).map { sampleNeed(originNode: NodeId.random(), createdAtSec: T0 / 1000 + Int64($0)) }
        for n in needs { rig.node.onFrame("L1", needFrameBytes(n)) }
        rig.runTasks(); rig.sent.removeAll()

        rig.peerInventory("L2", [])
        let first = rig.pushedNeeds("L2")
        XCTAssertEqual(2, first.count, "first burst is capped at two frames")
        XCTAssertEqual(needs.last!.msgIdHex, first.first!.msgIdHex, "newest first")
        XCTAssertEqual(1, rig.tasks.count)
        XCTAssertEqual(2_000, rig.tasks[0].0, "a continuation is scheduled at 2 s")

        var guardCount = 0
        while !rig.tasks.isEmpty && guardCount < 20 { rig.runTasks(); guardCount += 1 }
        let all = rig.pushedNeeds("L2")
        XCTAssertEqual(Set(needs.map { $0.msgIdHex }), Set(all.map { $0.msgIdHex }), "every frame arrives exactly once")
        XCTAssertEqual(needs.count, all.count)
        XCTAssertEqual(needs.reversed().map { $0.msgIdHex }, all.map { $0.msgIdHex }, "order is newest first overall")
    }

    func testContinuationStopsWhenTheLinkGoesDown() {
        let rig = Rig(budget: 1)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        for i in 0..<4 { rig.node.onFrame("L1", needFrameBytes(sampleNeed(originNode: NodeId.random(), createdAtSec: T0 / 1000 + Int64(i)))) }
        rig.runTasks(); rig.sent.removeAll()
        rig.peerInventory("L2", [])
        let before = rig.pushedNeeds("L2").count
        rig.node.linkDown("L2")
        rig.runTasks()
        XCTAssertEqual(before, rig.pushedNeeds("L2").count)
    }

    func testUnlimitedBudgetKeepsTheOldPushEverythingBehaviour() {
        let rig = Rig(budget: Int.max)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        for i in 0..<30 { rig.node.onFrame("L1", needFrameBytes(sampleNeed(originNode: NodeId.random(), createdAtSec: T0 / 1000 + Int64(i)))) }
        rig.runTasks(); rig.sent.removeAll()
        rig.peerInventory("L2", [])
        XCTAssertEqual(30, rig.pushedNeeds("L2").count)
        XCTAssertTrue(rig.tasks.isEmpty)
    }

    func testAckForPeerGoesBeforeNeedsInTheFirstBurst() {
        let rig = Rig(budget: 1)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        // Learn the peer's id through its HELLO so the planner can recognise ACKs addressed to it.
        let peerId = NodeId.random()
        let hello = Frame(type: FrameTypes.hello, ttl: 1, timestampMs: T0, senderId: peerId,
                          payload: encodeHello(Hello(nodeId: peerId, caps: 0, hopsToGateway: 0xFF, appVersion: 1)))
        rig.node.onFrame("L2", encodeFrame(hello))
        for i in 0..<3 { rig.node.onFrame("L1", needFrameBytes(sampleNeed(originNode: NodeId.random(), createdAtSec: T0 / 1000 + Int64(i)))) }
        let n = sampleNeed(originNode: peerId), ack = sampleAck(n)
        let af = Frame(type: FrameTypes.ack, ttl: 7, timestampMs: T0, senderId: NodeId.random(), recipientId: peerId, payload: encodeAck(ack))
        rig.node.onFrame("L1", encodeFrame(af))
        rig.runTasks(); rig.sent.removeAll()
        rig.peerInventory("L2", [])
        let firstType = rig.sent.filter { $0.0 == "L2" }.compactMap { FrameCodec.decode($0.1) }
            .first { $0.type != FrameTypes.hello && $0.type != FrameTypes.inventory }!.type
        XCTAssertEqual(FrameTypes.ack, firstType)
        XCTAssertEqual(16, Hex.encode(ack.ackId).count)
    }
}
