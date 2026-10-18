import XCTest
import RelayCore
@testable import RelayKit

final class ScreenModelsTests: XCTestCase {
    private let now = UI.at(18, 14, 0)

    // ---- Language
    func testLanguageModelListsTheOfferedLanguagesOnly() {
        let m = LanguageScreenModel.make(selected: .hi, strings: UI.strings(.hi))
        XCTAssertEqual([Lang.en, .hi], m.rows.map { $0.lang })
        XCTAssertEqual([false, true], m.rows.map { $0.isSelected })
        XCTAssertEqual("अपनी भाषा चुनें", m.title)
        XCTAssertEqual("हिन्दी", m.rows[1].nativeName)
        XCTAssertEqual("Hindi", m.rows[1].englishName)
        XCTAssertEqual("Hindi सुनें", m.rows[1].speakLabel)
        XCTAssertEqual("हिन्दी। नमस्ते, यह रिले है।", m.rows[1].greeting)
        XCTAssertEqual("English. Hello, this is Relay.", m.rows[0].greeting)
        XCTAssertEqual("No Waray-Waray voice is installed on this phone.", LanguageScreenModel.noVoiceMessage(.war, strings: UI.strings(.en)))
    }

    func testTheLanguageListFollowsWhatIsOffered() {
        // Odia switched on in the build and on the backend: it joins the list. Filipino and Waray stay hidden.
        let m = LanguageScreenModel.make(selected: .en, offered: [.en, .hi, .or], strings: UI.strings(.en))
        XCTAssertEqual([Lang.en, .hi, .or], m.rows.map { $0.lang })
        XCTAssertEqual(["English", "हिन्दी", "ଓଡ଼ିଆ"], m.rows.map { $0.nativeName })
    }

    func testTheNotAvailableLineIsEnglishAndNamesTheLanguage() {
        XCTAssertEqual("Odia is not available yet; showing English.", UI.strings(.en).home.languageUnavailable("Odia"))
        XCTAssertEqual("Odia अभी उपलब्ध नहीं है; अंग्रेज़ी दिखाई जा रही है।", UI.strings(.hi).home.languageUnavailable("Odia"))
        // hidden languages show English rather than an invented translation
        XCTAssertEqual("Odia is not available yet; showing English.", UI.strings(.or).home.languageUnavailable("Odia"))
    }

    // ---- Home
    func testHomeHeaderLineAndStatusCard() {
        let st = VillagerStatus(kind: .reachable, dot: .green, peers: 3, hopsToGateway: 2, syncedAgoMs: 60_000)
        var n = UI.need("7f3a0001", at: now - 180_000, item: "Drinking water", landmark: "the primary school")
        n.sentToMesh = true; n.sentToMeshAtMs = now - 120_000
        let m = HomeModel.make(villager: st, area: "  Ward 4, Puri district ", needs: [n], now: now, strings: UI.strings(.en))
        XCTAssertEqual("Relay", m.title)
        XCTAssertEqual("Ward 4, Puri district", m.area)
        XCTAssertEqual("Ward 4, Puri district · Reachable", m.headerLine)
        XCTAssertEqual(.good, m.statusTone)
        XCTAssertEqual("Your messages can reach help", m.statusTitle)
        XCTAssertEqual("Gateway 2 hops away · online 1 min ago", m.statusDetail)
        XCTAssertEqual("Your messages can reach help. Gateway 2 hops away · online 1 min ago. Reachable. Opens Network", m.statusAccessibility)
        XCTAssertFalse(m.showsTurnOnBluetooth)
        XCTAssertEqual("Press and say what you need", m.micTitle)
        XCTAssertEqual("Water · food · medicine · rescue · shelter", m.micSubtitle)
        XCTAssertEqual("Press and say what you need. Water · food · medicine · rescue · shelter", m.micAccessibility)
        XCTAssertEqual(["Urgent · R-7F3A · near the primary school"], m.recent.map { $0.metaLine })
        XCTAssertEqual(3, m.help.lines.count)
        XCTAssertEqual("Close", m.help.close)
    }

    func testHomeWithoutAreaAndWithBluetoothOff() {
        let st = VillagerStatus(kind: .bluetoothOff, dot: .grey, peers: 0, hopsToGateway: nil, syncedAgoMs: nil)
        let m = HomeModel.make(villager: st, area: "", needs: [], now: now, strings: UI.strings(.en))
        XCTAssertEqual("Off", m.headerLine)
        XCTAssertTrue(m.showsTurnOnBluetooth)
        XCTAssertEqual("Turn on", m.turnOnLabel)
        XCTAssertTrue(m.recent.isEmpty)
        XCTAssertEqual("No requests yet", m.noRequests)
        XCTAssertEqual(.neutral, m.statusTone)
    }

    func testHomeShowsTheTwoNewestRequests() {
        let needs = [UI.need("a", at: 1), UI.need("b", at: 3), UI.need("c", at: 2)]
        let st = VillagerStatus(kind: .searching, dot: .grey, peers: 0, hopsToGateway: nil, syncedAgoMs: nil)
        let m = HomeModel.make(villager: st, area: "", needs: needs, now: now, strings: UI.strings(.en))
        XCTAssertEqual(["b", "c"], m.recent.map { $0.id })
    }

    // ---- New request
    func testAskScreenModelWhileListening() {
        var d = AskLogic.applyIntake(AskDraft(), RuleIntake.shared.extract("We are 6 people, no drinking water, near the primary school", langHint: .en))
        d = AskLogic.toggleFlag(d, .child)
        let state = AskState(draft: d, transcript: "We are 6 people, no drinking water")
        let m = AskScreenModel.make(state: state, voice: .listening(level: 0.4), lang: .en, strings: UI.strings(.en))
        XCTAssertEqual("New request", m.title)
        XCTAssertEqual("1 of 3", m.stepText)
        XCTAssertEqual("What do you need most right now?", m.headline)
        XCTAssertEqual("Listening", m.listeningWord)
        XCTAssertTrue(m.isListening)
        XCTAssertEqual("English", m.languageName)
        XCTAssertEqual("“We are 6 people, no drinking water”", m.transcriptText)
        XCTAssertFalse(m.transcriptIsPrompt)
        XCTAssertEqual("Stop listening", m.micButtonLabel)
        XCTAssertEqual("Type instead", m.switchModeLabel)
        XCTAssertTrue(m.canContinue)
        XCTAssertEqual(["Water", "Food", "Medicine", "Rescue", "Shelter"], m.mainChips.map { $0.label })
        XCTAssertEqual([true, false, false, false, false], m.mainChips.map { $0.isSelected })
        XCTAssertTrue(m.moreChips.isEmpty)
        XCTAssertFalse(m.moreIsOpen)
        XCTAssertEqual("Water", m.understoodChips.first)
        XCTAssertTrue(m.understoodChips.contains("6 · children"))
    }

    func testAskScreenModelPausedEmptyAndTyping() {
        let s = UI.strings(.en)
        let empty = AskScreenModel.make(state: AskState(), voice: .paused, lang: .hi, strings: s)
        XCTAssertEqual("Paused", empty.listeningWord)
        XCTAssertEqual("हिन्दी", empty.languageName)
        XCTAssertEqual(s.ask.sayItPrompt, empty.transcriptText)
        XCTAssertTrue(empty.transcriptIsPrompt)
        XCTAssertFalse(empty.canContinue)
        XCTAssertTrue(empty.understoodChips.isEmpty)
        XCTAssertEqual("Start listening", empty.micButtonLabel)
        let typing = AskScreenModel.make(state: AskState(typing: true), voice: .paused, lang: .en, strings: s)
        XCTAssertNil(typing.listeningWord)
        XCTAssertNil(typing.micButtonLabel)
        XCTAssertEqual("Use voice instead", typing.switchModeLabel)
        // "Done, check it" is disabled while the recogniser finishes.
        let withText = AskState(draft: AskDraft(category: .water), transcript: "water")
        XCTAssertTrue(AskScreenModel.make(state: withText, voice: .paused, lang: .en, strings: s).canContinue)
        XCTAssertFalse(AskScreenModel.make(state: withText, voice: .paused, lang: .en, strings: s, busy: true).canContinue)
    }

    func testAskScreenModelProblemAndMoreChips() {
        let s = UI.strings(.en)
        let problem = VoiceProblem.from(text: "microphone permission denied", offerVoiceSettings: false, offerAppSettings: true, lang: .en, strings: s)
        let st = AskState(draft: AskDraft(category: .missingPerson))
        let m = AskScreenModel.make(state: st, voice: .problem(problem), lang: .en, strings: s)
        XCTAssertEqual(problem, m.problem)
        XCTAssertTrue(m.moreIsOpen)
        XCTAssertEqual(["Missing person", "Other"], m.moreChips.map { $0.label })
        XCTAssertEqual([true, false], m.moreChips.map { $0.isSelected })
        let typing = AskScreenModel.make(state: AskState(draft: AskDraft(), typing: true), voice: .problem(problem), lang: .en, strings: s)
        XCTAssertNil(typing.problem, "typing mode hides the voice problem")
        XCTAssertTrue(AskScreenModel.make(state: AskState(), voice: .paused, lang: .en, strings: s, moreOpened: true).moreIsOpen)
    }

    // ---- Check and send
    private var checkDraft: AskDraft {
        var d = AskDraft(category: .water, people: 6, vulnFlags: [.child, .elderly], landmark: "primary school", summary: "Pump broken since yesterday", urgency: 2)
        d.answers = [6: 2]
        return d
    }

    func testCheckModelRowsQuestionsAndUrgency() {
        let m = CheckModel.make(draft: checkDraft, locUi: .ready, accuracyM: 40, phase: .idle, lang: .en, strings: UI.strings(.en))
        XCTAssertEqual("Check and send", m.title)
        XCTAssertEqual("2 of 3", m.stepText)
        XCTAssertEqual([CheckEditor.need, .people, .location, .note], m.rows.map { $0.editor })
        XCTAssertEqual(["Need", "People", "Where", "Note"], m.rows.map { $0.label })
        XCTAssertEqual(["Water", "6 · children, elderly", "Near primary school", "Pump broken since yesterday"], m.rows.map { $0.value })
        XCTAssertEqual("GPS · about 40 m accuracy", m.rows[2].secondary)
        XCTAssertFalse(m.rows[2].offersTurnOn)
        XCTAssertFalse(m.rows[2].secondaryIsWarning)
        XCTAssertNil(m.rows[3].secondary, "English notes need no 'sent in English' line")
        XCTAssertEqual([3, 4], [] + QuestionBank.select(.rescue, []), "bank order sanity")
        // Water questions: children_or_elderly is skipped because the flags say so; how long remains.
        XCTAssertEqual([6], m.questions.map { $0.id })
        XCTAssertEqual(["Don't know", "Since today", "1-2 days", "3+ days"], m.questions[0].options.map { $0.label })
        XCTAssertEqual([false, false, true, false], m.questions[0].options.map { $0.isSelected })
        XCTAssertEqual(["Can wait", "Soon", "Urgent", "Life at risk"], m.urgencyWords)
        XCTAssertEqual(2, m.urgency)
        XCTAssertEqual("Urgent: needed in the next few hours.", m.urgencyHelp)
        XCTAssertEqual("Send", m.sendTitle)
        XCTAssertTrue(m.sendEnabled)
    }

    func testCheckModelEmptyDraftAndLocationOff() {
        let m = CheckModel.make(draft: AskDraft(), locUi: .off, accuracyM: nil, phase: .idle, lang: .en, strings: UI.strings(.en))
        XCTAssertEqual(["Not chosen yet", "1", "No landmark yet", "No note"], m.rows.map { $0.value })
        XCTAssertEqual("Location is turned off on this phone", m.rows[2].secondary)
        XCTAssertTrue(m.rows[2].offersTurnOn)
        XCTAssertTrue(m.rows[2].secondaryIsWarning)
        XCTAssertTrue(m.questions.isEmpty)
    }

    func testCheckModelWhileLocatingAndInOtherLanguages() {
        let en = CheckModel.make(draft: checkDraft, locUi: .acquiring, accuracyM: nil, phase: .locating, lang: .en, strings: UI.strings(.en))
        XCTAssertEqual("Getting your location…", en.sendTitle)
        XCTAssertFalse(en.sendEnabled)
        XCTAssertEqual("Still finding your location…", en.rows[2].secondary)
        XCTAssertTrue(en.rows[2].secondaryIsWarning)
        let hi = CheckModel.make(draft: checkDraft, locUi: .ready, accuracyM: 12, phase: .idle, lang: .hi, strings: UI.strings(.hi))
        XCTAssertEqual("जाँचें और भेजें", hi.title)
        XCTAssertEqual("भेजें", hi.sendTitle)
        XCTAssertEqual(UI.strings(.hi).ask.sentInEnglish, hi.rows[3].secondary, "non-English notes say they go out in English")
        XCTAssertEqual("पता नहीं", hi.questions[0].options[0].label, "answers are in the requester's language")
        XCTAssertEqual(QuestionBank.byId(6)!.text(.hi), hi.questions[0].text)
        XCTAssertEqual(UI.strings(.hi).ask.hearQuestion(QuestionBank.byId(6)!.text(.hi)), hi.questions[0].hearLabel)
    }

    func testNeedEditorOffersAllSevenCategories() {
        let chips = CheckModel.needEditorChips(AskDraft(category: .shelter), strings: UI.strings(.en))
        XCTAssertEqual(["Water", "Food", "Medicine", "Rescue", "Shelter", "Missing person", "Other"], chips.map { $0.label })
        XCTAssertEqual(.shelter, chips.first { $0.isSelected }?.category)
    }
}
