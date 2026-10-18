import XCTest
import RelayCore
@testable import RelayKit

final class StateFilesTests: TempDirTestCase {
    private func mine(_ id: String) -> MyNeed {
        MyNeed(msgIdHex: id, createdAtMs: 1_000, category: .water, urgency: 2, people: 4, item: "water", landmark: "bridge",
               summary: "need water", lang: .hi, needPayloadB64: "AAAA")
    }

    func testGatewayStateRoundTripAndCorruptFile() throws {
        let f = file("g.json")
        let a = GatewayState(file: f)
        a.markUploaded(["aa", "bb"]); a.setAckCursor(42)
        let b = GatewayState(file: f)
        XCTAssertTrue(b.isUploaded("aa")); XCTAssertTrue(b.isUploaded("bb")); XCTAssertFalse(b.isUploaded("cc"))
        XCTAssertEqual(42, b.ackCursor())

        try Data("{ definitely not json".utf8).write(to: f)
        let c = GatewayState(file: f)
        XCTAssertEqual(0, c.ackCursor()); XCTAssertEqual(0, c.uploadedCount())
    }

    func testGatewayStateReadsAndroidFile() throws {
        let f = file("g.json")
        try Data(#"{"cursor":17,"uploaded":["aa","bb"]}"#.utf8).write(to: f)
        let s = GatewayState(file: f)
        XCTAssertEqual(17, s.ackCursor()); XCTAssertEqual(2, s.uploadedCount())
    }

    func testGatewayStateBoundsRememberedIds() {
        let s = GatewayState(file: nil, maxRemembered: 3)
        s.markUploaded(["1", "2", "3", "4"])
        XCTAssertFalse(s.isUploaded("1")); XCTAssertTrue(s.isUploaded("4")); XCTAssertEqual(3, s.uploadedCount())
    }

    func testMyNeedsPersistWithAckProgressAndSurviveCorruption() throws {
        let f = file("n.json")
        let s = MyNeedsStore(file: f)
        s.add(mine("m1")); s.add(mine("m2"))
        XCTAssertEqual(["m2", "m1"], s.list().map { $0.msgIdHex })
        XCTAssertEqual(.queued, s.list().first { $0.msgIdHex == "m1" }?.stage)

        s.update("m1") { var n = $0; n.sentToMesh = true; return n }
        XCTAssertEqual(.sent, s.list().first { $0.msgIdHex == "m1" }?.stage)
        s.update("m1") { $0.withAck(.received, ticket: 142, text: "Received", eta: 0xFFFF) }
        XCTAssertEqual(.received, s.list().first { $0.msgIdHex == "m1" }?.stage)
        s.update("m1") { $0.withAck(.dispatched, ticket: 142, text: "Help dispatched", eta: 20) }

        let again = try XCTUnwrap(MyNeedsStore(file: f).list().first { $0.msgIdHex == "m1" })
        XCTAssertEqual(.dispatched, again.stage)
        XCTAssertEqual(142, again.ticketNo); XCTAssertEqual("Help dispatched", again.ackText); XCTAssertEqual("Received", again.receivedText)

        // A late RECEIVED ack must not downgrade a DISPATCHED need.
        let down = again.withAck(.received, ticket: 142, text: "Received", eta: 0xFFFF)
        XCTAssertEqual(.dispatched, down.stage); XCTAssertEqual("Help dispatched", down.ackText)

        try Data("garbage".utf8).write(to: f)
        XCTAssertTrue(MyNeedsStore(file: f).list().isEmpty)
    }

    func testRemoveDropsTheNeedAndPersists() {
        let f = file("n.json")
        let s = MyNeedsStore(file: f)
        s.add(mine("m1")); s.add(mine("m2"))
        XCTAssertTrue(s.remove("m1"))
        XCTAssertFalse(s.remove("m1"))
        XCTAssertEqual(["m2"], s.list().map { $0.msgIdHex })
        XCTAssertEqual(["m2"], MyNeedsStore(file: f).list().map { $0.msgIdHex })
    }

    func testAddingTheSameNeedTwiceKeepsOne() {
        let s = MyNeedsStore(file: file("n.json"))
        s.add(mine("m1")); s.add(mine("m1"))
        XCTAssertEqual(1, s.list().count)
        XCTAssertNil(s.update("nope") { $0 })
    }
}
