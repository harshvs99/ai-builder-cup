package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.gemma.TriagePrompt
import org.relay.app.ui.ask.AskDraft
import org.relay.app.ui.ask.AskLogic
import org.relay.core.intake.IntakeField
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.VulnFlag
import org.relay.core.triage.Ask
import org.relay.core.triage.Choice
import org.relay.core.triage.DisasterContext
import org.relay.core.triage.DisasterGate
import org.relay.core.triage.DisasterType
import org.relay.core.triage.Fact
import org.relay.core.triage.FactKey
import org.relay.core.triage.Hazard
import org.relay.core.triage.Relation
import org.relay.core.triage.Tri
import org.relay.core.triage.TriageEngine

class TriageTest {
    // ------------------------------------------------------------------ model reply parsing
    @Test fun parsesAFencedReplyAndIgnoresUnknownKeys() {
        val raw = "```json\n" + """{"answer":"no","facts":{"conscious@2":"no","heavy_bleeding@1":"yes","bogus":"yes","conscious@9":"no"},"choices":{"location":"roof","water_depth":"swimming"},"hazards":["BLEEDING","NOPE"],"patients":["MOTHER","ALIEN"],"people":2,"landmark":"school","next":"breathing@2"}""" + "\n```"
        val i = TriagePrompt.parse(raw, Ask.fact(Fact.CONSCIOUS, 1))!!
        assertEquals(Tri.NO, i.facts[FactKey(Fact.CONSCIOUS, 2)])
        assertEquals(Tri.NO, i.facts[FactKey(Fact.CONSCIOUS, 1)])   // from "answer", for the person asked about
        assertEquals(Tri.YES, i.facts[FactKey(Fact.HEAVY_BLEEDING, 1)])
        assertEquals(mapOf(Choice.LOCATION to "roof"), i.choices)  // an option key that does not exist is dropped
        assertEquals(setOf(Hazard.BLEEDING), i.hazards)
        assertEquals(setOf(Relation.MOTHER), i.patients)
        assertEquals(2, i.people)
        assertEquals("school", i.landmark)
        assertEquals("breathing@2", i.next)
        assertEquals(3, i.facts.size)
    }

    @Test fun aChoiceAnswerFillsTheAskedChoice() {
        val i = TriagePrompt.parse("""{"answer":"waist"}""", Ask.choice(Choice.WATER_DEPTH))!!
        assertEquals("waist", i.choices[Choice.WATER_DEPTH])
        assertNull(TriagePrompt.parse("""{"answer":"deep-ish"}""", Ask.choice(Choice.WATER_DEPTH)))
    }

    @Test fun theAnswerFieldFillsTheAskedFactWhenFactsLackIt() {
        val i = TriagePrompt.parse("""{"answer":"yes"}""", Ask.fact(Fact.WATER_RISING))!!
        assertEquals(Tri.YES, i.facts[FactKey(Fact.WATER_RISING, 0)])
    }

    @Test fun anExplicitFactBeatsTheAnswerField() {
        val i = TriagePrompt.parse("""{"answer":"yes","facts":{"conscious@1":"no"}}""", Ask.fact(Fact.CONSCIOUS, 1))!!
        assertEquals(Tri.NO, i.facts[FactKey(Fact.CONSCIOUS, 1)])
    }

    @Test fun doneAloneIsAUsableReply() {
        assertTrue(TriagePrompt.parse("""{"done":true}""", null)!!.done)
    }

    @Test fun garbageAndEmptyRepliesGiveNothing() {
        assertNull(TriagePrompt.parse("I am sorry, I cannot help with that.", Ask.fact(Fact.CONSCIOUS, 1)))
        assertNull(TriagePrompt.parse("{}", Ask.fact(Fact.CONSCIOUS, 1)))
        assertNull(TriagePrompt.parse("""{"answer":"maybe","facts":{"conscious@1":"perhaps"},"people":0,"landmark":"","next":"made_up"}""", Ask.fact(Fact.CONSCIOUS, 1)))
        assertNull(TriagePrompt.parse("""{"people":9000}""", null))
    }

    // ------------------------------------------------------------------ the prompt carries the decision architecture
    private val now = 1_800_000_000L

    @Test fun theTurnMessageListsOnlyTheAllowedMenuAndNamesThePeople() {
        val s = TriageEngine.start("my mother and my son are hurt", listOf(Category.MEDICAL), Lang.EN, 2)
        val m = TriagePrompt.turn("haan", Ask.fact(Fact.CONSCIOUS, 1), s, "hi")
        assertTrue(m.contains("1 your mother; 2 your son"))
        assertTrue(m.contains("conscious@2 [MUST] Is your son awake and answering you?"))
        assertTrue(m.contains("Disaster: none declared"))
        assertFalse(m.contains("water_rising"))       // not on the menu without a declaration
        assertTrue(m.contains("haan"))
    }

    @Test fun aDeclaredDisasterAppearsWithItsAgeAndUnlocksItsQuestions() {
        val d = DisasterGate.active(listOf(DisasterContext(DisasterType.FLOOD, now - 14 * 3600)), now)
        val s = TriageEngine.start("stuck", listOf(Category.RESCUE), Lang.EN, 1, d)
        val m = TriagePrompt.turn("ok", null, s, "en", mapOf("flood" to 14L))
        assertTrue(m.contains("flood (declared 14 h ago)"))
        assertTrue(m.contains("more than 12 hours old"))
        assertTrue(m.contains("water_rising"))
        assertTrue(m.contains("location Where exactly are you? options: roof/upper_floor/ground_floor/vehicle/outdoors"))
    }

    @Test fun theSystemPromptStatesTheRulesTheCodeEnforces() {
        val p = TriagePrompt.SYSTEM
        assertTrue(p.contains("MENU") && p.contains("MUST") && p.contains("never bring up floods"))
        assertTrue(p.contains("separately"))
    }

    // ------------------------------------------------------------------ the disaster store
    @Test fun storeRoundTripsAndKeepsTheNewestPerType() {
        val l = listOf(DisasterContext(DisasterType.FLOOD, 100), DisasterContext(DisasterType.CYCLONE, 200))
        assertEquals(l, DisasterStore.decode(DisasterStore.encode(l)))
        assertTrue(DisasterStore.decode("").isEmpty())
        assertTrue(DisasterStore.decode("garbage,flood:x,volcano:5").isEmpty())
    }

    // ------------------------------------------------------------------ into the draft
    private fun triaged(text: String, vararg cats: Category, answer: (org.relay.core.triage.TriageState) -> org.relay.core.triage.TriageState = { it }) =
        TriageEngine.result(answer(TriageEngine.start(text, cats.toList(), Lang.EN)))

    @Test fun triageRaisesUrgencyAddsFlagsAndAnswers() {
        val r = triaged("my wife is bleeding, we are stuck", Category.MEDICAL, Category.RESCUE) {
            TriageEngine.record(TriageEngine.record(TriageEngine.record(it, Fact.CONSCIOUS, Tri.YES), Fact.HEAVY_BLEEDING, Tri.YES), Fact.ROAD_REACHABLE, Tri.NO)
        }
        val d = AskLogic.applyTriage(AskDraft(category = Category.MEDICAL, urgency = 1), r)
        assertEquals(Category.MEDICAL, d.category)
        assertEquals(listOf(Category.RESCUE), d.extraCategories)
        assertEquals(3, d.urgency)
        assertTrue(VulnFlag.INJURED in d.vulnFlags)
        assertEquals(1, d.answers[1])   // conscious: yes
        assertEquals(2, d.answers[2])   // road reachable: no
        assertTrue(IntakeField.CATEGORY in d.edited && IntakeField.URGENCY in d.edited)
        assertTrue(d.summary.isNotEmpty() && AskLogic.byteLength(d.summary) <= AskLogic.MAX_SUMMARY)
    }

    @Test fun triageNeverLowersUrgencyOrOverwritesHandEditedFields() {
        val r = triaged("child has fever", Category.MEDICAL)
        val d = AskLogic.applyTriage(
            AskDraft(category = Category.MEDICAL, urgency = 3, summary = "typed note", edited = setOf(IntakeField.SUMMARY)),
            r,
        )
        assertEquals(3, d.urgency)
        assertEquals("typed note", d.summary)
    }

    @Test fun otherNeedsTheSpeakerAskedForSurviveTriage() {
        val r = triaged("hurt, and we need water", Category.MEDICAL)
        val d = AskLogic.applyTriage(AskDraft(category = Category.WATER, extraCategories = listOf(Category.MEDICAL)), r)
        assertEquals(Category.MEDICAL, d.category)
        assertEquals(listOf(Category.WATER), d.extraCategories)
        assertEquals(2, AskLogic.perCategory(d).size)
    }

    @Test fun waterAndFoodStayOneTicketBesideTheTriagedOne() {
        val r = triaged("injured and hungry", Category.MEDICAL)
        val d = AskLogic.applyTriage(AskDraft(category = Category.FOOD, extraCategories = listOf(Category.WATER, Category.MEDICAL)), r)
        assertEquals(Category.MEDICAL, d.category)
        assertEquals(1, d.extraCategories.size)
    }

    @Test fun answersFromTriageAreOnlyKeptForTheFirstCategoryQuestions() {
        // rescue-only request: the rescue questions (3 and 4) are the ones that go out
        val r = triaged("stuck on the roof", Category.RESCUE) {
            TriageEngine.record(it, Fact.WATER_RISING, Tri.YES)
        }
        val d = AskLogic.applyTriage(AskDraft(category = Category.RESCUE), r)
        assertEquals(listOf(NeedAnswer(3, 1), NeedAnswer(4, 1)), AskLogic.wireAnswers(d))
    }

    @Test fun nothingTriagedLeavesTheDraftAlone() {
        val r = triaged("hello", Category.OTHER)
        val d = AskDraft(category = Category.WATER)
        assertEquals(d, AskLogic.applyTriage(d, r))
    }

    @Test fun followUpFlagsFollowTheChosenCategories() {
        val water = AskLogic.relevantFlags(listOf(Category.WATER))
        assertTrue(VulnFlag.CHILD in water && VulnFlag.ELDERLY in water && VulnFlag.PREGNANT !in water)
        val medical = AskLogic.relevantFlags(listOf(Category.MEDICAL))
        assertTrue(VulnFlag.PREGNANT in medical && VulnFlag.INJURED in medical)
        assertTrue(VulnFlag.TRAPPED in AskLogic.relevantFlags(listOf(Category.RESCUE)))
    }

    @Test fun peopleInputAppearsOnlyOnceThereIsSomethingToAttachItTo() {
        assertTrue(!AskLogic.showDetails(AskDraft(), ""))
        assertTrue(AskLogic.showDetails(AskDraft(category = Category.FOOD), ""))
        assertTrue(AskLogic.showDetails(AskDraft(), "we are hungry"))
    }
}
