import XCTest
@testable import RelayCore

/// Port of the Android `core-protocol/.../TriageTest.kt` (decision architecture: Planner, per-person questions, choices, disaster gate).
final class TriageTests: XCTestCase {
    private let now: Int64 = 1_800_000_000

    private func active(_ types: DisasterType..., ageHours: Int64 = 1) -> DisasterGate.Active {
        DisasterGate.active(types.map { DisasterContext(type: $0, issuedAtSec: now - ageHours * 3600) }, nowSec: now)
    }

    private func start(_ text: String, _ cats: NeedCategory..., people: Int = 1, disasters: DisasterGate.Active = .none) -> TriageState {
        TriageEngine.start(text: text, categories: cats, lang: .en, peopleHint: people, disasters: disasters)
    }

    /// Ask until the engine stops, answering by question id (default `dflt`); returns the final state and the ids asked in order.
    private func converse(_ s0: TriageState, _ answers: [String: Any] = [:], dflt: Tri = .no) -> (TriageState, [String]) {
        var s = s0
        var asked: [String] = []
        while let a = TriageEngine.next(s) {
            asked.append(a.id)
            let v = answers[a.id]
            if let c = a.choice {
                s = TriageEngine.recordChoice(s, c, (v as? String) ?? Planner.options(c, s)[0])
            } else {
                s = TriageEngine.record(s, a, (v as? Tri) ?? dflt)
            }
            XCTAssertLessThan(asked.count, 20)
            if asked.count >= 20 { break }
        }
        return (s, asked)
    }

    // ------------------------------------------------------------------ routing
    func testSimpleRequestsStayOnTheOneShotPath() {
        XCTAssertEqual(.simple, ComplexityRouter.route(text: "we need drinking water near the school", categories: [.water]))
        XCTAssertEqual(.simple, ComplexityRouter.route(text: "no food for two days", categories: [.food, .shelter]))
        XCTAssertEqual(.simple, ComplexityRouter.route(text: "", categories: []))
    }

    func testMedicalAndRescueGoToTriage() {
        XCTAssertEqual(.triage, ComplexityRouter.route(text: "my mother needs medicine", categories: [.medical]))
        XCTAssertEqual(.triage, ComplexityRouter.route(text: "stuck on the roof", categories: [.rescue]))
        XCTAssertEqual(.triage, ComplexityRouter.route(text: "water", categories: [.water, .rescue]))
    }

    func testANamedDangerRoutesEvenWhenCategoryLooksSimple() {
        XCTAssertEqual(.triage, ComplexityRouter.route(text: "a snake bit my son, we need water", categories: [.water]))
        XCTAssertEqual(.triage, ComplexityRouter.route(text: "water", categories: [.water], vuln: [.injured]))
        XCTAssertEqual(.simple, ComplexityRouter.route(text: "flood, we need food", categories: [.food]))
    }

    // ------------------------------------------------------------------ hazards, yes/no, options, people
    func testHazardsAreFoundInEnglishHindiAndOdia() {
        XCTAssertTrue(HazardLexicon.detect("the electric pole fell in the water").contains(.electric))
        XCTAssertTrue(HazardLexicon.detect("she is not breathing").contains(.notBreathing))
        XCTAssertTrue(HazardLexicon.detect("उसकी साँस नहीं चल रही").contains(.notBreathing))
        XCTAssertTrue(HazardLexicon.detect("वो बेहोश है").contains(.unconscious))
        XCTAssertTrue(HazardLexicon.detect("ରକ୍ତ ବାହାରୁଛି").contains(.bleeding))
        XCTAssertTrue(HazardLexicon.detect("saanp ne kaat liya").contains(.snake))
        XCTAssertTrue(HazardLexicon.detect("water is rising fast").contains(.flood))
        XCTAssertTrue(HazardLexicon.detect("we need rice").isEmpty)
    }

    func testYesNoInSeveralLanguages() {
        XCTAssertEqual(.yes, YesNo.detect("yes he is"))
        XCTAssertEqual(.yes, YesNo.detect("हाँ"))
        XCTAssertEqual(.yes, YesNo.detect("ହଁ"))
        XCTAssertEqual(.no, YesNo.detect("no, not really"))
        XCTAssertEqual(.no, YesNo.detect("नहीं"))
        XCTAssertEqual(.no, YesNo.detect("hindi po"))
        XCTAssertEqual(.unknown, YesNo.detect("I don't know"))
        XCTAssertNil(YesNo.detect("the water is brown"))
        XCTAssertNil(YesNo.detect("yes and no"))
    }

    func testOptionsAreRecognisedInSpeech() {
        XCTAssertEqual("roof", Options.detect(.location, "we are on the roof"))
        XCTAssertEqual("roof", Options.detect(.location, "हम छत पर हैं"))
        XCTAssertEqual("ground_floor", Options.detect(.location, "ground floor of the house"))
        XCTAssertEqual("upper_floor", Options.detect(.location, "second floor"))
        XCTAssertEqual("rubble", Options.detect(.location, "under the rubble"))
        XCTAssertEqual("waist", Options.detect(.waterDepth, "up to my waist"))
        XCTAssertEqual("over_head", Options.detect(.waterDepth, "over my head"))
        XCTAssertEqual("two", Options.detect(.patients, "two of them"))
        XCTAssertEqual("floor_2", Options.detect(.floor, "second"))
        XCTAssertNil(Options.detect(.location, "we need rice"))
        // only options on offer can match
        XCTAssertNil(Options.detect(.location, "on the roof", offered: ["ground_floor"]))
    }

    func testRelationsAreFoundInOrderSpoken() {
        XCTAssertEqual([.son, .mother], Subjects.relations("my son and my mother are hurt"))
        XCTAssertEqual([.mother], Subjects.relations("मेरी माँ बीमार है"))
        XCTAssertEqual([.wife, .baby], Subjects.relations("wife and baby"))
        XCTAssertTrue(Subjects.relations("we need rice").isEmpty)
        XCTAssertEqual(3, Subjects.fromText("mother father son daughter").count)
    }

    func testADangerNamedInAnAnswerBeatsTheBareYesNoAndGoesToTheAskedPerson() {
        let s = start("hurt", .medical)
        let r = RuleInterpreter.interpret("no he is not breathing", asked: .fact(.conscious, 2), state: s)
        XCTAssertEqual(.no, r.facts[FactKey(.breathing, 2)])
        XCTAssertEqual(.no, r.facts[FactKey(.conscious, 2)])
    }

    // ------------------------------------------------------------------ disaster gate
    func testADeclarationCountsForTwentyFourHoursAndThenStops() {
        XCTAssertEqual([.flood], active(.flood, ageHours: 1).types)
        XCTAssertEqual([.flood], active(.flood, ageHours: 23).types)
        XCTAssertTrue(active(.flood, ageHours: 25).types.isEmpty)
        XCTAssertTrue(DisasterGate.active([], nowSec: now).types.isEmpty)
        // clock skew of a few minutes into the future is tolerated, an hour is not
        XCTAssertEqual(1, DisasterGate.active([DisasterContext(type: .flood, issuedAtSec: now + 300)], nowSec: now).types.count)
        XCTAssertTrue(DisasterGate.active([DisasterContext(type: .flood, issuedAtSec: now + 3600)], nowSec: now).types.isEmpty)
    }

    func testOlderThanTwelveHoursIsMarkedAging() {
        XCTAssertFalse(active(.flood, ageHours: 6).aging)
        XCTAssertTrue(active(.flood, ageHours: 18).aging)
        let mixed = DisasterGate.active([DisasterContext(type: .flood, issuedAtSec: now - 20 * 3600), DisasterContext(type: .cyclone, issuedAtSec: now - 3600)], nowSec: now)
        XCTAssertFalse(mixed.aging)
        XCTAssertEqual(2, mixed.types.count)
    }

    func testNoDisasterQuestionsWithoutADeclarationEvenIfThePersonMentionsFlood() {
        let s = start("flood water is rising, we are stuck", .rescue)
        let (_, asked) = converse(s)
        XCTAssertTrue(asked.contains("location"))
        XCTAssertFalse(asked.contains("water_rising"))
        XCTAssertFalse(asked.contains("water_depth"))
        XCTAssertFalse(asked.contains("live_wire"))
        // the safety advice still follows the words
        XCTAssertTrue(TriageEngine.result(s).advisories.contains { $0.id == .flood })
    }

    func testFloodQuestionsAppearOnlyUnderAFloodDeclaration() {
        let (_, asked) = converse(start("we are stuck", .rescue, disasters: active(.flood)))
        for id in ["location", "water_rising", "water_depth", "live_wire"] { XCTAssertTrue(asked.contains(id), id) }
        XCTAssertFalse(asked.contains { ["gas_smell", "slope_moving", "shelter_holding", "building_unsafe"].contains($0) })
        let (_, none) = converse(start("we are stuck", .rescue, disasters: active(.flood, ageHours: 30)))
        XCTAssertFalse(none.contains("water_rising"))
    }

    func testEarthquakeAsksAboutBuildingGasAndShaking() {
        let (_, asked) = converse(start("we are stuck", .rescue, disasters: active(.earthquake)))
        for id in ["location", "gas_smell", "building_unsafe", "still_shaking"] { XCTAssertTrue(asked.contains(id), id) }
        XCTAssertFalse(asked.contains("water_depth"))
    }

    func testLocationOptionsFollowTheDisaster() {
        let flood = start("stuck", .rescue, disasters: active(.flood))
        let quake = start("stuck", .rescue, disasters: active(.earthquake))
        let none = start("stuck", .rescue)
        XCTAssertTrue(Planner.options(.location, flood).contains("roof") && !Planner.options(.location, flood).contains("rubble"))
        XCTAssertTrue(Planner.options(.location, quake).contains("rubble"))
        XCTAssertTrue(Planner.options(.location, none).contains("basement"))
    }

    // ------------------------------------------------------------------ location protocol
    func testUpperFloorFollowsUpWithHowHigh() {
        var s = start("we are stuck", .rescue)
        XCTAssertEqual("location", TriageEngine.next(s)!.id)
        s = TriageEngine.recordChoice(s, .location, "upper_floor")
        XCTAssertEqual("floor", TriageEngine.next(s)!.id)
        s = TriageEngine.recordChoice(s, .floor, "floor_2")
        let r = TriageEngine.result(s)
        XCTAssertEqual([NeedAnswer(4, 1)], r.answers)
        XCTAssertTrue(r.summary.contains("2 floors up"))
    }

    func testARoofSaidInPassingIsNotAskedAgain() {
        let s = start("we are four people on the roof", .rescue)
        XCTAssertEqual("roof", s.choices[.location])
        XCTAssertFalse(converse(s).1.contains("location"))
    }

    func testGroundLevelAnswersMapToTheWireGroundValue() {
        let s = TriageEngine.recordChoice(start("stuck", .rescue), .location, "ground_floor")
        XCTAssertEqual([NeedAnswer(4, 2)], TriageEngine.result(s).answers)
    }

    func testDeepWaterOrRubbleIsCritical() {
        let deep = TriageEngine.recordChoice(start("stuck", .rescue, disasters: active(.flood)), .waterDepth, "chest")
        XCTAssertEqual(3, TriageEngine.result(deep).urgency)
        let rubble = TriageEngine.recordChoice(start("stuck", .rescue), .location, "rubble")
        XCTAssertTrue(TriageEngine.result(rubble).critical)
        XCTAssertTrue(TriageEngine.result(rubble).vuln.contains(.trapped))
        XCTAssertFalse(TriageEngine.result(start("stuck", .rescue)).critical)
    }

    // ------------------------------------------------------------------ per-person questions
    func testNotBreathingEndsQuestionsAtOnceAndLeadsWithChestPushes() {
        let s = start("my father is not breathing", .medical)
        XCTAssertNil(TriageEngine.next(s))
        let r = TriageEngine.result(s)
        XCTAssertEqual(3, r.urgency)
        XCTAssertEqual(.cpr, r.advisories.first?.id)
        XCTAssertTrue(r.summary.contains("not breathing"))
    }

    func testTwoNamedPeopleAreAskedAboutSeparatelyWithTheirNames() {
        let s = start("my mother and my son are hurt", .medical, people: 2)
        XCTAssertEqual([.mother, .son], s.subjects.map(\.relation))
        XCTAssertEqual(["conscious@1", "conscious@2"], Planner.menu(s).prefix(2).map(\.ask.id))
        XCTAssertEqual("Is your mother awake and answering you?", Planner.text(.fact(.conscious, 1), s, .en))
        XCTAssertEqual("Is your son awake and answering you?", Planner.text(.fact(.conscious, 2), s, .en))
        XCTAssertTrue(Planner.text(.fact(.conscious, 1), s, .hi).contains("आपकी माँ"))
    }

    func testEachPersonGetsTheirOwnFollowUps() {
        var s = start("my mother and my son are hurt", .medical, people: 2)
        s = TriageEngine.record(s, fact: .conscious, answer: .no, subject: 1)
        s = TriageEngine.record(s, fact: .conscious, answer: .yes, subject: 2)
        let ids = Planner.menu(s).map(\.ask.id)
        XCTAssertTrue(ids.contains("breathing@1"))      // mother: not awake, so is she breathing
        XCTAssertFalse(ids.contains("breathing@2"))     // son: awake
        XCTAssertTrue(ids.contains("heavy_bleeding@2"))
        XCTAssertEqual("breathing@1", TriageEngine.next(s)!.id) // mandatory beats optional
    }

    func testSummaryNamesWhoHasWhat() {
        var s = start("my mother and my son are hurt", .medical, people: 2)
        s = TriageEngine.record(s, fact: .conscious, answer: .no, subject: 1)
        s = TriageEngine.record(s, fact: .breathing, answer: .yes, subject: 1)
        s = TriageEngine.record(s, fact: .conscious, answer: .yes, subject: 2)
        s = TriageEngine.record(s, fact: .heavyBleeding, answer: .yes, subject: 2)
        let r = TriageEngine.result(s)
        XCTAssertEqual("mother unresponsive, son heavy bleeding", r.summary)
        XCTAssertLessThanOrEqual(r.summary.utf8.count, 40)
        XCTAssertEqual(Set([TriageBank.AdvisoryId.recovery, .bleeding]), Set(r.advisories.map(\.id)))
        XCTAssertEqual([NeedAnswer(1, 2)], r.answers.filter { $0.questionId == 1 }) // worst person speaks for the ticket
    }

    func testUnnamedGroupIsAskedHowManyNeedHelp() {
        var s = start("three of us, someone is hurt", .medical, people: 3)
        XCTAssertEqual("patients", TriageEngine.next(s)!.id)
        s = TriageEngine.recordChoice(s, .patients, "two")
        XCTAssertEqual(2, s.patients.count)
        XCTAssertEqual("Is the first person awake and answering you?", Planner.text(.fact(.conscious, 1), s, .en))
        XCTAssertEqual("Is the second person awake and answering you?", Planner.text(.fact(.conscious, 2), s, .en))
        // alone, or named, it is never asked
        XCTAssertFalse(Planner.menu(start("my mother is hurt", .medical, people: 3)).contains { $0.ask.id == "patients" })
        XCTAssertFalse(Planner.menu(start("I am hurt", .medical, people: 1)).contains { $0.ask.id == "patients" })
    }

    func testMoreWordsMoreQuestionsButNeverPastTheBudget() {
        let one = start("hurt", .medical)
        let three = start("mother father son are hurt", .medical)
        XCTAssertEqual(5, Planner.budget(one))
        XCTAssertEqual(11, Planner.budget(three))
        let (_, asked) = converse(start("mother father son are hurt and stuck", .medical, .rescue, disasters: active(.flood)), dflt: .unknown)
        XCTAssertLessThanOrEqual(asked.count, 12)
    }

    func testMedicalConversationAsksAwakeThenBreathingThenBleeding() {
        var s = start("my brother fell and is hurt", .medical)
        XCTAssertEqual("conscious@1", TriageEngine.next(s)!.id)
        s = TriageEngine.record(s, fact: .conscious, answer: .no)
        XCTAssertEqual("breathing@1", TriageEngine.next(s)!.id)
        s = TriageEngine.record(s, fact: .breathing, answer: .yes)
        XCTAssertEqual("heavy_bleeding@1", TriageEngine.next(s)!.id)
        s = TriageEngine.record(s, fact: .heavyBleeding, answer: .no)
        XCTAssertEqual("road_reachable", TriageEngine.next(s)!.id)
        let r = TriageEngine.result(s)
        XCTAssertEqual(3, r.urgency)
        XCTAssertEqual(.recovery, r.advisories.first?.id)
        XCTAssertTrue(r.answers.contains(NeedAnswer(1, 2)))
    }

    func testNeverAsksTheSameQuestionTwiceEvenWhenAnswerWasUnknown() {
        var s = start("stuck in flood", .rescue, disasters: active(.flood))
        s = TriageEngine.record(s, fact: .waterRising, answer: .unknown)
        XCTAssertFalse(Planner.menu(s).contains { $0.ask.id == "water_rising" })
    }

    // ------------------------------------------------------------------ the model's say, within bounds
    func testASuggestionReordersOptionalQuestionsOnly() {
        let s = start("we are stuck", .rescue, disasters: active(.flood))
        XCTAssertEqual("live_wire", TriageEngine.next(s, heard: Interpretation(next: "live_wire"))!.id)
        XCTAssertEqual("location", TriageEngine.next(s, heard: Interpretation(next: "not_on_the_menu"))!.id)
        // gas_smell is earthquake-only: the model cannot ask it under a flood
        XCTAssertEqual("location", TriageEngine.next(s, heard: Interpretation(next: "gas_smell"))!.id)
    }

    func testTheModelCannotSkipAMandatoryQuestion() {
        let s = start("my mother is hurt", .medical)
        XCTAssertEqual("conscious@1", TriageEngine.next(s, heard: Interpretation(next: "road_reachable"))!.id)
        XCTAssertEqual("conscious@1", TriageEngine.next(s, heard: Interpretation(done: true))!.id)
    }

    func testTheModelMayEndAConversationOnceMandatoryQuestionsAreDone() {
        var s = start("my mother is hurt", .medical)
        s = TriageEngine.record(s, fact: .conscious, answer: .yes)
        XCTAssertNil(TriageEngine.next(s, heard: Interpretation(done: true)))
        XCTAssertNotNil(TriageEngine.next(s))
    }

    func testModelPatientListRefinesWhoIsAskedAbout() {
        let s = start("my mother and my son, mother has chest pain", .medical, people: 2)
        XCTAssertEqual(2, s.patients.count)
        let refined = TriageEngine.absorb(s, Interpretation(patients: [.mother]))
        XCTAssertEqual([.mother], refined.subjects.map(\.relation))
    }

    // ------------------------------------------------------------------ safety asymmetry
    func testADirectAnswerReplacesWhatWasInferredButUnknownNeverErasesIt() {
        var s = start("she fainted", .medical)
        XCTAssertEqual(.no, s.facts[FactKey(.conscious, 1)])
        s = TriageEngine.record(s, fact: .conscious, answer: .unknown)
        XCTAssertEqual(.no, s.facts[FactKey(.conscious, 1)])
        s = TriageEngine.record(s, fact: .conscious, answer: .yes)
        XCTAssertEqual(.yes, s.facts[FactKey(.conscious, 1)])
    }

    func testWhenRulesAndModelDisagreeTheDangerousReadingWins() {
        let rules = Interpretation(facts: [FactKey(.conscious, 1): .no, FactKey(.heavyBleeding, 1): .no])
        let model = Interpretation(facts: [FactKey(.conscious, 1): .yes, FactKey(.heavyBleeding, 1): .yes], hazards: [.snake])
        let m = TriageMerge.merge(rules, model)
        XCTAssertEqual(.no, m.facts[FactKey(.conscious, 1)])
        XCTAssertEqual(.yes, m.facts[FactKey(.heavyBleeding, 1)])
        XCTAssertTrue(m.hazards.contains(.snake))
        XCTAssertEqual(rules, TriageMerge.merge(rules, nil))
    }

    func testAWordMatchBeatsAModelGuessForAPlace() {
        let rules = Interpretation(choices: [.location: "roof"])
        let model = Interpretation(choices: [.location: "outdoors", .floor: "floor_1"])
        let m = TriageMerge.merge(rules, model)
        XCTAssertEqual("roof", m.choices[.location])
        XCTAssertEqual("floor_1", m.choices[.floor])
    }

    // ------------------------------------------------------------------ text and ids
    func testAskIdsRoundTrip() {
        for id in ["conscious@2", "water_rising", "location", "floor"] { XCTAssertEqual(id, Ask.parse(id)?.id) }
        XCTAssertNil(Ask.parse("conscious@9"))
        XCTAssertNil(Ask.parse("nonsense"))
        XCTAssertEqual("conscious@1", Ask.parse("conscious")?.id)
    }

    func testAnswersAreValidQuestionBankValues() {
        let (s, _) = converse(start("hurt", .medical, .rescue, disasters: active(.flood)))
        let r = TriageEngine.result(s)
        XCTAssertLessThanOrEqual(r.answers.count, 3)
        for a in r.answers { XCTAssertTrue(QuestionBank.isValid(a.questionId, a.value)) }
    }

    func testEveryQuestionOptionAndAdvisoryHasTextInEveryLanguage() {
        let subjects = [Subject(1, .mother), Subject(2)]
        for lang in Lang.allCases {
            for f in Fact.allCases {
                let t = TriageBank.question(f, subject: 1, subjects: subjects, lang)
                XCTAssertFalse(t.isEmpty, "\(f) \(lang)")
                XCTAssertFalse(t.contains("{who}"), "\(f) \(lang) placeholder")
            }
            for c in Choice.allCases { XCTAssertFalse(TriageBank.question(c, lang).isEmpty, "\(c) \(lang)") }
            for c in Choice.allCases { for o in Planner.options(c, start("x", .rescue)) { XCTAssertFalse(TriageBank.optionText(o, lang).isEmpty, "\(o) \(lang)") } }
            for r in Relation.allCases { XCTAssertFalse(TriageBank.who([Subject(1, r)], 1, lang).isEmpty) }
            for i in 1...3 { XCTAssertFalse(TriageBank.who([Subject(1), Subject(2), Subject(3)], i, lang).isEmpty) }
            for a in TriageBank.advisories.values {
                XCTAssertFalse(a.title(lang).isEmpty, "\(a.id) \(lang) title")
                XCTAssertTrue(!a.lines(lang).isEmpty && a.lines(lang).allSatisfy { !$0.isEmpty }, "\(a.id) \(lang) lines")
            }
            XCTAssertFalse(TriageBank.sentNote(lang).isEmpty)
        }
        XCTAssertEqual(TriageBank.AdvisoryId.allCases.count, TriageBank.advisories.count)
    }

    func testEveryOptionOnOfferIsRecognisableByItsOwnWords() {
        for c in Choice.allCases { XCTAssertFalse(Options.all(c).isEmpty) }
    }

    func testWarayAndCebuanoGetTagalogAdviceNotEnglish() {
        let cpr = TriageBank.advisories[.cpr]!
        XCTAssertEqual(cpr.lines(.tl), cpr.lines(.war))
        XCTAssertEqual(cpr.lines(.tl), cpr.lines(.ceb))
    }
}
