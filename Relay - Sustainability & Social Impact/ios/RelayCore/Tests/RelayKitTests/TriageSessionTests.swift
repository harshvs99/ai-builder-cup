import XCTest
import RelayCore
@testable import RelayKit

/// Port of the Android app-module `TriageTest.kt` plus the view-model behaviour (`TriageSession`).
final class TriageSessionTests: XCTestCase {
    private let now: Int64 = 1_800_000_000

    // ------------------------------------------------------------------ model reply parsing
    func testParsesAFencedReplyAndIgnoresUnknownKeys() throws {
        let raw = "```json\n" + #"{"answer":"no","facts":{"conscious@2":"no","heavy_bleeding@1":"yes","bogus":"yes","conscious@9":"no"},"choices":{"location":"roof","water_depth":"swimming"},"hazards":["BLEEDING","NOPE"],"patients":["MOTHER","ALIEN"],"people":2,"landmark":"school","next":"breathing@2"}"# + "\n```"
        let i = try XCTUnwrap(TriagePrompt.parse(raw, asked: .fact(.conscious, 1)))
        XCTAssertEqual(.no, i.facts[FactKey(.conscious, 2)])
        XCTAssertEqual(.no, i.facts[FactKey(.conscious, 1)])   // from "answer", for the person asked about
        XCTAssertEqual(.yes, i.facts[FactKey(.heavyBleeding, 1)])
        XCTAssertEqual([.location: "roof"], i.choices)         // an option key that does not exist is dropped
        XCTAssertEqual([.bleeding], i.hazards)
        XCTAssertEqual([.mother], i.patients)
        XCTAssertEqual(2, i.people)
        XCTAssertEqual("school", i.landmark)
        XCTAssertEqual("breathing@2", i.next)
        XCTAssertEqual(3, i.facts.count)
    }

    func testAChoiceAnswerFillsTheAskedChoice() throws {
        let i = try XCTUnwrap(TriagePrompt.parse(#"{"answer":"waist"}"#, asked: .choice(.waterDepth)))
        XCTAssertEqual("waist", i.choices[.waterDepth])
        XCTAssertNil(TriagePrompt.parse(#"{"answer":"deep-ish"}"#, asked: .choice(.waterDepth)))
    }

    func testTheAnswerFieldFillsTheAskedFactWhenFactsLackIt() throws {
        let i = try XCTUnwrap(TriagePrompt.parse(#"{"answer":"yes"}"#, asked: .fact(.waterRising)))
        XCTAssertEqual(.yes, i.facts[FactKey(.waterRising, 0)])
    }

    func testAnExplicitFactBeatsTheAnswerField() throws {
        let i = try XCTUnwrap(TriagePrompt.parse(#"{"answer":"yes","facts":{"conscious@1":"no"}}"#, asked: .fact(.conscious, 1)))
        XCTAssertEqual(.no, i.facts[FactKey(.conscious, 1)])
    }

    func testDoneAloneIsAUsableReply() throws {
        XCTAssertTrue(try XCTUnwrap(TriagePrompt.parse(#"{"done":true}"#, asked: nil)).done)
    }

    func testGarbageAndEmptyRepliesGiveNothing() {
        XCTAssertNil(TriagePrompt.parse("I am sorry, I cannot help with that.", asked: .fact(.conscious, 1)))
        XCTAssertNil(TriagePrompt.parse("{}", asked: .fact(.conscious, 1)))
        XCTAssertNil(TriagePrompt.parse(#"{"answer":"maybe","facts":{"conscious@1":"perhaps"},"people":0,"landmark":"","next":"made_up"}"#, asked: .fact(.conscious, 1)))
        XCTAssertNil(TriagePrompt.parse(#"{"people":9000}"#, asked: nil))
    }

    // ------------------------------------------------------------------ the prompt carries the decision architecture
    func testTheTurnMessageListsOnlyTheAllowedMenuAndNamesThePeople() {
        let s = TriageEngine.start(text: "my mother and my son are hurt", categories: [.medical], lang: .en, peopleHint: 2)
        let m = TriagePrompt.turn(utterance: "haan", asked: .fact(.conscious, 1), state: s, langHint: "hi")
        XCTAssertTrue(m.contains("1 your mother; 2 your son"))
        XCTAssertTrue(m.contains("conscious@2 [MUST] Is your son awake and answering you?"))
        XCTAssertTrue(m.contains("Disaster: none declared"))
        XCTAssertFalse(m.contains("water_rising"))       // not on the menu without a declaration
        XCTAssertTrue(m.contains("haan"))
    }

    func testADeclaredDisasterAppearsWithItsAgeAndUnlocksItsQuestions() {
        let d = DisasterGate.active([DisasterContext(type: .flood, issuedAtSec: now - 14 * 3600)], nowSec: now)
        let s = TriageEngine.start(text: "stuck", categories: [.rescue], lang: .en, peopleHint: 1, disasters: d)
        let m = TriagePrompt.turn(utterance: "ok", asked: nil, state: s, langHint: "en", issuedAgesH: ["flood": 14])
        XCTAssertTrue(m.contains("flood (declared 14 h ago)"))
        XCTAssertTrue(m.contains("more than 12 hours old"))
        XCTAssertTrue(m.contains("water_rising"))
        XCTAssertTrue(m.contains("location Where exactly are you? options: roof/upper_floor/ground_floor/vehicle/outdoors"))
    }

    func testTheSystemPromptStatesTheRulesTheCodeEnforces() {
        let p = TriagePrompt.system
        XCTAssertTrue(p.contains("MENU") && p.contains("MUST") && p.contains("never bring up floods"))
        XCTAssertTrue(p.contains("separately"))
    }

    // ------------------------------------------------------------------ into the draft
    private func triaged(_ text: String, _ cats: [NeedCategory], _ answer: (TriageState) -> TriageState = { $0 }) -> TriageResult {
        TriageEngine.result(answer(TriageEngine.start(text: text, categories: cats, lang: .en)))
    }

    func testTriageRaisesUrgencyAddsFlagsAndAnswers() {
        let r = triaged("my wife is bleeding, we are stuck", [.medical, .rescue]) {
            var s = TriageEngine.record($0, fact: .conscious, answer: .yes)
            s = TriageEngine.record(s, fact: .heavyBleeding, answer: .yes)
            return TriageEngine.record(s, fact: .roadReachable, answer: .no)
        }
        let d = AskLogic.applyTriage(AskDraft(category: .medical, urgency: 1), r)
        XCTAssertEqual(.medical, d.category)
        XCTAssertEqual([.rescue], d.extraCategories)
        XCTAssertEqual(3, d.urgency)
        XCTAssertTrue(d.vulnFlags.contains(.injured))
        XCTAssertEqual(1, d.answers[1])
        XCTAssertEqual(2, d.answers[2])
        XCTAssertTrue(d.edited.contains(.category) && d.edited.contains(.urgency))
        XCTAssertTrue(!d.summary.isEmpty && AskLogic.byteLength(d.summary) <= AskLogic.maxSummary)
    }

    func testTriageNeverLowersUrgencyOrOverwritesHandEditedFields() {
        let r = triaged("child has fever", [.medical])
        let d = AskLogic.applyTriage(AskDraft(category: .medical, summary: "typed note", urgency: 3, edited: [.summary]), r)
        XCTAssertEqual(3, d.urgency)
        XCTAssertEqual("typed note", d.summary)
    }

    func testOtherNeedsTheSpeakerAskedForSurviveTriage() {
        let r = triaged("hurt, and we need water", [.medical])
        let d = AskLogic.applyTriage(AskDraft(category: .water, extraCategories: [.medical]), r)
        XCTAssertEqual(.medical, d.category)
        XCTAssertEqual([.water], d.extraCategories)
        XCTAssertEqual(2, AskLogic.perCategory(d).count)
    }

    func testWaterAndFoodStayOneTicketBesideTheTriagedOne() {
        let r = triaged("injured and hungry", [.medical])
        let d = AskLogic.applyTriage(AskDraft(category: .food, extraCategories: [.water, .medical]), r)
        XCTAssertEqual(.medical, d.category)
        XCTAssertEqual(1, d.extraCategories.count)
    }

    func testAnswersFromTriageAreOnlyKeptForTheFirstCategoryQuestions() {
        // rescue-only request: the rescue questions (3 and 4) are the ones that go out
        let r = triaged("stuck on the roof", [.rescue]) { TriageEngine.record($0, fact: .waterRising, answer: .yes) }
        let d = AskLogic.applyTriage(AskDraft(category: .rescue), r)
        XCTAssertEqual([NeedAnswer(3, 1), NeedAnswer(4, 1)], AskLogic.wireAnswers(d))
    }

    func testNothingTriagedLeavesTheDraftAlone() {
        let r = triaged("hello", [.other])
        let d = AskDraft(category: .water)
        XCTAssertEqual(d, AskLogic.applyTriage(d, r))
    }

    func testFollowUpFlagsFollowTheChosenCategories() {
        let water = AskLogic.relevantFlags([.water])
        XCTAssertTrue(water.contains(.child) && water.contains(.elderly) && !water.contains(.pregnant))
        let medical = AskLogic.relevantFlags([.medical])
        XCTAssertTrue(medical.contains(.pregnant) && medical.contains(.injured))
        XCTAssertTrue(AskLogic.relevantFlags([.rescue]).contains(.trapped))
    }

    func testPeopleInputAppearsOnlyOnceThereIsSomethingToAttachItTo() {
        XCTAssertFalse(AskLogic.showDetails(AskDraft(), ""))
        XCTAssertTrue(AskLogic.showDetails(AskDraft(category: .food), ""))
        XCTAssertTrue(AskLogic.showDetails(AskDraft(), "we are hungry"))
    }

    func testFlowAppliesTriageToItsDraft() {
        var flow = AskFlow()
        flow.onTranscript("my father is not breathing", lang: .en)
        flow.applyTriage(triaged("my father is not breathing", [.medical]))
        XCTAssertEqual(3, flow.draft.urgency)
        XCTAssertEqual(.medical, flow.draft.category)
    }

    // ------------------------------------------------------------------ the conversation
    func testSessionAsksAndEndsWithAdvice() {
        var t = TriageSession()
        XCTAssertTrue(t.begin(text: "my brother is hurt", categories: [.medical], lang: .en, hasBrain: false))
        XCTAssertEqual(.asking, t.ui.phase)
        XCTAssertEqual("conscious@1", t.ui.ask?.id)
        XCTAssertEqual("Is your brother awake and answering you?", t.ui.questionText)
        // a spoken "no" is read by the rules and recorded
        XCTAssertTrue(t.startHeard("no"))
        XCTAssertEqual(.thinking, t.ui.phase)
        t.finishHeard(RuleInterpreter.interpret("no", asked: t.pending?.asked ?? .fact(.conscious, 1)))
        XCTAssertEqual("breathing@1", t.ui.ask?.id)
        t.tap(.no, label: "No")
        XCTAssertEqual(.done, t.ui.phase)
        XCTAssertEqual(.cpr, t.ui.result?.value.advisories.first?.id)
        XCTAssertTrue(t.ui.result!.value.critical)
    }

    func testAnUnclearAnswerIsAskedAgainOnceThenTakenAsUnknown() {
        var t = TriageSession()
        t.begin(text: "child has high fever", categories: [.medical], lang: .en, hasBrain: false)
        let ask = t.ui.ask!
        t.startHeard("the water is brown")
        t.finishHeard(RuleInterpreter.interpret("the water is brown", asked: ask, state: t.state))
        XCTAssertEqual(.asking, t.ui.phase)
        XCTAssertTrue(t.ui.unclear)
        XCTAssertEqual(ask, t.ui.ask)
        t.startHeard("hmm")
        t.finishHeard(RuleInterpreter.interpret("hmm", asked: ask, state: t.state))
        XCTAssertNotEqual(ask, t.ui.ask)
        XCTAssertFalse(t.ui.unclear)
    }

    func testAChoiceQuestionShowsButtonsAndAcceptsSpeechOrTaps() {
        var t = TriageSession()
        t.begin(text: "we are stuck", categories: [.rescue], lang: .en, hasBrain: false)
        XCTAssertEqual("location", t.ui.ask?.id)
        XCTAssertEqual(["roof", "upper_floor", "ground_floor", "basement", "outdoors", "vehicle", "rubble"], t.ui.options.map(\.key))
        XCTAssertEqual("On the roof", t.ui.options.first?.label)
        t.startHeard("we are on the roof")
        t.finishHeard(RuleInterpreter.interpret("we are on the roof", asked: t.ui.ask, state: t.state))
        XCTAssertEqual("roof", t.state?.choices[.location])
        XCTAssertNotEqual("location", t.ui.ask?.id)
    }

    func testAButtonOnAChoiceQuestionIsRecordedAndDontKnowSkipsIt() {
        var t = TriageSession()
        t.begin(text: "we are stuck", categories: [.rescue], lang: .en, hasBrain: false)
        t.tapOption(t.ui.options.first { $0.key == "upper_floor" }, dontKnowLabel: "Don't know")
        XCTAssertEqual("floor", t.ui.ask?.id)
        t.tapOption(nil, dontKnowLabel: "Don't know")
        XCTAssertNotEqual("floor", t.ui.ask?.id)
        t.finishNow()
        XCTAssertEqual("upper_floor", t.ui.result?.value.choices[.location])
        XCTAssertNil(t.ui.result?.value.choices[.floor])
    }

    func testADisasterDeclarationUnlocksItsQuestionsAndIsNoted() {
        var t = TriageSession()
        let d = DisasterGate.active([DisasterContext(type: .flood, issuedAtSec: now - 3600)], nowSec: now)
        t.begin(text: "we are stuck", categories: [.rescue], lang: .en, hasBrain: false, disasters: d)
        XCTAssertEqual("flood", t.ui.disasterNote)
        XCTAssertTrue(Planner.menu(t.state!).contains { $0.ask.id == "water_rising" })
    }

    func testTheModelsFirstReadingCanReplaceTheQuestionOnScreen() {
        var t = TriageSession()
        t.begin(text: "my mother and my son are hurt", categories: [.medical], lang: .en, hasBrain: true, peopleHint: 2)
        XCTAssertEqual("conscious@1", t.ui.ask?.id)
        // the model says only the mother needs help: the son's question must not stay on the menu
        t.absorbFirstReading(Interpretation(patients: [.mother]))
        XCTAssertEqual("conscious@1", t.ui.ask?.id)
        XCTAssertEqual([.mother], t.state?.subjects.map(\.relation))
        // not applied once somebody has answered
        t.tap(.yes, label: "Yes")
        let before = t.state
        t.absorbFirstReading(Interpretation(patients: [.mother, .father]))
        XCTAssertEqual(before, t.state)
    }

    func testSameTextKeepsTheConversationDifferentTextRestarts() {
        var t = TriageSession()
        t.begin(text: "child is hurt", categories: [.medical], lang: .en, hasBrain: true)
        XCTAssertEqual(.loading, t.ui.brain)
        t.tap(.yes, label: "Yes")
        XCTAssertFalse(t.begin(text: "child is hurt", categories: [.medical], lang: .en, hasBrain: true))
        XCTAssertEqual("heavy_bleeding@1", t.ui.ask?.id)
        XCTAssertTrue(t.begin(text: "stuck on roof", categories: [.rescue], lang: .en, hasBrain: false))
        // a roof said in passing is not asked again, so the rescue conversation starts at the next question
        XCTAssertEqual("anyone_hurt", t.ui.ask?.id)
    }

    func testAnswersWhenNothingIsWaitingAreIgnored() {
        var t = TriageSession()
        XCTAssertFalse(t.startHeard("yes"))
        t.begin(text: "not breathing", categories: [.medical], lang: .en, hasBrain: false)
        XCTAssertEqual(.done, t.ui.phase)
        XCTAssertFalse(t.startHeard("yes"))
        let lines = t.ui.lines.count
        t.tap(.yes, label: "Yes")
        XCTAssertEqual(lines, t.ui.lines.count)
    }

    func testTheNewStringsExistInEveryLanguage() {
        for l in Lang.allCases {
            let a = AskStrings(l)
            for s in [a.triageTitle, a.triageAnswerHint, a.triageUnclear, a.triageYes, a.triageNo, a.triageDontKnow, a.triageThinking,
                      a.triageSkip, a.triageCritical, a.triageAdviceTitle, a.triageReview, a.triageModelReady, a.triageModelLoading,
                      a.triageModelOff, a.triageYouSaid, a.triageStartMic, a.headlineHint, a.howManyPeople, a.triageDeclared("flood")] {
                XCTAssertFalse(s.isEmpty)
            }
        }
    }
}
