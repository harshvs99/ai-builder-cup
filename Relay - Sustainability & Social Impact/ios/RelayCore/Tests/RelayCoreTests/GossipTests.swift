import XCTest
@testable import RelayCore

final class GossipTests: XCTestCase {

    // ------------------------------------------------------------ end to end on a mesh

    /// Make [name] a gateway that ACKs every NEED it delivers.
    private func autoAck(_ net: TestNet, _ name: String) {
        let gw = net.node(name)
        gw.node.isGateway = true
        gw.onNeedHook = { need in try! gw.node.originateAck(sampleAck(need, ticket: 142)) }
    }

    func testShortChainDeliversNeedAndAckByFloodingAlone() throws {
        let net = TestNet()
        let names = (0...5).map { "n\($0)" }   // 5 hops: within TTL 7
        net.chain(names)
        autoAck(net, "n5")
        let origin = net.node("n0")
        let need = sampleNeed(originNode: origin.id)
        try origin.node.originateNeed(need)
        net.runUntilIdle()
        for n in names.dropFirst() { XCTAssertEqual(1, net.node(n).needsDelivered.count, "\(n) delivered NEED once") }
        XCTAssertEqual(1, origin.needsDelivered.count) // originator also "delivers" locally
        XCTAssertEqual(1, origin.acksForMe.count, "ACK reached origin exactly once")
        XCTAssertEqual(142, origin.acksForMe[0].ticketNo)
        for n in names { XCTAssertEqual(1, net.node(n).acksSeen.count, "\(n) sees ACK once") }
        for n in names.dropFirst().dropLast() { XCTAssertTrue(net.node(n).acksForMe.isEmpty) }
    }

    func testTenNodeChainDeliversNeedAndAckHundredPercentWithInventoryRounds() throws {
        let net = TestNet()
        let names = (0...9).map { "n\($0)" }
        net.chain(names)
        autoAck(net, "n9")
        let origin = net.node("n0")
        try origin.node.originateNeed(sampleNeed(originNode: origin.id))
        net.runUntilIdle()
        // 9 hops > TTL 7: pure flooding stops at hop 7 (documented behaviour)
        XCTAssertEqual(1, net.node("n7").needsDelivered.count)
        XCTAssertEqual(0, net.node("n8").needsDelivered.count)
        XCTAssertEqual(0, net.node("n9").needsDelivered.count)
        var rounds = 0
        while (net.node("n9").needsDelivered.isEmpty || origin.acksForMe.isEmpty) && rounds < 12 {
            net.resyncRound(); rounds += 1
        }
        XCTAssertEqual(1, net.node("n9").needsDelivered.count, "NEED delivered to gateway")
        XCTAssertEqual(1, origin.acksForMe.count, "ACK delivered to origin")
        XCTAssertLessThanOrEqual(rounds, 8, "converges within a few rounds, took \(rounds)")
        // everything delivered exactly once at every node
        for n in names {
            let nd = net.node(n)
            XCTAssertEqual(1, nd.needsDelivered.count, "\(n) NEED once")
            XCTAssertEqual(1, nd.acksSeen.count, "\(n) ACK once")
        }
    }

    func testEachNodeDeliversEachMessageOnceInADenseMesh() throws {
        let net = TestNet(seed: 5)
        let names = (0...7).map { "m\($0)" }
        for i in names.indices { for j in (i + 1)..<names.count { net.connect(names[i], names[j]) } }
        let origin = net.node("m0")
        let needs = (0...4).map { _ in sampleNeed(originNode: origin.id) }
        for n in needs { try origin.node.originateNeed(n) }
        net.runUntilIdle()
        var dupTotal: Int64 = 0
        for n in names {
            let nd = net.node(n)
            XCTAssertEqual(5, nd.needsDelivered.count, n)
            XCTAssertEqual(5, Set(nd.needsDelivered.map { hex($0.msgId) }).count)
            dupTotal += nd.node.stats().duplicates
        }
        XCTAssertGreaterThan(dupTotal, 0, "a dense mesh must have produced duplicates that were dropped")
    }

    func testTtlExpiryStopsFloodingButInventorySyncDeliversLater() throws {
        let net = TestNet()
        let names = (0...9).map { "n\($0)" }
        net.chain(names)
        let origin = net.node("n0")
        try origin.node.originateNeed(sampleNeed(originNode: origin.id))
        net.runUntilIdle()
        // n7 holds it with ttl 1: stored, not flooded onwards
        XCTAssertEqual(1, net.node("n7").needsDelivered.count)
        XCTAssertEqual(0, net.node("n8").needsDelivered.count)
        XCTAssertEqual(1, net.node("n7").node.storedNeeds().count)
        XCTAssertEqual(1, net.node("n7").node.storedNeeds()[0].1.ttl)
        // link n7-n8 flaps: the new link's INVENTORY exchange delivers it
        net.disconnect("n7", "n8"); net.connect("n7", "n8")
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("n8").needsDelivered.count)
        XCTAssertEqual(0, net.node("n9").needsDelivered.count, "ttl 1 on arrival: stored but not flooded to n9")
        XCTAssertEqual(1, net.node("n8").node.storedNeeds().count)
    }

    func testWalkerBridgesTwoPartitions() throws {
        let net = TestNet()
        net.chain(["a0", "a1", "a2"])
        net.chain(["b0", "b1", "b2"])
        autoAck(net, "b2")
        let a0 = net.node("a0")
        try a0.node.originateNeed(sampleNeed(originNode: a0.id))
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("a2").needsDelivered.count)
        XCTAssertEqual(0, net.node("b0").needsDelivered.count)

        // walker visits cluster A, leaves, visits cluster B
        net.connect("w", "a2"); net.runUntilIdle()
        XCTAssertEqual(1, net.node("w").needsDelivered.count)
        net.disconnect("w", "a2")
        net.connect("w", "b0"); net.runUntilIdle()
        for n in ["b0", "b1", "b2"] { XCTAssertEqual(1, net.node(n).needsDelivered.count, "\(n) got NEED via walker") }
        net.disconnect("w", "b0")
        XCTAssertEqual(0, a0.acksForMe.count)

        // ... and carries the gateway's ACK back
        net.connect("w", "b0"); net.runUntilIdle(); net.disconnect("w", "b0")
        XCTAssertEqual(1, net.node("w").acksSeen.count)
        net.connect("w", "a2"); net.runUntilIdle()
        XCTAssertEqual(1, a0.acksForMe.count, "ACK delivered to origin across the partition")
    }

    func testLossyChainConvergesAfterResyncRounds() throws {
        let net = TestNet(seed: 11)
        net.lossProbability = 0.2
        let names = (0...9).map { "n\($0)" }
        net.chain(names)
        autoAck(net, "n9")
        let origin = net.node("n0")
        try origin.node.originateNeed(sampleNeed(originNode: origin.id))
        net.runUntilIdle()
        var rounds = 0
        while (net.node("n9").needsDelivered.isEmpty || origin.acksForMe.isEmpty) && rounds < 60 { net.resyncRound(); rounds += 1 }
        XCTAssertEqual(1, net.node("n9").needsDelivered.count)
        XCTAssertEqual(1, origin.acksForMe.count)
    }

    // ------------------------------------------------------------ single-node protocol rules

    func testDuplicateFrameFromTwoLinksDeliveredOnce() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.node.linkUp("L3")
        let need = sampleNeed(originNode: NodeId.random())
        let raw = needFrameBytes(need, ttl: 5)
        h.node.onFrame("L1", raw)
        h.node.onFrame("L2", raw)
        h.runTasks()
        XCTAssertEqual(1, h.needs.count)
        XCTAssertEqual(1, h.node.stats().duplicates)
        // flooded once per eligible link (L2, L3 from the L1 copy; L1 is the source). L2's duplicate is dropped.
        h.clearSent()
        h.node.onFrame("L3", raw)
        h.runTasks()
        XCTAssertTrue(h.sent.allSatisfy { FrameCodec.decode($0.1)?.type != FrameTypes.need })
    }

    func testFloodGoesToAllLinksExceptSourceWithTtlMinusOneAfterJitter() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.node.linkUp("L3")
        h.clearSent()
        let need = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("L1", needFrameBytes(need, ttl: 5))
        XCTAssertTrue(h.sent.isEmpty, "nothing sent before jitter fires")
        XCTAssertEqual(1, h.tasks.count)
        XCTAssertTrue((10...220).contains(h.tasks[0].0), "jitter \(h.tasks[0].0)")
        h.runTasks()
        XCTAssertEqual(0, h.sentTo("L1").count)
        for l in ["L2", "L3"] {
            let frames = h.sentTo(l, FrameTypes.need)
            XCTAssertEqual(1, frames.count)
            let f = frames[0]
            XCTAssertEqual(4, f.ttl)
            XCTAssertEqual(encodeNeed(need), f.payload)
            XCTAssertEqual(T0, f.timestampMs)           // untouched on relay
            XCTAssertEqual(need.originNode, f.senderId)
        }
    }

    func testJitterCoversTheWholeRange() {
        var seen = Set<Int64>()
        for _ in 0..<300 {
            let h = SoloHarness()
            h.node.linkUp("L1")
            let need = sampleNeed(originNode: NodeId.random())
            h.node.onFrame("L1", needFrameBytes(need, ttl: 5))
            seen.insert(h.tasks[0].0)
        }
        XCTAssertTrue(seen.allSatisfy { (10...220).contains($0) })
    }

    func testTtlOneIsStoredAndDeliveredButNotFlooded() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        let need = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("L1", needFrameBytes(need, ttl: 1))
        h.runTasks()
        XCTAssertEqual(1, h.needs.count)
        XCTAssertEqual(1, h.node.storedNeeds().count)
        XCTAssertTrue(h.sent.isEmpty)
        // ttl 0 as well
        let n2 = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("L1", needFrameBytes(n2, ttl: 0)); h.runTasks()
        XCTAssertEqual(2, h.needs.count); XCTAssertTrue(h.sent.isEmpty)
    }

    func testFrameMoreThan24hInFutureIsDropped() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        let need = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("L1", needFrameBytes(need, ts: T0 + 24 * 3600_000 + 1)); h.runTasks()
        XCTAssertEqual(0, h.needs.count); XCTAssertTrue(h.sent.isEmpty)
        h.node.onFrame("L1", needFrameBytes(need, ts: T0 + 24 * 3600_000 - 1000)); h.runTasks()
        XCTAssertEqual(1, h.needs.count)
        // no clock-skew rejection for the past
        let old = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("L1", needFrameBytes(old, ts: T0 - 400 * 24 * 3600_000)); h.runTasks()
        XCTAssertEqual(2, h.needs.count)
    }

    func testOpaqueTypeRelayedOnceNeverStoredOrDelivered() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        let sig = (0..<64).map { UInt8($0) }
        let sender = NodeId.random()
        let frame = Frame(type: 0x7E, ttl: 5, timestampMs: T0, senderId: sender, payload: [1, 2, 3], signature: sig)
        let raw = encodeFrame(frame)
        h.node.onFrame("L1", raw); h.runTasks()
        let outs = h.sentTo("L2", 0x7E)
        XCTAssertEqual(1, outs.count)
        let out = outs[0]
        XCTAssertEqual(4, out.ttl)
        XCTAssertEqual(sig, out.signature)
        XCTAssertEqual(frame.payload, out.payload)
        XCTAssertEqual(0, h.sentTo("L1").count)
        h.clearSent()
        h.node.onFrame("L2", raw); h.node.onFrame("L1", raw); h.runTasks()
        XCTAssertTrue(h.sent.isEmpty, "second sighting is not relayed again")
        XCTAssertTrue(h.needs.isEmpty && h.node.storedNeeds().isEmpty && h.node.storedAcks().isEmpty)
        XCTAssertEqual(2, h.node.stats().duplicates)
        XCTAssertEqual(16, OpaqueKey.of(frame).count * 2)
    }

    func testUnknownPayloadVersionOfKnownTypeIsOpaque() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        let need = sampleNeed(originNode: NodeId.random())
        var p = encodeNeed(need); p[0] = 3
        let raw = encodeFrame(Frame(type: FrameTypes.need, ttl: 4, timestampMs: T0, senderId: need.originNode, payload: p))
        h.node.onFrame("L1", raw); h.runTasks()
        XCTAssertTrue(h.needs.isEmpty)
        let outs = h.sentTo("L2", FrameTypes.need)
        XCTAssertEqual(1, outs.count)
        XCTAssertEqual(3, outs[0].ttl)
        h.clearSent(); h.node.onFrame("L1", raw); h.runTasks(); XCTAssertTrue(h.sent.isEmpty)
    }

    func testInvalidV1NeedIsDroppedNotRelayed() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        let need = sampleNeed(originNode: NodeId.random())
        var p = encodeNeed(need); p[1 + 8 + 8 + 4 + 1] = 9 // urgency 9
        h.node.onFrame("L1", encodeFrame(Frame(type: FrameTypes.need, ttl: 6, timestampMs: T0, senderId: need.originNode, payload: p)))
        h.runTasks()
        XCTAssertTrue(h.needs.isEmpty && h.sent.isEmpty)
        XCTAssertEqual(1, h.node.stats().framesRejected)
    }

    func testLinkUpSendsHelloThenChunkedInventory() throws {
        let h = SoloHarness()
        h.node.linkUp("L0")
        let needs = (0..<40).map { _ in sampleNeed(originNode: NodeId.random()) }
        for n in needs { try h.node.originateNeed(n) }
        h.runTasks(); h.clearSent()
        h.node.linkUp("L1")
        let frames = h.sent.filter { $0.0 == "L1" }
        XCTAssertEqual(FrameTypes.hello, FrameCodec.decode(frames[0].1)!.type)
        let inv = frames.dropFirst().map { FrameCodec.decode($0.1)! }
        XCTAssertTrue(inv.allSatisfy { $0.type == FrameTypes.inventory && $0.ttl == 1 })
        XCTAssertTrue(frames.allSatisfy { $0.1.count <= 182 })
        let chunks = inv.map { InventoryCodec.decode($0.payload)! }
        XCTAssertEqual([19, 19, 2], chunks.map { $0.ids.count })
        XCTAssertEqual([0, 1, 2], chunks.map { $0.chunkIndex })
        XCTAssertTrue(chunks.allSatisfy { $0.chunkCount == 3 })
        let advertised = Set(chunks.flatMap { $0.ids }.map { hex($0) })
        XCTAssertEqual(Set(needs.map { hex($0.msgId) }), advertised)
        let hello = HelloCodec.decode(FrameCodec.decode(frames[0].1)!.payload)!
        XCTAssertEqual(h.id, hello.nodeId)
    }

    func testEmptyStoreSendsSingleEmptyInventoryChunk() {
        let h = SoloHarness()
        h.node.linkUp("L1")
        let inv = h.sentTo("L1", FrameTypes.inventory).map { InventoryCodec.decode($0.payload)! }
        XCTAssertEqual(1, inv.count)
        XCTAssertEqual(0, inv[0].ids.count); XCTAssertEqual(1, inv[0].chunkCount)
    }

    func testInventoryDiffPushesOnlyMissingFramesWithFreshTimestampToThatLinkOnly() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        let a = sampleNeed(originNode: NodeId.random()), b = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("L1", needFrameBytes(a, ttl: 6))
        h.node.onFrame("L1", needFrameBytes(b, ttl: 3))
        h.runTasks(); h.clearSent()
        h.now = T0 + 5_000
        // L2's inventory contains only `a`
        let inv = Frame(type: FrameTypes.inventory, ttl: 1, timestampMs: h.now, senderId: NodeId.random(),
                        payload: encodeInventory(Inventory(0, 1, [a.msgId])))
        h.node.onFrame("L2", encodeFrame(inv))
        XCTAssertEqual(0, h.sentTo("L1").count)
        let pushedAll = h.sentTo("L2", FrameTypes.need)
        XCTAssertEqual(1, pushedAll.count)
        let pushed = pushedAll[0]
        XCTAssertEqual(encodeNeed(b), pushed.payload)
        XCTAssertEqual(3, pushed.ttl, "ttl unchanged")
        XCTAssertEqual(T0 + 5_000, pushed.timestampMs, "timestamp re-stamped to now")
        XCTAssertTrue(h.tasks.isEmpty, "diff push is not jittered")
    }

    func testMultiChunkInventoryOnlyActsWhenComplete() {
        let h = SoloHarness()
        h.node.linkUp("L1")
        let a = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("L1", needFrameBytes(a, ttl: 6)); h.runTasks(); h.clearSent()
        func chunk(_ i: Int, _ n: Int, _ ids: [[UInt8]]) -> [UInt8] {
            encodeFrame(Frame(type: FrameTypes.inventory, ttl: 1, timestampMs: T0, senderId: NodeId.random(),
                              payload: encodeInventory(Inventory(i, n, ids))))
        }
        h.node.onFrame("L1", chunk(0, 2, [NodeId.random()]))
        XCTAssertTrue(h.sent.isEmpty, "incomplete: no push yet")
        h.node.onFrame("L1", chunk(1, 2, [a.msgId]))
        XCTAssertTrue(h.sentTo("L1", FrameTypes.need).isEmpty, "peer has it per chunk 2: still nothing to push")
        // a new chunk 0 restarts accumulation
        h.node.onFrame("L1", chunk(0, 2, []))
        h.node.onFrame("L1", chunk(1, 2, []))
        XCTAssertEqual(1, h.sentTo("L1", FrameTypes.need).count)
    }

    func testOriginateNeedStoresDeliversAndFloodsWithInitialTtl() throws {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        let need = sampleNeed(originNode: h.id)
        let frame = try h.node.originateNeed(need)
        XCTAssertEqual(7, frame.ttl)
        XCTAssertEqual(h.id, frame.senderId)
        XCTAssertEqual(1, h.needs.count)
        XCTAssertEqual(1, h.node.storedNeeds().count)
        h.runTasks()
        for l in ["L1", "L2"] {
            let fs = h.sentTo(l, FrameTypes.need)
            XCTAssertEqual(1, fs.count)
            XCTAssertEqual(7, fs[0].ttl)
        }
        // re-originating the same msg_id does nothing new
        h.clearSent(); try h.node.originateNeed(need); h.runTasks()
        XCTAssertTrue(h.sent.isEmpty); XCTAssertEqual(1, h.needs.count)
    }

    func testOriginateAckIsDirectedAndOnlyTargetShowsIt() throws {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.clearSent()
        let originNode = NodeId.random()
        let ack = sampleAck(sampleNeed(originNode: originNode))
        let frame = try h.node.originateAck(ack)
        XCTAssertEqual(h.id, frame.senderId)
        XCTAssertEqual(originNode, frame.recipientId)
        XCTAssertEqual(7, frame.ttl)
        XCTAssertEqual(1, h.acksSeen.count); XCTAssertEqual(0, h.acksForMe.count)
        h.runTasks()
        let sentAcks = h.sentTo("L1", FrameTypes.ack)
        XCTAssertEqual(1, sentAcks.count)
        XCTAssertEqual(7, sentAcks[0].ttl)
        // an ACK addressed to this node is shown to the user
        let mine = sampleAck(sampleNeed(originNode: h.id))
        h.node.onFrame("L1", encodeFrame(Frame(type: FrameTypes.ack, ttl: 3, timestampMs: T0, senderId: NodeId.random(),
                                               recipientId: h.id, payload: encodeAck(mine))))
        XCTAssertEqual(1, h.acksForMe.count)
        XCTAssertEqual(2, h.node.storedAcks().count) // the one we originated + the one for us
    }

    func testExpiryByCreatedAtForNeedsAndFirstStoredForAcks() {
        let h = SoloHarness()
        h.node.linkUp("L1")
        let oldNeed = sampleNeed(originNode: NodeId.random(), createdAtSec: T0 / 1000 - 71 * 3600)
        let ack = sampleAck(sampleNeed(originNode: NodeId.random()))
        h.node.onFrame("L1", needFrameBytes(oldNeed))
        h.node.onFrame("L1", encodeFrame(Frame(type: FrameTypes.ack, ttl: 3, timestampMs: T0 - 100 * 3600_000,
                                               senderId: NodeId.random(), recipientId: ack.targetNode, payload: encodeAck(ack))))
        h.node.expire()
        XCTAssertEqual(1, h.node.storedNeeds().count); XCTAssertEqual(1, h.node.storedAcks().count)
        h.now = T0 + 2 * 3600_000 // NEED now 73 h old; ACK stored 2 h ago (its frame timestamp is irrelevant)
        h.node.expire()
        XCTAssertEqual(0, h.node.storedNeeds().count)
        XCTAssertEqual(1, h.node.storedAcks().count, "ACK expiry is by first-stored time")
        h.now = T0 + 73 * 3600_000
        h.node.expire()
        XCTAssertEqual(0, h.node.storedAcks().count)
        // keys stay in the seen set: re-receiving the expired NEED is a duplicate, not a new delivery
        let before = h.needs.count
        h.node.onFrame("L1", needFrameBytes(oldNeed)); h.runTasks()
        XCTAssertEqual(before, h.needs.count)
    }

    func testSeenSetIsBoundedLru() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        let sender = NodeId.random()
        func opaque(_ i: Int) -> [UInt8] {
            encodeFrame(Frame(type: 0x7E, ttl: 3, timestampMs: T0 + Int64(i), senderId: sender, payload: [1]))
        }
        let first = opaque(0)
        h.node.onFrame("L1", first); h.runTasks(); h.clearSent()
        for i in 1...GossipNode.seenCapacity {
            h.node.onFrame("L1", opaque(i))
            if h.tasks.count > 1000 { h.runTasks() }
        }
        h.runTasks(); h.clearSent()
        h.node.onFrame("L1", first); h.runTasks()
        XCTAssertEqual(1, h.sentTo("L2", 0x7E).count, "evicted key is relayed again")
        // recent key still deduped
        h.clearSent(); h.node.onFrame("L1", opaque(GossipNode.seenCapacity)); h.runTasks()
        XCTAssertTrue(h.sent.isEmpty)
    }

    func testHelloMustMatchSenderAndTracksHopsToGateway() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        XCTAssertEqual(0xFF, h.node.hopsToGateway)
        let peer = NodeId.random()
        func hello(_ sender: [UInt8], _ node: [UInt8], _ caps: Int, _ hops: Int) -> [UInt8] {
            encodeFrame(Frame(type: FrameTypes.hello, ttl: 1, timestampMs: T0, senderId: sender,
                              payload: encodeHello(Hello(nodeId: node, caps: caps, hopsToGateway: hops, appVersion: 1))))
        }
        h.node.onFrame("L1", hello(peer, NodeId.random(), 1, 0))     // node_id != sender_id: dropped
        XCTAssertEqual(0xFF, h.node.hopsToGateway); XCTAssertTrue(h.node.peers().isEmpty)
        h.clearSent()
        h.node.onFrame("L1", hello(peer, peer, 1, 0))
        XCTAssertEqual(1, h.node.hopsToGateway)
        XCTAssertTrue(h.node.peers()["L1"]!.isGateway)
        // value changed: HELLO re-announced to every link
        let announced = h.sentTo("L2", FrameTypes.hello).map { HelloCodec.decode($0.payload)! }
        XCTAssertEqual(1, announced.count)
        XCTAssertEqual(1, announced[0].hopsToGateway)
        h.node.onFrame("L2", hello(NodeId.random(), [UInt8](repeating: 0, count: 8), 0, 0)) // mismatch again: ignored
        let p2 = NodeId.random()
        h.node.onFrame("L2", hello(p2, p2, 0, 3))
        XCTAssertEqual(1, h.node.hopsToGateway) // min(0,3)+1
        h.node.linkDown("L1")
        XCTAssertEqual(4, h.node.hopsToGateway)
        // unknown stays unknown, cap at 0xFE
        let h2 = SoloHarness(); h2.node.linkUp("A")
        let p3 = NodeId.random()
        h2.node.onFrame("A", hello(p3, p3, 0, 0xFF)); XCTAssertEqual(0xFF, h2.node.hopsToGateway)
        let p4 = NodeId.random(); h2.node.linkUp("B")
        h2.node.onFrame("B", hello(p4, p4, 0, 0xFE)); XCTAssertEqual(0xFE, h2.node.hopsToGateway)
    }

    func testGatewayFlagReannouncesAndForcesHopsZero() {
        let h = SoloHarness()
        h.node.linkUp("L1"); h.clearSent()
        h.node.isGateway = true
        XCTAssertEqual(0, h.node.hopsToGateway)
        let hellos = h.sentTo("L1", FrameTypes.hello)
        XCTAssertEqual(1, hellos.count)
        let hello = HelloCodec.decode(hellos[0].payload)!
        XCTAssertTrue(hello.isGateway); XCTAssertEqual(0, hello.hopsToGateway)
        h.clearSent()
        h.node.isGateway = true // no change, no chatter
        XCTAssertTrue(h.sent.isEmpty)
        h.node.isGateway = false
        let off = h.sentTo("L1", FrameTypes.hello)
        XCTAssertEqual(1, off.count)
        XCTAssertFalse(HelloCodec.decode(off[0].payload)!.isGateway)
    }

    func testHopsToGatewayPropagatesAlongAChain() {
        let net = TestNet()
        net.chain(["g", "a", "b", "c"])
        net.node("g").node.isGateway = true
        net.runUntilIdle()
        XCTAssertEqual(0, net.node("g").node.hopsToGateway)
        XCTAssertEqual(1, net.node("a").node.hopsToGateway)
        XCTAssertEqual(2, net.node("b").node.hopsToGateway)
        XCTAssertEqual(3, net.node("c").node.hopsToGateway)
    }

    func testDuplicateLinkPolicyKeepsLowerIdAsCentral() {
        let lo = [UInt8](repeating: 0x01, count: 8), hi = [UInt8](repeating: 0xF0, count: 8)
        XCTAssertTrue(DuplicateLinkPolicy.keepAsCentral(myId: lo, peerId: hi))
        XCTAssertFalse(DuplicateLinkPolicy.keepAsCentral(myId: hi, peerId: lo))
        XCTAssertFalse(DuplicateLinkPolicy.keepAsCentral(myId: lo, peerId: lo))
        // unsigned compare: 0x80.. is greater than 0x7F..
        XCTAssertTrue(DuplicateLinkPolicy.keepAsCentral(myId: [UInt8](repeating: 0x7F, count: 8), peerId: [UInt8](repeating: 0x80, count: 8)))
    }

    func testFrameFromUnknownLinkStillDeliversNeedButIgnoresHandshakeFrames() {
        let h = SoloHarness()
        let need = sampleNeed(originNode: NodeId.random())
        h.node.onFrame("ghost", needFrameBytes(need, ttl: 3))
        XCTAssertEqual(1, h.needs.count)
        let peer = NodeId.random()
        h.node.onFrame("ghost", encodeFrame(Frame(type: FrameTypes.hello, ttl: 1, timestampMs: T0, senderId: peer,
                                                  payload: encodeHello(Hello(nodeId: peer, caps: 1, hopsToGateway: 0, appVersion: 1)))))
        XCTAssertTrue(h.node.peers().isEmpty)
    }

    // ---- additions: the LRU seen-set and the in-memory store behave like Kotlin's LinkedHashMap

    func testInMemoryStoreKeepsInsertionOrderAndExpires() {
        let s = InMemoryPacketStore()
        for (i, k) in ["a", "b", "c"].enumerated() { s.put(StoredPacket(key: k, type: 0x70, raw: [UInt8(i)], expiryBasisMs: Int64(i * 10))) }
        s.put(StoredPacket(key: "a", type: 0x70, raw: [9], expiryBasisMs: 100)) // replace keeps position
        XCTAssertEqual(["a", "b", "c"], s.allKeys())
        XCTAssertEqual([9], s.get("a")!.raw)
        XCTAssertEqual(["b", "c"], s.expire(25))
        XCTAssertEqual(["a"], s.allKeys())
        s.remove("a")
        XCTAssertFalse(s.contains("a"))
    }
}
