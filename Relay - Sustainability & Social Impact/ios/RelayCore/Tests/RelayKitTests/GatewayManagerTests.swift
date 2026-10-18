import XCTest
import RelayCore
@testable import RelayKit

/// Runs scheduled tasks only when the test says so.
final class ManualScheduler: Scheduler, @unchecked Sendable {
    private let lock = NSLock()
    private var queued: [(delay: Int64, task: @Sendable () -> Void)] = []

    func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void) {
        lock.lock(); queued.append((delayMs, task)); lock.unlock()
    }

    var delays: [Int64] { lock.lock(); defer { lock.unlock() }; return queued.map { $0.delay } }

    /// Runs (and removes) every task queued with this delay; tasks they queue are left for the next call.
    func run(delay: Int64) {
        lock.lock()
        let due = queued.filter { $0.delay == delay }
        queued.removeAll { $0.delay == delay }
        lock.unlock()
        due.forEach { $0.task() }
    }
}

final class GatewayManagerTests: XCTestCase {
    private let http = FakeHTTP()
    private let scheduler = ManualScheduler()
    private let offline = Box(false)
    private let changes = Box([(Bool, SyncOutcome?)]())
    private var node: GossipNode!
    private var store: InMemoryPacketStore!
    private var manager: GatewayManager!

    override func setUp() {
        store = InMemoryPacketStore()
        node = TestSupport.node(nodeId: TestSupport.id(0x40), store: store)
        let syncer = GatewaySyncer(node: node, store: store, state: GatewayState(file: nil), client: SyncClient(transport: http),
                                   config: { SyncConfig(baseUrl: "http://x:1", deviceKey: "k") }, maxFramesPerSync: 1)
        let off = offline, ch = changes
        manager = GatewayManager(node: node, syncer: syncer, scheduler: scheduler, pretendOffline: { off.value },
                                 onChange: { net, outcome in ch.mutate { $0.append((net, outcome)) } })
    }

    func testBecomesGatewayOnlyWhileStartedWithInternetAndNotPretendingOffline() {
        manager.setInternetAvailable(true)
        XCTAssertFalse(node.isGateway, "not started yet")
        manager.start()
        XCTAssertTrue(node.isGateway)
        offline.value = true
        manager.refresh()
        XCTAssertFalse(node.isGateway, "Pretend offline")
        offline.value = false
        manager.refresh()
        XCTAssertTrue(node.isGateway)
        manager.setInternetAvailable(false)
        XCTAssertFalse(node.isGateway)
        manager.setInternetAvailable(true)
        manager.stop()
        XCTAssertFalse(node.isGateway)
    }

    func testGoingOnlineKicksAnImmediateSync() throws {
        _ = try node.originateNeed(TestSupport.need(1))
        manager.start()
        manager.setInternetAvailable(true)
        XCTAssertTrue(scheduler.delays.contains(0), "a kick is queued")
        XCTAssertTrue(http.requests.isEmpty)
        scheduler.run(delay: 0)
        XCTAssertEqual(1, http.requests.count)
        let last = try XCTUnwrap(changes.value.last)
        XCTAssertTrue(last.0)
        XCTAssertEqual(true, last.1?.ok)
    }

    func testKickIsCoalescedAndIgnoredWhenNotAGateway() {
        manager.start()
        manager.kick()
        XCTAssertFalse(scheduler.delays.contains(0), "not a gateway: nothing to sync")
        manager.setInternetAvailable(true)
        manager.kick(); manager.kick(); manager.kick()
        XCTAssertEqual(1, scheduler.delays.filter { $0 == 0 }.count)
    }

    func testPeriodicTickRunsEveryTenSecondsAndStopsAfterStop() {
        manager.setInternetAvailable(true)
        manager.start()
        scheduler.run(delay: 0) // the kick from going online
        let base = http.requests.count
        XCTAssertTrue(scheduler.delays.contains(GatewayManager.tickIntervalMs))
        scheduler.run(delay: GatewayManager.tickIntervalMs)
        XCTAssertEqual(base + 1, http.requests.count)
        XCTAssertTrue(scheduler.delays.contains(GatewayManager.tickIntervalMs), "re-armed after the round")
        manager.stop()
        scheduler.run(delay: GatewayManager.tickIntervalMs)
        XCTAssertEqual(base + 1, http.requests.count, "a stopped manager does not sync")
        XCTAssertFalse(scheduler.delays.contains(GatewayManager.tickIntervalMs), "and does not re-arm")
    }

    func testMoreWaitingTriggersAnImmediateFollowUp() throws {
        for i in 0..<3 { _ = try node.originateNeed(TestSupport.need(20 + i)) }
        manager.setInternetAvailable(true)
        manager.start()
        var rounds = 0
        while scheduler.delays.contains(0) && rounds < 10 { scheduler.run(delay: 0); rounds += 1 }
        XCTAssertEqual(3, http.requests.count, "3 NEEDs at 1 frame per round: each round with more waiting kicks the next")
        XCTAssertEqual([1, 1, 1], http.requests.map { ($0.body?["frames"] as? [String])?.count ?? -1 })
    }

    func testFailedRoundIsReportedAndDoesNotChainFollowUps() throws {
        _ = try node.originateNeed(TestSupport.need(1))
        http.status = 503
        manager.setInternetAvailable(true)
        manager.start()
        scheduler.run(delay: 0)
        XCTAssertEqual(false, changes.value.last?.1?.ok)
        XCTAssertFalse(scheduler.delays.contains(0))
    }

    func testStartIsIdempotent() {
        manager.start(); manager.start()
        XCTAssertEqual(1, scheduler.delays.filter { $0 == GatewayManager.tickIntervalMs }.count)
    }
}
