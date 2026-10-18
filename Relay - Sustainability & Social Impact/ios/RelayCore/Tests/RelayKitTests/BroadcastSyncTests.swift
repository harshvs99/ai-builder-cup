import XCTest
import RelayCore
@testable import RelayKit

/// Port of the Android `SyncTest.kt` broadcast cases (SPEC 5.6, section 7 `broadcasts`).
final class BroadcastSyncTests: TempDirTestCase {
    private let http = FakeHTTP()
    private let gatewayId = TestSupport.id(0x40)
    private var cfg: SyncConfig { SyncConfig(baseUrl: "http://127.0.0.1:8080/", deviceKey: "dev-relay-key") }

    private func syncer(_ node: GossipNode, _ store: InMemoryPacketStore, _ state: GatewayState) -> GatewaySyncer {
        let c = cfg
        return GatewaySyncer(node: node, store: store, state: state, client: SyncClient(transport: http), config: { c })
    }

    private func payload(_ type: DisasterType, ageSec: Int64 = 60, id: [UInt8] = TestSupport.id(0x61)) throws -> String {
        let b = Broadcast(id: id, kind: .disaster, disaster: type, issuedAt: Int64(Date().timeIntervalSince1970) - ageSec)
        return Data(try BroadcastCodec.encode(b)).base64EncodedString()
    }

    private func body(_ broadcasts: [String]?) -> [String: Any] {
        var o: [String: Any] = ["accepted": 0, "duplicates": 0, "rejected": 0, "acks": [String](), "ack_cursor": 0]
        if let broadcasts { o["broadcasts"] = broadcasts }
        return o
    }

    func testEverySyncReturnsLiveBroadcastsAndTheGatewayOriginatesEachOnce() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let s = syncer(node, store, GatewayState(file: nil))
        let p = try payload(.flood)
        http.respond { _ in self.body([p]) }
        let first = s.syncOnce()
        XCTAssertTrue(first.ok); XCTAssertEqual(1, first.broadcastsOriginated)
        XCTAssertEqual(1, store.all().filter { $0.type == FrameTypes.broadcast }.count)
        // the backend repeats the same live list every round: nothing new is originated, nothing duplicated
        XCTAssertEqual(0, s.syncOnce().broadcastsOriginated)
        XCTAssertEqual(1, store.all().filter { $0.type == FrameTypes.broadcast }.count)
    }

    func testBroadcastsAreNeverUploadedBackAndABadOrExpiredOneIsSkipped() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let s = syncer(node, store, GatewayState(file: nil))
        let list = [
            try payload(.cyclone, id: TestSupport.id(0x62)),
            try payload(.flood, ageSec: 25 * 3600, id: TestSupport.id(0x63)),
            "not-base64-!!",
            Data([1, 2, 3]).base64EncodedString(),
        ]
        http.respond { _ in self.body(list) }
        let out = s.syncOnce()
        XCTAssertTrue(out.ok)
        XCTAssertEqual(1, out.broadcastsOriginated)           // only the live, well-formed one
        XCTAssertEqual(0, s.syncOnce().uploaded)              // a broadcast is not a NEED: it goes down, never up
    }

    func testAnOlderBackendWithoutTheKeyChangesNothing() {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        http.respond { _ in self.body(nil) }
        let out = syncer(node, store, GatewayState(file: nil)).syncOnce()
        XCTAssertTrue(out.ok); XCTAssertEqual(0, out.broadcastsOriginated)
    }

    func testAMalformedBroadcastsValueNeverFailsTheAcksNextToIt() throws {
        let c = SyncClient(transport: http)
        let r = try c.parse(#"{"accepted":1,"acks":[],"ack_cursor":4,"broadcasts":"nope"}"#)
        XCTAssertEqual(4, r.ackCursor)
        XCTAssertTrue(r.broadcasts.isEmpty)
        XCTAssertEqual(1, try c.parse(#"{"acks":[],"ack_cursor":1,"broadcasts":["\#(try payload(.flood))"]}"#).broadcasts.count)
    }
}
