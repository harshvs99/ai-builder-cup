import XCTest
@testable import RelayCore

/// Port of the Android `core-protocol/.../AnnouncementTest.kt` (SPEC 5.7). The golden vectors are in VectorsTests.
final class AnnouncementTests: XCTestCase {
    private let hour: Int64 = 3_600_000
    private let id: [UInt8] = [0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f, 0x60, 0x61]

    private func ann(
        lang: Lang = .en, status: AnnouncementStatus = .active, district: Int = 0, approvedAtMs: Int64 = T0,
        expiresAt: Int64 = 0, text: String = "Shelters are open", announcementId: [UInt8]? = nil
    ) -> Announcement {
        Announcement(announcementId: announcementId ?? id, lang: lang, district: district, status: status,
                     approvedAt: approvedAtMs / 1000, expiresAt: expiresAt, text: text)
    }

    private func frame(_ a: Announcement, ttl: Int = 7, ts: Int64 = T0) -> [UInt8] {
        encodeFrame(Frame(type: FrameTypes.announcement, ttl: ttl, timestampMs: ts, senderId: NodeId.random(),
                          payload: try! AnnouncementCodec.encode(a)))
    }

    // ------------------------------------------------------------ codec
    func testCodecRoundTripsAndTheLargestFrameIsOneWrite() throws {
        for l in Lang.allCases {
            for st in [AnnouncementStatus.active, .withdrawn] {
                let a = ann(lang: l, status: st, district: 1, expiresAt: 1_790_086_400, text: "पुरी में शरण स्थल खुले हैं।")
                XCTAssertEqual(a, AnnouncementCodec.decode(try AnnouncementCodec.encode(a)))
            }
        }
        let biggest = ann(approvedAtMs: 0xFFFF_FFFF * 1000, expiresAt: 0xFFFF_FFFF, text: String(repeating: "a", count: 139))
        let payload = try AnnouncementCodec.encode(biggest)
        XCTAssertEqual(160, payload.count)
        XCTAssertEqual(160, AnnouncementCodec.maxPayload)
        XCTAssertEqual(182, 22 + payload.count) // exactly SPEC 2's single-write limit (MTU 185 - 3)
        XCTAssertEqual(biggest, AnnouncementCodec.decode(payload))
        // an empty text is allowed (a WITHDRAWN frame may carry none)
        XCTAssertEqual(ann(text: ""), AnnouncementCodec.decode(try AnnouncementCodec.encode(ann(text: ""))))
    }

    func testEncoderCutsALongTextOnACharacterBoundary() throws {
        let p = try AnnouncementCodec.encode(ann(lang: .hi, text: String(repeating: "अ", count: 100)))
        XCTAssertEqual(20 + 1 + 138, p.count)
        XCTAssertEqual(String(repeating: "अ", count: 46), AnnouncementCodec.decode(p)?.text)
    }

    func testDecoderRejectsWhatTheSpecSaysToDrop() throws {
        let good = try AnnouncementCodec.encode(ann())
        XCTAssertNotNil(AnnouncementCodec.decode(good))
        func mutated(_ f: (inout [UInt8]) -> Void) -> [UInt8] { var c = good; f(&c); return c }
        XCTAssertNil(AnnouncementCodec.decode(mutated { $0[0] = 2 }))                                  // version
        XCTAssertNil(AnnouncementCodec.decode(Array(good.prefix(20))))                                  // no text length byte
        XCTAssertNil(AnnouncementCodec.decode(good + [0]))                                              // trailing byte
        XCTAssertNil(AnnouncementCodec.decode(Array(good.dropLast())))                                  // text runs past the end
        XCTAssertNil(AnnouncementCodec.decode(mutated { $0[9] = 6 }))                                   // lang 6
        XCTAssertNil(AnnouncementCodec.decode(mutated { $0[11] = 2 }))                                  // status 2
        XCTAssertNil(AnnouncementCodec.decode(Array(good.prefix(20)) + [140] + [UInt8](repeating: 0x61, count: 140))) // text over 139
        XCTAssertNil(AnnouncementCodec.decode(Array(good.prefix(20)) + [2, 0xC3, 0x28]))               // invalid UTF-8
    }

    func testAnUnknownDistrictStillDecodesSoThePhoneCanRelayIt() throws {
        let a = ann(district: 200)
        XCTAssertEqual(a, AnnouncementCodec.decode(try AnnouncementCodec.encode(a)))
        XCTAssertNil(AnnouncementDistricts.idOf(200))
        XCTAssertEqual("delhi", AnnouncementDistricts.idOf(1))
        XCTAssertEqual(0, AnnouncementDistricts.indexOf(" Puri "))
        XCTAssertNil(AnnouncementDistricts.indexOf("nowhere"))
    }

    func testEncoderRefusesInvalidFields() {
        var neg = ann(); neg.approvedAt = -1
        var big = ann(); big.expiresAt = 1 << 32
        for bad in [ann(announcementId: [UInt8](repeating: 0, count: 7)), ann(district: 256), ann(district: -1), neg, big] {
            XCTAssertThrowsError(try AnnouncementCodec.encode(bad))
        }
    }

    func testTheDedupeKeyIsDerivedSoAWithdrawalIsANewFrameThatSupersedes() {
        let active = ann(), withdrawn = ann(status: .withdrawn), hindi = ann(lang: .hi)
        XCTAssertEqual(16, active.keyHex.count)
        XCTAssertEqual(3, Set([active.keyHex, withdrawn.keyHex, hindi.keyHex]).count)
        XCTAssertEqual(active.keyHex, ann(approvedAtMs: T0 + 5000, text: "other words").keyHex) // the text is not part of the key
        XCTAssertNotEqual(active.keyHex, ann(announcementId: [UInt8](repeating: 0, count: 8)).keyHex)
    }

    func testLifeIsSeventyTwoHoursAndEndsAtExpiresAt() {
        let nowSec = T0 / 1000
        XCTAssertTrue(ann().isLive(nowSec: nowSec))
        XCTAssertTrue(ann().isLive(nowSec: nowSec + 72 * 3600))
        XCTAssertFalse(ann().isLive(nowSec: nowSec + 72 * 3600 + 1))
        XCTAssertTrue(ann(expiresAt: nowSec + 100).isLive(nowSec: nowSec))
        XCTAssertFalse(ann(expiresAt: nowSec + 100).isLive(nowSec: nowSec + 100))
        XCTAssertTrue(ann(approvedAtMs: T0 + 3_600_000).isLive(nowSec: nowSec)) // the phone's clock may be behind
    }

    // ------------------------------------------------------------ gossip
    func testAGatewayOriginatesAndEveryPhoneStoresAndRelaysIt() throws {
        let net = TestNet()
        net.chain(["gw", "a", "b", "c"])
        let en = ann(), hi = ann(lang: .hi, text: "शरण स्थल खुले हैं")
        XCTAssertNotNil(try net.node("gw").node.originateAnnouncement(en))
        XCTAssertNotNil(try net.node("gw").node.originateAnnouncement(hi))
        net.runUntilIdle()
        for n in ["gw", "a", "b", "c"] {
            XCTAssertEqual(Set([en, hi]), Set(net.node(n).announcements), n)
            XCTAssertEqual(2, net.node(n).node.storedAnnouncements().count, n)
        }
        XCTAssertEqual(2, net.node("c").node.stats().announcementsDelivered)
    }

    func testOriginatingTheSamePayloadAgainIsFreeAndAWithdrawalIsNew() throws {
        let net = TestNet()
        net.chain(["g1", "a", "g2"])
        _ = try net.node("g1").node.originateAnnouncement(ann())
        net.runUntilIdle()
        XCTAssertNil(try net.node("g1").node.originateAnnouncement(ann()))
        XCTAssertNil(try net.node("g2").node.originateAnnouncement(ann())) // already holds it from the mesh
        XCTAssertNotNil(try net.node("g2").node.originateAnnouncement(ann(status: .withdrawn)))
        net.runUntilIdle()
        XCTAssertEqual([AnnouncementStatus.active, .withdrawn], net.node("a").announcements.map { $0.status })
        XCTAssertEqual(1, net.node("g1").announcements.filter { $0.status == .active }.count)
    }

    func testAnOfflinePhoneThatLaterMeetsACarrierGetsIt() throws {
        let net = TestNet()
        net.chain(["gw", "carrier"])
        _ = try net.node("gw").node.originateAnnouncement(ann())
        net.runUntilIdle()
        net.disconnect("gw", "carrier")
        _ = net.node("far")
        net.connect("carrier", "far")
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("far").announcements.count)
    }

    func testEveryPhoneRelaysAnAnnouncementOfAnotherDistrictAndLanguage() throws {
        let net = TestNet()
        net.chain(["gw", "a", "b"])
        _ = try net.node("gw").node.originateAnnouncement(ann(lang: .or, district: 1))
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("b").announcements.count) // stored and relayed; whether it is shown is the board's rule
    }

    func testAnUnknownDistrictIsStoredAndRelayed() {
        let net = TestNet()
        net.chain(["a", "b", "c"])
        net.node("a").node.onFrame("b", frame(ann(district: 77)))
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("a").announcements.count)
        XCTAssertEqual(1, net.node("c").announcements.count)
    }

    func testStaleOrEndedAnnouncementsAreNeitherStoredNorRelayed() throws {
        let net = TestNet()
        net.chain(["a", "b", "c"])
        let old = ann(approvedAtMs: T0 - 73 * hour)
        let ended = ann(lang: .hi, expiresAt: T0 / 1000 - 5)
        net.node("a").node.onFrame("b", frame(old))
        net.node("a").node.onFrame("b", frame(ended))
        net.runUntilIdle()
        XCTAssertTrue(net.node("a").announcements.isEmpty)
        XCTAssertTrue(net.node("a").node.storedAnnouncements().isEmpty)
        XCTAssertTrue(net.node("c").announcements.isEmpty)
        XCTAssertNil(try net.node("a").node.originateAnnouncement(old))
    }

    func testAStoredAnnouncementLeavesTheStoreSeventyTwoHoursAfterFirstStorage() throws {
        let net = TestNet()
        _ = try net.node("a").node.originateAnnouncement(ann(approvedAtMs: T0 - 20 * hour))
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("a").node.storedAnnouncements().count)
        XCTAssertEqual(T0, net.node("a").node.storedAnnouncements().first?.receivedAtMs)
        net.runFor(60 * hour) // 80 h after approval: past its life even though 72 h have not passed since storage
        XCTAssertEqual(0, net.node("a").node.storedAnnouncements().count)
        net.runFor(13 * hour)
        net.node("a").node.expire()
        XCTAssertEqual(0, net.node("a").node.storedAnnouncements().count)
    }

    func testAPhoneThatMissedItOnTheFloodGetsItFromTheInventoryDiff() throws {
        let net = TestNet()
        _ = net.node("gw"); _ = net.node("late")
        _ = try net.node("gw").node.originateAnnouncement(ann())
        net.runUntilIdle()
        net.connect("gw", "late")
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("late").announcements.count)
    }

    func testAnOlderBuildRelaysAnUnknownVersionOnceAndNeverStoresIt() throws {
        let net = TestNet()
        net.chain(["a", "b"])
        var v2 = try AnnouncementCodec.encode(ann())
        v2[0] = 2
        let raw = encodeFrame(Frame(type: FrameTypes.announcement, ttl: 7, timestampMs: T0, senderId: NodeId.random(), payload: v2))
        net.node("a").node.onFrame("b", raw)
        net.runUntilIdle()
        XCTAssertTrue(net.node("a").announcements.isEmpty)
        XCTAssertTrue(net.node("a").node.storedAnnouncements().isEmpty)
    }

    func testAnInvalidAnnouncementIsRejectedNotRelayed() throws {
        let net = TestNet()
        net.chain(["a", "b"])
        var bad = try AnnouncementCodec.encode(ann())
        bad[9] = 6
        net.node("a").node.onFrame("b", encodeFrame(Frame(type: FrameTypes.announcement, ttl: 7, timestampMs: T0, senderId: NodeId.random(), payload: bad)))
        net.runUntilIdle()
        XCTAssertEqual(1, net.node("a").node.stats().framesRejected)
        XCTAssertTrue(net.node("b").announcements.isEmpty)
    }

    func testARescueRequestIsPushedBeforeAnAnnouncementAndHintsAfterIt() {
        let need = sampleNeed(originNode: NodeId.random())
        func pkt(_ type: Int, _ key: String, _ basis: Int64) -> StoredPacket { StoredPacket(key: key, type: type, raw: [UInt8](repeating: 0, count: 10), expiryBasisMs: basis) }
        let order = PushPlanner.order(
            [pkt(FrameTypes.locHint, "h", 5), pkt(FrameTypes.announcement, "a", 9), pkt(FrameTypes.need, need.msgIdHex, 1), pkt(FrameTypes.broadcast, "b", 1)],
            peerNodeId: nil)
        XCTAssertEqual(["b", need.msgIdHex, "a", "h"], order.map(\.key))
    }
}
