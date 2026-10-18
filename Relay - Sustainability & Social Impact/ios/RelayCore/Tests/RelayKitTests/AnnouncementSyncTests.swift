import XCTest
import RelayCore
@testable import RelayKit

/// Port of the Android `SyncTest.kt` announcement cases (SPEC 5.7, section 7 `announcements`).
final class AnnouncementSyncTests: TempDirTestCase {
    private let http = FakeHTTP()
    private let gatewayId = TestSupport.id(0x40)
    private var cfg: SyncConfig { SyncConfig(baseUrl: "http://127.0.0.1:8080/", deviceKey: "dev-relay-key") }

    private func syncer(_ node: GossipNode, _ store: InMemoryPacketStore, _ state: GatewayState, district: String? = nil) -> GatewaySyncer {
        let c = cfg
        return GatewaySyncer(node: node, store: store, state: state, client: SyncClient(transport: http), config: { c }, district: { district })
    }

    private func payload(_ lang: Lang, _ status: AnnouncementStatus = .active, id: [UInt8] = TestSupport.id(0x71), text: String = "Shelters are open",
                         ageSec: Int64 = 60) throws -> String {
        let a = Announcement(announcementId: id, lang: lang, district: 0, status: status,
                             approvedAt: Int64(Date().timeIntervalSince1970) - ageSec, expiresAt: 0, text: text)
        return Data(try AnnouncementCodec.encode(a)).base64EncodedString()
    }

    private func body(_ announcements: [String], _ cursor: Int64) -> [String: Any] {
        ["accepted": 0, "duplicates": 0, "rejected": 0, "acks": [String](), "ack_cursor": 0, "announcements": announcements, "announcement_cursor": cursor]
    }

    func testTheGatewaySendsItsDistrictAndCursorAndOriginatesEachAnnouncementOnce() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        let s = syncer(node, store, state, district: "puri")
        let list = [try payload(.en), try payload(.hi, text: "शरण स्थल खुले हैं")]
        http.respond { _ in self.body(list, 12) }

        let first = s.syncOnce()
        XCTAssertTrue(first.ok); XCTAssertEqual(2, first.announcementsOriginated)
        XCTAssertEqual("puri", http.requests[0].body?["district"] as? String)
        XCTAssertEqual(0, (http.requests[0].body?["announcement_cursor"] as? NSNumber)?.int64Value)
        XCTAssertEqual(2, store.all().filter { $0.type == FrameTypes.announcement }.count)
        XCTAssertEqual(12, state.announcementCursor())

        // next round sends the stored cursor; the same payloads again are not originated twice
        XCTAssertEqual(0, s.syncOnce().announcementsOriginated)
        XCTAssertEqual(12, (http.requests[1].body?["announcement_cursor"] as? NSNumber)?.int64Value)
        XCTAssertEqual(2, store.all().filter { $0.type == FrameTypes.announcement }.count)
    }

    func testAWithdrawalIsANewFrameAndAnnouncementsAreNeverUploaded() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let s = syncer(node, store, GatewayState(file: nil))
        let a = try payload(.en), w = try payload(.en, .withdrawn)
        http.respond { _ in self.body([a], 1) }
        XCTAssertEqual(1, s.syncOnce().announcementsOriginated)
        http.respond { _ in self.body([w], 2) }
        XCTAssertEqual(1, s.syncOnce().announcementsOriginated)
        XCTAssertEqual(2, store.all().filter { $0.type == FrameTypes.announcement }.count)
        XCTAssertEqual(0, s.syncOnce().uploaded) // announcements go down, never up
        XCTAssertNil(http.requests[0].body?["district"]) // no district known: the field is left out
    }

    func testBadOrExpiredAnnouncementPayloadsAreSkippedAndAFailedRoundKeepsTheCursor() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        let s = syncer(node, store, state)
        let list = ["not-base64-!!", Data([1, 2, 3]).base64EncodedString(), try payload(.en, id: TestSupport.id(0x72), text: "old", ageSec: 80 * 3600)]
        http.respond { _ in self.body(list, 5) }
        let out = s.syncOnce()
        XCTAssertTrue(out.ok); XCTAssertEqual(0, out.announcementsOriginated)
        XCTAssertEqual(5, state.announcementCursor())
        http.status = 500
        XCTAssertFalse(s.syncOnce().ok)
        XCTAssertEqual(5, state.announcementCursor())
    }

    func testTheAnnouncementCursorSurvivesARestartAndOldStateFilesLoad() throws {
        let f = file("gw2.json")
        GatewayState(file: f).setAnnouncementCursor(33)
        XCTAssertEqual(33, GatewayState(file: f).announcementCursor())
        try #"{"cursor":4,"uploaded":["aa"]}"#.write(to: file("old.json"), atomically: true, encoding: .utf8)
        let old = GatewayState(file: file("old.json"))
        XCTAssertEqual(4, old.ackCursor()); XCTAssertEqual(0, old.announcementCursor()); XCTAssertTrue(old.isUploaded("aa"))
    }

    func testAMalformedAnnouncementsValueNeverFailsTheAcksNextToIt() throws {
        let c = SyncClient(transport: http)
        let r = try c.parse(#"{"accepted":1,"acks":[],"ack_cursor":4,"announcements":"nope","announcement_cursor":"x"}"#)
        XCTAssertEqual(4, r.ackCursor)
        XCTAssertTrue(r.announcements.isEmpty); XCTAssertNil(r.announcementCursor)
        let ok = try c.parse(#"{"acks":[],"ack_cursor":1,"announcements":["\#(try payload(.en))"],"announcement_cursor":9}"#)
        XCTAssertEqual(1, ok.announcements.count); XCTAssertEqual(9, ok.announcementCursor)
    }
}
