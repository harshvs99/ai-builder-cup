import XCTest
import RelayCore
@testable import RelayKit

final class RequestDetailLogicTests: XCTestCase {
    private let t0: Int64 = 1_700_000_000_000

    private func need(_ id: String = "7f3a1c20") -> MyNeed {
        MyNeed(msgIdHex: id, createdAtMs: t0, category: .water, urgency: 2, people: 6, item: "Drinking water", landmark: "school",
               summary: "pump", lang: .en, needPayloadB64: "")
    }
    private var received: MyNeed { need().withAck(.received, ticket: 42, text: "We have it.", eta: 0xFFFF, nowMs: t0 + 1_000) }
    private var dispatched: MyNeed { received.withAck(.dispatched, ticket: 42, text: "Help dispatched, ETA 45 min", eta: 45, nowMs: t0 + 2_000) }

    func testReferenceUsesMsgIdUntilTicketKnown() {
        XCTAssertEqual("R-7F3A", RequestLogic.reference(need()))
        XCTAssertEqual("#42", RequestLogic.reference(received))
        XCTAssertEqual("R-AB", RequestLogic.reference(need("ab")))
    }

    func testTitleUsesItemElseCategory() {
        let c = UI.strings(.en).common
        XCTAssertEqual("Drinking water · 6 people", RequestLogic.title(need(), c))
        var n = need(); n.item = ""; n.people = 1
        XCTAssertEqual("Water · 1 person", RequestLogic.title(n, c))
    }

    func testShareTextEnglish() {
        let text = RequestLogic.shareText(dispatched, UI.strings(.en), area: "Ward 4", now: t0 + 3_000)
        let lines = text.components(separatedBy: "\n")
        XCTAssertEqual("Relay request #42", lines[0])
        XCTAssertEqual("Drinking water · 6 people (Urgent)", lines[1])
        XCTAssertTrue(lines[2].hasPrefix("#42 · sent "))
        XCTAssertTrue(lines[2].hasSuffix(" · Ward 4"))
        XCTAssertTrue(lines.contains("Near school"))
        XCTAssertTrue(lines.contains { $0.hasPrefix("Status: Help is coming · Expected by ") })
        XCTAssertEqual("Control room: Help dispatched, ETA 45 min", lines.last)
    }

    func testShareTextFollowsLanguageAndOmitsEmptyParts() {
        var n = need(); n.landmark = ""
        let lines = RequestLogic.shareText(n, UI.strings(.hi), area: "", now: t0).components(separatedBy: "\n")
        XCTAssertTrue(lines[0].hasPrefix("Relay अनुरोध R-7F3A"))
        XCTAssertFalse(lines[2].hasSuffix("·"))
        XCTAssertFalse(lines.contains { $0.contains("के पास") })
        XCTAssertFalse(lines.contains { $0.hasPrefix("कंट्रोल रूम:") })
    }

    func testShareTextCancelled() {
        let text = RequestLogic.shareText(need().cancel(nowMs: t0 + 5), UI.strings(.en), area: "", now: t0 + 10)
        XCTAssertTrue(text.components(separatedBy: "\n").contains("Status: Cancelled"))
    }

    func testAutoReadOncePerNewMessage() {
        XCTAssertFalse(RequestLogic.shouldAutoRead(readAloud: true, need(), alreadyRead: []), "no message yet")
        XCTAssertTrue(RequestLogic.shouldAutoRead(readAloud: true, received, alreadyRead: []))
        XCTAssertFalse(RequestLogic.shouldAutoRead(readAloud: false, received, alreadyRead: []), "setting off")
        let read: Set<String> = [RequestLogic.messageKey(received)]
        XCTAssertFalse(RequestLogic.shouldAutoRead(readAloud: true, received, alreadyRead: read), "already read")
        XCTAssertTrue(RequestLogic.shouldAutoRead(readAloud: true, dispatched, alreadyRead: read), "newer ack is a new message")
        var blank = received; blank.ackText = ""
        XCTAssertFalse(RequestLogic.shouldAutoRead(readAloud: true, blank, alreadyRead: []), "blank text")
    }

    func testMessageKeyDiffersPerRequestAndIsStable() {
        var other = received; other.msgIdHex = "0000ffff"
        XCTAssertNotEqual(RequestLogic.messageKey(received), RequestLogic.messageKey(other))
        // JVM String.hashCode: "" = 0, "a" = 97, "Hello" = 69609650. The key must not change between runs or from Android.
        XCTAssertEqual(0, RequestLogic.javaHashCode(""))
        XCTAssertEqual(97, RequestLogic.javaHashCode("a"))
        XCTAssertEqual(69_609_650, RequestLogic.javaHashCode("Hello"))
        XCTAssertEqual("7f3a1c20:0:\(RequestLogic.javaHashCode("We have it."))", RequestLogic.messageKey(received))
    }

    func testCancelledTimelineEndsWithCancelledRow() {
        let rows = RequestTimeline.rows(for: received.cancel(nowMs: t0 + 5_000), now: t0 + 6_000, strings: UI.strings(.en))
        XCTAssertEqual("Cancelled", rows.last?.title)
        XCTAssertEqual(rows.count, rows.filter { $0.state == .done }.count)
        XCTAssertEqual("Seen by the control room", rows[rows.count - 2].title)
    }

    func testLiveTimelineShowsLiveTagAndEta() {
        let n = dispatched.withAck(.onTheWay, ticket: 42, text: "On the way", eta: 30, nowMs: t0 + 3_000)
        let last = RequestTimeline.rows(for: n, now: t0 + 4_000, strings: UI.strings(.en)).last!
        XCTAssertEqual("Team on the way", last.title)
        XCTAssertEqual("LIVE", last.tag)
        XCTAssertEqual(.current, last.state)
        XCTAssertTrue(last.subtitle!.contains("Expected by"))
    }

    func testExpectedByOnlyOnCurrentStep() {
        let n = dispatched.withAck(.onTheWay, ticket: 42, text: "On the way", eta: 30, nowMs: t0 + 3_000)
        let rows = RequestTimeline.rows(for: n, now: t0 + 4_000, strings: UI.strings(.en))
        XCTAssertEqual(1, rows.filter { $0.subtitle?.contains("Expected by") == true }.count)
        XCTAssertTrue(rows.last!.subtitle!.contains("Expected by"))
    }

    func testHearItInNamesTheMessageLanguage() {
        var n = received; n.lang = .hi
        XCTAssertEqual("Hear it in Hindi", RequestLogic.hearItLabel(n, UI.strings(.en)))
        XCTAssertEqual("हिन्दी में सुनें", RequestLogic.hearItLabel(n, UI.strings(.hi)))
        XCTAssertEqual("Hindi", RequestLogic.hearLanguageName(.hi, uiLang: .en))
        XCTAssertEqual("हिन्दी", RequestLogic.hearLanguageName(.hi, uiLang: .tl))
        XCTAssertEqual("Hear it in English", RequestLogic.hearItLabel(received, UI.strings(.en)))
    }

    func testDetailModelOpenRequestWithMessage() {
        let m = RequestDetailModel.make(dispatched, now: t0 + 3_000, area: "Ward 4", strings: UI.strings(.en))
        XCTAssertEqual("Request", m.screenTitle)
        XCTAssertEqual("Drinking water · 6 people", m.title)
        XCTAssertEqual("Urgent", m.badge)
        XCTAssertFalse(m.isClosed)
        XCTAssertTrue(m.showsActions)
        XCTAssertTrue(m.referenceLine.hasPrefix("#42 · sent "))
        XCTAssertEqual("Help is coming", m.timeline.last?.title)
        XCTAssertEqual("From the control room", m.message?.heading)
        XCTAssertEqual("Help dispatched, ETA 45 min", m.message?.text)
        XCTAssertEqual("Hear it in English", m.message?.hearLabel)
    }

    func testDetailModelClosedHasNoActionsAndNoMessageWhenSilent() {
        let m = RequestDetailModel.make(need().cancel(nowMs: t0 + 5), now: t0 + 10, area: "", strings: UI.strings(.en))
        XCTAssertTrue(m.isClosed)
        XCTAssertFalse(m.showsActions)
        XCTAssertEqual("Cancelled", m.badge)
        XCTAssertNil(m.message)
        let done = received.withAck(.resolved, ticket: 42, text: "Delivered. Stay safe.", eta: 0xFFFF, nowMs: t0 + 9_000)
        XCTAssertEqual("Closed", RequestDetailModel.make(done, now: t0 + 10_000, area: "", strings: UI.strings(.en)).badge)
    }
}
