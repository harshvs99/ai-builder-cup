import XCTest
import RelayCore
@testable import RelayKit

final class RequestListsTests: XCTestCase {
    private func need(_ id: String, _ at: Int64, ack: Int = -1, cancelled: Bool = false, item: String = "", urgency: Int = 2, people: Int = 6) -> MyNeed {
        UI.need(id, at: at, ack: ack, cancelled: cancelled, item: item, urgency: urgency, people: people)
    }

    func testSplitsActiveAndClosedNewestFirst() {
        let a = need("a", 100)
        let b = need("b", 300, ack: 3) // resolved
        let c = need("c", 200, cancelled: true)
        let d = need("d", 400, ack: 1)
        let (active, closed) = RequestLists.split([a, b, c, d])
        XCTAssertEqual(["d", "a"], active.map { $0.msgIdHex })
        XCTAssertEqual(["b", "c"], closed.map { $0.msgIdHex })
    }

    func testRecentTakesTwoNewestOpenOrClosed() {
        let needs = [need("a", 1), need("b", 3, ack: 3), need("c", 2)]
        XCTAssertEqual(["b", "c"], RequestLists.recent(needs).map { $0.msgIdHex })
        XCTAssertTrue(RequestLists.recent([]).isEmpty)
        XCTAssertEqual(3, RequestLists.recent(needs, 5).count)
    }

    func testNewestFirstIsStableForEqualTimes() {
        let needs = [need("a", 5), need("b", 5), need("c", 9), need("d", 5)]
        XCTAssertEqual(["c", "a", "b", "d"], RequestLists.newestFirst(needs).map { $0.msgIdHex })
    }

    func testRowTitleAndTrailing() {
        let c = UI.strings(.en).common
        XCTAssertEqual("Drinking water · 6 people", requestTitle(need("a", 1, item: "Drinking water"), c))
        XCTAssertEqual("Water · 1 person", requestTitle(need("a", 1, people: 1), c))
        XCTAssertEqual("Urgent", requestTrailing(need("a", 1), c))
        XCTAssertEqual("Closed", requestTrailing(need("a", 1, ack: 3), c))
        XCTAssertEqual("Cancelled", requestTrailing(need("a", 1, cancelled: true), c))
    }

    func testLabelLocalizesWireItemOutsideEnglish() {
        let hi = UI.strings(.hi).common
        let en = UI.strings(.en).common
        // restatement of the category: just the localized category
        XCTAssertEqual("पानी", requestLabel(.water, "water", english: false, categoryName: hi.categoryName))
        XCTAssertEqual("पानी", requestLabel(.water, "Drinking water", english: false, categoryName: hi.categoryName))
        XCTAssertEqual("दवा", requestLabel(.medical, "medicine", english: false, categoryName: hi.categoryName))
        XCTAssertEqual("पानी", requestLabel(.water, "", english: false, categoryName: hi.categoryName))
        // a more specific item keeps the item
        XCTAssertEqual("दवा: insulin", requestLabel(.medical, "insulin", english: false, categoryName: hi.categoryName))
        // English unchanged
        XCTAssertEqual("Water", requestLabel(.water, "water", english: true, categoryName: en.categoryName))
        XCTAssertEqual("Insulin", requestLabel(.medical, "insulin", english: true, categoryName: en.categoryName))
        XCTAssertEqual("Water", requestLabel(.water, " ", english: true, categoryName: en.categoryName))
        XCTAssertEqual("पानी · 6 लोग", requestTitle(need("a", 1, item: "water"), hi))
    }

    func testHomeCategoriesLine() {
        XCTAssertEqual("Water · food · medicine · rescue · shelter", UI.strings(.en).home.categoriesLine)
    }

    func testRowModelHasThreeLinesAndAccessibilityLabel() {
        let now = UI.at(18, 14, 0)
        var n = need("7f3a0001", now - 180_000, ack: -1, item: "Drinking water")
        n.landmark = "the primary school"
        n.sentToMesh = true
        n.sentToMeshAtMs = now - 120_000
        let r = RequestRowModel.make(n, now: now, strings: UI.strings(.en))
        XCTAssertEqual("7f3a0001", r.id)
        XCTAssertEqual("Drinking water · 6 people", r.title)
        XCTAssertEqual("Passed to a neighbour's phone · 2 min ago", r.statusLine)
        XCTAssertEqual("Urgent", r.trailing)
        XCTAssertEqual("Urgent · R-7F3A · near the primary school", r.metaLine)
        XCTAssertTrue(r.isLoud)
        XCTAssertFalse(r.isClosed)
        XCTAssertEqual("Drinking water · 6 people. Passed to a neighbour's phone · 2 min ago. Urgent", r.accessibilityLabel)
    }

    func testMetaLineOmitsBlankLandmarkAndUsesTicketOnceKnown() {
        let en = UI.strings(.en)
        XCTAssertEqual("Urgent · R-7F3A", RequestRowModel.metaLine(UI.need("7f3a0001"), en))
        let ticketed = UI.need("7f3a0001", ack: 0, urgency: 3, landmark: "bus stand").withAck(.received, ticket: 9, text: "ok", eta: 0xFFFF, nowMs: 5)
        XCTAssertEqual("Life at risk · #9 · near bus stand", RequestRowModel.metaLine(ticketed, en))
        XCTAssertEqual("ज़रूरी · R-7F3A · school के पास", RequestRowModel.metaLine(UI.need("7f3a0001", landmark: "school"), UI.strings(.hi)))
    }

    func testClosedRowIsNotLoud() {
        let r = RequestRowModel.make(UI.need("a1b2", ack: 3, urgency: 3), now: 1_700_000_100_000, strings: UI.strings(.en))
        XCTAssertTrue(r.isClosed)
        XCTAssertFalse(r.isLoud)
        XCTAssertEqual("Closed", r.trailing)
        XCTAssertTrue(r.statusLine.hasPrefix("Delivered"))
        let c = RequestRowModel.make(UI.need("a1b2", cancelled: true), now: 1_700_000_100_000, strings: UI.strings(.en))
        XCTAssertTrue(c.isCancelled)
        XCTAssertEqual("Cancelled", c.trailing)
    }

    func testRequestsTabSectionsAndEmptyState() {
        let now = 1_700_000_100_000 as Int64
        let needs = [need("a", 100), need("b", 300, ack: 3), need("d", 400, ack: 1)]
        let m = RequestsTabModel.make(needs: needs, now: now, strings: UI.strings(.en))
        XCTAssertEqual("Your requests", m.title)
        XCTAssertNil(m.emptyMessage)
        XCTAssertEqual(["Active", "Closed"], m.sections.map { $0.title })
        XCTAssertEqual([["d", "a"], ["b"]], m.sections.map { $0.rows.map { $0.id } })
        let onlyClosed = RequestsTabModel.make(needs: [need("b", 3, ack: 3)], now: now, strings: UI.strings(.en))
        XCTAssertEqual(["Closed"], onlyClosed.sections.map { $0.title })
        let empty = RequestsTabModel.make(needs: [], now: now, strings: UI.strings(.en))
        XCTAssertTrue(empty.sections.isEmpty)
        XCTAssertEqual(UI.strings(.en).home.emptyRequests, empty.emptyMessage)
    }
}
