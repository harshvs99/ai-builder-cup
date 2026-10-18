import XCTest
@testable import RelayKit

final class MeshHardeningTests: XCTestCase {
    // ---------------------------------------------------------------- backoff after a requestMtu timeout
    func testMtuTimeoutAbortsSetupAtOnceAndBackoffStartsAt2s() {
        let clock = ManualClock()
        let q = GattOpQueue(timeoutMs: BleConstants.opTimeoutMs, now: { clock.now })
        q.enqueue(GattOp("requestMtu") { .started })
        clock.now = 4_999; q.tick()
        XCTAssertNil(LinkHealth.setupAbortReason(phase: .mtu, inPhaseMs: 4_999, totalMs: 4_999, consecutiveOpTimeouts: q.consecutiveTimeouts))
        clock.now = 5_000; q.tick()
        // The timeout is detected on the 500 ms tick, not after the old 25 s setup deadline.
        XCTAssertNotNil(LinkHealth.setupAbortReason(phase: .mtu, inPhaseMs: 5_000, totalMs: 5_000, consecutiveOpTimeouts: q.consecutiveTimeouts))
        let b = Backoff()
        XCTAssertEqual(2_000, b.onFailure("aa", now: clock.now), "first reconnect delay")
        XCTAssertFalse(b.canAttempt("aa", now: clock.now + 1_999)); XCTAssertTrue(b.canAttempt("aa", now: clock.now + 2_000))
    }

    func testRepeatedFailuresStayExponentialAndCapped() {
        let b = Backoff()
        let d = (1...9).map { _ in b.onFailure("aa", now: 0) }
        XCTAssertEqual([2_000, 4_000, 8_000, 16_000, 32_000, 60_000, 60_000, 60_000, 60_000] as [Int64], d)
        XCTAssertEqual(2_000, BleConstants.backoffMinMs)
    }

    func testClearedBackoffLetsARestartedTransportReconnectAtOnce() {
        let b = Backoff()
        for _ in 0..<5 { b.onFailure("aa", now: 0) } // five toggles' worth of failures
        XCTAssertFalse(b.canAttempt("aa", now: 1_000))
        b.clear()
        XCTAssertTrue(b.canAttempt("aa", now: 0))
        XCTAssertEqual(2_000, b.onFailure("aa", now: 0))
    }

    func testConnectAndSetupDeadlines() {
        XCTAssertNil(LinkHealth.setupAbortReason(phase: .connecting, inPhaseMs: 9_000, totalMs: 9_000, consecutiveOpTimeouts: 0))
        XCTAssertEqual("connect timeout", LinkHealth.setupAbortReason(phase: .connecting, inPhaseMs: 10_001, totalMs: 10_001, consecutiveOpTimeouts: 0))
        XCTAssertNil(LinkHealth.setupAbortReason(phase: .discovering, inPhaseMs: 12_000, totalMs: 24_000, consecutiveOpTimeouts: 0))
        XCTAssertEqual("setup timeout in DISCOVERING", LinkHealth.setupAbortReason(phase: .discovering, inPhaseMs: 1_000, totalMs: 25_001, consecutiveOpTimeouts: 0))
        XCTAssertEqual("setup operation timed out in MTU", LinkHealth.setupAbortReason(phase: .mtu, inPhaseMs: 1, totalMs: 1, consecutiveOpTimeouts: 1))
        XCTAssertNil(LinkHealth.setupAbortReason(phase: .up, inPhaseMs: 99_000, totalMs: 99_000, consecutiveOpTimeouts: 5), "a live link is never aborted by setup rules")
    }

    // ---------------------------------------------------------------- cleanup paths
    func testClearedQueueIsFullyReusable() {
        let log = Box([String]())
        let q = GattOpQueue(timeoutMs: 1_000, now: { 0 })
        func op(_ l: String) -> GattOp { GattOp(l) { log.mutate { $0.append(l) }; return .started } }
        q.enqueue(op("a")); q.enqueue(op("b"))
        q.clear()
        XCTAssertEqual(0, q.size()); XCTAssertFalse(q.inFlight())
        q.enqueue(op("c"))
        XCTAssertEqual(["a", "c"], log.value)
        XCTAssertTrue(q.inFlight())
    }

    func testClearedQueueAfterBusyRetryStillPumps() {
        let log = Box([String]())
        let result = Box(StartResult.retry)
        let q = GattOpQueue(maxRetries: 8, now: { 0 })
        q.enqueue(GattOp("busy") { log.mutate { $0.append("busy") }; return result.value })
        q.clear() // waitingForTick must not survive a clear, or the next link's first op would hang until a tick
        result.value = .started
        q.enqueue(GattOp("fresh") { log.mutate { $0.append("fresh") }; return .started })
        XCTAssertEqual(["busy", "fresh"], log.value)
    }

    func testMacToNodeMapIsBounded() {
        let r = LinkRegistry(myId: [UInt8](repeating: 1, count: 8))
        for i in 0..<(LinkRegistry.maxMacEntries * 3) {
            let mac = "m\(i)"
            r.add("c:\(mac)", mac: mac, role: .central)
            var id = (0..<8).map { UInt8(truncatingIfNeeded: i + 10 + $0) }
            id[0] = UInt8(truncatingIfNeeded: i >> 8); id[1] = UInt8(truncatingIfNeeded: i); id[7] = 9
            r.onHello("c:\(mac)", peerNodeId: id)
            r.remove("c:\(mac)")
        }
        XCTAssertLessThanOrEqual(r.knownMacCount(), LinkRegistry.maxMacEntries)
        r.clear()
        XCTAssertEqual(0, r.knownMacCount())
    }

    func testScanRssiIsKeptPerAddressForCentralLinksOnlyAndBounded() {
        let r = LinkRegistry(myId: [UInt8](repeating: 1, count: 8))
        XCTAssertNil(r.rssiFor("c:aa"))
        r.noteScanRssi("aa", -71)
        r.noteScanRssi("bb", 127)   // "unavailable" is ignored
        r.noteScanRssi("cc", -200)  // out of range is ignored
        XCTAssertEqual(-71, r.rssiFor("c:aa"))
        XCTAssertNil(r.rssiFor("p:aa"), "peripheral links were never scanned")
        XCTAssertNil(r.rssiFor("c:bb")); XCTAssertNil(r.rssiFor("c:cc"))
        r.noteScanRssi("aa", -60) // latest scan wins
        XCTAssertEqual(-60, r.rssiFor("c:aa"))

        let n = LinkRegistry.maxMacEntries * 3
        for i in 0..<n { r.noteScanRssi("m\(i)", -50) }
        XCTAssertNil(r.rssiFor("c:aa"), "oldest entries are evicted")
        XCTAssertEqual(-50, r.rssiFor("c:m\(n - 1)"))
        r.clear()
        XCTAssertNil(r.rssiFor("c:m\(n - 1)"))
    }

    func testThrottleResetAfterBluetoothToggleStormAllowsScanAgain() {
        let t = ScanThrottle()
        for i in 0..<5 { XCTAssertTrue(t.tryAcquire(now: Int64(i) * 1_000)) } // five toggles in five seconds
        XCTAssertFalse(t.tryAcquire(now: 6_000))
        t.reset()
        XCTAssertTrue(t.tryAcquire(now: 6_000))
    }

    // ---------------------------------------------------------------- recent peers
    func testKeepsOnlyTheLastThreeDistinctNodesNewestFirst() {
        let changes = Box([[RecentPeers.Peer]]())
        let r = RecentPeers { p in changes.mutate { $0.append(p) } }
        r.record(mac: "m1", nodeIdHex: "n1"); r.record(mac: "m2", nodeIdHex: "n2"); r.record(mac: "m3", nodeIdHex: "n3"); r.record(mac: "m4", nodeIdHex: "n4")
        XCTAssertEqual(["n4", "n3", "n2"], r.all().map { $0.nodeIdHex })
        r.record(mac: "m9", nodeIdHex: "n2") // same node, new (rotated) address: moves to the front, no duplicate
        XCTAssertEqual(["n2", "n4", "n3"], r.all().map { $0.nodeIdHex })
        XCTAssertEqual("m9", r.all().first?.mac)
        let n = changes.value.count
        r.record(mac: "m9", nodeIdHex: "n2")
        XCTAssertEqual(n, changes.value.count, "recording the same newest peer again persists nothing")
    }

    func testPersistenceRoundTripAndGarbageTolerance() {
        let peers = [RecentPeers.Peer(mac: "AA:BB:CC:DD:EE:01", nodeIdHex: "0011223344556677"), RecentPeers.Peer(mac: "AA:BB:CC:DD:EE:02", nodeIdHex: "8899aabbccddeeff")]
        XCTAssertEqual(peers, RecentPeers.parse(RecentPeers.serialize(peers)))
        XCTAssertEqual([], RecentPeers.parse(nil))
        XCTAssertEqual([], RecentPeers.parse("junk;;a,b,c;,"))
        XCTAssertEqual(3, RecentPeers(initial: (0..<5).map { RecentPeers.Peer(mac: "m\($0)", nodeIdHex: "n\($0)") }).all().count)
    }

    func testNonRecentAdvertisersWaitOnlyInsideTheWindowAndWhileARecentPeerIsUnlinked() {
        let r = RecentPeers(initial: [RecentPeers.Peer(mac: "m1", nodeIdHex: "n1"), RecentPeers.Peer(mac: "m2", nodeIdHex: "n2")])
        let linked = Set<String>()
        XCTAssertFalse(r.shouldDefer(now: 1_000, preferUntil: 4_000, mac: "x", nodeIdHex: "n1") { linked.contains($0) }, "recent peer is never deferred")
        XCTAssertTrue(r.shouldDefer(now: 1_000, preferUntil: 4_000, mac: "x", nodeIdHex: "n9") { linked.contains($0) }, "stranger waits")
        XCTAssertFalse(r.shouldDefer(now: 4_000, preferUntil: 4_000, mac: "x", nodeIdHex: "n9") { linked.contains($0) }, "window over")
        XCTAssertTrue(r.shouldDefer(now: 1_000, preferUntil: 4_000, mac: "x", nodeIdHex: "n9") { $0 == "n1" }, "one recent peer still missing")
        XCTAssertFalse(r.shouldDefer(now: 1_000, preferUntil: 4_000, mac: "x", nodeIdHex: "n9") { _ in true }, "all recent peers linked")
        XCTAssertFalse(r.shouldDefer(now: 1_000, preferUntil: 4_000, mac: "m2", nodeIdHex: nil) { _ in false }, "no hint: matched by address")
        XCTAssertTrue(r.shouldDefer(now: 1_000, preferUntil: 4_000, mac: "zz", nodeIdHex: nil) { _ in false })
        XCTAssertFalse(RecentPeers().shouldDefer(now: 0, preferUntil: 4_000, mac: "x", nodeIdHex: "n9") { _ in false }, "nothing remembered, nothing deferred")
    }

    // ---------------------------------------------------------------- duty cycle and hours
    func testDutyModeByBatteryChargingAndForeground() {
        XCTAssertEqual(.normal, DutyCycle.choose(batteryPct: 80, charging: false, foreground: false))
        XCTAssertEqual(.continuous, DutyCycle.choose(batteryPct: 80, charging: false, foreground: true))
        XCTAssertEqual(.lowBattery, DutyCycle.choose(batteryPct: 19, charging: false, foreground: false))
        XCTAssertEqual(.lowBattery, DutyCycle.choose(batteryPct: 19, charging: false, foreground: true), "low battery wins over foreground")
        XCTAssertEqual(.normal, DutyCycle.choose(batteryPct: 20, charging: false, foreground: false), "20% is not low")
        XCTAssertEqual(.normal, DutyCycle.choose(batteryPct: 5, charging: true, foreground: false), "charging is never low")
        XCTAssertEqual(.normal, DutyCycle.choose(batteryPct: -1, charging: false, foreground: false), "unknown level is not low")
    }

    func testDutyWindowsAreAsSpecifiedAndRespectTheScanStartLimit() {
        XCTAssertEqual(3_000, DutyCycle.Mode.normal.scanMs); XCTAssertEqual(12_000, DutyCycle.Mode.normal.idleMs)
        XCTAssertEqual(30_000, DutyCycle.Mode.lowBattery.scanMs! + DutyCycle.Mode.lowBattery.idleMs)
        XCTAssertNil(DutyCycle.Mode.continuous.scanMs)
        for m in DutyCycle.Mode.allCases {
            XCTAssertLessThanOrEqual(m.startsPer30s, BleConstants.scanStartsPerWindow, "\(m.name) uses \(m.startsPer30s) starts per 30 s")
        }
        // Replay a minute of NORMAL cycling through the real throttle: never refused.
        let t = ScanThrottle(); var now: Int64 = 0
        for _ in 0..<8 { XCTAssertTrue(t.tryAcquire(now: now)); now += 15_000 }
    }

    func testMeshHoursEstimate() {
        XCTAssertEqual(4.0, MeshEnergy.drainPctPerHour(.continuous), accuracy: 1e-9)
        XCTAssertEqual(1.92, MeshEnergy.drainPctPerHour(.normal), accuracy: 1e-9)
        XCTAssertEqual(1.66, MeshEnergy.drainPctPerHour(.lowBattery), accuracy: 1e-9)
        XCTAssertEqual(25.0, MeshEnergy.hoursRemaining(batteryPct: 100, charging: false, mode: .continuous)!, accuracy: 1e-9)
        XCTAssertEqual(50.0 / 1.92, MeshEnergy.hoursRemaining(batteryPct: 50, charging: false, mode: .normal)!, accuracy: 1e-9)
        XCTAssertEqual(0.0, MeshEnergy.hoursRemaining(batteryPct: 0, charging: false, mode: .normal)!, accuracy: 1e-9)
        XCTAssertNil(MeshEnergy.hoursRemaining(batteryPct: 50, charging: true, mode: .normal), "charging")
        XCTAssertNil(MeshEnergy.hoursRemaining(batteryPct: -1, charging: false, mode: .normal), "unknown level")
        XCTAssertGreaterThan(MeshEnergy.hoursRemaining(batteryPct: 18, charging: false, mode: .lowBattery)!, 18 / MeshEnergy.drainPctPerHour(.normal), "lower duty means more hours")
    }
}
