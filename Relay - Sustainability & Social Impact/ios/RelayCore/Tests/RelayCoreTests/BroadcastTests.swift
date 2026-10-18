import XCTest
@testable import RelayCore

/// Port of the Android `core-protocol/.../BroadcastTest.kt` (SPEC 5.6).
final class BroadcastTests: XCTestCase {
    private let hour: Int64 = 3_600_000

    private func bc(issuedAtMs: Int64 = T0, type: DisasterType = .flood, kind: BroadcastKind = .disaster) -> Broadcast {
        Broadcast(id: NodeId.random(), kind: kind, disaster: type, issuedAt: issuedAtMs / 1000)
    }

    private func frame(_ b: Broadcast, ttl: Int = 7, ts: Int64 = T0) -> [UInt8] {
        encodeFrame(Frame(type: FrameTypes.broadcast, ttl: ttl, timestampMs: ts, senderId: NodeId.random(),
                          payload: try! BroadcastCodec.encode(b)))
    }

    // ------------------------------------------------------------ codec
    func testCodecRoundTripsAndIsTwentyFiveBytes() throws {
        for t in DisasterType.allCases {
            for k in [BroadcastKind.disaster, .disasterCleared] {
                let b = Broadcast(id: NodeId.random(), kind: k, disaster: t, issuedAt: 1_790_000_000, latE5: 2_029_612, lonE5: 8_582_451, radiusKm: 40)
                let p = try BroadcastCodec.encode(b)
                XCTAssertEqual(25, p.count)
                XCTAssertEqual(b, BroadcastCodec.decode(p))
            }
        }
        let edge = Broadcast(id: NodeId.random(), kind: .disaster, disaster: .landslide, issuedAt: 0xFFFF_FFFF, latE5: -9_000_000, lonE5: 18_000_000, radiusKm: 0xFFFF)
        XCTAssertEqual(edge, BroadcastCodec.decode(try BroadcastCodec.encode(edge)))
    }

    func testDecoderRejectsWhatTheSpecSaysToDrop() throws {
        let good = try BroadcastCodec.encode(bc())
        XCTAssertNotNil(BroadcastCodec.decode(good))
        func mutated(_ f: (inout [UInt8]) -> Void) -> [UInt8] { var c = good; f(&c); return c }
        XCTAssertNil(BroadcastCodec.decode(mutated { $0[0] = 2 }))
        XCTAssertNil(BroadcastCodec.decode(Array(good.prefix(24))))
        XCTAssertNil(BroadcastCodec.decode(good + [0]))
        XCTAssertNil(BroadcastCodec.decode(mutated { $0[9] = 0 }))   // kind 0
        XCTAssertNil(BroadcastCodec.decode(mutated { $0[9] = 3 }))   // kind 3
        XCTAssertNil(BroadcastCodec.decode(mutated { $0[10] = 4 }))  // disaster 4
        XCTAssertNil(BroadcastCodec.decode(mutated { c in let v = UInt32(bitPattern: 9_000_001); for k in 0..<4 { c[15 + k] = UInt8(truncatingIfNeeded: v >> UInt32(24 - 8 * k)) } }))
    }

    func testEncoderRefusesInvalidFields() {
        var a = bc(); a.id = [UInt8](repeating: 0, count: 7)
        var b = bc(); b.radiusKm = 70_000
        var c = bc(); c.issuedAt = -1
        var d = bc(); d.latE5 = 9_000_001
        for bad in [a, b, c, d] { XCTAssertThrowsError(try BroadcastCodec.encode(bad)) }
    }

    func testLifeIsTwentyFourHoursFromIssue() {
        let b = bc(issuedAtMs: T0)
        XCTAssertTrue(b.isLive(nowSec: T0 / 1000))
        XCTAssertTrue(b.isLive(nowSec: T0 / 1000 + 24 * 3600))
        XCTAssertFalse(b.isLive(nowSec: T0 / 1000 + 24 * 3600 + 1))
        XCTAssertTrue(b.isLive(nowSec: T0 / 1000 - 300))    // drift
        XCTAssertFalse(b.isLive(nowSec: T0 / 1000 - 3600))  // from the future
    }

    // ------------------------------------------------------------ gossip: gateways catch it, offline phones carry it
    func testAGatewayOriginatesAndEveryConnectedPhoneStoresAndRelaysIt() throws {
        let net = TestNet()
        net.chain(["gw", "a", "b", "c"])
        let b = bc()
        XCTAssertNotNil(try net.node("gw").node.originateBroadcast(b))
        net.runUntilIdle()
        for n in ["gw", "a", "b", "c"] {
            XCTAssertEqual([b], net.node(n).broadcasts, n)
            XCTAssertEqual([b], net.node(n).node.storedBroadcasts().map { $0.0 })
        }
    }

    func testTwoGatewaysOriginatingTheSameBroadcastDoNotDuplicateAnything() throws {
        let net = TestNet()
        net.chain(["g1", "a", "g2"])
        let b = bc()
        _ = try net.node("g1").node.originateBroadcast(b)
        net.runUntilIdle()
        XCTAssertNil(try net.node("g2").node.originateBroadcast(b)) // already holds it: nothing new to send
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("a").broadcasts.count)
        XCTAssertEqual(1, net.node("g2").broadcasts.count)
    }

    func testAnOfflinePhoneThatLaterMeetsACarrierGetsIt() throws {
        let net = TestNet()
        net.chain(["gw", "carrier"])
        _ = try net.node("gw").node.originateBroadcast(bc())
        net.runUntilIdle()
        // the carrier walks away from the gateway and meets a phone that never saw it
        net.disconnect("gw", "carrier")
        _ = net.node("far")
        net.connect("carrier", "far")
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("far").broadcasts.count)
    }

    func testAPhoneThatMissedItOnTheFloodGetsItFromTheInventoryDiff() throws {
        let net = TestNet()
        _ = net.node("gw"); _ = net.node("late")
        _ = try net.node("gw").node.originateBroadcast(bc())
        net.runUntilIdle()
        net.connect("gw", "late")
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("late").broadcasts.count)
    }

    func testExpiredBroadcastsAreNotStoredNorRelayed() throws {
        let net = TestNet()
        net.chain(["a", "b", "c"])
        let old = bc(issuedAtMs: T0 - 25 * hour)
        net.node("a").node.onFrame("b", frame(old, ts: T0)) // arrives from a neighbour, but it is 25 h old
        net.runUntilIdle()
        XCTAssertTrue(net.node("a").broadcasts.isEmpty)
        XCTAssertTrue(net.node("a").node.storedBroadcasts().isEmpty)
        XCTAssertTrue(net.node("c").broadcasts.isEmpty)
        XCTAssertNil(try net.node("a").node.originateBroadcast(old))
    }

    func testAStoredBroadcastLeavesTheStoreAtTwentyFourHoursNotSeventyTwo() throws {
        let net = TestNet()
        _ = try net.node("a").node.originateBroadcast(bc())
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("a").node.storedBroadcasts().count)
        net.runFor(23 * hour)
        net.node("a").node.expire()
        XCTAssertEqual(1, net.node("a").node.storedBroadcasts().count)
        net.runFor(2 * hour)
        net.node("a").node.expire()
        XCTAssertEqual(0, net.node("a").node.storedBroadcasts().count)
        XCTAssertEqual(1, net.node("a").node.stats().broadcastsDelivered)
    }

    func testLifeCountsFromIssueNotFromWhenAPhoneGotIt() {
        let net = TestNet()
        _ = net.node("a")
        let b = bc(issuedAtMs: T0 - 20 * hour)             // issued 20 h ago, reaches this phone only now
        net.node("a").node.onFrame("x", frame(b))
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("a").node.storedBroadcasts().count)
        net.runFor(5 * hour)                               // 25 h after issue
        net.node("a").node.expire()
        XCTAssertEqual(0, net.node("a").node.storedBroadcasts().count)
    }

    func testAnOlderBuildRelaysAnUnknownVersionOnceAndNeverStoresIt() throws {
        let net = TestNet()
        net.chain(["a", "b"])
        var v2 = try BroadcastCodec.encode(bc())
        v2[0] = 2
        let raw = encodeFrame(Frame(type: FrameTypes.broadcast, ttl: 7, timestampMs: T0, senderId: NodeId.random(), payload: v2))
        net.node("a").node.onFrame("b", raw)
        net.runUntilIdle()
        XCTAssertTrue(net.node("a").broadcasts.isEmpty)
        XCTAssertTrue(net.node("a").node.storedBroadcasts().isEmpty)
    }

    func testBroadcastsAreSentToAPeerBeforeOtherAcksAndNeeds() {
        let peer = NodeId.random()
        let n = sampleNeed(originNode: NodeId.random(), createdAtSec: 5_000)
        let needPkt = StoredPacket(key: n.msgIdHex, type: FrameTypes.need, raw: needFrameBytes(n, ts: T0), expiryBasisMs: n.createdAt * 1000)
        let ack = sampleAck(n)
        let ackPkt = StoredPacket(key: ack.ackIdHex, type: FrameTypes.ack,
                                  raw: encodeFrame(Frame(type: FrameTypes.ack, ttl: 7, timestampMs: T0, senderId: NodeId.random(), recipientId: NodeId.random(), payload: encodeAck(ack))),
                                  expiryBasisMs: T0)
        let b = bc()
        let bPkt = StoredPacket(key: b.keyHex, type: FrameTypes.broadcast, raw: frame(b), expiryBasisMs: T0)
        XCTAssertEqual([bPkt.key, ackPkt.key, needPkt.key], PushPlanner.order([needPkt, ackPkt, bPkt], peerNodeId: peer).map(\.key))
    }

    // ------------------------------------------------------------ the gate that reads them
    func testAnAreaOnlyHidesADeclarationWhenThePhoneKnowsItIsOutside() {
        let nowSec = T0 / 1000
        let here = (latE5: 2_029_612, lonE5: 8_582_451) // Bhubaneswar
        let near = DisasterContext(type: .flood, issuedAtSec: nowSec - 3600, area: .init(latE5: 2_030_000, lonE5: 8_580_000, radiusKm: 30))
        var far = DisasterContext(type: .flood, issuedAtSec: nowSec - 3600, area: .init(latE5: 1_960_000, lonE5: 8_480_000, radiusKm: 30)) // ~120 km away
        XCTAssertFalse(DisasterGate.active([near], nowSec: nowSec, here: here).types.isEmpty)
        XCTAssertTrue(DisasterGate.active([far], nowSec: nowSec, here: here).types.isEmpty)
        XCTAssertFalse(DisasterGate.active([far], nowSec: nowSec, here: nil).types.isEmpty)        // position unknown: keep it
        var whole = far; whole.area = nil
        XCTAssertFalse(DisasterGate.active([whole], nowSec: nowSec, here: here).types.isEmpty)     // whole-area
        far.area = .init(latE5: 1_960_000, lonE5: 8_480_000, radiusKm: 0)
        XCTAssertFalse(DisasterGate.active([far], nowSec: nowSec, here: here).types.isEmpty)
    }

    func testDisasterGateFreshnessAgingAndExpiry() {
        let now: Int64 = 1_800_000_000
        func active(_ ageH: Double) -> DisasterGate.Active {
            DisasterGate.active([DisasterContext(type: .cyclone, issuedAtSec: now - Int64(ageH * 3600))], nowSec: now)
        }
        XCTAssertFalse(active(1).aging)
        XCTAssertFalse(active(12).aging)
        XCTAssertTrue(active(13).aging)
        XCTAssertEqual([.cyclone], active(24).types)
        XCTAssertTrue(active(24.01).types.isEmpty)
        // one fresh declaration vouches for the area
        let both = DisasterGate.active([DisasterContext(type: .flood, issuedAtSec: now - 20 * 3600), DisasterContext(type: .cyclone, issuedAtSec: now - 3600)], nowSec: now)
        XCTAssertFalse(both.aging)
        XCTAssertEqual([.flood, .cyclone], both.types)
    }
}
