import XCTest
@testable import RelayCore

final class LocHintTests: XCTestCase {
    private func hint(ref: [UInt8] = NodeId.random(), relay: [UInt8] = NodeId.random(), acc: Int = 2, rssi: Int = -70) -> LocHint {
        LocHint(refMsgId: ref, relayNode: relay, latE5: Geo.toE5(20.29601), lonE5: Geo.toE5(85.82433), acc: acc, rssiDbm: rssi,
                observedAt: T0 / 1000)
    }

    private func hintFrame(_ hint: LocHint, ttl: Int = 7, sender: [UInt8]? = nil, payload: [UInt8]? = nil) -> [UInt8] {
        encodeFrame(Frame(type: FrameTypes.locHint, ttl: ttl, timestampMs: T0, senderId: sender ?? hint.relayNode,
                          payload: payload ?? (try! LocHintCodec.encode(hint))))
    }

    private func putI32(_ b: inout [UInt8], _ at: Int, _ v: Int32) {
        let u = UInt32(bitPattern: v)
        for k in 0..<4 { b[at + k] = UInt8(truncatingIfNeeded: u >> UInt32(24 - 8 * k)) }
    }

    // ------------------------------------------------------------ codec

    func testCodecRoundTripsAndIsExactlyThirtyOneBytes() throws {
        for acc in 1...4 {
            for rssi in [-128, -127, -67, 0, 127] {
                let x = hint(acc: acc, rssi: rssi)
                let p = try LocHintCodec.encode(x)
                XCTAssertEqual(31, p.count)
                XCTAssertEqual(x, LocHintCodec.decode(p))
            }
        }
        var x = hint()
        x.latE5 = -9_000_000; x.lonE5 = 18_000_000; x.observedAt = 0xFFFF_FFFF
        XCTAssertEqual(x, LocHintCodec.decode(try LocHintCodec.encode(x)))
    }

    func testDecoderRejectsWhatTheSpecSaysToDrop() throws {
        let good = try LocHintCodec.encode(hint())
        XCTAssertNotNil(LocHintCodec.decode(good))
        var v2 = good; v2[0] = 2
        XCTAssertNil(LocHintCodec.decode(v2))
        XCTAssertNil(LocHintCodec.decode(Array(good.prefix(30))))
        XCTAssertNil(LocHintCodec.decode(good + [0]))
        var acc0 = good; acc0[25] = 0
        XCTAssertNil(LocHintCodec.decode(acc0))
        var acc5 = good; acc5[25] = 5
        XCTAssertNil(LocHintCodec.decode(acc5))
        var lat = good; putI32(&lat, 17, 9_000_001)
        XCTAssertNil(LocHintCodec.decode(lat))
        var lon = good; putI32(&lon, 21, -18_000_001)
        XCTAssertNil(LocHintCodec.decode(lon))
    }

    func testEncoderRefusesInvalidFields() {
        var rssiBig = hint(); rssiBig.rssiDbm = 128
        var latBig = hint(); latBig.latE5 = 9_000_001
        var negObserved = hint(); negObserved.observedAt = -1
        for bad in [hint(acc: 0), hint(acc: 5), rssiBig, latBig, negObserved, hint(ref: [UInt8](repeating: 0, count: 7))] {
            XCTAssertThrowsError(try LocHintCodec.encode(bad), "expected an error for \(bad)")
        }
    }

    func testAccuracyBuckets() {
        XCTAssertEqual(1, LocHintCodec.bucketFor(0)); XCTAssertEqual(1, LocHintCodec.bucketFor(10))
        XCTAssertEqual(2, LocHintCodec.bucketFor(10.1)); XCTAssertEqual(2, LocHintCodec.bucketFor(25))
        XCTAssertEqual(3, LocHintCodec.bucketFor(50)); XCTAssertEqual(4, LocHintCodec.bucketFor(100))
        XCTAssertNil(LocHintCodec.bucketFor(100.1)); XCTAssertNil(LocHintCodec.bucketFor(Float.nan)); XCTAssertNil(LocHintCodec.bucketFor(-1))
        XCTAssertEqual([10, 25, 50, 100], (1...4).map { hint(acc: $0).accuracyMaxM })
    }

    func testKeyIsDerivedPerNeedAndRelay() {
        let ref = NodeId.random(), relay = NodeId.random()
        let k = LocHintCodec.key(ref, relay)
        XCTAssertEqual(8, k.count)
        XCTAssertEqual(k, LocHintCodec.key(ref, relay))
        XCTAssertNotEqual(k, LocHintCodec.key(ref, NodeId.random()))
        XCTAssertNotEqual(k, LocHintCodec.key(NodeId.random(), relay))
        // timestamp, position and rssi do not matter: re-sending an updated hint is the same hint
        XCTAssertEqual(hint(ref: ref, relay: relay, rssi: -50).keyHex, hint(ref: ref, relay: relay, rssi: -90).keyHex)
    }

    // ------------------------------------------------------------ gossip

    func testHintIsStoredDeliveredFloodedAndNotRepeated() throws {
        let s = SoloHarness()
        s.node.linkUp("L1"); s.node.linkUp("L2"); s.clearSent()
        let x = hint()
        let raw = hintFrame(x)
        s.node.onFrame("L1", raw); s.runTasks()
        XCTAssertEqual([x], s.hints)
        XCTAssertEqual(1, s.node.stats().locHintsDelivered)
        XCTAssertEqual(1, s.node.storedLocHints().count)
        let out = try XCTUnwrap(s.sentTo("L2", FrameTypes.locHint).first)
        XCTAssertEqual(1, s.sentTo("L2", FrameTypes.locHint).count)
        XCTAssertEqual(6, out.ttl)
        XCTAssertEqual(Array(raw[14...]), Array(encodeFrame(out)[14...])) // payload + sender untouched
        XCTAssertEqual(0, s.sentTo("L1", FrameTypes.locHint).count)
        s.clearSent(); s.node.onFrame("L2", raw); s.runTasks()
        XCTAssertTrue(s.sent.isEmpty); XCTAssertEqual(1, s.hints.count); XCTAssertEqual(1, s.node.stats().duplicates)
    }

    func testHintWithRelayDifferentFromSenderIsRejectedAndNotRelayed() throws {
        let s = SoloHarness()
        s.node.linkUp("L1"); s.node.linkUp("L2"); s.clearSent()
        s.node.onFrame("L1", hintFrame(hint(), sender: NodeId.random())); s.runTasks()
        XCTAssertTrue(s.hints.isEmpty && s.sent.isEmpty && s.node.storedLocHints().isEmpty)
        XCTAssertEqual(1, s.node.stats().framesRejected)
        // invalid payloads are dropped too
        s.node.onFrame("L1", hintFrame(hint(), payload: Array(try LocHintCodec.encode(hint()).prefix(30)))); s.runTasks()
        XCTAssertTrue(s.sent.isEmpty); XCTAssertEqual(2, s.node.stats().framesRejected)
    }

    func testUnknownHintPayloadVersionIsRelayedOnceAsOpaque() throws {
        let s = SoloHarness()
        s.node.linkUp("L1"); s.node.linkUp("L2"); s.clearSent()
        let x = hint()
        var p = try LocHintCodec.encode(x); p[0] = 2
        s.node.onFrame("L1", hintFrame(x, payload: p)); s.runTasks()
        XCTAssertTrue(s.hints.isEmpty && s.node.storedLocHints().isEmpty)
        XCTAssertEqual(1, s.sentTo("L2", FrameTypes.locHint).count)
    }

    func testTtlOneHintIsStoredButNotFlooded() {
        let s = SoloHarness()
        s.node.linkUp("L1"); s.node.linkUp("L2"); s.clearSent()
        s.node.onFrame("L1", hintFrame(hint(), ttl: 1)); s.runTasks()
        XCTAssertEqual(1, s.hints.count); XCTAssertTrue(s.sent.isEmpty)
    }

    func testOriginateLocHintFloodsWithInitialTtlAndOnlyOncePerNeed() throws {
        let s = SoloHarness()
        s.node.linkUp("L1"); s.clearSent()
        let mine = hint(relay: s.id)
        XCTAssertNotNil(try s.node.originateLocHint(mine)); s.runTasks()
        let out = try XCTUnwrap(s.sentTo("L1", FrameTypes.locHint).first)
        XCTAssertEqual(1, s.sentTo("L1", FrameTypes.locHint).count)
        XCTAssertEqual(7, out.ttl); XCTAssertEqual(s.id, out.senderId); XCTAssertNil(out.recipientId)
        XCTAssertEqual([mine], s.hints)
        // a second hint for the same NEED from this node (even with a better position) is refused
        s.clearSent()
        var better = mine; better.acc = 1; better.rssiDbm = -40
        XCTAssertNil(try s.node.originateLocHint(better)); s.runTasks()
        XCTAssertTrue(s.sent.isEmpty); XCTAssertEqual(1, s.hints.count)
        XCTAssertThrowsError(try s.node.originateLocHint(hint()), "relay_node must be this node")
    }

    func testInventoryAdvertisesHintsAndAPeerThatLacksOneGetsIt() {
        let net = TestNet()
        net.connect("a", "b")
        let b = net.node("b")
        // a hint arrives at b from nowhere: b stores it; a is then connected and lacks it
        let a = net.node("a")
        net.runUntilIdle()
        b.node.onFrame("a", hintFrame(hint(relay: NodeId.random()), ttl: 1)) // ttl 1: not flooded, only stored
        net.runUntilIdle()
        XCTAssertEqual(1, b.locHints.count); XCTAssertTrue(a.locHints.isEmpty)
        net.resyncRound()
        XCTAssertEqual(1, a.locHints.count, "a got the hint through the inventory diff")
        net.resyncRound()
        XCTAssertEqual(1, a.locHints.count)
    }

    func testHintsExpireAfter72HoursFromFirstStored() {
        let s = SoloHarness()
        s.node.linkUp("L1")
        s.node.onFrame("L1", hintFrame(hint()))
        XCTAssertEqual(1, s.node.storedLocHints().count)
        s.now += 71 * 3600 * 1000; s.node.expire(); XCTAssertEqual(1, s.node.storedLocHints().count)
        s.now += 2 * 3600 * 1000; s.node.expire(); XCTAssertEqual(0, s.node.storedLocHints().count)
    }

    func testHintsAreOrderedAfterNeedsInThePushPlan() {
        func pk(_ key: String, _ type: Int, _ basis: Int64) -> StoredPacket {
            StoredPacket(key: key, type: type, raw: [1, 2, 3], expiryBasisMs: basis)
        }
        let ordered = PushPlanner.order([pk("h", FrameTypes.locHint, 9), pk("n", FrameTypes.need, 1)], peerNodeId: nil)
        XCTAssertEqual(["n", "h"], ordered.map { $0.key })
    }

    // ------------------------------------------------------------ responder

    private final class Rig: @unchecked Sendable {
        var enabled: Bool
        var fix: RelayFix?
        var rssi: Int?
        let s = SoloHarness()
        var requests: [Int64] = []
        var logs: [String] = []
        var responder: LocHintResponder!
        let origin = NodeId.random()

        private struct RigClock: Clock { unowned let rig: Rig; func nowMs() -> Int64 { rig.s.now } }
        private struct RigScheduler: Scheduler {
            unowned let rig: Rig
            func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void) { rig.s.tasks.append((delayMs, task)) }
        }

        init(enabled: Bool = true, fix: RelayFix? = nil, rssi: Int? = -64) {
            self.enabled = enabled; self.fix = fix; self.rssi = rssi
            responder = LocHintResponder(
                node: s.node, clock: RigClock(rig: self), scheduler: RigScheduler(rig: self),
                enabled: { [unowned self] in self.enabled }, fix: { [unowned self] in self.fix },
                rssi: { [unowned self] _ in self.rssi }, requestFix: { [unowned self] in self.requests.append(self.s.now) },
                log: { [unowned self] in self.logs.append($0) })
        }

        func fixNow(accuracyM: Float = 20, ageMs: Int64 = 0, lat: Double = 20.29601, lon: Double = 85.82433) -> RelayFix {
            RelayFix(lat: lat, lon: lon, accuracyM: accuracyM, atMs: s.now - ageMs)
        }

        /// Bring up link [id] and say HELLO as [peer].
        func link(_ id: String, _ peer: [UInt8]) {
            s.node.linkUp(id)
            s.node.onFrame(id, encodeFrame(Frame(type: FrameTypes.hello, ttl: 1, timestampMs: s.now, senderId: peer,
                                                 payload: encodeHello(Hello(nodeId: peer, caps: 0, hopsToGateway: 0xFF, appVersion: 1)))))
        }

        func need(locAcc: Int = 0, origin: [UInt8]? = nil) -> Need {
            var n = sampleNeed(originNode: origin ?? self.origin)
            n.locAcc = locAcc; n.latE5 = 0; n.lonE5 = 0
            return n
        }

        /// Deliver [n] on [link] through the node and give the responder the callback, as the app does.
        func receive(_ link: String, _ n: Need) {
            s.needs.removeAll()
            s.node.onFrame(link, needFrameBytes(n, ttl: 7, sender: n.originNode, ts: s.now))
            for x in s.needs { responder.onNeed(x.0, fromLink: link) }
        }

        func hints() -> [LocHint] { s.node.storedLocHints().map { $0.0 } }
    }

    func testEmitsOneHintForANoFixNeedHeardDirectlyFromItsOrigin() throws {
        let r = Rig(); r.fix = r.fixNow(accuracyM: 30)
        r.link("L1", r.origin)
        let n = r.need()
        r.receive("L1", n)
        let x = try XCTUnwrap(r.hints().first)
        XCTAssertEqual(1, r.hints().count)
        XCTAssertEqual(n.msgId, x.refMsgId); XCTAssertEqual(r.s.id, x.relayNode)
        XCTAssertEqual(3, x.acc); XCTAssertEqual(-64, x.rssiDbm)
        XCTAssertEqual(Geo.toE5(20.29601), x.latE5); XCTAssertEqual(Geo.toE5(85.82433), x.lonE5)
        XCTAssertEqual(r.s.now / 1000, x.observedAt)
        XCTAssertTrue(r.requests.isEmpty)
        // hearing the same NEED again (resync) neither re-delivers nor re-hints
        r.receive("L1", n)
        XCTAssertEqual(1, r.hints().count)
    }

    func testNothingWhenSwitchedOff() {
        let r = Rig(enabled: false); r.fix = r.fixNow()
        r.link("L1", r.origin); r.receive("L1", r.need()); r.s.runTasks()
        XCTAssertTrue(r.hints().isEmpty); XCTAssertTrue(r.requests.isEmpty)
    }

    func testSwitchingOffWhileWaitingForAFixCancelsTheHint() {
        let r = Rig(); r.link("L1", r.origin); r.receive("L1", r.need())
        XCTAssertEqual(1, r.requests.count)
        r.enabled = false; r.fix = r.fixNow()
        r.s.runTasks()
        XCTAssertTrue(r.hints().isEmpty)
    }

    func testNothingForANeedThatHasAFix() {
        let r = Rig(); r.fix = r.fixNow()
        r.link("L1", r.origin)
        for acc in 1...5 { r.receive("L1", r.need(locAcc: acc)) }
        XCTAssertTrue(r.hints().isEmpty)
    }

    func testNothingUnlessTheLinkPeerIsTheOrigin() {
        let r = Rig(); r.fix = r.fixNow()
        r.link("L1", NodeId.random())        // the NEED names r.origin but came from another phone
        r.receive("L1", r.need())
        XCTAssertTrue(r.hints().isEmpty, "relayed NEED")
        r.link("L2", NodeId.random())        // frame.sender_id == origin but the link's HELLO is someone else
        r.receive("L2", r.need())
        XCTAssertTrue(r.hints().isEmpty)
        r.s.node.linkUp("L3")                // link that never said HELLO
        r.receive("L3", r.need())
        XCTAssertTrue(r.hints().isEmpty)
        r.receive("nowhere", r.need())       // unknown link
        XCTAssertTrue(r.hints().isEmpty)
        r.s.needs.removeAll()
        r.responder.onNeed(r.need(), fromLink: nil) // locally originated
        XCTAssertTrue(r.hints().isEmpty)
    }

    func testNothingWithAnImpreciseOrOldFix() {
        let r = Rig(); r.link("L1", r.origin)
        r.fix = r.fixNow(accuracyM: 100.5)
        r.receive("L1", r.need())
        XCTAssertTrue(r.hints().isEmpty)
        // a 100 m fix is the worst allowed
        let r2 = Rig(); r2.link("L1", r2.origin); r2.fix = r2.fixNow(accuracyM: 100)
        r2.receive("L1", r2.need()); XCTAssertEqual(4, r2.hints().first?.acc)
        // older than two minutes is not where the phone is now
        let r3 = Rig(); r3.link("L1", r3.origin); r3.fix = r3.fixNow(ageMs: 120_001)
        r3.receive("L1", r3.need()); r3.s.runTasks(); XCTAssertTrue(r3.hints().isEmpty)
        let r4 = Rig(); r4.link("L1", r4.origin); r4.fix = r4.fixNow(ageMs: 119_000)
        r4.receive("L1", r4.need()); XCTAssertEqual(1, r4.hints().count)
    }

    func testWaitsForAFixThenEmitsOrGivesUp() {
        let r = Rig(); r.link("L1", r.origin)
        r.receive("L1", r.need())
        XCTAssertTrue(r.hints().isEmpty); XCTAssertEqual(1, r.requests.count)     // asked the platform for a fix, once
        r.s.now += 4_000
        r.fix = r.fixNow(accuracyM: 15)                                           // GPS came up
        r.s.runTasks()
        XCTAssertEqual(1, r.hints().count)
        XCTAssertEqual(2, r.hints().first?.acc)
        XCTAssertEqual(T0 / 1000, r.hints().first?.observedAt, "observed_at is when the NEED arrived")
        XCTAssertEqual(1, r.requests.count)

        let g = Rig(); g.link("L1", g.origin); g.receive("L1", g.need())
        g.s.now += 40_000; g.s.runTasks()                                         // never got a fix
        XCTAssertTrue(g.hints().isEmpty); XCTAssertTrue(g.logs.contains { $0.contains("skipped") })
    }

    func testUnknownRssiIsMinus128AndExtremesAreClamped() {
        let r = Rig(rssi: nil); r.fix = r.fixNow(); r.link("L1", r.origin)
        r.receive("L1", r.need()); XCTAssertEqual(LocHintCodec.rssiUnknown, r.hints().first?.rssiDbm)
        let r2 = Rig(rssi: -300); r2.fix = r2.fixNow(); r2.link("L1", r2.origin)
        r2.receive("L1", r2.need()); XCTAssertEqual(-127, r2.hints().first?.rssiDbm)
    }

    func testBadFixCoordinatesNeverProduceAHint() {
        let r = Rig(); r.link("L1", r.origin); r.fix = r.fixNow(lat: Double.nan)
        r.receive("L1", r.need()); r.s.runTasks()
        XCTAssertTrue(r.hints().isEmpty)
        let r2 = Rig(); r2.link("L1", r2.origin); r2.fix = r2.fixNow(lat: 123.0)
        r2.receive("L1", r2.need()); r2.s.runTasks(); XCTAssertTrue(r2.hints().isEmpty)
    }
}
