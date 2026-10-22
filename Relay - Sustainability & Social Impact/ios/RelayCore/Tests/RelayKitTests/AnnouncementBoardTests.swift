import XCTest
import RelayCore
@testable import RelayKit

/// Show/hide rules of the Home card (SPEC 5.7). Mirror of Kotlin `AnnouncementBoardTest`.
final class AnnouncementBoardTests: TempDirTestCase {
    private let now: Int64 = 1_790_000_000_000
    private let min: Int64 = 60_000
    private func id(_ n: Int) -> [UInt8] { [UInt8](repeating: UInt8(n), count: 8) }

    private func held(_ n: Int, _ lang: Lang, _ status: AnnouncementStatus = .active, district: Int = 0, text: String? = nil,
                       approvedAgoMin: Int64 = 5, receivedAgoMin: Int64 = 5) -> HeldFrame {
        HeldFrame(announcement: Announcement(announcementId: id(n), lang: lang, district: district, status: status,
                                             approvedAt: (now - approvedAgoMin * min) / 1000, expiresAt: 0, text: text ?? "t\(n) \(lang.tag)"),
                  receivedAtMs: now - receivedAgoMin * min)
    }

    private func show(_ frames: [HeldFrame], district: String? = "puri", lang: Lang = .hi, http: [HttpAnnouncement] = [],
                      dismissed: Set<String> = [], at: Int64? = nil) -> [AnnouncementCard] {
        AnnouncementBoard.visible(held: frames, http: http, district: district, uiLang: lang, nowMs: at ?? now, dismissed: dismissed)
    }

    func testAFrameInTheUiLanguageAndDistrictIsShown() {
        let cards = show([held(1, .hi), held(1, .en)])
        XCTAssertEqual(1, cards.count)
        XCTAssertEqual(.hi, cards[0].lang); XCTAssertEqual("t1 hi", cards[0].text); XCTAssertFalse(cards[0].fromHttp)
    }

    func testAnotherDistrictIsRelayedButNotShownAndAPhoneWithNoDistrictShowsEverything() {
        let delhi = held(1, .hi, district: 1)
        XCTAssertTrue(show([delhi], district: "puri").isEmpty)
        XCTAssertEqual(1, show([delhi], district: "delhi").count)
        XCTAssertEqual(1, show([delhi], district: nil).count)
        XCTAssertTrue(show([held(1, .hi, district: 77)], district: "puri").isEmpty) // a district this build does not know
    }

    func testEnglishIsShownOnlyAfterTenMinutesWithoutAFrameInTheUiLanguage() {
        let en = held(1, .en, receivedAgoMin: 9)
        XCTAssertTrue(show([en]).isEmpty)
        XCTAssertEqual(.en, show([held(1, .en, receivedAgoMin: 10)]).first?.lang)
        XCTAssertEqual(.en, show([en], at: now + 2 * min).first?.lang)
        // the Hindi frame arrives later: it replaces the English card
        XCTAssertEqual(.hi, show([held(1, .en, receivedAgoMin: 30), held(1, .hi, receivedAgoMin: 1)]).first?.lang)
        // an English phone never waits
        XCTAssertEqual(.en, show([held(1, .en, receivedAgoMin: 0)], lang: .en).first?.lang)
        // other languages are not a fallback
        XCTAssertTrue(show([held(1, .or, receivedAgoMin: 60)]).isEmpty)
    }

    func testTheWaitCountsFromTheFirstFrameOfThatAnnouncementNotTheLatest() {
        XCTAssertEqual(.en, show([held(1, .or, receivedAgoMin: 12), held(1, .en, receivedAgoMin: 1)]).first?.lang)
    }

    func testAWithdrawnFrameRemovesTheCardAndShowsNothing() {
        let frames = [held(1, .hi), held(1, .en, receivedAgoMin: 30), held(1, .hi, .withdrawn)]
        XCTAssertTrue(show(frames).isEmpty) // not even the English one
        XCTAssertTrue(show([held(1, .en, receivedAgoMin: 30), held(1, .en, .withdrawn)]).isEmpty)
        // a withdrawal that arrives before the approval hides it too
        XCTAssertTrue(show([held(1, .hi, .withdrawn), held(1, .hi)]).isEmpty)
        // another announcement is not affected
        XCTAssertEqual([Hex.encode(id(2))], show(frames + [held(2, .hi)]).map(\.announcementId))
    }

    func testAWithdrawnEnglishFallbackAlsoDisappearsWhenThePhoneHasNoUiLanguageFrame() {
        XCTAssertTrue(show([held(1, .en, receivedAgoMin: 30), held(1, .en, .withdrawn, receivedAgoMin: 1)]).isEmpty)
    }

    func testNewestFirstAtMostThree() {
        let frames = (1...5).map { held($0, .hi, approvedAgoMin: Int64(10 - $0)) }
        XCTAssertEqual(["t5 hi", "t4 hi", "t3 hi"], show(frames).map(\.text))
    }

    func testADismissedAnnouncementStaysGoneInEveryLanguage() {
        let hex = held(1, .hi).announcement.idHex
        XCTAssertTrue(show([held(1, .hi), held(1, .en)], dismissed: [hex]).isEmpty)
        XCTAssertEqual(1, show([held(1, .hi), held(2, .hi)], dismissed: [hex]).count)
    }

    func testStaleAndEndedAnnouncementsAreNotShown() {
        XCTAssertTrue(show([held(1, .hi, approvedAgoMin: 73 * 60)]).isEmpty)
        let ended = HeldFrame(announcement: Announcement(announcementId: id(1), lang: .hi, district: 0, status: .active,
                                                         approvedAt: (now - 5 * min) / 1000, expiresAt: (now - min) / 1000, text: "x"), receivedAtMs: now)
        XCTAssertTrue(show([ended]).isEmpty)
    }

    // ------------------------------------------------------------ http
    private func httpA(_ n: Int, _ texts: [Lang: String], district: String = "puri", fetchedAgoMin: Int64 = 0) -> HttpAnnouncement {
        HttpAnnouncement(announcementId: held(n, .en).announcement.idHex, district: district, approvedAt: (now - 5 * min) / 1000,
                         texts: texts, fetchedAtMs: now - fetchedAgoMin * min)
    }

    func testTheFullTextFromTheBackendBeatsTheMeshShortForm() {
        let card = show([held(1, .hi, text: "short…")], http: [httpA(1, [.hi: "the whole message", .en: "whole en"])]).first
        XCTAssertEqual("the whole message", card?.text); XCTAssertEqual(true, card?.fromHttp)
    }

    func testAPhoneThatOnlyHasTheBackendListShowsItWithTheSameLanguageRules() {
        XCTAssertEqual("hi text", show([], http: [httpA(1, [.hi: "hi text", .en: "en text"])]).first?.text)
        XCTAssertTrue(show([], http: [httpA(2, [.en: "en text"], fetchedAgoMin: 3)]).isEmpty)
        XCTAssertEqual("en text", show([], http: [httpA(2, [.en: "en text"], fetchedAgoMin: 11)]).first?.text)
        XCTAssertTrue(show([], http: [httpA(3, [.hi: "x"], district: "delhi")]).isEmpty)
    }

    func testAMeshWithdrawalHidesTheBackendCopyToo() {
        XCTAssertTrue(show([held(1, .hi, .withdrawn)], http: [httpA(1, [.hi: "x"])]).isEmpty)
    }

    func testOneNotificationPerAnnouncementOnFirstReceipt() {
        let cards = show([held(1, .hi), held(2, .hi)])
        XCTAssertEqual(2, AnnouncementBoard.toNotify(cards, notified: []).count)
        XCTAssertEqual([cards[1].announcementId], AnnouncementBoard.toNotify(cards, notified: [cards[0].announcementId]).map(\.announcementId))
    }

    func testActiveListParsingKeepsFullTextsAndGivesOldEntriesAnId() throws {
        let json = """
        {"announcements":[
          {"id":7,"announcement_id":"0101010101010101","district":"puri","approved_at":1790000000,"texts":{"en":"Hello","hi":"नमस्ते","xx":"?"},"future":1},
          {"id":8,"district":"puri","approved_at":1790000001,"texts":{"en":"Old row"}},
          {"id":9,"announcement_id":"02","district":"puri","approved_at":1,"texts":{"en":"  "}}]}
        """
        let list = try XCTUnwrap(AnnouncementJson.parseActive(json, fetchedAtMs: 5))
        XCTAssertEqual(2, list.count)
        XCTAssertEqual("0101010101010101", list[0].announcementId); XCTAssertEqual([Lang.en: "Hello", Lang.hi: "नमस्ते"], list[0].texts)
        XCTAssertEqual("http-8", list[1].announcementId)
        XCTAssertNil(AnnouncementJson.parseActive("garbage", fetchedAtMs: 0)); XCTAssertNil(AnnouncementJson.parseActive(nil, fetchedAtMs: 0)); XCTAssertNil(AnnouncementJson.parseActive("{}", fetchedAtMs: 0))
    }

    func testHeldFramesAreReadFromAPacketStoreAndTheOtherTypesAreIgnored() throws {
        let store = InMemoryPacketStore()
        let a = Announcement(announcementId: id(1), lang: .hi, district: 0, status: .active, approvedAt: now / 1000, expiresAt: 0, text: "x")
        let raw = try FrameCodec.encode(Frame(type: FrameTypes.announcement, ttl: 7, timestampMs: now, senderId: id(9), payload: try AnnouncementCodec.encode(a)))
        store.put(StoredPacket(key: a.keyHex, type: FrameTypes.announcement, raw: raw, expiryBasisMs: now))
        store.put(StoredPacket(key: "junk", type: FrameTypes.announcement, raw: [1, 2], expiryBasisMs: 0))
        store.put(StoredPacket(key: "n", type: FrameTypes.need, raw: [1], expiryBasisMs: 0))
        let frames = AnnouncementBoard.heldFrom(store)
        XCTAssertEqual(1, frames.count); XCTAssertEqual("x", frames[0].announcement.text); XCTAssertEqual(now, frames[0].receivedAtMs)
    }

    // ------------------------------------------------------------ store
    func testTheStoreKeepsTheBackendListDismissalsAndNotificationsAcrossRestarts() throws {
        let dir = file("ann"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let s = AnnouncementStore(dir: dir)
        XCTAssertTrue(s.http().isEmpty); XCTAssertEqual(0, s.lastHttpFetchMs())
        try s.saveHttp(#"{"announcements":[{"id":1,"announcement_id":"aa","district":"puri","approved_at":1790000000,"texts":{"hi":"x"}}]}"#, fetchedAtMs: 1234)
        s.dismiss("aa"); s.markNotified("aa"); s.markNotified("bb")
        let again = AnnouncementStore(dir: dir)
        XCTAssertEqual(["aa"], again.http().map(\.announcementId)); XCTAssertEqual(1234, again.http().first?.fetchedAtMs)
        XCTAssertEqual(1234, again.lastHttpFetchMs())
        XCTAssertEqual(["aa"], again.dismissed()); XCTAssertEqual(["aa", "bb"], again.notified())
    }

    func testADamagedStoreReadsAsEmptyAndTheRememberedIdsAreCapped() throws {
        let dir = file("ann2"); try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try "{ nope".write(to: dir.appendingPathComponent("announcements_http.json"), atomically: true, encoding: .utf8)
        try "garbage".write(to: dir.appendingPathComponent("announcements_state.json"), atomically: true, encoding: .utf8)
        let s = AnnouncementStore(dir: dir)
        XCTAssertTrue(s.http().isEmpty); XCTAssertTrue(s.dismissed().isEmpty)
        for i in 0..<250 { s.dismiss("id\(i)") }
        XCTAssertEqual(200, s.dismissed().count); XCTAssertTrue(s.dismissed().contains("id249")); XCTAssertFalse(s.dismissed().contains("id0"))
    }
}
