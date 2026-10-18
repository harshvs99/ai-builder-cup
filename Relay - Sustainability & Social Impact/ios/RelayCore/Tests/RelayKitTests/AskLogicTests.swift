import XCTest
import RelayCore
@testable import RelayKit

final class AskLogicTests: XCTestCase {
    private let en = UI.strings(.en).common
    private let hi = UI.strings(.hi).common

    private func draftFrom(_ text: String) -> AskDraft { AskLogic.applyIntake(AskDraft(), RuleIntake.shared.extract(text, langHint: .en)) }

    func testDraftFromIntake() {
        let d = draftFrom("we are 6 people, children and elderly, no drinking water, near the school")
        XCTAssertEqual(.water, d.category)
        XCTAssertEqual(6, d.people)
        XCTAssertTrue(d.vulnFlags.contains(.child))
        XCTAssertTrue(d.vulnFlags.contains(.elderly))
        XCTAssertFalse(d.landmark.trimmed.isEmpty)
    }

    func testNothingUnderstoodIsNilCategory() {
        let d = draftFrom("hello")
        XCTAssertNil(d.category)
        XCTAssertFalse(AskLogic.hasContent(d, ""))
        XCTAssertTrue(AskLogic.hasContent(d, "hello"))
        XCTAssertTrue(AskLogic.hasContent(AskLogic.setCategory(d, .other), ""))
        XCTAssertFalse(AskLogic.hasContent(d, "   "))
    }

    func testUserEditsBeatLaterExtraction() {
        var d = draftFrom("need water near the school")
        d = AskLogic.setCategory(d, .medical)
        d = AskLogic.setLandmark(d, "temple")
        d = AskLogic.setPeople(d, 4)
        d = AskLogic.setUrgency(d, 3)
        let later = AskLogic.applyIntake(d, RuleIntake.shared.extract("we need food, 9 people, near the market", langHint: .en))
        XCTAssertEqual(.medical, later.category)
        XCTAssertEqual("temple", later.landmark)
        XCTAssertEqual(4, later.people)
        XCTAssertEqual(3, later.urgency)
    }

    func testClearingACategoryChoiceHandsItBackToExtraction() {
        var d = AskLogic.setCategory(draftFrom("need water"), .food)
        XCTAssertEqual(.food, d.category)
        d = AskLogic.applyIntake(AskLogic.setCategory(d, nil), RuleIntake.shared.extract("need water", langHint: .en))
        XCTAssertEqual(.water, d.category)
        XCTAssertFalse(d.edited.contains(.category))
    }

    func testByteLimitsAreEnforcedOnCodePointBoundaries() {
        XCTAssertEqual(16, AskLogic.byteLength(AskLogic.setItem(AskDraft(), String(repeating: "x", count: 40)).item))
        XCTAssertEqual(24, AskLogic.byteLength(AskLogic.setLandmark(AskDraft(), String(repeating: "y", count: 40)).landmark))
        XCTAssertEqual(40, AskLogic.byteLength(AskLogic.setSummary(AskDraft(), String(repeating: "z", count: 60)).summary))
        // Devanagari is 3 bytes per character: 13 characters = 39 bytes, the 14th would make 42.
        let hindi = AskLogic.setSummary(AskDraft(), String(repeating: "क", count: 20)).summary
        XCTAssertEqual(13, hindi.unicodeScalars.count)
        XCTAssertEqual(39, AskLogic.byteLength(hindi))
        // Extraction results are clamped too.
        let long = NeedDraft(category: .water, urgency: 1, people: 1, vulnFlags: [], item: String(repeating: "i", count: 30),
                             landmark: String(repeating: "l", count: 30), summary: String(repeating: "s", count: 80), lang: .en)
        let d = AskLogic.applyIntake(AskDraft(), IntakeDraft(need: long, questionIds: []))
        XCTAssertEqual(16, AskLogic.byteLength(d.item))
        XCTAssertEqual(24, AskLogic.byteLength(d.landmark))
        XCTAssertEqual(40, AskLogic.byteLength(d.summary))
    }

    func testCharsLeftUsesTheWidthOfWhatWasTyped() {
        XCTAssertEqual(40, AskLogic.charsLeft("", 40))
        XCTAssertEqual(35, AskLogic.charsLeft("hello", 40))
        XCTAssertEqual(0, AskLogic.charsLeft(String(repeating: "a", count: 40), 40))
        XCTAssertEqual(1, AskLogic.charsLeft(String(repeating: "क", count: 12), 40)) // 36 bytes used, 4 left, 3 per character
    }

    func testUrgencyIsClampedAndSetByHand() {
        XCTAssertEqual(3, AskLogic.setUrgency(AskDraft(), 9).urgency)
        XCTAssertEqual(0, AskLogic.setUrgency(AskDraft(), -2).urgency)
        let d = draftFrom("someone is trapped, please save us, life in danger")
        XCTAssertGreaterThanOrEqual(d.urgency, 2)
        // The four words, in order, in every language.
        for lang in UI.appLangs {
            let words = (0...3).map { UI.strings(lang).common.urgencyWord($0) }
            XCTAssertEqual(4, Set(words).count, "\(lang)")
        }
        XCTAssertEqual("Life at risk", en.urgencyWord(3))
    }

    func testPeopleLine() {
        XCTAssertEqual("6 · children, elderly", AskLogic.peopleLine(en, 6, [.elderly, .child]))
        XCTAssertEqual("1 · trapped", AskLogic.peopleLine(en, 1, [.trapped]))
        XCTAssertEqual("3", AskLogic.peopleLine(en, 3, []))
        XCTAssertEqual("2 · बच्चे, बुज़ुर्ग", AskLogic.peopleLine(hi, 2, [.child, .elderly]))
    }

    func testNeedWordsPrefersASpecificItemElseTheCategory() {
        XCTAssertEqual("Water", AskLogic.needWords(en, AskDraft(category: .water, item: "water")))
        XCTAssertEqual("पानी", AskLogic.needWords(hi, AskDraft(category: .water, item: "water")))
        XCTAssertEqual("insulin", AskLogic.needWords(en, AskDraft(category: .medical, item: "insulin")))
        XCTAssertEqual("", AskLogic.needWords(en, AskDraft()))
    }

    func testUnderstoodChipsFollowTheDraft() {
        let near = UI.strings(.en).ask.nearLandmark
        XCTAssertTrue(AskLogic.understood(en, near: near, AskDraft()).isEmpty)
        let chips = AskLogic.understood(
            en, near: near, AskDraft(category: .water, people: 6, landmark: "primary school", summary: "Pump broken since yesterday")
        )
        XCTAssertEqual(["Water", "6 people", "Near primary school", "Pump broken since yesterday"], chips)
    }

    func testQuestionsFollowTheCategoryAndStaleAnswersDrop() {
        var d = AskLogic.setCategory(AskDraft(), .rescue)
        XCTAssertEqual([3, 4], d.questionIds)
        d = AskLogic.setAnswer(d, 3, 1)
        d = AskLogic.setAnswer(d, 99, 1) // not asked: ignored
        d = AskLogic.setAnswer(d, 4, 7) // not a value of that question: ignored
        XCTAssertEqual([3: 1], d.answers)
        d = AskLogic.setCategory(d, .medical)
        XCTAssertTrue(d.answers.isEmpty)
        // Choosing the same answer again clears it.
        d = AskLogic.setAnswer(AskLogic.setAnswer(d, 1, 2), 1, 2)
        XCTAssertTrue(d.answers.isEmpty)
    }

    func testFlagsChangeTheQuestionsAndPruneTheirAnswers() {
        // "children or elderly with you" is skipped when the flags already say so.
        var d = AskLogic.setCategory(AskDraft(), .water)
        XCTAssertEqual([5, 6], d.questionIds)
        d = AskLogic.setAnswer(d, 5, 1)
        d = AskLogic.toggleFlag(d, .child)
        XCTAssertEqual([6], d.questionIds)
        XCTAssertNil(d.answers[5])
        XCTAssertTrue(d.edited.contains(.vuln))
        d = AskLogic.toggleFlag(d, .child)
        XCTAssertEqual([5, 6], d.questionIds)
    }

    func testAnswersMapToV2Bytes() {
        var d = AskLogic.setCategory(AskDraft(), .rescue)
        XCTAssertTrue(AskLogic.wireAnswers(d).isEmpty)
        d = AskLogic.setAnswer(AskLogic.setAnswer(d, 4, 1), 3, 2)
        // Asking order, not tap order.
        XCTAssertEqual([NeedAnswer(3, 2), NeedAnswer(4, 1)], AskLogic.wireAnswers(d))
        XCTAssertEqual([(3 << 3) | 2, (4 << 3) | 1], AskLogic.wireAnswers(d).map { $0.wireByte })
        XCTAssertLessThanOrEqual(AskLogic.wireAnswers(d).count, QuestionBank.maxAnswers)
    }

    func testWireItemFallsBackToTheCategoryWord() {
        XCTAssertEqual("water", AskLogic.wireItem(AskDraft(category: .water)))
        XCTAssertEqual("medicine", AskLogic.wireItem(AskDraft(category: .medical, item: "  ")))
        XCTAssertEqual("insulin", AskLogic.wireItem(AskDraft(category: .medical, item: " insulin ")))
        XCTAssertEqual("help", AskLogic.wireItem(AskDraft()))
        XCTAssertEqual("missing person", AskLogic.wireItem(AskDraft(category: .missingPerson)))
    }

    func testPeopleIsClamped() {
        XCTAssertEqual(255, AskLogic.setPeople(AskDraft(), 9_999).people)
        XCTAssertEqual(1, AskLogic.setPeople(AskDraft(), 0).people)
    }

    func testMoreCategoriesRowOpensForTheirChoice() {
        XCTAssertFalse(AskLogic.showsMoreCategories(AskDraft(category: .water), opened: false))
        XCTAssertTrue(AskLogic.showsMoreCategories(AskDraft(category: .water), opened: true))
        XCTAssertTrue(AskLogic.showsMoreCategories(AskDraft(category: .missingPerson), opened: false))
        XCTAssertTrue(AskLogic.showsMoreCategories(AskDraft(category: .other), opened: false))
        XCTAssertFalse(AskLogic.showsMoreCategories(AskDraft(), opened: false))
    }

    func testLocationText() {
        let ask = UI.strings(.en).ask
        XCTAssertEqual("GPS · about 40 m accuracy", AskLogic.locationText(ask, .ready, accuracyM: 40.2))
        XCTAssertEqual("GPS · about 1 m accuracy", AskLogic.locationText(ask, .ready, accuracyM: 0.2))
        XCTAssertEqual("GPS", AskLogic.locationText(ask, .ready, accuracyM: nil))
        XCTAssertEqual("GPS", AskLogic.locationText(ask, .ready, accuracyM: -1))
        XCTAssertEqual("GPS", AskLogic.locationText(ask, .ready, accuracyM: .nan))
        XCTAssertEqual("Getting location…", AskLogic.locationText(ask, .acquiring, accuracyM: nil))
        XCTAssertEqual(ask.locationOff, AskLogic.locationText(ask, .off, accuracyM: nil))
        XCTAssertEqual(ask.locationOff, AskLogic.locationText(ask, .noPermission, accuracyM: nil))
        XCTAssertTrue(AskLogic.offersTurnOnLocation(.off))
        XCTAssertTrue(AskLogic.offersTurnOnLocation(.noPermission))
        XCTAssertFalse(AskLogic.offersTurnOnLocation(.acquiring))
        XCTAssertFalse(AskLogic.offersTurnOnLocation(.ready))
    }
}
