import XCTest
@testable import RelayCore

final class IntakeTests: XCTestCase {
    private let intake = RuleIntake()

    // ------------------------------------------------------------------ question bank
    func testBankHasTheAgreedQuestionsPerCategory() {
        func keys(_ c: Category) -> [String] { QuestionBank.forCategory(c).map { $0.key } }
        XCTAssertEqual(["conscious", "road_reachable"], keys(.medical))
        XCTAssertEqual(["water_rising", "roof_or_ground"], keys(.rescue))
        XCTAssertEqual(["children_or_elderly", "how_long"], keys(.water))
        XCTAssertEqual(["children_or_elderly", "how_long"], keys(.food))
        XCTAssertEqual(["house_damaged"], keys(.shelter))
        XCTAssertEqual(["last_seen_today"], keys(.missingPerson))
        XCTAssertEqual([], keys(.other))
    }

    func testEveryQuestionAndOptionHasTextInEveryBankLanguage() {
        for q in QuestionBank.all {
            XCTAssertTrue((1...QuestionBank.maxId).contains(q.id))
            XCTAssertEqual("unknown", q.option(0)!.key, "value 0 is unknown")
            for lang in [Lang.en, .hi, .or, .tl, .war] {
                XCTAssertFalse(q.text(lang).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "\(q.key) \(lang)")
                for o in q.options {
                    XCTAssertFalse(o.label(lang).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "\(q.key)/\(o.key) \(lang)")
                }
            }
            // Cebuano has no text yet: falls back to English
            XCTAssertEqual(q.text(.en), q.text(.ceb))
            XCTAssertEqual(q.options.count, Set(q.options.map { $0.value }).count)
            XCTAssertTrue(q.options.allSatisfy { (0...7).contains($0.value) })
        }
        XCTAssertEqual(QuestionBank.all.count, Set(QuestionBank.all.map { $0.id }).count)
        XCTAssertEqual(QuestionBank.all.count, Set(QuestionBank.all.map { $0.key }).count)
    }

    func testSelectionIsCappedOrderedAndSkipsWhatTheTextAnswered() {
        XCTAssertEqual([1, 2], QuestionBank.select(.medical, []))
        XCTAssertEqual([5, 6], QuestionBank.select(.food, []))
        XCTAssertEqual([6], QuestionBank.select(.food, [.child]))
        XCTAssertEqual([], QuestionBank.select(.other, []))
        XCTAssertTrue(Category.allCases.allSatisfy { QuestionBank.select($0, []).count <= 3 })
    }

    // ------------------------------------------------------------------ engine
    func testRuleIntakePicksQuestionsFromTheDetectedCategory() {
        let d = intake.extract("my mother is unconscious and needs a doctor, near the school")
        XCTAssertEqual(.medical, d.need.category)
        XCTAssertEqual([1, 2], d.questionIds)
        XCTAssertEqual(d.need, RuleExtractor.extract("my mother is unconscious and needs a doctor, near the school"))
        XCTAssertTrue(d.answers.isEmpty)
        XCTAssertTrue(d.sources.values.allSatisfy { $0 == .rules })
        XCTAssertEqual([], intake.extract("hello there nice weather").questionIds)
    }

    func testApplyAnswerRecordsReplacesAndIgnoresStrangers() {
        let d0 = intake.extract("five people stuck on the roof, water rising")
        XCTAssertEqual([3, 4], d0.questionIds)
        let d1 = intake.applyAnswer(d0, questionId: 4, answerValue: 1)
        XCTAssertEqual([NeedAnswer(4, 1)], d1.answers)
        XCTAssertEqual([3], d1.pending)
        let d2 = intake.applyAnswer(d1, questionId: 3, answerValue: 1)
        XCTAssertEqual([NeedAnswer(3, 1), NeedAnswer(4, 1)], d2.answers) // in asking order
        XCTAssertTrue(d2.pending.isEmpty)
        let d3 = intake.applyAnswer(d2, questionId: 4, answerValue: 2) // changed their mind
        XCTAssertEqual([NeedAnswer(3, 1), NeedAnswer(4, 2)], d3.answers)
        XCTAssertEqual(d3, intake.applyAnswer(d3, questionId: 1, answerValue: 1)) // conscious was not asked
        XCTAssertEqual(d3, intake.applyAnswer(d3, questionId: 3, answerValue: 3)) // water_rising has no value 3
        XCTAssertEqual(d3, intake.applyAnswer(d3, questionId: 99, answerValue: 0))
    }

    func testAnsweredDraftBecomesAV2NeedAndBackWithoutAnswersStaysV1() throws {
        let d = intake.applyAnswer(intake.extract("pregnant woman in labour, need ambulance"), questionId: 1, answerValue: 2)
        func need(_ x: IntakeDraft) -> Need {
            x.toNeed(msgId: [UInt8](repeating: 1, count: 8), originNode: [UInt8](repeating: 2, count: 8),
                     createdAtSec: 1_790_000_000, latE5: 2029612, lonE5: 8582451, locAcc: 1)
        }
        let withAnswer = try NeedCodec.encode(need(d))
        XCTAssertEqual(2, withAnswer[0])
        XCTAssertEqual([NeedAnswer(1, 2)], NeedCodec.decode(withAnswer)!.answers)
        let without = try NeedCodec.encode(need(intake.extract("pregnant woman in labour, need ambulance")))
        XCTAssertEqual(1, without[0])
        XCTAssertEqual(without.count + 3, withAnswer.count)
    }

    // ------------------------------------------------------------------ wire
    private let base = sampleNeed(originNode: [UInt8](repeating: 7, count: 8))

    private func with(_ n: Need, answers: [NeedAnswer]) -> Need { var c = n; c.answers = answers; return c }

    func testAnswersRoundTripInEverySlotCount() throws {
        for answers in [
            [NeedAnswer(1, 0)], [NeedAnswer(1, 2), NeedAnswer(2, 1)],
            [NeedAnswer(8, 1), NeedAnswer(6, 3), NeedAnswer(3, 2)],
        ] {
            let n = with(base, answers: answers)
            let p = try NeedCodec.encode(n)
            XCTAssertEqual(2, p[0])
            XCTAssertEqual(try NeedCodec.encode(with(base, answers: [])).count + 3, p.count)
            XCTAssertEqual(n, NeedCodec.decode(p))
        }
        XCTAssertEqual(base, NeedCodec.decode(try NeedCodec.encode(base)))
    }

    func testAnswerByteLayoutIsIdHighFiveBitsValueLowThree() throws {
        let p = try NeedCodec.encode(with(base, answers: [NeedAnswer(1, 2), NeedAnswer(6, 3)]))
        XCTAssertEqual([0x0A, 0x33, 0x00], Array(p.suffix(3)).map { Int($0) })
    }

    func testEncoderRejectsBadAnswers() {
        func bad(_ answers: [NeedAnswer], _ what: String) {
            XCTAssertThrowsError(try NeedCodec.encode(with(base, answers: answers)), "should reject \(what)")
        }
        bad([NeedAnswer(1, 3)], "value not defined for the question")
        bad([NeedAnswer(9, 0)], "not in the bank")
        bad([NeedAnswer(0, 0)], "id 0")
        bad([NeedAnswer(1, 1), NeedAnswer(1, 2)], "duplicate")
        bad((0..<4).map { NeedAnswer($0 + 1, 0) }, "more than 3")
    }

    func testDecoderRejectsNonCanonicalAnswerBytes() throws {
        let v2 = try NeedCodec.encode(with(base, answers: [NeedAnswer(1, 2)]))
        func withTail(_ b: UInt8...) -> [UInt8] { Array(v2.dropLast(3)) + b }
        XCTAssertNotNil(NeedCodec.decode(withTail(0x0A, 0, 0)))
        XCTAssertNil(NeedCodec.decode(withTail(0, 0x0A, 0)))     // answer after an empty slot
        XCTAssertNil(NeedCodec.decode(withTail(0, 0, 0)))        // v2 without answers
        XCTAssertNil(NeedCodec.decode(withTail(0x0A, 0x0A, 0)))  // duplicate
        XCTAssertNil(NeedCodec.decode(withTail(0x0B, 0, 0)))     // conscious=3
        XCTAssertNil(NeedCodec.decode(withTail(0xF9, 0, 0)))     // id 31
        XCTAssertNil(NeedCodec.decode(v2 + [0]))
        XCTAssertNil(NeedCodec.decode(Array(v2.dropLast(1))))
    }

    func testWorstCaseV2FrameFitsTheLinkLimit() throws {
        var n = base
        n.item = String(repeating: "i", count: 16); n.landmark = String(repeating: "l", count: 24)
        n.summary = String(repeating: "s", count: 40); n.people = 255; n.vulnFlags = 0xFF
        n.answers = [NeedAnswer(8, 1), NeedAnswer(7, 2), NeedAnswer(1, 0)]
        let p = try NeedCodec.encode(n)
        XCTAssertEqual(121, p.count)
        XCTAssertEqual(NeedCodec.maxPayloadV2, p.count)
        let frame = try FrameCodec.encode(Frame(type: FrameTypes.need, ttl: 7, timestampMs: T0,
                                                senderId: [UInt8](repeating: 0, count: 8), payload: p))
        XCTAssertEqual(143, frame.count)
        XCTAssertLessThanOrEqual(frame.count, 182)
    }

    func testV2NeedIsDeliveredRelayedAndAnswersSurvive() throws {
        let net = TestNet()
        net.chain(["a", "b", "c"])
        let need = with(sampleNeed(originNode: net.node("a").id), answers: [NeedAnswer(1, 2), NeedAnswer(2, 1)])
        try net.node("a").node.originateNeed(need)
        net.runUntilIdle()
        XCTAssertEqual([need], net.node("c").needsDelivered)
    }

    // ------------------------------------------------------------------ FieldMerge
    private let rules = RuleExtractor.extract("My father is unconscious near the school, we are 3 people")

    func testPerEvalPolicyTakesModelCategoryItemSummaryAndRulesTheRest() {
        let model = ModelFields(
            category: .rescue, item: "stretcher", landmark: "temple", urgency: 1, people: 9,
            vulnFlags: [], summary: "man collapsed")
        let d = FieldMerge.perEval.toIntake(rules, model)
        XCTAssertEqual(.rescue, d.need.category)
        XCTAssertEqual("stretcher", d.need.item)
        XCTAssertEqual("man collapsed", d.need.summary)
        XCTAssertEqual(rules.landmark, d.need.landmark)
        XCTAssertEqual(rules.urgency, d.need.urgency)
        XCTAssertEqual(rules.people, d.need.people)
        XCTAssertEqual(rules.vulnFlags, d.need.vulnFlags)
        XCTAssertEqual(.model, d.sources[.category])
        XCTAssertEqual(.model, d.sources[.item])
        XCTAssertEqual(.rules, d.sources[.landmark])
        // questions follow the merged category, not the rules' one
        XCTAssertEqual(QuestionBank.select(.rescue, rules.vulnFlags), d.questionIds)
    }

    func testAnyFieldCanBeFlippedAndMissingOrInvalidModelValuesFallBackToRules() {
        let policy = FieldMerge([.urgency: .model, .landmark: .model])
        let (a, srcA) = policy.merge(rules, ModelFields(landmark: "  ", urgency: 0))
        XCTAssertEqual(0, a.urgency)
        XCTAssertEqual(.model, srcA[.urgency])
        XCTAssertEqual(rules.landmark, a.landmark)                       // blank model text is "nothing"
        XCTAssertEqual(.rules, srcA[.landmark])
        let (b, _) = policy.merge(rules, ModelFields(category: nil, urgency: 7, people: 0))
        XCTAssertEqual(rules.urgency, b.urgency)                         // out of range: ignored
        XCTAssertEqual(rules.people, b.people)
        let (c, srcC) = FieldMerge.perEval.merge(rules, nil)
        XCTAssertEqual(rules, c)
        XCTAssertTrue(srcC.values.allSatisfy { $0 == .rules })
        XCTAssertEqual(rules, FieldMerge.rulesOnly.merge(rules, ModelFields(category: .food, item: "x")).0)
    }

    func testPreferredSourceThatHasNothingYieldsToTheOther() {
        let none = RuleExtractor.extract("hello there nice weather") // OTHER, no item, no landmark
        let (m, src) = FieldMerge.rulesOnly.merge(none, ModelFields(category: .food, item: "rice", landmark: "temple"))
        XCTAssertEqual(.food, m.category)
        XCTAssertEqual("rice", m.item)
        XCTAssertEqual("temple", m.landmark)
        XCTAssertEqual(.model, src[.category])
    }

    func testModelTextIsClampedToWireLimits() {
        let (m, _) = FieldMerge.perEval.merge(rules, ModelFields(item: String(repeating: "x", count: 60), summary: String(repeating: "ଅ", count: 60)))
        XCTAssertLessThanOrEqual(m.item.utf8.count, 16)
        XCTAssertLessThanOrEqual(m.summary.utf8.count, 40)
    }
}
