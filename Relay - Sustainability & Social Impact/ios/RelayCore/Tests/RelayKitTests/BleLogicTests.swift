import XCTest
import RelayCore
@testable import RelayKit

final class BleLogicTests: XCTestCase {
    // ---------------------------------------------------------------- backoff
    func testBackoffDoublesFrom2sTo60sCap() {
        let b = Backoff()
        let delays = (1...8).map { _ in b.onFailure("aa:bb", now: 0) }
        XCTAssertEqual([2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000] as [Int64], delays)
    }

    func testBackoffGatesAttemptsPerMacAndResetsOnSuccess() {
        let b = Backoff()
        XCTAssertTrue(b.canAttempt("m1", now: 0))
        b.onFailure("m1", now: 1_000)
        XCTAssertFalse(b.canAttempt("m1", now: 2_999))
        XCTAssertTrue(b.canAttempt("m1", now: 3_000))
        XCTAssertTrue(b.canAttempt("m2", now: 1_001), "other MACs are unaffected")
        b.onFailure("m1", now: 3_000) // second failure: 4 s
        XCTAssertFalse(b.canAttempt("m1", now: 6_999))
        XCTAssertTrue(b.canAttempt("m1", now: 7_000))
        b.onSuccess("m1")
        XCTAssertTrue(b.canAttempt("m1", now: 3_001))
        XCTAssertEqual(2_000, b.onFailure("m1", now: 0))
    }

    func testBlockUntilDoesNotCountAsFailureAndNeverShortens() {
        let b = Backoff()
        b.blockUntil("m", untilMs: 60_000)
        XCTAssertFalse(b.canAttempt("m", now: 59_999)); XCTAssertTrue(b.canAttempt("m", now: 60_000))
        XCTAssertEqual(0, b.failures("m"))
        b.onFailure("m", now: 0) // would only block until 2 s; the longer block stays
        XCTAssertFalse(b.canAttempt("m", now: 30_000))
    }

    // ---------------------------------------------------------------- allowlist
    func testEmptyAllowlistAcceptsEveryone() {
        XCTAssertTrue(Allowlist.matches("", nodeIdHex: "a1b2c3d4e5f60718"))
        XCTAssertTrue(Allowlist.matches("  , ", nodeIdHex: "a1b2c3d4e5f60718"))
    }

    func testAllowlistMatchesPrefixesCaseInsensitively() {
        let csv = "A1B2, 99ff ,0011223344"
        XCTAssertTrue(Allowlist.matches(csv, nodeIdHex: "a1b2c3d4e5f60718"))
        XCTAssertTrue(Allowlist.matches(csv, nodeIdHex: "99ff000000000000"))
        XCTAssertTrue(Allowlist.matches(csv, nodeIdHex: "0011223344556677"))
        XCTAssertFalse(Allowlist.matches(csv, nodeIdHex: "a1b3c3d4e5f60718"))
        XCTAssertFalse(Allowlist.matches(csv, nodeIdHex: "ff99000000000000"))
        XCTAssertEqual(["a1b2", "99ff", "0011223344"], Allowlist.parse(csv))
    }

    // ---------------------------------------------------------------- scan throttle
    func testScanThrottleAllowsFiveStartsPer30Seconds() {
        let t = ScanThrottle()
        for i in 0..<5 { XCTAssertTrue(t.tryAcquire(now: Int64(i) * 1_000)) }
        XCTAssertFalse(t.tryAcquire(now: 5_000))
        XCTAssertEqual(26_000, t.waitMs(now: 4_000)) // oldest start at 0 -> allowed again at 30 s
        XCTAssertFalse(t.tryAcquire(now: 29_999))
        XCTAssertTrue(t.tryAcquire(now: 30_000))
        XCTAssertFalse(t.tryAcquire(now: 30_001), "the window is full again")
    }

    // ---------------------------------------------------------------- duplicate links
    private let low = [UInt8](repeating: 0x10, count: 8)
    private let high = [UInt8](repeating: 0x80, count: 8)

    func testLowerIdNodeKeepsItsCentralLinkAndClosesPeripheral() {
        // I am the LOWER id: I should be central on the kept link.
        let r = LinkRegistry(myId: low)
        r.add("c:AA", mac: "AA", role: .central); r.add("p:BB", mac: "BB", role: .peripheral)
        XCTAssertEqual(.keep, r.onHello("c:AA", peerNodeId: high))
        XCTAssertEqual(.closeThis(reason: "duplicate link to \(Hex.encode(high).take(8)); keeping central side"), r.onHello("p:BB", peerNodeId: high))
    }

    func testLowerIdNodeClosesOlderPeripheralWhenCentralHelloArrivesSecond() {
        let r = LinkRegistry(myId: low)
        r.add("p:BB", mac: "BB", role: .peripheral); r.add("c:AA", mac: "AA", role: .central)
        XCTAssertEqual(.keep, r.onHello("p:BB", peerNodeId: high))
        XCTAssertEqual(.closeOther(otherLinkId: "p:BB"), r.onHello("c:AA", peerNodeId: high))
    }

    func testHigherIdNodeKeepsPeripheralAndClosesCentral() {
        // I am the HIGHER id: the peer (lower) should be the central, so I keep my peripheral link.
        let r = LinkRegistry(myId: high)
        r.add("c:AA", mac: "AA", role: .central); r.add("p:BB", mac: "BB", role: .peripheral)
        XCTAssertEqual(.keep, r.onHello("p:BB", peerNodeId: low))
        XCTAssertEqual(.closeThis(reason: "duplicate link to \(Hex.encode(low).take(8)); keeping peripheral side"), r.onHello("c:AA", peerNodeId: low))
    }

    func testBothSidesAgreeOnTheSurvivingLink() {
        // Underlying links: L1 = A central -> B peripheral, L2 = B central -> A peripheral.
        // A sees: c:L1 (central), p:L2 (peripheral). B sees: p:L1 (peripheral), c:L2 (central).
        let a = LinkRegistry(myId: low), b = LinkRegistry(myId: high)
        a.add("c:L1", mac: "L1", role: .central); a.add("p:L2", mac: "L2", role: .peripheral)
        b.add("p:L1", mac: "L1", role: .peripheral); b.add("c:L2", mac: "L2", role: .central)
        var closedByA = Set<String>(), closedByB = Set<String>()
        func apply(_ d: LinkRegistry.Decision, _ thisLink: String, _ into: inout Set<String>) {
            switch d {
            case .closeThis: into.insert(thisLink)
            case .closeOther(let other): into.insert(other)
            case .keep: break
            }
        }
        apply(a.onHello("c:L1", peerNodeId: high), "c:L1", &closedByA); apply(a.onHello("p:L2", peerNodeId: high), "p:L2", &closedByA)
        apply(b.onHello("p:L1", peerNodeId: low), "p:L1", &closedByB); apply(b.onHello("c:L2", peerNodeId: low), "c:L2", &closedByB)
        XCTAssertEqual(["p:L2"], closedByA) // L2 dies on A's side
        XCTAssertEqual(["c:L2"], closedByB) // and on B's side: the same underlying link
    }

    func testTwoLinksOfTheSameRoleKeepTheOlder() {
        let r = LinkRegistry(myId: low)
        r.add("c:AA", mac: "AA", role: .central); r.add("c:CC", mac: "CC", role: .central) // peer's address rotated
        XCTAssertEqual(.keep, r.onHello("c:AA", peerNodeId: high))
        if case .closeThis = r.onHello("c:CC", peerNodeId: high) {} else { XCTFail("expected closeThis") }
    }

    func testDifferentNodesAreNotDuplicates() {
        let r = LinkRegistry(myId: low)
        r.add("c:AA", mac: "AA", role: .central); r.add("p:BB", mac: "BB", role: .peripheral)
        XCTAssertEqual(.keep, r.onHello("c:AA", peerNodeId: [UInt8](repeating: 0x55, count: 8)))
        XCTAssertEqual(.keep, r.onHello("p:BB", peerNodeId: [UInt8](repeating: 0x66, count: 8)))
    }

    func testOwnNodeIdIsClosed() {
        let r = LinkRegistry(myId: low)
        r.add("c:AA", mac: "AA", role: .central)
        if case .closeThis = r.onHello("c:AA", peerNodeId: low) {} else { XCTFail("expected closeThis") }
    }

    func testShouldConnectAvoidsMacsAndNodesWeAlreadyHaveALinkTo() {
        let r = LinkRegistry(myId: low)
        XCTAssertTrue(r.shouldConnect("AA"))
        r.add("p:AA", mac: "AA", role: .peripheral)
        XCTAssertFalse(r.shouldConnect("AA"), "live link on that MAC")
        r.add("c:CC", mac: "CC", role: .central); _ = r.onHello("c:CC", peerNodeId: high)
        r.remove("c:CC")
        XCTAssertTrue(r.shouldConnect("CC"), "link gone, may reconnect")
        r.add("p:DD", mac: "DD", role: .peripheral); _ = r.onHello("p:DD", peerNodeId: high) // same node now reachable via another MAC
        XCTAssertFalse(r.shouldConnect("CC"), "CC maps to a node we are already linked to")
    }

    func testLinkCapsCountCorrectly() {
        let r = LinkRegistry(myId: low)
        for i in 0..<3 { r.add("c:\(i)", mac: "\(i)", role: .central) }
        for i in 0..<2 { r.add("p:\(i)", mac: "p\(i)", role: .peripheral) }
        XCTAssertEqual(5, r.total()); XCTAssertEqual(3, r.centralCount())
        r.remove("c:0"); XCTAssertEqual(4, r.total())
    }

    func testLinkIdHelpers() {
        XCTAssertEqual("c:AA:BB", LinkId.central("AA:BB"))
        XCTAssertEqual("p:UUID", LinkId.peripheral("UUID"))
        XCTAssertTrue(LinkId.isCentral("c:x")); XCTAssertFalse(LinkId.isCentral("p:x"))
        XCTAssertEqual("AA:BB", LinkId.address("c:AA:BB"))
    }

    // ---------------------------------------------------------------- loc_acc buckets
    func testLocAccBucketsFollowSpec() {
        XCTAssertEqual(1, LocAccuracy.bucket(0)); XCTAssertEqual(1, LocAccuracy.bucket(9.9))
        XCTAssertEqual(2, LocAccuracy.bucket(10)); XCTAssertEqual(2, LocAccuracy.bucket(49.9))
        XCTAssertEqual(3, LocAccuracy.bucket(50)); XCTAssertEqual(3, LocAccuracy.bucket(199))
        XCTAssertEqual(4, LocAccuracy.bucket(200)); XCTAssertEqual(4, LocAccuracy.bucket(999))
        XCTAssertEqual(5, LocAccuracy.bucket(1000)); XCTAssertEqual(5, LocAccuracy.bucket(50_000))
        XCTAssertEqual(5, LocAccuracy.bucket(nil)); XCTAssertEqual(5, LocAccuracy.bucket(Double.nan))
        XCTAssertEqual(5, LocAccuracy.bucket(-1)) // CLLocation: negative horizontalAccuracy = invalid
    }

    // ---------------------------------------------------------------- SPEC section 2 constants
    func testGattUuidsMatchTheSpec() throws {
        let spec = try String(contentsOf: specURL(), encoding: .utf8)
        XCTAssertTrue(spec.contains("`\(BleConstants.serviceUuid)`"), "service UUID must appear in protocol/SPEC.md")
        XCTAssertTrue(spec.contains("`\(BleConstants.charUuid)`"), "characteristic UUID must appear in protocol/SPEC.md")
        XCTAssertNotNil(UUID(uuidString: BleConstants.serviceUuid))
        XCTAssertNotNil(UUID(uuidString: BleConstants.charUuid))
        XCTAssertEqual(BleConstants.serviceUuid, BleConstants.serviceUuid.lowercased())
    }

    func testOtherTransportConstantsMatchAndroid() {
        XCTAssertEqual(517, BleConstants.requestedMtu); XCTAssertEqual(185, BleConstants.minMtu)
        XCTAssertEqual(3, BleConstants.attOverhead); XCTAssertEqual(23, BleConstants.defaultMtu)
        XCTAssertEqual(4, BleConstants.maxCentralLinks); XCTAssertEqual(7, BleConstants.maxTotalLinks)
        XCTAssertEqual(5_000, BleConstants.opTimeoutMs); XCTAssertEqual(25_000, BleConstants.setupTimeoutMs)
        XCTAssertEqual(2_000, BleConstants.backoffMinMs); XCTAssertEqual(60_000, BleConstants.backoffMaxMs)
    }

    private func specURL() throws -> URL {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let candidate = dir.appendingPathComponent("protocol/SPEC.md")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        throw XCTSkip("protocol/SPEC.md not found above \(#filePath)")
    }
}
