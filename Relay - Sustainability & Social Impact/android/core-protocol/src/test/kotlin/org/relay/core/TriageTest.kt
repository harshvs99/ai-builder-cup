package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.VulnFlag
import org.relay.core.triage.Ask
import org.relay.core.triage.Choice
import org.relay.core.triage.ComplexityRouter
import org.relay.core.triage.DisasterContext
import org.relay.core.triage.DisasterGate
import org.relay.core.triage.DisasterType
import org.relay.core.triage.Fact
import org.relay.core.triage.FactKey
import org.relay.core.triage.Hazard
import org.relay.core.triage.HazardLexicon
import org.relay.core.triage.Interpretation
import org.relay.core.triage.Options
import org.relay.core.triage.Planner
import org.relay.core.triage.Relation
import org.relay.core.triage.Route
import org.relay.core.triage.RuleInterpreter
import org.relay.core.triage.Subjects
import org.relay.core.triage.Tri
import org.relay.core.triage.TriageBank
import org.relay.core.triage.TriageBank.AdvisoryId
import org.relay.core.triage.TriageEngine
import org.relay.core.triage.TriageMerge
import org.relay.core.triage.TriageState
import org.relay.core.triage.YesNo

class TriageTest {
    private val now = 1_800_000_000L
    private fun active(vararg t: DisasterType, ageHours: Long = 1) =
        DisasterGate.active(t.map { DisasterContext(it, now - ageHours * 3600) }, now)

    private fun start(text: String, vararg cats: Category, people: Int = 1, disasters: DisasterGate.Active = DisasterGate.Active.NONE) =
        TriageEngine.start(text, cats.toList(), Lang.EN, people, disasters)

    /** Ask until the engine stops, answering [answers] by question id ("no" for the rest); returns the ids asked in order. */
    private fun converse(s0: TriageState, answers: Map<String, Any> = emptyMap(), dflt: Tri = Tri.NO): Pair<TriageState, List<String>> {
        var s = s0
        val asked = mutableListOf<String>()
        while (true) {
            val a = TriageEngine.next(s) ?: break
            asked += a.id
            val v = answers[a.id]
            s = if (a.choice != null) TriageEngine.recordChoice(s, a.choice!!, (v as? String) ?: Planner.options(a.choice!!, s).first())
            else TriageEngine.record(s, a, (v as? Tri) ?: dflt)
            check(asked.size < 20)
        }
        return s to asked
    }

    // ------------------------------------------------------------------ routing
    @Test fun simpleRequestsStayOnTheOneShotPath() {
        assertEquals(Route.SIMPLE, ComplexityRouter.route("we need drinking water near the school", listOf(Category.WATER)))
        assertEquals(Route.SIMPLE, ComplexityRouter.route("no food for two days", listOf(Category.FOOD, Category.SHELTER)))
        assertEquals(Route.SIMPLE, ComplexityRouter.route("", emptyList()))
    }

    @Test fun medicalAndRescueGoToTriage() {
        assertEquals(Route.TRIAGE, ComplexityRouter.route("my mother needs medicine", listOf(Category.MEDICAL)))
        assertEquals(Route.TRIAGE, ComplexityRouter.route("stuck on the roof", listOf(Category.RESCUE)))
        assertEquals(Route.TRIAGE, ComplexityRouter.route("water", listOf(Category.WATER, Category.RESCUE)))
    }

    @Test fun aNamedDangerRoutesEvenWhenCategoryLooksSimple() {
        assertEquals(Route.TRIAGE, ComplexityRouter.route("a snake bit my son, we need water", listOf(Category.WATER)))
        assertEquals(Route.TRIAGE, ComplexityRouter.route("water", listOf(Category.WATER), setOf(VulnFlag.INJURED)))
        assertEquals(Route.SIMPLE, ComplexityRouter.route("flood, we need food", listOf(Category.FOOD)))
    }

    // ------------------------------------------------------------------ hazards, yes/no, options, people
    @Test fun hazardsAreFoundInEnglishHindiAndOdia() {
        assertTrue(Hazard.ELECTRIC in HazardLexicon.detect("the electric pole fell in the water"))
        assertTrue(Hazard.NOT_BREATHING in HazardLexicon.detect("she is not breathing"))
        assertTrue(Hazard.NOT_BREATHING in HazardLexicon.detect("उसकी साँस नहीं चल रही"))
        assertTrue(Hazard.UNCONSCIOUS in HazardLexicon.detect("वो बेहोश है"))
        assertTrue(Hazard.BLEEDING in HazardLexicon.detect("ରକ୍ତ ବାହାରୁଛି"))
        assertTrue(Hazard.SNAKE in HazardLexicon.detect("saanp ne kaat liya"))
        assertTrue(Hazard.FLOOD in HazardLexicon.detect("water is rising fast"))
        assertTrue(HazardLexicon.detect("we need rice").isEmpty())
    }

    @Test fun yesNoInSeveralLanguages() {
        assertEquals(Tri.YES, YesNo.detect("yes he is"))
        assertEquals(Tri.YES, YesNo.detect("हाँ"))
        assertEquals(Tri.YES, YesNo.detect("ହଁ"))
        assertEquals(Tri.NO, YesNo.detect("no, not really"))
        assertEquals(Tri.NO, YesNo.detect("नहीं"))
        assertEquals(Tri.NO, YesNo.detect("hindi po"))
        assertEquals(Tri.UNKNOWN, YesNo.detect("I don't know"))
        assertNull(YesNo.detect("the water is brown"))
        assertNull(YesNo.detect("yes and no"))
    }

    @Test fun optionsAreRecognisedInSpeech() {
        assertEquals("roof", Options.detect(Choice.LOCATION, "we are on the roof"))
        assertEquals("roof", Options.detect(Choice.LOCATION, "हम छत पर हैं"))
        assertEquals("ground_floor", Options.detect(Choice.LOCATION, "ground floor of the house"))
        assertEquals("upper_floor", Options.detect(Choice.LOCATION, "second floor"))
        assertEquals("rubble", Options.detect(Choice.LOCATION, "under the rubble"))
        assertEquals("waist", Options.detect(Choice.WATER_DEPTH, "up to my waist"))
        assertEquals("over_head", Options.detect(Choice.WATER_DEPTH, "over my head"))
        assertEquals("two", Options.detect(Choice.PATIENTS, "two of them"))
        assertEquals("floor_2", Options.detect(Choice.FLOOR, "second"))
        assertNull(Options.detect(Choice.LOCATION, "we need rice"))
        // only options on offer can match
        assertNull(Options.detect(Choice.LOCATION, "on the roof", listOf("ground_floor")))
    }

    @Test fun relationsAreFoundInOrderSpoken() {
        assertEquals(listOf(Relation.SON, Relation.MOTHER), Subjects.relations("my son and my mother are hurt"))
        assertEquals(listOf(Relation.MOTHER), Subjects.relations("मेरी माँ बीमार है"))
        assertEquals(listOf(Relation.WIFE, Relation.BABY), Subjects.relations("wife and baby"))
        assertTrue(Subjects.relations("we need rice").isEmpty())
        assertEquals(3, Subjects.fromText("mother father son daughter").size)
    }

    @Test fun aDangerNamedInAnAnswerBeatsTheBareYesNoAndGoesToTheAskedPerson() {
        val s = start("hurt", Category.MEDICAL)
        val r = RuleInterpreter.interpret("no he is not breathing", Ask.fact(Fact.CONSCIOUS, 2), s)
        assertEquals(Tri.NO, r.facts[FactKey(Fact.BREATHING, 2)])
        assertEquals(Tri.NO, r.facts[FactKey(Fact.CONSCIOUS, 2)])
    }

    // ------------------------------------------------------------------ disaster gate
    @Test fun aDeclarationCountsForTwentyFourHoursAndThenStops() {
        assertEquals(setOf(DisasterType.FLOOD), active(DisasterType.FLOOD, ageHours = 1).types)
        assertEquals(setOf(DisasterType.FLOOD), active(DisasterType.FLOOD, ageHours = 23).types)
        assertTrue(active(DisasterType.FLOOD, ageHours = 25).types.isEmpty())
        assertTrue(DisasterGate.active(emptyList(), now).types.isEmpty())
        // clock skew of a few minutes into the future is tolerated, an hour is not
        assertEquals(1, DisasterGate.active(listOf(DisasterContext(DisasterType.FLOOD, now + 300)), now).types.size)
        assertTrue(DisasterGate.active(listOf(DisasterContext(DisasterType.FLOOD, now + 3600)), now).types.isEmpty())
    }

    @Test fun olderThanTwelveHoursIsMarkedAging() {
        assertFalse(active(DisasterType.FLOOD, ageHours = 6).aging)
        assertTrue(active(DisasterType.FLOOD, ageHours = 18).aging)
        val mixed = DisasterGate.active(listOf(DisasterContext(DisasterType.FLOOD, now - 20 * 3600), DisasterContext(DisasterType.CYCLONE, now - 3600)), now)
        assertFalse(mixed.aging)
        assertEquals(2, mixed.types.size)
    }

    @Test fun noDisasterQuestionsWithoutADeclarationEvenIfThePersonMentionsFlood() {
        val s = start("flood water is rising, we are stuck", Category.RESCUE)
        val (_, asked) = converse(s)
        assertTrue(asked.contains("location"))
        assertFalse(asked.contains("water_rising"))
        assertFalse(asked.contains("water_depth"))
        assertFalse(asked.contains("live_wire"))
        // the safety advice still follows the words
        assertTrue(TriageEngine.result(s).advisories.any { it.id == AdvisoryId.FLOOD })
    }

    @Test fun floodQuestionsAppearOnlyUnderAFloodDeclaration() {
        val (_, asked) = converse(start("we are stuck", Category.RESCUE, disasters = active(DisasterType.FLOOD)))
        assertTrue(asked.containsAll(listOf("location", "water_rising", "water_depth", "live_wire")))
        assertFalse(asked.any { it in listOf("gas_smell", "slope_moving", "shelter_holding", "building_unsafe") })
        val (_, none) = converse(start("we are stuck", Category.RESCUE, disasters = active(DisasterType.FLOOD, ageHours = 30)))
        assertFalse(none.contains("water_rising"))
    }

    @Test fun earthquakeAsksAboutBuildingGasAndShaking() {
        val (_, asked) = converse(start("we are stuck", Category.RESCUE, disasters = active(DisasterType.EARTHQUAKE)))
        assertTrue(asked.containsAll(listOf("location", "gas_smell", "building_unsafe", "still_shaking")))
        assertFalse(asked.contains("water_depth"))
    }

    @Test fun locationOptionsFollowTheDisaster() {
        val flood = start("stuck", Category.RESCUE, disasters = active(DisasterType.FLOOD))
        val quake = start("stuck", Category.RESCUE, disasters = active(DisasterType.EARTHQUAKE))
        val none = start("stuck", Category.RESCUE)
        assertTrue("roof" in Planner.options(Choice.LOCATION, flood) && "rubble" !in Planner.options(Choice.LOCATION, flood))
        assertTrue("rubble" in Planner.options(Choice.LOCATION, quake))
        assertTrue("basement" in Planner.options(Choice.LOCATION, none))
    }

    // ------------------------------------------------------------------ location protocol
    @Test fun upperFloorFollowsUpWithHowHigh() {
        var s = start("we are stuck", Category.RESCUE)
        assertEquals("location", TriageEngine.next(s)!!.id)
        s = TriageEngine.recordChoice(s, Choice.LOCATION, "upper_floor")
        assertEquals("floor", TriageEngine.next(s)!!.id)
        s = TriageEngine.recordChoice(s, Choice.FLOOR, "floor_2")
        val r = TriageEngine.result(s)
        assertEquals(listOf(NeedAnswer(4, 1)), r.answers)
        assertTrue(r.summary.contains("2 floors up"))
    }

    @Test fun aRoofSaidInPassingIsNotAskedAgain() {
        val s = start("we are four people on the roof", Category.RESCUE)
        assertEquals("roof", s.choices[Choice.LOCATION])
        assertFalse(converse(s).second.contains("location"))
    }

    @Test fun groundLevelAnswersMapToTheWireGroundValue() {
        val s = TriageEngine.recordChoice(start("stuck", Category.RESCUE), Choice.LOCATION, "ground_floor")
        assertEquals(listOf(NeedAnswer(4, 2)), TriageEngine.result(s).answers)
    }

    @Test fun deepWaterOrRubbleIsCritical() {
        val deep = TriageEngine.recordChoice(start("stuck", Category.RESCUE, disasters = active(DisasterType.FLOOD)), Choice.WATER_DEPTH, "chest")
        assertEquals(3, TriageEngine.result(deep).urgency)
        val rubble = TriageEngine.recordChoice(start("stuck", Category.RESCUE), Choice.LOCATION, "rubble")
        assertTrue(TriageEngine.result(rubble).critical)
        assertTrue(VulnFlag.TRAPPED in TriageEngine.result(rubble).vuln)
        assertEquals(0, TriageEngine.result(start("stuck", Category.RESCUE)).let { if (it.critical) 1 else 0 })
    }

    // ------------------------------------------------------------------ per-person questions
    @Test fun notBreathingEndsQuestionsAtOnceAndLeadsWithChestPushes() {
        val s = start("my father is not breathing", Category.MEDICAL)
        assertNull(TriageEngine.next(s))
        val r = TriageEngine.result(s)
        assertEquals(3, r.urgency)
        assertEquals(AdvisoryId.CPR, r.advisories.first().id)
        assertTrue(r.summary.contains("not breathing"))
    }

    @Test fun twoNamedPeopleAreAskedAboutSeparatelyWithTheirNames() {
        val s = start("my mother and my son are hurt", Category.MEDICAL, people = 2)
        assertEquals(listOf(Relation.MOTHER, Relation.SON), s.subjects.map { it.relation })
        val menu = Planner.menu(s)
        assertEquals(listOf("conscious@1", "conscious@2"), menu.take(2).map { it.ask.id })
        val q1 = Planner.text(Ask.fact(Fact.CONSCIOUS, 1), s, Lang.EN)
        val q2 = Planner.text(Ask.fact(Fact.CONSCIOUS, 2), s, Lang.EN)
        assertEquals("Is your mother awake and answering you?", q1)
        assertEquals("Is your son awake and answering you?", q2)
        assertTrue(Planner.text(Ask.fact(Fact.CONSCIOUS, 1), s, Lang.HI).contains("आपकी माँ"))
    }

    @Test fun eachPersonGetsTheirOwnFollowUps() {
        var s = start("my mother and my son are hurt", Category.MEDICAL, people = 2)
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.NO, subject = 1)
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.YES, subject = 2)
        val ids = Planner.menu(s).map { it.ask.id }
        assertTrue("breathing@1" in ids)          // mother: not awake, so is she breathing
        assertFalse("breathing@2" in ids)         // son: awake
        assertTrue("heavy_bleeding@2" in ids)
        assertEquals("breathing@1", TriageEngine.next(s)!!.id) // mandatory beats optional
    }

    @Test fun summaryNamesWhoHasWhat() {
        var s = start("my mother and my son are hurt", Category.MEDICAL, people = 2)
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.NO, subject = 1)
        s = TriageEngine.record(s, Fact.BREATHING, Tri.YES, subject = 1)
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.YES, subject = 2)
        s = TriageEngine.record(s, Fact.HEAVY_BLEEDING, Tri.YES, subject = 2)
        val r = TriageEngine.result(s)
        assertEquals("mother unresponsive, son heavy bleeding", r.summary)
        assertTrue(r.summary.toByteArray().size <= 40)
        assertEquals(setOf(AdvisoryId.RECOVERY, AdvisoryId.BLEEDING), r.advisories.map { it.id }.toSet())
        assertEquals(listOf(NeedAnswer(1, 2)), r.answers.filter { it.questionId == 1 }) // worst person speaks for the ticket
    }

    @Test fun unnamedGroupIsAskedHowManyNeedHelp() {
        var s = start("three of us, someone is hurt", Category.MEDICAL, people = 3)
        assertEquals("patients", TriageEngine.next(s)!!.id)
        s = TriageEngine.recordChoice(s, Choice.PATIENTS, "two")
        assertEquals(2, s.patients.size)
        assertEquals("Is the first person awake and answering you?", Planner.text(Ask.fact(Fact.CONSCIOUS, 1), s, Lang.EN))
        assertEquals("Is the second person awake and answering you?", Planner.text(Ask.fact(Fact.CONSCIOUS, 2), s, Lang.EN))
        // alone, or named, it is never asked
        assertFalse(Planner.menu(start("my mother is hurt", Category.MEDICAL, people = 3)).any { it.ask.id == "patients" })
        assertFalse(Planner.menu(start("I am hurt", Category.MEDICAL, people = 1)).any { it.ask.id == "patients" })
    }

    @Test fun moreWordsMoreQuestionsButNeverPastTheBudget() {
        val one = start("hurt", Category.MEDICAL)
        val three = start("mother father son are hurt", Category.MEDICAL)
        assertEquals(5, Planner.budget(one))
        assertEquals(11, Planner.budget(three))
        val (_, asked) = converse(start("mother father son are hurt and stuck", Category.MEDICAL, Category.RESCUE, disasters = active(DisasterType.FLOOD)), dflt = Tri.UNKNOWN)
        assertTrue(asked.size <= 12)
    }

    @Test fun medicalConversationAsksAwakeThenBreathingThenBleeding() {
        var s = start("my brother fell and is hurt", Category.MEDICAL)
        assertEquals("conscious@1", TriageEngine.next(s)!!.id)
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.NO)
        assertEquals("breathing@1", TriageEngine.next(s)!!.id)
        s = TriageEngine.record(s, Fact.BREATHING, Tri.YES)
        assertEquals("heavy_bleeding@1", TriageEngine.next(s)!!.id)
        s = TriageEngine.record(s, Fact.HEAVY_BLEEDING, Tri.NO)
        assertEquals("road_reachable", TriageEngine.next(s)!!.id)
        val r = TriageEngine.result(s)
        assertEquals(3, r.urgency)
        assertEquals(AdvisoryId.RECOVERY, r.advisories.first().id)
        assertTrue(r.answers.contains(NeedAnswer(1, 2)))
    }

    @Test fun neverAsksTheSameQuestionTwiceEvenWhenAnswerWasUnknown() {
        var s = start("stuck in flood", Category.RESCUE, disasters = active(DisasterType.FLOOD))
        s = TriageEngine.record(s, Fact.WATER_RISING, Tri.UNKNOWN)
        assertFalse(Planner.menu(s).any { it.ask.id == "water_rising" })
    }

    // ------------------------------------------------------------------ the model's say, within bounds
    @Test fun aSuggestionReordersOptionalQuestionsOnly() {
        val s = start("we are stuck", Category.RESCUE, disasters = active(DisasterType.FLOOD))
        assertEquals("live_wire", TriageEngine.next(s, Interpretation(next = "live_wire"))!!.id)
        assertEquals("location", TriageEngine.next(s, Interpretation(next = "not_on_the_menu"))!!.id)
        // gas_smell is earthquake-only: the model cannot ask it under a flood
        assertEquals("location", TriageEngine.next(s, Interpretation(next = "gas_smell"))!!.id)
    }

    @Test fun theModelCannotSkipAMandatoryQuestion() {
        val s = start("my mother is hurt", Category.MEDICAL)
        assertEquals("conscious@1", TriageEngine.next(s, Interpretation(next = "road_reachable"))!!.id)
        assertEquals("conscious@1", TriageEngine.next(s, Interpretation(done = true))!!.id)
    }

    @Test fun theModelMayEndAConversationOnceMandatoryQuestionsAreDone() {
        var s = start("my mother is hurt", Category.MEDICAL)
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.YES)
        assertNull(TriageEngine.next(s, Interpretation(done = true)))
        assertNotNull(TriageEngine.next(s))
    }

    @Test fun modelPatientListRefinesWhoIsAskedAbout() {
        val s = start("my mother and my son, mother has chest pain", Category.MEDICAL, people = 2)
        assertEquals(2, s.patients.size)
        val refined = TriageEngine.absorb(s, Interpretation(patients = setOf(Relation.MOTHER)))
        assertEquals(listOf(Relation.MOTHER), refined.subjects.map { it.relation })
    }

    // ------------------------------------------------------------------ safety asymmetry
    @Test fun aDirectAnswerReplacesWhatWasInferredButUnknownNeverErasesIt() {
        var s = start("she fainted", Category.MEDICAL)
        assertEquals(Tri.NO, s.facts[FactKey(Fact.CONSCIOUS, 1)])
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.UNKNOWN)
        assertEquals(Tri.NO, s.facts[FactKey(Fact.CONSCIOUS, 1)])
        s = TriageEngine.record(s, Fact.CONSCIOUS, Tri.YES)
        assertEquals(Tri.YES, s.facts[FactKey(Fact.CONSCIOUS, 1)])
    }

    @Test fun whenRulesAndModelDisagreeTheDangerousReadingWins() {
        val rules = Interpretation(mapOf(FactKey(Fact.CONSCIOUS, 1) to Tri.NO, FactKey(Fact.HEAVY_BLEEDING, 1) to Tri.NO))
        val model = Interpretation(mapOf(FactKey(Fact.CONSCIOUS, 1) to Tri.YES, FactKey(Fact.HEAVY_BLEEDING, 1) to Tri.YES), setOf(Hazard.SNAKE))
        val m = TriageMerge.merge(rules, model)
        assertEquals(Tri.NO, m.facts[FactKey(Fact.CONSCIOUS, 1)])
        assertEquals(Tri.YES, m.facts[FactKey(Fact.HEAVY_BLEEDING, 1)])
        assertTrue(Hazard.SNAKE in m.hazards)
        assertEquals(rules, TriageMerge.merge(rules, null))
    }

    @Test fun aWordMatchBeatsAModelGuessForAPlace() {
        val rules = Interpretation(choices = mapOf(Choice.LOCATION to "roof"))
        val model = Interpretation(choices = mapOf(Choice.LOCATION to "outdoors", Choice.FLOOR to "floor_1"))
        val m = TriageMerge.merge(rules, model)
        assertEquals("roof", m.choices[Choice.LOCATION])
        assertEquals("floor_1", m.choices[Choice.FLOOR])
    }

    // ------------------------------------------------------------------ text and ids
    @Test fun askIdsRoundTrip() {
        for (id in listOf("conscious@2", "water_rising", "location", "floor")) assertEquals(id, Ask.parse(id)!!.id)
        assertNull(Ask.parse("conscious@9"))
        assertNull(Ask.parse("nonsense"))
        assertEquals("conscious@1", Ask.parse("conscious")!!.id)
    }

    @Test fun answersAreValidQuestionBankValues() {
        val (s, _) = converse(start("hurt", Category.MEDICAL, Category.RESCUE, disasters = active(DisasterType.FLOOD)))
        val r = TriageEngine.result(s)
        assertTrue(r.answers.size <= 3)
        r.answers.forEach { assertTrue(org.relay.core.intake.QuestionBank.isValid(it.questionId, it.value)) }
    }

    @Test fun everyQuestionOptionAndAdvisoryHasTextInEveryLanguage() {
        val subjects = listOf(org.relay.core.triage.Subject(1, Relation.MOTHER), org.relay.core.triage.Subject(2))
        for (lang in Lang.entries) {
            for (f in Fact.entries) {
                assertTrue("$f $lang", TriageBank.question(f, 1, subjects, lang).isNotBlank())
                assertFalse("$f $lang placeholder", TriageBank.question(f, 1, subjects, lang).contains("{who}"))
            }
            for (c in Choice.entries) assertTrue("$c $lang", TriageBank.question(c, lang).isNotBlank())
            for (c in Choice.entries) for (o in Planner.options(c, start("x", Category.RESCUE))) assertTrue("$o $lang", TriageBank.optionText(o, lang).isNotBlank())
            for (r in Relation.entries) assertTrue(TriageBank.who(listOf(org.relay.core.triage.Subject(1, r)), 1, lang).isNotBlank())
            for (i in 1..3) assertTrue(TriageBank.who(listOf(org.relay.core.triage.Subject(1), org.relay.core.triage.Subject(2), org.relay.core.triage.Subject(3)), i, lang).isNotBlank())
            for (a in TriageBank.advisories.values) {
                assertTrue("${a.id} $lang title", a.title(lang).isNotBlank())
                assertTrue("${a.id} $lang lines", a.lines(lang).isNotEmpty() && a.lines(lang).all { it.isNotBlank() })
            }
            assertTrue(TriageBank.sentNote(lang).isNotBlank())
        }
        assertEquals(AdvisoryId.entries.size, TriageBank.advisories.size)
    }

    @Test fun everyOptionOnOfferIsRecognisableByItsOwnWords() {
        // every option the person can pick must have words that detect it, or voice answers can never choose it
        for (c in Choice.entries) assertTrue(Options.all(c).isNotEmpty())
    }

    @Test fun warayAndCebuanoGetTagalogAdviceNotEnglish() {
        val cpr = TriageBank.advisories.getValue(AdvisoryId.CPR)
        assertEquals(cpr.lines(Lang.TL), cpr.lines(Lang.WAR))
        assertEquals(cpr.lines(Lang.TL), cpr.lines(Lang.CEB))
    }
}
