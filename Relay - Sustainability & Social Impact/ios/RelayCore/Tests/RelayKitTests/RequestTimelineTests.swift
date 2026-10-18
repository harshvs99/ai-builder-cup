import XCTest
import RelayCore
@testable import RelayKit

final class RequestTimelineTests: XCTestCase {
    private typealias Key = RequestTimeline.Key
    private typealias State = RequestTimeline.State
    private let t0: Int64 = 1_700_000_000_000
    private func need() -> MyNeed {
        MyNeed(msgIdHex: "aabbccdd", createdAtMs: t0, category: .water, urgency: 2, people: 6, item: "water", landmark: "school",
               summary: "pump broken", lang: .en, needPayloadB64: "")
    }

    func testFreshRequestHasOnlySavedAndItIsCurrent() {
        let s = RequestTimeline.steps(need(), now: t0)
        XCTAssertEqual([.saved], s.map { $0.key })
        XCTAssertEqual(.current, s[0].state)
        XCTAssertEqual(t0, s[0].atMs)
    }

    func testSentToMeshAddsNeighbourStep() {
        let n = need().markSentToMesh(nowMs: t0 + 5_000)
        let s = RequestTimeline.steps(n, now: t0 + 6_000)
        XCTAssertEqual([.saved, .passedToNeighbour], s.map { $0.key })
        XCTAssertEqual([.done, .current], s.map { $0.state })
        XCTAssertEqual(t0 + 5_000, s[1].atMs)
    }

    func testUploadedByThisPhoneReachesGatewayWithoutAnAck() {
        let n = need().markSentToMesh(nowMs: t0 + 1_000).markUploaded(nowMs: t0 + 9_000)
        let s = RequestTimeline.steps(n, now: t0 + 10_000)
        XCTAssertEqual([.saved, .passedToNeighbour, .reachedGateway], s.map { $0.key })
        XCTAssertEqual(t0 + 9_000, s.last?.atMs)
    }

    func testReceivedAckImpliesNeighbourAndGatewayEvenWithoutTimestamps() {
        let n = need().withAck(.received, ticket: 7, text: "got it", eta: 0xFFFF, nowMs: t0 + 60_000)
        let s = RequestTimeline.steps(n, now: t0 + 61_000)
        XCTAssertEqual([.saved, .passedToNeighbour, .reachedGateway, .seenByControlRoom], s.map { $0.key })
        XCTAssertEqual(0, s[1].atMs) // reached, time not recorded
        XCTAssertEqual(t0 + 60_000, s[2].atMs) // falls back to the ack time
        XCTAssertEqual(2, s.last?.urgency)
        XCTAssertEqual(.current, s.last?.state)
    }

    func testDispatchedCarriesEta() {
        let n = need().withAck(.received, ticket: 7, text: "r", eta: 0xFFFF, nowMs: t0 + 1_000)
            .withAck(.dispatched, ticket: 7, text: "d", eta: 45, nowMs: t0 + 2_000)
        let s = RequestTimeline.steps(n, now: t0 + 3_000)
        XCTAssertEqual(.helpComing, s.last?.key)
        XCTAssertEqual(t0 + 2_000 + 45 * 60_000, s.last?.etaAtMs)
        XCTAssertEqual(false, s.last?.etaOverdue)
        XCTAssertEqual(false, s.last?.live)
        XCTAssertEqual(true, RequestTimeline.steps(n, now: t0 + 2_000 + 46 * 60_000).last?.etaOverdue)
    }

    func testOnTheWayIsLiveAndKeepsEtaWhenAckHasNone() {
        let n = need().withAck(.dispatched, ticket: 7, text: "d", eta: 30, nowMs: t0 + 2_000)
            .withAck(.onTheWay, ticket: 7, text: "w", eta: 0xFFFF, nowMs: t0 + 3_000)
        XCTAssertEqual(.onTheWay, n.stage)
        let last = RequestTimeline.steps(n, now: t0 + 4_000).last!
        XCTAssertEqual(.teamOnTheWay, last.key)
        XCTAssertTrue(last.live)
        XCTAssertEqual(t0 + 2_000 + 30 * 60_000, last.etaAtMs)
        XCTAssertEqual(t0 + 3_000, last.atMs)
    }

    func testResolvedHasAllStepsDoneAndNoCurrent() {
        let n = need().markSentToMesh(nowMs: t0 + 1)
            .withAck(.received, ticket: 7, text: "r", eta: 0xFFFF, nowMs: t0 + 2)
            .withAck(.dispatched, ticket: 7, text: "d", eta: 20, nowMs: t0 + 3)
            .withAck(.onTheWay, ticket: 7, text: "w", eta: 10, nowMs: t0 + 4)
            .withAck(.resolved, ticket: 7, text: "done", eta: 0xFFFF, nowMs: t0 + 5)
        XCTAssertEqual(.resolved, n.stage)
        XCTAssertTrue(n.isClosed)
        let s = RequestTimeline.steps(n, now: t0 + 6)
        XCTAssertEqual(Key.allCases, s.map { $0.key })
        XCTAssertTrue(s.allSatisfy { $0.state == .done })
        XCTAssertFalse(s.contains { $0.live })
        XCTAssertEqual(t0 + 5, s.last?.atMs)
        XCTAssertEqual(.delivered, RequestTimeline.latest(n, now: t0 + 6).key)
    }

    func testCancelledKeepsReachedStepsButNothingIsCurrent() {
        let n = need().markSentToMesh(nowMs: t0 + 1).cancel(nowMs: t0 + 2)
        XCTAssertTrue(n.isClosed)
        let s = RequestTimeline.steps(n, now: t0 + 3)
        XCTAssertEqual([.saved, .passedToNeighbour], s.map { $0.key })
        XCTAssertTrue(s.allSatisfy { $0.state == .done })
    }

    func testTimestampsAreSetOnlyTheFirstTime() {
        let n = need().markSentToMesh(nowMs: 100).markSentToMesh(nowMs: 200).markUploaded(nowMs: 300).markUploaded(nowMs: 400)
        XCTAssertEqual(100, n.sentToMeshAtMs)
        XCTAssertEqual(300, n.uploadedAtMs)
        let a = need().withAck(.received, ticket: 1, text: "a", eta: 0xFFFF, nowMs: 10).withAck(.received, ticket: 1, text: "a", eta: 0xFFFF, nowMs: 20)
        XCTAssertEqual(10, a.receivedAtMs)
        XCTAssertEqual([.saved], RequestTimeline.steps(need(), now: t0 + 10_000_000).map { $0.key })
    }
}
