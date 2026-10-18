import XCTest
import RelayCore
@testable import RelayKit

final class AskFlowTests: XCTestCase {
    private func ids(_ n: UInt8) -> [UInt8] { (0..<8).map { n &+ UInt8($0) } }

    func testBuiltNeedEncodesV1WithoutAnswersAndV2With() throws {
        var flow = AskFlow()
        flow.onTranscript("rescue us, we are on the roof, 4 people, water is rising", lang: .en)
        flow.setCategory(.rescue)
        let v1 = flow.buildNeed(msgId: ids(0), originNode: ids(8), createdAtSec: 1_700_000_000, lang: .hi, fix: nil)
        XCTAssertEqual(UInt8(NeedCodec.version), try NeedCodec.encode(v1)[0])
        XCTAssertEqual(0, v1.locAcc)
        XCTAssertEqual(0, v1.latE5)
        XCTAssertEqual(.hi, v1.lang)
        XCTAssertEqual(4, v1.people)
        flow.setAnswer(3, 1)
        let fix = Fix(lat: 19.8, lon: 85.83, accuracyM: 30)
        let v2 = flow.buildNeed(msgId: ids(0), originNode: ids(8), createdAtSec: 1_700_000_000, lang: .hi, fix: fix)
        XCTAssertEqual(UInt8(NeedCodec.versionWithAnswers), try NeedCodec.encode(v2)[0])
        XCTAssertEqual([NeedAnswer(3, 1)], v2.answers)
        XCTAssertEqual(2, v2.locAcc)
        XCTAssertEqual(Geo.toE5(19.8), v2.latE5)
        XCTAssertEqual(Geo.toE5(85.83), v2.lonE5)
        XCTAssertEqual(v2, NeedCodec.decode(try NeedCodec.encode(v2)))
    }

    func testChipOnlyRequestStillHasAnItemOnTheWire() {
        var flow = AskFlow()
        flow.toggleCategory(.water)
        let n = flow.buildNeed(msgId: ids(0), originNode: ids(0), createdAtSec: 1, lang: .en, fix: nil)
        XCTAssertEqual(.water, n.category)
        XCTAssertEqual("food and water", n.item)
        // Nothing chosen at all still builds (category Other).
        XCTAssertEqual(.other, AskFlow().buildNeed(msgId: ids(0), originNode: ids(0), createdAtSec: 1, lang: .en, fix: nil).category)
    }

    func testReextractsOnEveryTranscriptAndChipBeatsExtraction() {
        var flow = AskFlow()
        flow.onTranscript("need wat", lang: .en)
        flow.onTranscript("need water", lang: .en)
        XCTAssertEqual(.water, flow.draft.category)
        flow.toggleCategory(.medical)
        flow.onTranscript("need water for 5 people", lang: .en)
        XCTAssertEqual([.water, .medical], flow.draft.categories, "a tapped chip adds to what was understood")
        XCTAssertEqual(5, flow.draft.people)
        XCTAssertEqual("need water for 5 people", flow.state.transcript)
        // Tapping a chosen chip again removes it.
        flow.toggleCategory(.medical)
        XCTAssertEqual([.water], flow.draft.categories)
    }

    func testWaterAndFoodAreOneChipAndOneTicket() {
        var flow = AskFlow()
        flow.toggleCategory(.water)
        flow.toggleCategory(.food)
        XCTAssertEqual([], flow.draft.categories, "food is the same chip as water: the second tap removes it")
        flow.toggleCategory(.food)
        XCTAssertEqual([.food], flow.draft.categories)
        flow.toggleCategory(.water)
        XCTAssertEqual([], flow.draft.categories)
        flow.toggleCategory(.water)
        let n = flow.buildNeeds(newMsgId: { self.ids(1) }, originNode: ids(8), createdAtSec: 1, lang: .en, fix: nil)
        XCTAssertEqual(1, n.count)
        XCTAssertEqual("food and water", n[0].item)
        XCTAssertEqual(.water, n[0].category)
    }

    func testTappingAnUnderstoodChipConfirmsItInsteadOfDroppingIt() {
        var flow = AskFlow()
        flow.onTranscript("need water", lang: .en)
        flow.toggleCategory(.water)
        XCTAssertEqual([.water], flow.draft.categories)
        XCTAssertTrue(flow.draft.edited.contains(.category))
        flow.toggleCategory(.water)
        flow.onTranscript("need water", lang: .en)
        XCTAssertEqual([.water], flow.draft.categories, "removing the last chip hands the decision back to extraction")
    }

    func testEditorCanCorrectFoodToWaterAndItSticks() {
        var flow = AskFlow()
        flow.onTranscript("we need rice for 4 people", lang: .en)
        XCTAssertEqual(.food, flow.draft.category)
        XCTAssertFalse(flow.draft.item.isEmpty, "extraction filled an item for food")
        flow.pickCategory(.water)
        XCTAssertEqual([.water], flow.draft.categories, "the sibling is swapped, not added")
        XCTAssertEqual("", flow.draft.item, "the item guessed for food does not follow")
        // More speech or a re-extraction later does not bring food or rice back.
        flow.onTranscript("we need rice for 4 people near the school", lang: .en)
        XCTAssertEqual([.water], flow.draft.categories)
        XCTAssertEqual("", flow.draft.item)
        let need = flow.buildNeed(msgId: ids(0), originNode: ids(8), createdAtSec: 1, lang: .en, fix: nil)
        XCTAssertEqual(.water, need.category)
        XCTAssertEqual("food and water", need.item)
    }

    func testRemovingThePrimaryPromotesTheNext() {
        var flow = AskFlow()
        flow.toggleCategory(.rescue)
        flow.toggleCategory(.medical)
        flow.toggleCategory(.rescue)
        XCTAssertEqual([.medical], flow.draft.categories)
    }

    func testSeveralGroupsBuildOneNeedEachSharingTheRest() throws {
        var flow = AskFlow()
        flow.toggleCategory(.water)
        flow.toggleCategory(.medical)
        flow.toggleCategory(.rescue)
        flow.setPeople(4)
        flow.setLandmark("school")
        flow.setUrgency(3)
        flow.setItem("insulin")
        var n: UInt8 = 0
        let needs = flow.buildNeeds(newMsgId: { n += 10; return self.ids(n) }, originNode: ids(8), createdAtSec: 1, lang: .en, fix: nil)
        XCTAssertEqual([.water, .medical, .rescue], needs.map(\.category))
        XCTAssertEqual(3, Set(needs.map(\.msgId)).count, "each NEED has its own id")
        XCTAssertEqual(["insulin", "medicine", "rescue"], needs.map(\.item), "the typed item stays with the first group")
        XCTAssertTrue(needs.allSatisfy { $0.people == 4 && $0.landmark == "school" && $0.urgency == 3 })
        for need in needs { XCTAssertEqual(need, NeedCodec.decode(try NeedCodec.encode(need))) }
    }

    func testOneOrNoCategoryStillBuildsExactlyOneNeed() {
        var flow = AskFlow()
        XCTAssertEqual(1, flow.buildNeeds(newMsgId: { self.ids(1) }, originNode: ids(8), createdAtSec: 1, lang: .en, fix: nil).count)
        flow.toggleCategory(.water)
        XCTAssertEqual(1, flow.buildNeeds(newMsgId: { self.ids(1) }, originNode: ids(8), createdAtSec: 1, lang: .en, fix: nil).count)
    }

    func testAnswersGoOnlyToTheCategoryTheirQuestionBelongsTo() {
        var flow = AskFlow()
        flow.toggleCategory(.rescue)
        flow.toggleCategory(.medical)
        flow.setAnswer(3, 1)
        let needs = flow.buildNeeds(newMsgId: { self.ids(1) }, originNode: ids(8), createdAtSec: 1, lang: .en, fix: nil)
        XCTAssertEqual([NeedAnswer(3, 1)], needs[0].answers)
        XCTAssertTrue(needs[1].answers.isEmpty)
    }

    func testPrefillAppliesOnceAndSurvivesExtraction() {
        var flow = AskFlow()
        flow.prefill(category: .medical, landmark: "bus stand")
        flow.prefill(category: .food, landmark: "elsewhere")
        flow.onTranscript("need water near the school", lang: .en)
        XCTAssertEqual(.medical, flow.draft.category)
        XCTAssertEqual("bus stand", flow.draft.landmark)
        var blank = AskFlow()
        blank.prefill(category: .shelter, landmark: "  ")
        XCTAssertEqual(.shelter, blank.draft.category)
        XCTAssertEqual("", blank.draft.landmark)
    }

    private struct FakeEngine: IntakeEngine {
        func extract(_ text: String, langHint: Lang?) -> IntakeDraft {
            IntakeDraft(need: NeedDraft(category: .shelter, urgency: 2, people: 7, vulnFlags: [.pregnant], item: "tarp", landmark: "church",
                                        summary: "from fake", lang: .en), questionIds: [7])
        }
    }

    func testEngineIsInjectable() {
        var flow = AskFlow(engine: FakeEngine())
        flow.onTranscript("anything", lang: .en)
        let d = flow.draft
        XCTAssertEqual(.shelter, d.category)
        XCTAssertEqual(7, d.people)
        XCTAssertEqual("tarp", d.item)
        XCTAssertEqual([.pregnant], d.vulnFlags)
        XCTAssertEqual(QuestionBank.select(.shelter, [.pregnant]), d.questionIds)
    }

    func testStepMachine() {
        var flow = AskFlow()
        XCTAssertEqual(.newRequest, flow.step)
        XCTAssertFalse(flow.canContinue)
        XCTAssertFalse(flow.goToCheck(), "nothing said yet: stay on New request")
        XCTAssertEqual(.newRequest, flow.step)
        flow.onTranscript("need water", lang: .en)
        XCTAssertTrue(flow.canContinue)
        XCTAssertTrue(flow.goToCheck())
        XCTAssertEqual(.check, flow.step)
        XCTAssertEqual(2, flow.step.rawValue)
        // Back keeps the draft.
        flow.backToNewRequest()
        XCTAssertEqual(.newRequest, flow.step)
        XCTAssertEqual(.water, flow.draft.category)
    }

    func testChipAloneIsEnoughToContinue() {
        var flow = AskFlow()
        flow.toggleCategory(.other)
        XCTAssertTrue(flow.goToCheck())
    }

    func testSendPhaseGuardsDoubleSend() {
        var flow = AskFlow()
        XCTAssertFalse(flow.isSending)
        XCTAssertTrue(flow.beginLocating())
        XCTAssertEqual(.locating, flow.sendPhase)
        XCTAssertTrue(flow.isSending)
        XCTAssertFalse(flow.beginLocating(), "a second Send tap is ignored")
        flow.endSend()
        XCTAssertFalse(flow.isSending)
        flow.beginLocating()
        flow.backToNewRequest()
        XCTAssertEqual(.idle, flow.sendPhase)
    }

    func testTypingAndAutoStartFlags() {
        var flow = AskFlow()
        XCTAssertFalse(flow.state.typing)
        flow.setTyping(true)
        XCTAssertTrue(flow.state.typing)
        XCTAssertFalse(flow.autoStartHandled)
        flow.autoStartHandled = true
        flow.onTranscript("need food", lang: .en)
        XCTAssertTrue(flow.autoStartHandled)
    }

    func testResetStartsCleanButKeepsTheEngine() {
        var flow = AskFlow(engine: FakeEngine())
        flow.onTranscript("x", lang: .en)
        flow.goToCheck()
        flow.autoStartHandled = true
        flow.reset()
        XCTAssertEqual(AskState(), flow.state)
        XCTAssertEqual(.newRequest, flow.step)
        XCTAssertFalse(flow.autoStartHandled)
        flow.onTranscript("y", lang: .en)
        XCTAssertEqual("tarp", flow.draft.item, "the injected engine survives a reset")
    }

    func testEditsAfterCheckUseTheSameDraft() {
        var flow = AskFlow()
        flow.onTranscript("need water, 3 people", lang: .en)
        flow.goToCheck()
        flow.setPeople(8)
        flow.setItem("bottled water")
        flow.setLandmark("temple")
        flow.setSummary("pump broken")
        flow.setUrgency(3)
        flow.toggleFlag(.elderly)
        let n = flow.buildNeed(msgId: ids(1), originNode: ids(2), createdAtSec: 5, lang: .en, fix: nil)
        XCTAssertEqual(8, n.people)
        XCTAssertEqual("bottled water", n.item)
        XCTAssertEqual("temple", n.landmark)
        XCTAssertEqual("pump broken", n.summary)
        XCTAssertEqual(3, n.urgency)
        XCTAssertEqual(VulnFlag.elderly.bit, n.vulnFlags)
    }

    // ---- voice problems

    func testVoiceProblemFromSpeechDecision() {
        let s = UI.strings(.en)
        let unavailable = SpeechLogic.onError(SpeechLogic.errorLanguageUnavailable, lang: .hi, offlineAttempt: true, online: false, alreadyRetried: false)
        let p1 = VoiceProblem.from(unavailable, lang: .hi, strings: s)
        XCTAssertEqual(.voicePackMissing, p1?.kind)
        XCTAssertEqual(s.ask.voicePackMissing("Hindi"), p1?.message)
        XCTAssertEqual(.openVoiceSettings, p1?.action)
        XCTAssertEqual("Open settings", p1?.actionLabel)
        let perm = SpeechLogic.onError(SpeechLogic.errorInsufficientPermissions, lang: .en, offlineAttempt: false, online: true, alreadyRetried: false)
        XCTAssertEqual(.micDenied, VoiceProblem.from(perm, lang: .en, strings: s)?.kind)
        XCTAssertEqual(.openAppSettings, VoiceProblem.from(perm, lang: .en, strings: s)?.action)
        let nomatch = SpeechLogic.onError(SpeechLogic.errorNoMatch, lang: .en, offlineAttempt: false, online: true, alreadyRetried: false)
        let p3 = VoiceProblem.from(nomatch, lang: .en, strings: s)
        XCTAssertEqual(.didntCatch, p3?.kind)
        XCTAssertNil(p3?.action)
        let busy = SpeechLogic.onError(SpeechLogic.errorRecognizerBusy, lang: .en, offlineAttempt: false, online: true, alreadyRetried: false)
        XCTAssertEqual(.unavailable, VoiceProblem.from(busy, lang: .en, strings: s)?.kind)
        let audio = SpeechLogic.onError(SpeechLogic.errorAudio, lang: .en, offlineAttempt: false, online: true, alreadyRetried: false)
        XCTAssertEqual(.unavailable, VoiceProblem.from(audio, lang: .en, strings: s)?.kind)
        let retry = SpeechLogic.onError(SpeechLogic.errorNetwork, lang: .en, offlineAttempt: true, online: true, alreadyRetried: false)
        XCTAssertNil(VoiceProblem.from(retry, lang: .en, strings: s), "an online retry shows nothing")
    }

    func testVoiceProblemFromAndroidStyleState() {
        let s = UI.strings(.hi)
        func kind(_ text: String, v: Bool = false, a: Bool = false) -> VoiceProblem.Kind {
            VoiceProblem.from(text: text, offerVoiceSettings: v, offerAppSettings: a, lang: .or, strings: s).kind
        }
        XCTAssertEqual(.voicePackMissing, kind("whatever", v: true))
        XCTAssertEqual(.micDenied, kind("microphone permission denied", a: true))
        XCTAssertEqual(.didntCatch, kind("Didn't catch that — tap the mic"))
        XCTAssertEqual(.unavailable, kind("Voice input failed (error 4)"))
        let p = VoiceProblem.from(text: "", offerVoiceSettings: true, offerAppSettings: false, lang: .or, strings: s)
        XCTAssertEqual(s.ask.voicePackMissing("Odia"), p.message, "the English language name, as Android")
    }
}
