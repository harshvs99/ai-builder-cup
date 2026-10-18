package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.intake.FieldMerge
import org.relay.core.intake.FieldSource
import org.relay.core.intake.IntakeField
import org.relay.core.intake.ModelFields
import org.relay.core.intake.QuestionBank
import org.relay.core.intake.RuleIntake
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.VulnFlag

class IntakeTest {

    // ------------------------------------------------------------------ question bank
    @Test fun bankHasTheAgreedQuestionsPerCategory() {
        fun keys(c: Category) = QuestionBank.forCategory(c).map { it.key }
        assertEquals(listOf("conscious", "road_reachable"), keys(Category.MEDICAL))
        assertEquals(listOf("water_rising", "roof_or_ground"), keys(Category.RESCUE))
        assertEquals(listOf("children_or_elderly", "how_long"), keys(Category.WATER))
        assertEquals(listOf("children_or_elderly", "how_long"), keys(Category.FOOD))
        assertEquals(listOf("house_damaged"), keys(Category.SHELTER))
        assertEquals(listOf("last_seen_today"), keys(Category.MISSING_PERSON))
        assertEquals(emptyList<String>(), keys(Category.OTHER))
    }

    @Test fun everyQuestionAndOptionHasTextInEveryBankLanguage() {
        for (q in QuestionBank.all) {
            assertTrue(q.id in 1..QuestionBank.MAX_ID)
            assertEquals("value 0 is unknown", "unknown", q.option(0)!!.key)
            for (lang in listOf(Lang.EN, Lang.HI, Lang.OR, Lang.TL, Lang.WAR)) {
                assertTrue("${q.key} $lang", q.text(lang).isNotBlank())
                for (o in q.options) assertTrue("${q.key}/${o.key} $lang", o.label(lang).isNotBlank())
            }
            // Cebuano has no text yet: falls back to English
            assertEquals(q.text(Lang.EN), q.text(Lang.CEB))
            assertEquals(q.options.size, q.options.map { it.value }.toSet().size)
            assertTrue(q.options.all { it.value in 0..7 })
        }
        assertEquals(QuestionBank.all.size, QuestionBank.all.map { it.id }.toSet().size)
        assertEquals(QuestionBank.all.size, QuestionBank.all.map { it.key }.toSet().size)
    }

    @Test fun selectionIsCappedOrderedAndSkipsWhatTheTextAnswered() {
        assertEquals(listOf(1, 2), QuestionBank.select(Category.MEDICAL, emptySet()))
        assertEquals(listOf(5, 6), QuestionBank.select(Category.FOOD, emptySet()))
        assertEquals(listOf(6), QuestionBank.select(Category.FOOD, setOf(VulnFlag.CHILD)))
        assertEquals(emptyList<Int>(), QuestionBank.select(Category.OTHER, emptySet()))
        assertTrue(Category.entries.all { QuestionBank.select(it, emptySet()).size <= 3 })
    }

    // ------------------------------------------------------------------ engine
    @Test fun ruleIntakePicksQuestionsFromTheDetectedCategory() {
        val d = RuleIntake.extract("my mother is unconscious and needs a doctor, near the school")
        assertEquals(Category.MEDICAL, d.need.category)
        assertEquals(listOf(1, 2), d.questionIds)
        assertEquals(d.need, RuleExtractor.extract("my mother is unconscious and needs a doctor, near the school"))
        assertTrue(d.answers.isEmpty())
        assertTrue(d.sources.values.all { it == FieldSource.RULES })
        assertEquals(emptyList<Int>(), RuleIntake.extract("hello there nice weather").questionIds)
    }

    @Test fun applyAnswerRecordsReplacesAndIgnoresStrangers() {
        val d0 = RuleIntake.extract("five people stuck on the roof, water rising")
        assertEquals(listOf(3, 4), d0.questionIds)
        val d1 = RuleIntake.applyAnswer(d0, 4, 1)
        assertEquals(listOf(NeedAnswer(4, 1)), d1.answers)
        assertEquals(listOf(3), d1.pending)
        val d2 = RuleIntake.applyAnswer(d1, 3, 1)
        assertEquals(listOf(NeedAnswer(3, 1), NeedAnswer(4, 1)), d2.answers) // in asking order
        assertTrue(d2.pending.isEmpty())
        val d3 = RuleIntake.applyAnswer(d2, 4, 2) // changed their mind
        assertEquals(listOf(NeedAnswer(3, 1), NeedAnswer(4, 2)), d3.answers)
        assertSame(d3, RuleIntake.applyAnswer(d3, 1, 1)) // conscious was not asked
        assertSame(d3, RuleIntake.applyAnswer(d3, 3, 3)) // water_rising has no value 3
        assertSame(d3, RuleIntake.applyAnswer(d3, 99, 0))
    }

    @Test fun answeredDraftBecomesAV2NeedAndBackWithoutAnswersStaysV1() {
        val d = RuleIntake.applyAnswer(RuleIntake.extract("pregnant woman in labour, need ambulance"), 1, 2)
        fun need(x: org.relay.core.intake.IntakeDraft) = x.toNeed(ByteArray(8) { 1 }, ByteArray(8) { 2 }, 1_790_000_000, 2029612, 8582451, 1)
        val withAnswer = NeedCodec.encode(need(d))
        assertEquals(2, withAnswer[0].toInt())
        assertEquals(listOf(NeedAnswer(1, 2)), NeedCodec.decode(withAnswer)!!.answers)
        val without = NeedCodec.encode(need(RuleIntake.extract("pregnant woman in labour, need ambulance")))
        assertEquals(1, without[0].toInt())
        assertEquals(without.size + 3, withAnswer.size)
    }

    // ------------------------------------------------------------------ wire
    private val base = sampleNeed(ByteArray(8) { 7 })

    @Test fun answersRoundTripInEverySlotCount() {
        for (answers in listOf(
            listOf(NeedAnswer(1, 0)), listOf(NeedAnswer(1, 2), NeedAnswer(2, 1)),
            listOf(NeedAnswer(8, 1), NeedAnswer(6, 3), NeedAnswer(3, 2)),
        )) {
            val n = base.copy(answers = answers)
            val p = NeedCodec.encode(n)
            assertEquals(2, p[0].toInt())
            assertEquals(NeedCodec.encode(base.copy(answers = emptyList())).size + 3, p.size)
            assertEquals(n, NeedCodec.decode(p))
        }
        assertEquals(base, NeedCodec.decode(NeedCodec.encode(base)))
    }

    @Test fun answerByteLayoutIsIdHighFiveBitsValueLowThree() {
        val p = NeedCodec.encode(base.copy(answers = listOf(NeedAnswer(1, 2), NeedAnswer(6, 3))))
        assertEquals(listOf(0x0A, 0x33, 0x00), p.takeLast(3).map { it.toInt() and 0xFF })
    }

    @Test fun encoderRejectsBadAnswers() {
        fun bad(answers: List<NeedAnswer>) {
            try { NeedCodec.encode(base.copy(answers = answers)); fail("should reject $answers") } catch (_: IllegalArgumentException) {}
        }
        bad(listOf(NeedAnswer(1, 3)))                      // value not defined for the question
        bad(listOf(NeedAnswer(9, 0)))                      // not in the bank
        bad(listOf(NeedAnswer(0, 0)))
        bad(listOf(NeedAnswer(1, 1), NeedAnswer(1, 2)))    // duplicate
        bad(List(4) { NeedAnswer(it + 1, 0) })             // more than 3
    }

    @Test fun decoderRejectsNonCanonicalAnswerBytes() {
        val v2 = NeedCodec.encode(base.copy(answers = listOf(NeedAnswer(1, 2))))
        fun withTail(vararg b: Int) = v2.copyOf(v2.size - 3) + ByteArray(3) { b[it].toByte() }
        assertNotNull(NeedCodec.decode(withTail(0x0A, 0, 0)))
        assertNull(NeedCodec.decode(withTail(0, 0x0A, 0)))     // answer after an empty slot
        assertNull(NeedCodec.decode(withTail(0, 0, 0)))        // v2 without answers
        assertNull(NeedCodec.decode(withTail(0x0A, 0x0A, 0)))  // duplicate
        assertNull(NeedCodec.decode(withTail(0x0B, 0, 0)))     // conscious=3
        assertNull(NeedCodec.decode(withTail(0xF9, 0, 0)))     // id 31
        assertNull(NeedCodec.decode(v2 + byteArrayOf(0)))
        assertNull(NeedCodec.decode(v2.copyOf(v2.size - 1)))
    }

    @Test fun worstCaseV2FrameFitsTheLinkLimit() {
        val s16 = "i".repeat(16); val s24 = "l".repeat(24); val s40 = "s".repeat(40)
        val n = base.copy(item = s16, landmark = s24, summary = s40, people = 255, vulnFlags = 0xFF,
            answers = listOf(NeedAnswer(8, 1), NeedAnswer(7, 2), NeedAnswer(1, 0)))
        val p = NeedCodec.encode(n)
        assertEquals(121, p.size)
        assertEquals(NeedCodec.MAX_PAYLOAD_V2, p.size)
        val frame = FrameCodec.encode(Frame(type = FrameTypes.NEED, ttl = 7, timestampMs = T0, senderId = ByteArray(8), payload = p))
        assertEquals(143, frame.size)
        assertTrue(frame.size <= 182)
    }

    @Test fun v2NeedIsDeliveredRelayedAndAnswersSurvive() {
        val net = TestNet()
        net.chain(listOf("a", "b", "c"))
        val need = sampleNeed(net.node("a").id).copy(answers = listOf(NeedAnswer(1, 2), NeedAnswer(2, 1)))
        net.node("a").node.originateNeed(need)
        net.runUntilIdle()
        assertEquals(listOf(need), net.node("c").needsDelivered)
    }

    // ------------------------------------------------------------------ FieldMerge
    private val rules = RuleExtractor.extract("My father is unconscious near the school, we are 3 people")

    @Test fun perEvalPolicyTakesModelCategoryItemSummaryAndRulesTheRest() {
        val model = ModelFields(
            category = Category.RESCUE, item = "stretcher", landmark = "temple", urgency = 1, people = 9,
            vulnFlags = emptySet(), summary = "man collapsed",
        )
        val d = FieldMerge.PER_EVAL.toIntake(rules, model)
        assertEquals(Category.RESCUE, d.need.category)
        assertEquals("stretcher", d.need.item)
        assertEquals("man collapsed", d.need.summary)
        assertEquals(rules.landmark, d.need.landmark)
        assertEquals(rules.urgency, d.need.urgency)
        assertEquals(rules.people, d.need.people)
        assertEquals(rules.vulnFlags, d.need.vulnFlags)
        assertEquals(FieldSource.MODEL, d.sources[IntakeField.CATEGORY])
        assertEquals(FieldSource.MODEL, d.sources[IntakeField.ITEM])
        assertEquals(FieldSource.RULES, d.sources[IntakeField.LANDMARK])
        // questions follow the merged category, not the rules' one
        assertEquals(QuestionBank.select(Category.RESCUE, rules.vulnFlags), d.questionIds)
    }

    @Test fun anyFieldCanBeFlippedAndMissingOrInvalidModelValuesFallBackToRules() {
        val policy = FieldMerge(mapOf(IntakeField.URGENCY to FieldSource.MODEL, IntakeField.LANDMARK to FieldSource.MODEL))
        val (a, srcA) = policy.merge(rules, ModelFields(urgency = 0, landmark = "  "))
        assertEquals(0, a.urgency)
        assertEquals(FieldSource.MODEL, srcA[IntakeField.URGENCY])
        assertEquals(rules.landmark, a.landmark)                       // blank model text is "nothing"
        assertEquals(FieldSource.RULES, srcA[IntakeField.LANDMARK])
        val (b, _) = policy.merge(rules, ModelFields(urgency = 7, people = 0, category = null))
        assertEquals(rules.urgency, b.urgency)                         // out of range: ignored
        assertEquals(rules.people, b.people)
        val (c, srcC) = FieldMerge.PER_EVAL.merge(rules, null)
        assertEquals(rules, c)
        assertTrue(srcC.values.all { it == FieldSource.RULES })
        assertEquals(rules, FieldMerge.RULES_ONLY.merge(rules, ModelFields(category = Category.FOOD, item = "x")).first)
    }

    @Test fun preferredSourceThatHasNothingYieldsToTheOther() {
        val none = RuleExtractor.extract("hello there nice weather") // OTHER, no item, no landmark
        val (m, src) = FieldMerge.RULES_ONLY.merge(none, ModelFields(category = Category.FOOD, item = "rice", landmark = "temple"))
        assertEquals(Category.FOOD, m.category)
        assertEquals("rice", m.item)
        assertEquals("temple", m.landmark)
        assertEquals(FieldSource.MODEL, src[IntakeField.CATEGORY])
    }

    @Test fun modelTextIsClampedToWireLimits() {
        val (m, _) = FieldMerge.PER_EVAL.merge(rules, ModelFields(item = "x".repeat(60), summary = "ଅ".repeat(60)))
        assertTrue(m.item.toByteArray().size <= 16)
        assertTrue(m.summary.toByteArray().size <= 40)
    }
}
