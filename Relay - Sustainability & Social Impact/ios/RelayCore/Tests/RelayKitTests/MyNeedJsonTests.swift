import XCTest
import RelayCore
@testable import RelayKit

final class MyNeedJsonTests: TempDirTestCase {
    /// A my_needs.json entry exactly as written before the redesign.
    private let legacy = """
        {"id":"aa11bb22","at":1700000000000,"cat":2,"urg":3,"ppl":6,"item":"water","lm":"school",
        "sum":"pump broken","lang":1,"need":"QUJD","sent":true,"ack":1,"ticket":42,"atext":"Help dispatched","eta":45,"rtext":"Received"}
        """

    func testLegacyFileLoadsWithDefaults() throws {
        let n = try XCTUnwrap(MyNeed.fromJson(Data(legacy.utf8)))
        XCTAssertEqual("aa11bb22", n.msgIdHex)
        XCTAssertEqual(.water, n.category)
        XCTAssertEqual(.hi, n.lang)
        XCTAssertTrue(n.sentToMesh)
        XCTAssertEqual(1, n.ackStatus)
        XCTAssertEqual(.dispatched, n.stage)
        XCTAssertEqual(0, n.vulnFlags)
        XCTAssertTrue(n.answers.isEmpty)
        XCTAssertEqual(1700000000000, n.savedAtMs) // defaults to the created time
        XCTAssertEqual(0, n.sentToMeshAtMs)
        XCTAssertEqual(0, n.uploadedAtMs)
        XCTAssertEqual(0, n.resolvedAtMs)
        XCTAssertFalse(n.cancelled)
        XCTAssertFalse(n.isClosed)
    }

    func testNewFieldsRoundTrip() throws {
        let n = MyNeed(
            msgIdHex: "cc", createdAtMs: 5, category: .rescue, urgency: 3, people: 2, item: "rescue", landmark: "temple",
            summary: "roof", lang: .or, needPayloadB64: "x", ackStatus: 3, vulnFlags: 18, answers: [3: 1, 4: 2],
            sentToMeshAtMs: 6, uploadedAtMs: 7, receivedAtMs: 8, dispatchedAtMs: 9, onTheWayAtMs: 10, resolvedAtMs: 11,
            etaAtMs: 12, cancelled: true, cancelledAtMs: 13)
        let back = try XCTUnwrap(MyNeed.fromJson(try n.toJson()))
        XCTAssertEqual(n, back)
        XCTAssertEqual([3: 1, 4: 2], back.answers)
    }

    /// The field names are the on-disk contract with Android; this is the full key set, in sorted order.
    func testJsonUsesTheAndroidFieldNames() throws {
        let n = MyNeed(msgIdHex: "cc", createdAtMs: 5, category: .rescue, urgency: 3, people: 2, item: "i", landmark: "l",
                       summary: "s", lang: .or, needPayloadB64: "x", answers: [3: 1])
        let obj = try XCTUnwrap(JSONSerialization.jsonObject(with: try n.toJson()) as? [String: Any])
        XCTAssertEqual(["answers", "at", "atext", "cancelled", "cat", "eta", "eta_at", "id", "item", "lang", "lm", "need", "ppl", "rtext",
                        "sent", "sum", "t_cancel", "t_disp", "t_done", "t_otw", "t_recv", "t_saved", "t_sent", "t_up", "ticket", "urg", "vuln", "ack"].sorted(),
                       obj.keys.sorted())
        XCTAssertEqual(1, obj["cat"] as? Int)
        XCTAssertEqual(2, obj["lang"] as? Int)
        XCTAssertEqual(false, obj["sent"] as? Bool)
        XCTAssertEqual(-1, obj["ack"] as? Int)
        XCTAssertEqual(["3": 1], obj["answers"] as? [String: Int])
        XCTAssertEqual("cc", obj["id"] as? String)
    }

    func testMissingRequiredFieldIsRejected() {
        XCTAssertNil(MyNeed.fromJson(Data(#"{"id":"a","cat":2,"urg":3,"ppl":6}"#.utf8)))
        XCTAssertNil(MyNeed.fromJson(Data("not json".utf8)))
        // Unknown category code falls back to OTHER, like Android.
        XCTAssertEqual(.other, MyNeed.fromJson(Data(#"{"id":"a","at":1,"cat":99,"urg":0,"ppl":1}"#.utf8))?.category)
    }

    func testAckStatusesOnTheWayAndResolvedAreStages() throws {
        let base = try XCTUnwrap(MyNeed.fromJson(Data(legacy.utf8)))
        let otw = base.withAck(.onTheWay, ticket: 42, text: "on the way", eta: 10, nowMs: 1_000)
        XCTAssertEqual(.onTheWay, otw.stage)
        XCTAssertEqual(1_000, otw.onTheWayAtMs)
        XCTAssertEqual(.onTheWay, NeedStatusLogic.derive(otw, isGateway: false, storedLocally: true, uploaded: true))
        let done = otw.withAck(.resolved, ticket: 42, text: "delivered", eta: 0xFFFF, nowMs: 2_000)
        XCTAssertEqual(.resolved, done.stage)
        XCTAssertEqual(.resolved, NeedStatusLogic.derive(done, isGateway: false, storedLocally: true, uploaded: true))
        // A late lower ACK never moves it back.
        let late = done.withAck(.dispatched, ticket: 42, text: "old", eta: 5, nowMs: 3_000)
        XCTAssertEqual(.resolved, late.stage)
        XCTAssertEqual("delivered", late.ackText)
    }

    func testStoreLoadsLegacyFileAndPersistsCancel() throws {
        let f = file("my_needs.json")
        try Data("[\(legacy)]".utf8).write(to: f)
        let s = MyNeedsStore(file: f)
        XCTAssertEqual(1, s.list().count)
        s.update("aa11bb22") { $0.cancel(nowMs: 99) }
        let again = try XCTUnwrap(MyNeedsStore(file: f).list().first)
        XCTAssertTrue(again.cancelled)
        XCTAssertEqual(99, again.cancelledAtMs)
        XCTAssertEqual(1, again.ackStatus) // everything else survives
    }

    func testStoreSkipsUnparseableEntriesButKeepsTheRest() throws {
        let f = file("my_needs.json")
        try Data("[\(legacy), {\"id\":\"broken\"}, 7]".utf8).write(to: f)
        XCTAssertEqual(["aa11bb22"], MyNeedsStore(file: f).list().map { $0.msgIdHex })
    }
}
