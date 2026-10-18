import XCTest
import RelayCore
@testable import RelayKit

/// Runs the real `SyncClient` / `GatewaySyncer` against a fake backend behind the injectable `HTTPTransport`.
final class SyncTests: TempDirTestCase {
    private let http = FakeHTTP()
    private let gatewayId = TestSupport.id(0x40)
    private var cfg: SyncConfig { SyncConfig(baseUrl: "http://127.0.0.1:8080/", deviceKey: "dev-relay-key") } // trailing slash on purpose

    private func syncer(_ node: GossipNode, _ store: InMemoryPacketStore, _ state: GatewayState, logs: Box<[String]>? = nil, max: Int = 100) -> GatewaySyncer {
        let c = cfg
        return GatewaySyncer(node: node, store: store, state: state, client: SyncClient(transport: http), config: { c },
                             log: { l in logs?.mutate { $0.append(l) } }, maxFramesPerSync: max)
    }

    private func frames(_ r: FakeHTTP.Req) -> [[UInt8]] {
        ((r.body?["frames"] as? [String]) ?? []).map { [UInt8](Data(base64Encoded: $0)!) }
    }

    private func okBody(accepted: Int = 0, acks: [String] = [], cursor: Int64 = 0) -> [String: Any] {
        ["accepted": accepted, "duplicates": 0, "rejected": 0, "acks": acks, "ack_cursor": cursor]
    }

    func testUploadsOnlyNewFramesAndSendsHeaderAndGatewayId() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        let s = syncer(node, store, state)

        _ = try node.originateNeed(TestSupport.need(1)); _ = try node.originateNeed(TestSupport.need(2))
        let first = s.syncOnce()
        XCTAssertTrue(first.ok); XCTAssertEqual(2, first.uploaded)
        let r1 = try XCTUnwrap(http.requests.first)
        XCTAssertEqual(1, http.requests.count)
        XCTAssertEqual("POST", r1.method)
        XCTAssertEqual("http://127.0.0.1:8080/v1/sync", r1.url.absoluteString)
        XCTAssertEqual("dev-relay-key", r1.headers["X-Relay-Key"])
        XCTAssertEqual("application/json", r1.headers["Content-Type"])
        XCTAssertEqual(Hex.encode(gatewayId), r1.body?["gateway_node"] as? String)
        XCTAssertEqual(0, (r1.body?["ack_cursor"] as? NSNumber)?.int64Value)
        // frames are the exact stored raw bytes, standard base64 with padding
        let sent = frames(r1)
        let stored = store.all().filter { $0.type == FrameTypes.need }.map { $0.raw }
        XCTAssertEqual(stored, sent)
        let b64 = try XCTUnwrap((r1.body?["frames"] as? [String])?.first)
        XCTAssertTrue(b64.count % 4 == 0 && !b64.contains("\n"))

        // Second round: nothing new -> an empty upload (still polls for ACKs).
        XCTAssertEqual(0, s.syncOnce().uploaded)
        XCTAssertEqual(0, frames(http.requests[1]).count)

        // A third NEED uploads only that one.
        let n3 = TestSupport.need(3); _ = try node.originateNeed(n3)
        XCTAssertEqual(1, s.syncOnce().uploaded)
        let last = frames(http.requests[2])
        XCTAssertEqual(1, last.count)
        XCTAssertEqual(n3.msgIdHex, NeedCodec.decode(FrameCodec.decode(last[0])!.payload)?.msgIdHex)
    }

    func testAckFramesInTheStoreAreNeverUploaded() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        _ = try node.originateAck(TestSupport.ack(9, ref: TestSupport.id(1), target: TestSupport.id(2)))
        XCTAssertEqual(0, syncer(node, store, GatewayState(file: nil)).syncOnce().uploaded)
    }

    func testCursorIsSentPersistedAndReloaded() throws {
        let f = file("gw.json")
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let body = okBody(accepted: 1, cursor: 17)
        http.respond { _ in body }
        let state = GatewayState(file: f)
        _ = try node.originateNeed(TestSupport.need(1))
        let s = syncer(node, store, state)
        XCTAssertTrue(s.syncOnce().ok)
        XCTAssertEqual(17, state.ackCursor())

        // "Restart": a fresh state from the same file remembers cursor and uploaded ids.
        let reloaded = GatewayState(file: f)
        XCTAssertEqual(17, reloaded.ackCursor())
        XCTAssertTrue(reloaded.isUploaded(TestSupport.need(1).msgIdHex))
        let s2 = syncer(node, store, reloaded)
        XCTAssertEqual(0, s2.syncOnce().uploaded)
        XCTAssertEqual(17, (http.requests.last?.body?["ack_cursor"] as? NSNumber)?.int64Value)
    }

    func testReturnedAckPayloadsAreWrappedAndOriginatedIntoTheNode() throws {
        let store = InMemoryPacketStore()
        let rec = TestSupport.Recorder()
        let myOrigin = TestSupport.id(0x70) // pretend this gateway phone is also the requester
        let node = TestSupport.node(nodeId: myOrigin, store: store, listener: rec)
        let need = TestSupport.need(1, origin: myOrigin)
        _ = try node.originateNeed(need)

        let ack1 = TestSupport.ack(0x31, ref: need.msgId, target: myOrigin, ticket: 142, text: "Received. Ticket 142.")
        let ack2 = TestSupport.ack(0x32, ref: need.msgId, target: myOrigin, ticket: 142, status: .dispatched, text: "Help dispatched")
        let foreign = TestSupport.ack(0x33, ref: TestSupport.id(5), target: TestSupport.id(0x99), ticket: 9)
        let payloads = try [ack1, ack2, foreign].map { Data(try AckCodec.encode($0)).base64EncodedString() }
        let body = okBody(accepted: 1, acks: payloads, cursor: 3)
        http.respond { _ in body }
        let state = GatewayState(file: nil)
        let out = syncer(node, store, state).syncOnce()
        XCTAssertTrue(out.ok); XCTAssertEqual(3, out.acksOriginated)

        XCTAssertEqual(3, rec.acksSeen.count, "all three ACKs originated into the engine")
        XCTAssertEqual([142, 142], rec.acksForMe.map { $0.ticketNo }, "only ACKs targeted at this node are shown")
        XCTAssertEqual([.received, .dispatched], rec.acksForMe.map { $0.status })
        let storedAcks = node.storedAcks()
        XCTAssertEqual(3, storedAcks.count)
        let frame = storedAcks[0].1
        XCTAssertEqual(FrameTypes.ack, frame.type)
        XCTAssertEqual(7, frame.ttl, "ttl 7 at origination")
        XCTAssertEqual(myOrigin, frame.senderId, "sender is the gateway")
        XCTAssertNotNil(frame.recipientId)
        XCTAssertEqual(3, state.ackCursor())
    }

    func testServerErrorLeavesStateUntouchedAndNextRoundRetriesTheSameFrames() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        _ = try node.originateNeed(TestSupport.need(1))
        let logs = Box([String]())
        let s = syncer(node, store, state, logs: logs)

        http.status = 500
        let bad = s.syncOnce()
        XCTAssertFalse(bad.ok); XCTAssertTrue(bad.error!.contains("500"))
        XCTAssertEqual("HTTP 500: boom", bad.error)
        XCTAssertEqual(0, state.uploadedCount()); XCTAssertEqual(0, state.ackCursor())

        http.status = 200
        let good = s.syncOnce()
        XCTAssertTrue(good.ok); XCTAssertEqual(1, good.uploaded)
        XCTAssertEqual(1, frames(http.requests.last!).count)
        XCTAssertTrue(logs.value.contains { $0.hasPrefix("sync ok") })
    }

    func testUnreachableBackendIsReportedNotThrown() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        _ = try node.originateNeed(TestSupport.need(1))
        http.failure = URLError(.cannotConnectToHost)
        let out = syncer(node, store, GatewayState(file: nil)).syncOnce()
        XCTAssertFalse(out.ok); XCTAssertNotNil(out.error)
        XCTAssertEqual("connection refused", SyncHealth.shortError(out.error))
    }

    func testBadBackendUrlIsAFailure() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let s = GatewaySyncer(node: node, store: store, state: GatewayState(file: nil), client: SyncClient(transport: http),
                              config: { SyncConfig(baseUrl: "  not a url ", deviceKey: "k") })
        let out = s.syncOnce()
        XCTAssertFalse(out.ok)
        XCTAssertEqual("bad backend URL", SyncHealth.shortError(out.error))
        XCTAssertTrue(http.requests.isEmpty)
    }

    func testGarbageResponseBodyIsAFailureAndUndecodableAckIsSkipped() {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        let s = syncer(node, store, state)
        let body = okBody(acks: [Data([1, 2, 3]).base64EncodedString(), "!!notbase64!!"], cursor: 4)
        http.respond { _ in body }
        let out = s.syncOnce()
        XCTAssertTrue(out.ok); XCTAssertEqual(0, out.acksOriginated)
        XCTAssertEqual(4, state.ackCursor())

        http.respond { _ in ["nope": true] } // no ack_cursor
        let bad = s.syncOnce()
        XCTAssertFalse(bad.ok)
        XCTAssertEqual("bad response from backend", SyncHealth.shortError(bad.error))

        http.rawBody = [UInt8]("<html>captive portal</html>".utf8)
        XCTAssertFalse(s.syncOnce().ok)
    }

    func testBatchesAreCappedAndMoreWaitingIsReported() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        for i in 0..<5 { _ = try node.originateNeed(TestSupport.need(10 + i)) }
        let s = syncer(node, store, state, max: 2)
        XCTAssertEqual(2, s.syncOnce().uploaded); XCTAssertTrue(s.moreWaiting)
        XCTAssertEqual(2, s.syncOnce().uploaded); XCTAssertTrue(s.moreWaiting)
        XCTAssertEqual(1, s.syncOnce().uploaded); XCTAssertFalse(s.moreWaiting)
        XCTAssertEqual(5, state.uploadedCount())
    }

    func testClientParsesBackendResponseShape() throws {
        let c = SyncClient(transport: http)
        let json = #"{"accepted":3,"duplicates":1,"rejected":0,"acks":["AQID"],"ack_cursor":17}"#
        let r = try c.parse(json)
        XCTAssertEqual(3, r.accepted); XCTAssertEqual(1, r.duplicates); XCTAssertEqual(17, r.ackCursor)
        XCTAssertEqual([[1, 2, 3]], r.acks)
        XCTAssertThrowsError(try c.parse("not json"))
        // Counts are optional, like Android's optInt.
        let min = try c.parse(#"{"ack_cursor":2}"#)
        XCTAssertEqual(SyncResponse(accepted: 0, duplicates: 0, rejected: 0, acks: [], ackCursor: 2), min)
    }

    func testRequestBodyHasExactlyTheContractKeys() throws {
        let c = SyncClient(transport: http)
        _ = try c.sync(cfg, gatewayNode: gatewayId, frames: [[1, 2, 3], [255]], ackCursor: 9)
        let r = try XCTUnwrap(http.requests.first)
        XCTAssertEqual(["ack_cursor", "frames", "gateway_node"], (r.body ?? [:]).keys.sorted())
        XCTAssertEqual(["AQID", "/w=="], r.body?["frames"] as? [String])
        XCTAssertEqual(Hex.encode(gatewayId), r.body?["gateway_node"] as? String)
        XCTAssertEqual("application/json", r.headers["Accept"])
    }

    // ---------------------------------------------------------------- LOC_HINT upload (SPEC 5.5)

    private func typesIn(_ r: FakeHTTP.Req) -> [Int] { frames(r).map { FrameCodec.decode($0)!.type } }

    func testStoredHintIsUploadedWithNeedsOnceAndNotAgain() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        let s = syncer(node, store, state)
        _ = try node.originateNeed(TestSupport.need(1))
        let hint = TestSupport.hint(1, gatewayId)
        XCTAssertNotNil(try node.originateLocHint(hint))

        let first = s.syncOnce()
        XCTAssertTrue(first.ok); XCTAssertEqual(2, first.uploaded); XCTAssertEqual(1, first.hintsUploaded)
        let r1 = try XCTUnwrap(http.requests.first)
        XCTAssertEqual([FrameTypes.need, FrameTypes.locHint], typesIn(r1))
        // the hint goes out as its exact stored raw bytes
        XCTAssertEqual(store.all().first { $0.type == FrameTypes.locHint }?.raw, frames(r1)[1])
        XCTAssertTrue(state.isUploaded(hint.keyHex))
        XCTAssertTrue(s.pendingNeedKeys().isEmpty)

        // Second round: nothing new, so an empty upload.
        let second = s.syncOnce()
        XCTAssertEqual(0, second.uploaded); XCTAssertEqual(0, second.hintsUploaded)
        XCTAssertEqual(0, frames(http.requests[1]).count)

        // A later hint uploads alone.
        _ = try node.originateLocHint(TestSupport.hint(2, gatewayId))
        XCTAssertEqual(1, s.syncOnce().uploaded)
        XCTAssertEqual([FrameTypes.locHint], typesIn(http.requests[2]))
    }

    func testPendingNeedKeysIgnoreHintsAndAFailedRoundKeepsTheHintForRetry() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        let s = syncer(node, store, state)
        let hint = TestSupport.hint(1, gatewayId)
        _ = try node.originateLocHint(hint)
        XCTAssertTrue(s.pendingNeedKeys().isEmpty, "hints are not NEEDs")

        http.status = 500
        XCTAssertFalse(s.syncOnce().ok)
        XCTAssertFalse(state.isUploaded(hint.keyHex))

        http.status = 200
        let ok = s.syncOnce()
        XCTAssertTrue(ok.ok); XCTAssertEqual(1, ok.uploaded); XCTAssertEqual(1, ok.hintsUploaded)
        XCTAssertTrue(state.isUploaded(hint.keyHex))
    }

    func testNeedsGoBeforeHintsUnderTheCapAndMoreWaitingCountsBoth() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let state = GatewayState(file: nil)
        // Hints are stored first, NEEDs after: the NEEDs must still be uploaded first.
        _ = try node.originateLocHint(TestSupport.hint(1, gatewayId))
        _ = try node.originateLocHint(TestSupport.hint(2, gatewayId))
        for i in 0..<3 { _ = try node.originateNeed(TestSupport.need(10 + i)) }
        let s = syncer(node, store, state, max: 4)

        let a = s.syncOnce()
        XCTAssertEqual(4, a.uploaded); XCTAssertEqual(1, a.hintsUploaded)
        XCTAssertEqual([FrameTypes.need, FrameTypes.need, FrameTypes.need, FrameTypes.locHint], typesIn(http.requests[0]))
        XCTAssertTrue(s.moreWaiting)

        let b = s.syncOnce()
        XCTAssertEqual(1, b.uploaded); XCTAssertEqual(1, b.hintsUploaded)
        XCTAssertEqual([FrameTypes.locHint], typesIn(http.requests[1]))
        XCTAssertFalse(s.moreWaiting)
        XCTAssertEqual(5, state.uploadedCount())
    }

    func testOutcomeReportsHintsAcceptedOnlyWhenTheBackendSentTheHintsObject() throws {
        let store = InMemoryPacketStore()
        let node = TestSupport.node(nodeId: gatewayId, store: store)
        let s = syncer(node, store, GatewayState(file: nil))
        _ = try node.originateLocHint(TestSupport.hint(1, gatewayId))

        http.respond { _ in
            ["accepted": 0, "duplicates": 0, "rejected": 0, "acks": [String](), "ack_cursor": 1,
             "hints": ["accepted": 1, "duplicates": 0, "rejected": 0]]
        }
        let withHints = s.syncOnce()
        XCTAssertTrue(withHints.ok); XCTAssertEqual(1, withHints.hintsAccepted)

        _ = try node.originateLocHint(TestSupport.hint(2, gatewayId))
        http.respond { _ in ["accepted": 0, "duplicates": 0, "rejected": 0, "acks": [String](), "ack_cursor": 2] }
        let without = s.syncOnce()
        XCTAssertTrue(without.ok); XCTAssertEqual(1, without.hintsUploaded); XCTAssertNil(without.hintsAccepted)
    }

    func testClientParsingToleratesTheOptionalHintsObject() throws {
        let c = SyncClient(transport: http)
        let base = #""accepted":1,"duplicates":0,"rejected":0,"acks":[],"ack_cursor":2"#
        XCTAssertNil(try c.parse("{\(base)}").hints)
        XCTAssertEqual(HintCounts(accepted: 2, duplicates: 1, rejected: 3),
                       try c.parse(#"{\#(base),"hints":{"accepted":2,"duplicates":1,"rejected":3}}"#).hints)
        XCTAssertEqual(HintCounts(accepted: 4, duplicates: 0, rejected: 0), try c.parse(#"{\#(base),"hints":{"accepted":4}}"#).hints)
        // A malformed hints value is ignored, not a failure.
        XCTAssertNil(try c.parse(#"{\#(base),"hints":"nope"}"#).hints)
        XCTAssertNil(try c.parse(#"{\#(base),"hints":null}"#).hints)
        XCTAssertEqual(2, try c.parse(#"{\#(base),"hints":[1]}"#).ackCursor)
    }
}
