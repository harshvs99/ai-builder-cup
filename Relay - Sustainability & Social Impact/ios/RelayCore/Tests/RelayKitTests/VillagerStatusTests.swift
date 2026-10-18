import XCTest
@testable import RelayKit

final class VillagerStatusTests: XCTestCase {
    private typealias Dot = VillagerStatus.Dot
    private typealias Kind = VillagerStatus.Kind
    private let now: Int64 = 1_000_000
    private func peer(_ i: Int, gw: Bool = false) -> PeerInfo { PeerInfo(linkId: "link\(i)", nodeIdHex: "n\(i)", isGateway: gw, hops: gw ? 1 : 0xFF) }
    private func status(bt: Bool = true, peers: Int = 0, gateway: Bool = false, hops: Int = 0xFF, health: SyncHealth = SyncHealth()) -> MeshStatus {
        MeshStatus(serviceRunning: true, bluetoothOn: bt, peers: (0..<peers).map { peer($0) }, isGateway: gateway,
                   hopsToGateway: hops, lastSyncOkAtMs: health.lastOkAtMs, syncHealth: health)
    }

    func testBluetoothOffWinsOverEverything() {
        let v = VillagerStatus.from(status(bt: false, peers: 3, gateway: true, hops: 1), now: now)
        XCTAssertEqual(.bluetoothOff, v.kind)
        XCTAssertEqual(.grey, v.dot)
    }

    func testKnownGatewayIsReachableWithHops() {
        let v = VillagerStatus.from(status(peers: 2, hops: 2), now: now)
        XCTAssertEqual(.reachable, v.kind)
        XCTAssertEqual(.green, v.dot)
        XCTAssertEqual(2, v.hopsToGateway)
        XCTAssertEqual(2, v.peers)
        XCTAssertTrue(v.isReachable)
    }

    func testThisPhoneGatewayWithGoodSyncIsGreenAndAgeIsTimeSinceSync() {
        let ok = SyncHealth().record(true, nil, now - 180_000, "http://x:1")
        let v = VillagerStatus.from(status(gateway: true, health: ok), now: now)
        XCTAssertEqual(.thisPhoneGateway, v.kind)
        XCTAssertEqual(.green, v.dot)
        XCTAssertEqual(180_000, v.syncedAgoMs)
        XCTAssertNil(v.hopsToGateway)
    }

    func testGatewayThatHasNotSyncedYetIsStillSending() {
        let v = VillagerStatus.from(status(gateway: true), now: now)
        XCTAssertEqual(.thisPhoneGateway, v.kind)
        XCTAssertNil(v.syncedAgoMs)
    }

    func testGatewayWhoseBackendFailsIsWaitingAmber() {
        let bad = SyncHealth().record(false, "timeout", now - 1000, "http://x:1")
        let v = VillagerStatus.from(status(peers: 1, gateway: true, health: bad), now: now)
        XCTAssertEqual(.waitingForGateway, v.kind)
        XCTAssertEqual(.amber, v.dot)
    }

    func testPeersButNoGatewayIsWaiting() {
        let v = VillagerStatus.from(status(peers: 3), now: now)
        XCTAssertEqual(.waitingForGateway, v.kind)
        XCTAssertEqual(.amber, v.dot)
        XCTAssertEqual(3, v.peers)
    }

    func testNoPeersIsSearching() {
        let v = VillagerStatus.from(status(), now: now)
        XCTAssertEqual(.searching, v.kind)
        XCTAssertEqual(.grey, v.dot)
    }

    func testAGatewayOneHopAwayNeverShowsZeroHops() {
        XCTAssertEqual(1, VillagerStatus.from(status(peers: 1, hops: 0), now: now).hopsToGateway)
    }
}
