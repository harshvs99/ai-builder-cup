package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ui.ask.AskDraft
import org.relay.app.ui.ask.AskLogic
import org.relay.app.ui.ask.AskViewModel
import org.relay.app.ui.i18n.stringsFor
import org.relay.core.intake.IntakeDraft
import org.relay.core.intake.IntakeEngine
import org.relay.core.intake.IntakeField
import org.relay.core.intake.QuestionBank
import org.relay.core.intake.RuleIntake
import org.relay.core.nlu.NeedDraft
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.VulnFlag

class AskLogicTest {
    private val en = stringsFor(Lang.EN).common
    private val hi = stringsFor(Lang.HI).common

    private fun draftFrom(text: String) = AskLogic.applyIntake(AskDraft(), RuleIntake.extract(text, Lang.EN))

    @Test fun draftFromIntake() {
        val d = draftFrom("we are 6 people, children and elderly, no drinking water, near the school")
        assertEquals(Category.WATER, d.category)
        assertEquals(6, d.people)
        assertTrue(VulnFlag.CHILD in d.vulnFlags)
        assertTrue(VulnFlag.ELDERLY in d.vulnFlags)
        assertTrue(d.landmark.isNotBlank())
    }

    @Test fun nothingUnderstoodIsNullCategory() {
        val d = draftFrom("hello")
        assertNull(d.category)
        assertFalse(AskLogic.hasContent(d, ""))
        assertTrue(AskLogic.hasContent(d, "hello"))
        assertTrue(AskLogic.hasContent(AskLogic.setCategory(d, Category.OTHER), ""))
    }

    @Test fun userEditsBeatLaterExtraction() {
        var d = draftFrom("need water near the school")
        d = AskLogic.setCategory(d, Category.MEDICAL)
        d = AskLogic.setLandmark(d, "temple")
        d = AskLogic.setPeople(d, 4)
        d = AskLogic.setUrgency(d, 3)
        val later = AskLogic.applyIntake(d, RuleIntake.extract("we need food, 9 people, near the market", Lang.EN))
        assertEquals(Category.MEDICAL, later.category)
        assertEquals("temple", later.landmark)
        assertEquals(4, later.people)
        assertEquals(3, later.urgency)
    }

    @Test fun clearingACategoryChoiceHandsItBackToExtraction() {
        var d = AskLogic.setCategory(draftFrom("need water"), Category.FOOD)
        assertEquals(Category.FOOD, d.category)
        d = AskLogic.applyIntake(AskLogic.setCategory(d, null), RuleIntake.extract("need water", Lang.EN))
        assertEquals(Category.WATER, d.category)
        assertFalse(IntakeField.CATEGORY in d.edited)
    }

    @Test fun byteLimitsAreEnforcedOnCodePointBoundaries() {
        assertEquals(16, AskLogic.byteLength(AskLogic.setItem(AskDraft(), "x".repeat(40)).item))
        assertEquals(24, AskLogic.byteLength(AskLogic.setLandmark(AskDraft(), "y".repeat(40)).landmark))
        assertEquals(40, AskLogic.byteLength(AskLogic.setSummary(AskDraft(), "z".repeat(60)).summary))
        // Devanagari is 3 bytes per character: 13 characters = 39 bytes, the 14th would make 42.
        val hindi = AskLogic.setSummary(AskDraft(), "क".repeat(20)).summary
        assertEquals(13, hindi.length)
        assertEquals(39, AskLogic.byteLength(hindi))
        // Extraction results are clamped too.
        val long = NeedDraft(Category.WATER, 1, 1, emptySet(), "i".repeat(30), "l".repeat(30), "s".repeat(80), Lang.EN)
        val d = AskLogic.applyIntake(AskDraft(), IntakeDraft(long, emptyList()))
        assertEquals(16, AskLogic.byteLength(d.item))
        assertEquals(24, AskLogic.byteLength(d.landmark))
        assertEquals(40, AskLogic.byteLength(d.summary))
    }

    @Test fun charsLeftUsesTheWidthOfWhatWasTyped() {
        assertEquals(40, AskLogic.charsLeft("", 40))
        assertEquals(35, AskLogic.charsLeft("hello", 40))
        assertEquals(0, AskLogic.charsLeft("a".repeat(40), 40))
        assertEquals(1, AskLogic.charsLeft("क".repeat(12), 40)) // 36 bytes used, 4 left, 3 per character
    }

    @Test fun urgencyIsClampedAndSetByHand() {
        assertEquals(3, AskLogic.setUrgency(AskDraft(), 9).urgency)
        assertEquals(0, AskLogic.setUrgency(AskDraft(), -2).urgency)
        val d = draftFrom("someone is trapped, please save us, life in danger")
        assertTrue(d.urgency >= 2)
        // The four words, in order, in every language.
        for (lang in listOf(Lang.EN, Lang.HI, Lang.OR, Lang.TL, Lang.WAR)) {
            val words = (0..3).map { stringsFor(lang).common.urgencyWord(it) }
            assertEquals(4, words.toSet().size)
        }
        assertEquals("Life at risk", en.urgencyWord(3))
    }

    @Test fun peopleLine() {
        assertEquals("6 · children, elderly", AskLogic.peopleLine(en, 6, setOf(VulnFlag.ELDERLY, VulnFlag.CHILD)))
        assertEquals("1 · trapped", AskLogic.peopleLine(en, 1, setOf(VulnFlag.TRAPPED)))
        assertEquals("3", AskLogic.peopleLine(en, 3, emptySet()))
        assertEquals("2 · बच्चे, बुज़ुर्ग", AskLogic.peopleLine(hi, 2, setOf(VulnFlag.CHILD, VulnFlag.ELDERLY)))
    }

    @Test fun needWordsPrefersASpecificItemElseTheCategory() {
        assertEquals("Water", AskLogic.needWords(en, AskDraft(category = Category.WATER, item = "water")))
        assertEquals("पानी", AskLogic.needWords(hi, AskDraft(category = Category.WATER, item = "water")))
        assertEquals("insulin", AskLogic.needWords(en, AskDraft(category = Category.MEDICAL, item = "insulin")))
        assertEquals("", AskLogic.needWords(en, AskDraft()))
    }

    @Test fun understoodChipsFollowTheDraft() {
        val near = stringsFor(Lang.EN).ask::nearLandmark
        assertTrue(AskLogic.understood(en, near, AskDraft()).isEmpty())
        val chips = AskLogic.understood(
            en, near,
            AskDraft(category = Category.WATER, people = 6, landmark = "primary school", summary = "Pump broken since yesterday"),
        )
        assertEquals(listOf("Water", "6 people", "Near primary school", "Pump broken since yesterday"), chips)
    }

    @Test fun questionsFollowTheCategoryAndStaleAnswersDrop() {
        var d = AskLogic.setCategory(AskDraft(), Category.RESCUE)
        assertEquals(listOf(3, 4), d.questionIds)
        d = AskLogic.setAnswer(d, 3, 1)
        d = AskLogic.setAnswer(d, 99, 1) // not asked: ignored
        d = AskLogic.setAnswer(d, 4, 7) // not a value of that question: ignored
        assertEquals(mapOf(3 to 1), d.answers)
        d = AskLogic.setCategory(d, Category.MEDICAL)
        assertTrue(d.answers.isEmpty())
        // Choosing the same answer again clears it.
        d = AskLogic.setAnswer(AskLogic.setAnswer(d, 1, 2), 1, 2)
        assertTrue(d.answers.isEmpty())
    }

    @Test fun answersMapToV2Bytes() {
        var d = AskLogic.setCategory(AskDraft(), Category.RESCUE)
        assertTrue(AskLogic.wireAnswers(d).isEmpty())
        d = AskLogic.setAnswer(AskLogic.setAnswer(d, 4, 1), 3, 2)
        // Asking order, not tap order.
        assertEquals(listOf(NeedAnswer(3, 2), NeedAnswer(4, 1)), AskLogic.wireAnswers(d))
        assertEquals(listOf((3 shl 3) or 2, (4 shl 3) or 1), AskLogic.wireAnswers(d).map { it.wireByte })
        assertTrue(AskLogic.wireAnswers(d).size <= QuestionBank.MAX_ANSWERS)
    }

    @Test fun builtNeedEncodesV1WithoutAnswersAndV2With() {
        val vm = AskViewModel()
        vm.onTranscript("rescue us, we are on the roof, 4 people, water is rising", Lang.EN)
        vm.setCategory(Category.RESCUE)
        val id = ByteArray(8) { it.toByte() }
        val node = ByteArray(8) { (it + 8).toByte() }
        val v1 = vm.buildNeed(id, node, 1_700_000_000L, Lang.HI, null)
        assertEquals(NeedCodec.VERSION, NeedCodec.encode(v1)[0].toInt())
        assertEquals(0, v1.locAcc)
        assertEquals(0, v1.latE5)
        assertEquals(Lang.HI, v1.lang)
        vm.setAnswer(3, 1)
        val v2 = vm.buildNeed(id, node, 1_700_000_000L, Lang.HI, Triple(19.8, 85.83, 2))
        assertEquals(NeedCodec.VERSION_WITH_ANSWERS, NeedCodec.encode(v2)[0].toInt())
        assertEquals(listOf(NeedAnswer(3, 1)), v2.answers)
        assertEquals(2, v2.locAcc)
        assertTrue(v2.latE5 != 0)
        assertEquals(v2, NeedCodec.decode(NeedCodec.encode(v2)))
    }

    @Test fun chipOnlyRequestStillHasAnItemOnTheWire() {
        val vm = AskViewModel()
        vm.toggleCategory(Category.WATER)
        val n = vm.buildNeed(ByteArray(8), ByteArray(8), 1L, Lang.EN, null)
        assertEquals(Category.WATER, n.category)
        assertEquals("food and water", n.item)
        // Nothing chosen at all still builds (category Other).
        assertEquals(Category.OTHER, AskViewModel().buildNeed(ByteArray(8), ByteArray(8), 1L, Lang.EN, null).category)
    }

    @Test fun viewModelReextractsOnEveryTranscriptAndChipBeatsExtraction() {
        val vm = AskViewModel()
        vm.onTranscript("need wat", Lang.EN)
        vm.onTranscript("need water", Lang.EN)
        assertEquals(Category.WATER, vm.state.value.draft.category)
        vm.toggleCategory(Category.MEDICAL)
        vm.onTranscript("need water for 5 people", Lang.EN)
        assertEquals(listOf(Category.WATER, Category.MEDICAL), vm.state.value.draft.categories)
        assertEquals(5, vm.state.value.draft.people)
        // Tapping a chosen chip again removes it.
        vm.toggleCategory(Category.MEDICAL)
        assertEquals(listOf(Category.WATER), vm.state.value.draft.categories)
    }

    @Test fun waterAndFoodAreOneChipAndOneTicket() {
        val vm = AskViewModel()
        vm.toggleCategory(Category.WATER)
        vm.toggleCategory(Category.FOOD)
        assertEquals(emptyList<Category>(), vm.state.value.draft.categories)
        vm.toggleCategory(Category.FOOD)
        assertEquals(listOf(Category.FOOD), vm.state.value.draft.categories)
        vm.toggleCategory(Category.WATER)
        assertEquals(emptyList<Category>(), vm.state.value.draft.categories)
        vm.toggleCategory(Category.WATER)
        val n = vm.buildNeeds({ ByteArray(8) }, ByteArray(8), 1L, Lang.EN, null)
        assertEquals(1, n.size)
        assertEquals("food and water", n[0].item)
        assertEquals(Category.WATER, n[0].category)
    }

    @Test fun tappingAnUnderstoodChipConfirmsItInsteadOfDroppingIt() {
        val vm = AskViewModel()
        vm.onTranscript("need water", Lang.EN)
        vm.toggleCategory(Category.WATER)
        assertEquals(listOf(Category.WATER), vm.state.value.draft.categories)
        assertTrue(IntakeField.CATEGORY in vm.state.value.draft.edited)
        vm.toggleCategory(Category.WATER)
        vm.onTranscript("need water", Lang.EN)
        assertEquals(listOf(Category.WATER), vm.state.value.draft.categories)
    }

    @Test fun editorCanCorrectFoodToWaterAndItSticks() {
        val vm = AskViewModel()
        vm.onTranscript("we need rice for 4 people", Lang.EN)
        assertEquals(Category.FOOD, vm.state.value.draft.category)
        assertTrue(vm.state.value.draft.item.isNotEmpty())
        vm.pickCategory(Category.WATER)
        assertEquals(listOf(Category.WATER), vm.state.value.draft.categories)
        assertEquals("", vm.state.value.draft.item)
        vm.onTranscript("we need rice for 4 people near the school", Lang.EN)
        assertEquals(listOf(Category.WATER), vm.state.value.draft.categories)
        assertEquals("", vm.state.value.draft.item)
        val need = vm.buildNeed(ByteArray(8), ByteArray(8), 1L, Lang.EN, null)
        assertEquals(Category.WATER, need.category)
        assertEquals("food and water", need.item)
    }

    @Test fun removingThePrimaryPromotesTheNext() {
        val vm = AskViewModel()
        vm.toggleCategory(Category.RESCUE)
        vm.toggleCategory(Category.MEDICAL)
        vm.toggleCategory(Category.RESCUE)
        assertEquals(listOf(Category.MEDICAL), vm.state.value.draft.categories)
    }

    @Test fun severalGroupsBuildOneNeedEachSharingTheRest() {
        val vm = AskViewModel()
        vm.toggleCategory(Category.WATER)
        vm.toggleCategory(Category.MEDICAL)
        vm.toggleCategory(Category.RESCUE)
        vm.setPeople(4); vm.setLandmark("school"); vm.setUrgency(3); vm.setItem("insulin")
        var n = 0
        val needs = vm.buildNeeds({ ByteArray(8) { (++n).toByte() } }, ByteArray(8), 1L, Lang.EN, null)
        assertEquals(listOf(Category.WATER, Category.MEDICAL, Category.RESCUE), needs.map { it.category })
        assertEquals(3, needs.map { it.msgIdHex }.toSet().size)
        assertEquals(listOf("insulin", "medicine", "rescue"), needs.map { it.item })
        assertTrue(needs.all { it.people == 4 && it.landmark == "school" && it.urgency == 3 })
        needs.forEach { assertEquals(it, NeedCodec.decode(NeedCodec.encode(it))) }
    }

    @Test fun oneOrNoCategoryStillBuildsExactlyOneNeed() {
        val vm = AskViewModel()
        assertEquals(1, vm.buildNeeds({ ByteArray(8) }, ByteArray(8), 1L, Lang.EN, null).size)
        vm.toggleCategory(Category.WATER)
        assertEquals(1, vm.buildNeeds({ ByteArray(8) }, ByteArray(8), 1L, Lang.EN, null).size)
    }

    @Test fun answersGoOnlyToTheCategoryTheirQuestionBelongsTo() {
        val vm = AskViewModel()
        vm.toggleCategory(Category.RESCUE)
        vm.toggleCategory(Category.MEDICAL)
        vm.setAnswer(3, 1)
        val needs = vm.buildNeeds({ ByteArray(8) }, ByteArray(8), 1L, Lang.EN, null)
        assertEquals(listOf(NeedAnswer(3, 1)), needs[0].answers)
        assertTrue(needs[1].answers.isEmpty())
    }

    @Test fun prefillAppliesOnceAndSurvivesExtraction() {
        val vm = AskViewModel()
        vm.prefill(Category.MEDICAL, "bus stand")
        vm.prefill(Category.FOOD, "elsewhere")
        vm.onTranscript("need water near the school", Lang.EN)
        assertEquals(Category.MEDICAL, vm.state.value.draft.category)
        assertEquals("bus stand", vm.state.value.draft.landmark)
    }

    @Test fun engineIsInjectable() {
        val fake = object : IntakeEngine {
            override fun extract(text: String, langHint: Lang?): IntakeDraft =
                IntakeDraft(NeedDraft(Category.SHELTER, 2, 7, setOf(VulnFlag.PREGNANT), "tarp", "church", "from fake", Lang.EN), listOf(7))
        }
        val vm = AskViewModel(fake)
        vm.onTranscript("anything", Lang.EN)
        val d = vm.state.value.draft
        assertEquals(Category.SHELTER, d.category)
        assertEquals(7, d.people)
        assertEquals("tarp", d.item)
        assertEquals(listOf(7), d.questionIds)
    }
}
