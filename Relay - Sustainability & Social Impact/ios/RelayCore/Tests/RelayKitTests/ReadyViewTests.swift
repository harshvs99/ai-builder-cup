import XCTest
import RelayCore
@testable import RelayKit

/// Banner and screen rules. Mirror of Kotlin `ReadyViewTest`.
final class ReadyViewTests: XCTestCase {
    private func w(_ sev: String = "warning", landfall: Int64? = 1000 + 5 * 3600 + 1, expires: Int64? = nil) -> ReadyWarning {
        ReadyWarning(id: 4, source: "IMD", severity: sev, headline: "Cyclone", issuedAt: 900, expectedLandfallAt: landfall, expiresAt: expires)
    }

    private func pack(_ warning: ReadyWarning?, source: String = "published", shelters: Bool = true, checklistLang: String = "en") -> ReadyPack {
        let list = shelters
            ? ReadyJson.parsePack("{\"district\":\"puri\",\"version\":\"v\",\"shelters\":[{\"id\":\"a\",\"name\":\"A\",\"lat\":1.0,\"lon\":2.0}]}")!.shelters
            : []
        return ReadyPack(district: "puri", lang: "hi", generatedAt: 1, version: "v1", warning: warning, shelters: list,
                         sheltersSource: source, sheltersPublishedAt: nil, checklist: [], checklistLang: checklistLang)
    }

    func testSeverityMapsToTheThreeBannerColoursAndUnknownIsAWarning() {
        XCTAssertEqual(.watch, ReadyView.level(w("watch")))
        XCTAssertEqual(.warning, ReadyView.level(w("warning")))
        XCTAssertEqual(.alert, ReadyView.level(w("ALERT")))
        XCTAssertEqual(.warning, ReadyView.level(w("something-new")))
    }

    func testAnExpiredOrMissingWarningRaisesNoBanner() {
        XCTAssertNil(ReadyView.activeWarning(nil, nowSec: 1000))
        XCTAssertNil(ReadyView.activeWarning(pack(nil), nowSec: 1000))
        XCTAssertEqual(4, ReadyView.activeWarning(pack(w()), nowSec: 1000)?.id)
        XCTAssertEqual(4, ReadyView.activeWarning(pack(w(expires: 2000)), nowSec: 1999)?.id)
        XCTAssertNil(ReadyView.activeWarning(pack(w(expires: 2000)), nowSec: 2000))
    }

    func testLandfallIsWholeHoursRoundedUpAndHiddenWhenUnknownOrPast() {
        XCTAssertEqual(6, ReadyView.landfallHours(w(), nowSec: 1000))
        XCTAssertEqual(1, ReadyView.landfallHours(w(landfall: 1000 + 60), nowSec: 1000))
        XCTAssertEqual(5, ReadyView.landfallHours(w(landfall: 1000 + 5 * 3600), nowSec: 1000))
        XCTAssertNil(ReadyView.landfallHours(w(landfall: nil), nowSec: 1000))
        XCTAssertNil(ReadyView.landfallHours(w(landfall: 1000), nowSec: 1000))
        XCTAssertNil(ReadyView.landfallHours(w(landfall: 500), nowSec: 1000))
    }

    func testShownInAnotherLanguageWhenTheStepsAreNotInTheUiLanguage() {
        let plan = ReadyJson.parsePlan("{\"plan_id\":\"p\",\"lang\":\"hi\",\"steps_lang\":\"en\",\"steps\":[\"a\"]}")!
        XCTAssertTrue(ReadyView.stepsInOtherLanguage(plan, uiLang: "hi"))
        XCTAssertFalse(ReadyView.stepsInOtherLanguage(plan, uiLang: "en"))
        XCTAssertTrue(ReadyView.checklistInOtherLanguage(pack(w(), checklistLang: "en"), uiLang: "hi"))
        XCTAssertFalse(ReadyView.checklistInOtherLanguage(pack(w(), checklistLang: "hi"), uiLang: "hi"))
    }

    func testTheFooterSaysVerifiedForPublishedListsAndDemoOtherwise() {
        XCTAssertEqual(.verified, ReadyView.shelterFooter(pack(w(), source: "published")))
        XCTAssertEqual(.demo, ReadyView.shelterFooter(pack(w(), source: "demo")))
        XCTAssertEqual(.none, ReadyView.shelterFooter(pack(w(), source: "published", shelters: false)))
    }
}

/// Ready strings and the household in settings. Mirror of Kotlin `ReadyUiLogicTest` (the parts that are not Android-only).
final class ReadyStringsTests: XCTestCase {
    func testReadyStringsAreEnglishAndHindiAndHiddenLanguagesShowEnglish() {
        let en = ReadyStrings(.en), hi = ReadyStrings(.hi)
        XCTAssertEqual("Get ready", en.getReady); XCTAssertNotEqual(en.getReady, hi.getReady)
        for l in [Lang.or, .tl, .war] { XCTAssertEqual(en.getReady, ReadyStrings(l).getReady) }
        XCTAssertEqual("Landfall in about 6 h", en.landfallIn(6))
        XCTAssertEqual("Cyclone warning for Puri", en.notificationTitle(en.districtName("puri")))
        XCTAssertEqual("Your plan is ready.", en.notificationText)
        XCTAssertEqual("Delhi", en.districtName("delhi")); XCTAssertEqual("Foo", en.districtName("foo"))
    }

    func testTheBannerIsReadAsOneSentence() {
        let r = ReadyStrings(.en)
        XCTAssertEqual("Warning. Cyclone Fani. Landfall in about 6 h. Get ready", r.bannerDescription(.warning, headline: "Cyclone Fani", hours: 6))
        XCTAssertEqual("Alert. Evacuate. Get ready", r.bannerDescription(.alert, headline: "Evacuate", hours: nil))
    }

    func testHouseholdIsPartOfSettingsAndPersists() {
        XCTAssertEqual(1, Settings().household.people)
        XCTAssertFalse(Settings().householdSet)
        let store = InMemoryKeyValueStore()
        let a = AppSettings(store: store)
        a.update { var s = $0; s.household = Household(people: 4, elderly: true, medicalNeeds: "insulin"); s.householdSet = true; return s }
        let b = AppSettings(store: store).current
        XCTAssertEqual(Household(people: 4, elderly: true, medicalNeeds: "insulin"), b.household)
        XCTAssertTrue(b.householdSet)
    }
}
