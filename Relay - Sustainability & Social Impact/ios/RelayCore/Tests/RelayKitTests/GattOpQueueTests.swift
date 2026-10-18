import XCTest
@testable import RelayKit

final class GattOpQueueTests: XCTestCase {
    private final class Fake: @unchecked Sendable {
        var started: [String] = []
        func op(_ label: String, result: Box<StartResult>? = nil) -> GattOp {
            GattOp(label) { [self] in started.append(label); return result?.value ?? .started }
        }
    }

    private let clock = ManualClock()
    private func queue(timeout: Int64 = 5_000, maxQueued: Int = 512, maxRetries: Int = 3) -> GattOpQueue {
        let c = clock
        return GattOpQueue(timeoutMs: timeout, maxQueued: maxQueued, maxRetries: maxRetries, now: { c.now })
    }

    func testOnlyOneOperationIsInFlightAndOrderIsPreserved() {
        let f = Fake(), q = queue()
        q.enqueue(f.op("a")); q.enqueue(f.op("b")); q.enqueue(f.op("c"))
        XCTAssertEqual(["a"], f.started)
        XCTAssertTrue(q.inFlight())
        q.onComplete(); XCTAssertEqual(["a", "b"], f.started)
        q.onComplete(); XCTAssertEqual(["a", "b", "c"], f.started)
        q.onComplete(); XCTAssertFalse(q.inFlight())
        XCTAssertEqual(0, q.size())
    }

    func testCompletionWithNothingInFlightIsIgnored() {
        XCTAssertFalse(queue().onComplete())
    }

    func testStuckOperationTimesOutAndQueueAdvances() {
        let f = Fake(), q = queue(timeout: 5_000)
        q.enqueue(f.op("stuck")); q.enqueue(f.op("next"))
        clock.now = 4_999; q.tick(); XCTAssertEqual(["stuck"], f.started)
        clock.now = 5_000; q.tick(); XCTAssertEqual(["stuck", "next"], f.started)
        XCTAssertEqual(1, q.consecutiveTimeouts)
        XCTAssertEqual(1, q.totalTimeouts)
        q.onComplete()
        XCTAssertEqual(0, q.consecutiveTimeouts, "a real completion resets the consecutive counter")
        XCTAssertEqual(1, q.totalTimeouts)
    }

    func testConsecutiveTimeoutsAccumulate() {
        let f = Fake(), q = queue(timeout: 1_000)
        for i in 0..<3 { q.enqueue(f.op("op\(i)")) }
        for _ in 0..<3 { clock.now += 1_000; q.tick() }
        XCTAssertEqual(3, q.consecutiveTimeouts)
    }

    func testFailedStartIsSkippedImmediately() {
        let f = Fake(), q = queue()
        q.enqueue(f.op("bad", result: Box(.failed))); q.enqueue(f.op("good"))
        XCTAssertEqual(["bad", "good"], f.started)
        XCTAssertEqual(1, q.dropped)
        XCTAssertTrue(q.inFlight())
    }

    func testBusyOperationIsRetriedOnTickThenSucceeds() {
        let f = Fake(), q = queue(maxRetries: 2)
        let result = Box(StartResult.retry)
        q.enqueue(f.op("busy", result: result)); q.enqueue(f.op("after"))
        XCTAssertEqual(["busy"], f.started)
        q.tick(); XCTAssertEqual(["busy", "busy"], f.started)
        result.value = .started
        q.tick(); XCTAssertEqual(["busy", "busy", "busy"], f.started, "busy succeeds on its third attempt")
        q.onComplete(); XCTAssertEqual(["busy", "busy", "busy", "after"], f.started)
    }

    func testBusyBeyondRetryLimitIsDroppedAndNextRuns() {
        let f = Fake(), q = queue(maxRetries: 1)
        q.enqueue(f.op("busy", result: Box(.retry))); q.enqueue(f.op("after"))
        q.tick() // retry 1 -> still busy -> retries exceeded -> dropped, "after" starts
        XCTAssertEqual(["busy", "busy", "after"], f.started)
        XCTAssertEqual(1, q.dropped)
    }

    func testSynchronousCompletionInsideStartDoesNotWedgeTheQueue() {
        let f = Fake(), q = queue()
        q.enqueue(GattOp("sync") { [q] in f.started.append("sync"); q.onComplete(); return .started })
        q.enqueue(f.op("next"))
        XCTAssertEqual(["sync", "next"], f.started)
    }

    func testFullQueueRejectsNewOperations() {
        let f = Fake(), q = queue(maxQueued: 2)
        XCTAssertTrue(q.enqueue(f.op("running")))
        XCTAssertTrue(q.enqueue(f.op("q1")))
        XCTAssertTrue(q.enqueue(f.op("q2")))
        XCTAssertFalse(q.enqueue(f.op("q3")))
        XCTAssertEqual(1, q.dropped)
    }

    func testClearForgetsEverything() {
        let f = Fake(), q = queue()
        q.enqueue(f.op("a")); q.enqueue(f.op("b"))
        q.clear()
        XCTAssertEqual(0, q.size())
        q.enqueue(f.op("c"))
        XCTAssertEqual(["a", "c"], f.started)
    }

    func testEnqueueBurstWhileBusyDoesNotBurnTheRetryBudget() {
        let f = Fake(), q = queue(maxRetries: 2)
        let result = Box(StartResult.retry)
        q.enqueue(f.op("busy", result: result))
        for i in 0..<20 { q.enqueue(f.op("f\(i)")) }
        XCTAssertEqual(["busy"], f.started, "retries only happen on tick()")
        XCTAssertEqual(0, q.dropped)
        result.value = .started
        q.tick()
        XCTAssertEqual(["busy", "busy"], f.started)
    }
}
