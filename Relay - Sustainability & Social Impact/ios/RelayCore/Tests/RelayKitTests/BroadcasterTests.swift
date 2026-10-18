import XCTest
@testable import RelayKit

final class BroadcasterTests: XCTestCase {
    func testStreamYieldsCurrentThenDistinctChanges() async {
        let b = Broadcaster(1)
        var it = b.updates().makeAsyncIterator()
        let first = await it.next()
        XCTAssertEqual(1, first)
        b.send(1) // equal: not re-sent
        b.send(2)
        let second = await it.next()
        XCTAssertEqual(2, second)
        XCTAssertEqual(2, b.value)
    }

    func testSlowConsumerOnlySeesTheNewestValue() async {
        let b = Broadcaster(0)
        var it = b.updates().makeAsyncIterator()
        for i in 1...50 { b.send(i) }
        let newest = await it.next() // the 49 values in between were dropped, not queued
        XCTAssertEqual(50, newest)
    }

    func testModifyMutatesInPlace() {
        let b = Broadcaster([1, 2])
        b.modify { $0.append(3) }
        XCTAssertEqual([1, 2, 3], b.value)
    }

    func testCancelledConsumerIsRemoved() async {
        let b = Broadcaster(0)
        let t = Task { for await _ in b.updates() {} }
        try? await Task.sleep(nanoseconds: 20_000_000)
        t.cancel()
        _ = await t.value
        b.send(1) // must not crash or leak into a dead continuation
        XCTAssertEqual(1, b.value)
    }
}
