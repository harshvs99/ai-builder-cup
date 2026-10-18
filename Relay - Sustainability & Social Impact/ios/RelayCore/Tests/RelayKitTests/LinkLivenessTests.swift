import XCTest
@testable import RelayKit

final class LinkLivenessTests: XCTestCase {
    func testTimeoutIsThreeResyncIntervals() {
        XCTAssertEqual(90_000, LinkHealth.livenessTimeoutMs)
        XCTAssertEqual(3 * BleConstants.resyncIntervalMs, LinkHealth.livenessTimeoutMs)
    }

    func testSilentLinkIsClosedOnlyAfterTheTimeout() throws {
        var clock: Int64 = 1_000
        let lastRx = clock
        XCTAssertNil(LinkHealth.livenessAbortReason(lastRxAt: lastRx, now: clock))
        clock += LinkHealth.livenessTimeoutMs - 1
        XCTAssertNil(LinkHealth.livenessAbortReason(lastRxAt: lastRx, now: clock))
        clock += 1
        let reason = try XCTUnwrap(LinkHealth.livenessAbortReason(lastRxAt: lastRx, now: clock))
        XCTAssertTrue(reason.contains("90s"))
    }

    func testAnyFrameResetsTheWatchdog() {
        var clock: Int64 = 0
        var lastRx: Int64 = 0
        // One inventory every 30 s keeps a healthy link alive indefinitely.
        for _ in 0..<20 {
            clock += BleConstants.resyncIntervalMs
            XCTAssertNil(LinkHealth.livenessAbortReason(lastRxAt: lastRx, now: clock))
            lastRx = clock
        }
        // Two lost resyncs are tolerated; the third silent interval closes it.
        clock += 2 * BleConstants.resyncIntervalMs
        XCTAssertNil(LinkHealth.livenessAbortReason(lastRxAt: lastRx, now: clock))
        clock += BleConstants.resyncIntervalMs
        XCTAssertNotNil(LinkHealth.livenessAbortReason(lastRxAt: lastRx, now: clock))
    }

    func testMissingServiceIsRetriedExactlyOnce() {
        XCTAssertEqual(1_500, LinkHealth.discoveryRetryDelayMs(retriesSoFar: 0))
        XCTAssertNil(LinkHealth.discoveryRetryDelayMs(retriesSoFar: 1), "second miss gives up")
        XCTAssertNil(LinkHealth.discoveryRetryDelayMs(retriesSoFar: 5))
    }

    func testServiceChangedReconnectIsShortNotBackoff() {
        XCTAssertEqual(1_000, LinkHealth.serviceChangedReconnectMs)
        XCTAssertLessThan(LinkHealth.serviceChangedReconnectMs, BleConstants.backoffMinMs)
    }

    func testResyncIntervalMatchesTheEngine() {
        XCTAssertEqual(Int64(MeshEngine.resyncIntervalMs), BleConstants.resyncIntervalMs)
    }
}
