package org.relay.core.triage

import org.relay.core.nlu.Lex
import org.relay.core.nlu.normalizeText
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.VulnFlag

// ====================================================================== routing

/** Which path a request takes after it is spoken. */
enum class Route { SIMPLE, TRIAGE }

/**
 * Splits simple requests (water, food, shelter: the existing one-shot path) from ones that need a conversation
 * (a medical problem, a rescue, a named danger). Rules only, so it costs nothing and never needs the model; the model is
 * loaded only after this says TRIAGE.
 */
object ComplexityRouter {
    fun route(text: String, categories: Collection<Category>, vuln: Set<VulnFlag> = emptySet()): Route = when {
        categories.any { it == Category.MEDICAL || it == Category.RESCUE } -> Route.TRIAGE
        VulnFlag.INJURED in vuln || VulnFlag.TRAPPED in vuln -> Route.TRIAGE
        HazardLexicon.detect(text).any { it.lifeThreatening } -> Route.TRIAGE
        else -> Route.SIMPLE
    }
}

// ====================================================================== what one utterance told us

/**
 * What was understood from one utterance. [facts] and [choices] hold only what the person said or clearly meant; absent =
 * not said. [people], [landmark], [patients], [next] and [done] come from the model only: who needs medical help (relations
 * from the fixed list), and which allowed question it would ask next, or that it has heard enough.
 */
data class Interpretation(
    val facts: Map<FactKey, Tri> = emptyMap(),
    val hazards: Set<Hazard> = emptySet(),
    val people: Int? = null,
    val landmark: String? = null,
    val choices: Map<Choice, String> = emptyMap(),
    val patients: Set<Relation> = emptySet(),
    val next: String? = null,
    val done: Boolean = false,
    /** The model's view of which place advice fits ([PlaceAdvice] keys, most useful first); null = no view. */
    val advice: List<String>? = null,
)

/** Yes / no in the app's languages, for answers to a yes-or-no question. Null when the words say neither, or both. */
object YesNo {
    private val yes = Lex(
        "yes", "yeah", "yep", "yup", "ya", "sure", "correct", "still",
        "haan", "han", "haa", "ha", "ji", "jee", "हाँ", "हां", "हा", "जी", "ହଁ", "ହାଁ", "hau", "oo", "opo", "oho", "oo po", "tama")
    private val no = Lex(
        "no", "nope", "nah", "not", "never", "nothing", "none", "cannot", "can't", "isn't", "aren't", "doesn't", "don't", "won't", "stopped",
        "nahi", "nahin", "nai", "na", "naa", "नहीं", "नही", "ना", "न", "ନା", "ନାହିଁ", "hindi", "wala", "dili", "diri", "waray")
    private val unsure = Lex("don't know", "do not know", "dont know", "not sure", "unsure", "pata nahi", "पता नहीं", "ଜଣା ନାହିଁ", "hindi alam", "ewan", "diri aram")

    fun detect(text: String): Tri? {
        val t = try { normalizeText(text) } catch (_: Exception) { return null }
        if (t.isEmpty()) return null
        if (unsure.matches(t)) return Tri.UNKNOWN
        val y = yes.matches(t)
        val n = no.matches(t)
        return when { y && !n -> Tri.YES; n && !y -> Tri.NO; else -> null }
    }
}

/** Reads an utterance with rules alone. This is always run, even when a model is also used, so a model can never hide a danger. */
object RuleInterpreter {
    fun interpret(text: String, asked: Ask?, s: TriageState? = null): Interpretation {
        val hazards = HazardLexicon.detect(text)
        val facts = LinkedHashMap<FactKey, Tri>()
        val choices = LinkedHashMap<Choice, String>()
        // A danger named about "him" belongs to the person just asked about, else the first patient.
        val who = asked?.takeIf { it.fact?.scope == Scope.PERSON }?.subject ?: 1
        if (Hazard.NOT_BREATHING in hazards) facts[FactKey(Fact.BREATHING, who)] = Tri.NO
        if (Hazard.UNCONSCIOUS in hazards) facts[FactKey(Fact.CONSCIOUS, who)] = Tri.NO

        // A bare yes/no belongs to the question just asked, unless the sentence itself named the danger (above).
        asked?.fact?.let { f ->
            val key = FactKey(f, asked.subject)
            if (key !in facts) YesNo.detect(text)?.let { facts[key] = it }
        }
        asked?.choice?.let { c ->
            val offered = s?.let { Planner.options(c, it) }
            Options.detect(c, text, offered)?.let { choices[c] = it }
        }
        // Said in passing: only the unambiguous places (a roof is a roof; "road" or "open" could be anything).
        if (Choice.LOCATION !in choices) Options.detect(Choice.LOCATION, text, listOf("roof", "rubble", "basement"))?.let { choices[Choice.LOCATION] = it }
        return Interpretation(facts, hazards, choices = choices)
    }
}

/** Combines the rules' reading with a model's. The dangerous reading wins when they disagree. */
object TriageMerge {
    fun merge(rules: Interpretation, model: Interpretation?): Interpretation {
        if (model == null) return rules
        val facts = LinkedHashMap<FactKey, Tri>(rules.facts)
        for ((k, m) in model.facts) {
            val r = facts[k]
            facts[k] = when {
                r == null || r == Tri.UNKNOWN -> m
                m == Tri.UNKNOWN || m == r -> r
                k.fact.noIsDangerous -> Tri.NO
                k.fact.yesIsDangerous -> Tri.YES
                else -> r
            }
        }
        // A word list that matched is better evidence of a place or a depth than a model's reading; the model fills the gaps.
        val choices = LinkedHashMap(model.choices).apply { putAll(rules.choices) }
        return Interpretation(
            facts = facts, hazards = rules.hazards + model.hazards, people = model.people ?: rules.people,
            landmark = model.landmark ?: rules.landmark, choices = choices, patients = rules.patients + model.patients,
            next = model.next, done = model.done, advice = model.advice,
        )
    }
}

// ====================================================================== the conversation

data class TriageState(
    val lang: Lang,
    val medical: Boolean,
    val rescue: Boolean,
    /** The people being asked about; empty = one unnamed patient, until [Choice.PATIENTS] or a relation says otherwise. */
    val subjects: List<Subject> = emptyList(),
    val facts: Map<FactKey, Tri> = emptyMap(),
    val choices: Map<Choice, String> = emptyMap(),
    val hazards: Set<Hazard> = emptySet(),
    /** Ids of the questions asked ([Ask.id]), in order. */
    val asked: List<String> = emptyList(),
    val people: Int? = null,
    val landmark: String? = null,
    /** How many people the request is for (from New request), to know whether to ask how many need help. */
    val peopleHint: Int = 1,
    /** Disasters the control room declared and that are still inside the freshness window; empty = no disaster-specific questions. */
    val disasters: Set<DisasterType> = emptySet(),
    /** Every declaration is older than [DisasterGate.FRESH_SEC]: the premise may have changed. */
    val aging: Boolean = false,
    /** What the offline map says about where the person is; null = no map, or no good enough position. */
    val map: MapContext? = null,
    /** The model's latest pick of place advice ([Interpretation.advice]); null = it has given none. */
    val placeView: List<String>? = null,
) {
    val patients: List<Subject> get() = subjects.ifEmpty { listOf(Subject(1)) }
}

data class TriageResult(
    /** Medical first when both; the app turns each into its own ticket. */
    val categories: List<Category>,
    /** 0..3; the app never lowers a higher urgency the request already had. */
    val urgency: Int,
    val vuln: Set<VulnFlag>,
    val people: Int?,
    val landmark: String?,
    /** Wire answers (QuestionBank ids), at most 3, for the first category. */
    val answers: List<NeedAnswer>,
    /** English, at most 40 bytes, for the control room. */
    val summary: String,
    /** Highest priority first. */
    val advisories: List<TriageBank.Advisory>,
    /** Places from the offline map worth knowing (shelter, hospital, water to keep away from); after the first aid. */
    val places: List<PlaceLine>,
    val facts: Map<FactKey, Tri>,
    val choices: Map<Choice, String>,
    val hazards: Set<Hazard>,
    val subjects: List<Subject>,
) {
    /** A danger to life now: the screen shows the send button first and says why. */
    val critical: Boolean get() = urgency >= 3
}

object TriageEngine {
    /** Begin from the first thing the person said (all of it) and the categories already understood. */
    fun start(
        text: String, categories: Collection<Category>, lang: Lang,
        peopleHint: Int = 1, disasters: DisasterGate.Active = DisasterGate.Active.NONE, map: MapContext? = null,
    ): TriageState {
        val first = RuleInterpreter.interpret(text, null)
        val medicalByWords = first.hazards.any { it in medicalHazards }
        val subjects = Subjects.fromText(text)
        return absorb(
            TriageState(
                lang = lang,
                medical = Category.MEDICAL in categories || medicalByWords,
                rescue = Category.RESCUE in categories || first.hazards.any { it in rescueHazards },
                subjects = subjects, peopleHint = peopleHint, disasters = disasters.types, aging = disasters.aging,
                map = map?.takeUnless { it.isEmpty },
            ),
            first,
        )
    }

    private val medicalHazards = setOf(Hazard.NOT_BREATHING, Hazard.UNCONSCIOUS, Hazard.BLEEDING, Hazard.SNAKE, Hazard.LABOUR)
    private val rescueHazards = setOf(Hazard.TRAPPED)

    /** The next question, or null when the conversation should end and the advice be given. */
    fun next(s: TriageState, heard: Interpretation? = null): Ask? = Planner.pick(s, heard?.next, heard?.done ?: false)

    /** The person answered yes/no question [ask]: a direct answer replaces what was inferred. UNKNOWN never replaces a known fact. */
    fun record(s: TriageState, ask: Ask, answer: Tri, heard: Interpretation = Interpretation()): TriageState {
        val fact = ask.fact ?: return s
        val key = FactKey(fact, ask.subject)
        val facts = LinkedHashMap(s.facts)
        if (answer != Tri.UNKNOWN || key !in facts) facts[key] = answer
        var next = s.copy(facts = facts, asked = (s.asked + ask.id).distinct())
        if (fact == Fact.ANYONE_HURT && answer == Tri.YES) next = next.copy(medical = true)
        return absorb(next, heard.copy(facts = heard.facts - key))
    }

    /** Convenience for the common single-patient / scene case. */
    fun record(s: TriageState, fact: Fact, answer: Tri, heard: Interpretation = Interpretation(), subject: Int = 1): TriageState =
        record(s, Ask.fact(fact, subject), answer, heard)

    /** The person picked [option] for a choice. */
    fun recordChoice(s: TriageState, choice: Choice, option: String, heard: Interpretation = Interpretation()): TriageState {
        val choices = LinkedHashMap(s.choices)
        choices[choice] = option
        var next = s.copy(choices = choices, asked = (s.asked + choice.key).distinct())
        if (choice == Choice.PATIENTS) next = next.copy(subjects = patientsFor(next.subjects, option))
        return absorb(next, heard.copy(choices = heard.choices - choice))
    }

    /** Mark a question as asked without an answer ("I don't know" to a choice, or unclear twice). */
    fun skip(s: TriageState, ask: Ask): TriageState = s.copy(asked = (s.asked + ask.id).distinct())

    private fun patientsFor(existing: List<Subject>, option: String): List<Subject> {
        val n = when (option) { "one" -> 1; "two" -> 2; else -> 3 }
        val named = existing.take(n)
        return named + (named.size + 1..n).map { Subject(it) }
    }

    /** Fold in what an utterance said on the side (a danger, a fact, a place): the dangerous reading wins over what is known. */
    fun absorb(s: TriageState, heard: Interpretation): TriageState {
        val facts = LinkedHashMap(s.facts)
        for ((k, v) in heard.facts) {
            val old = facts[k]
            facts[k] = when {
                old == null || old == Tri.UNKNOWN -> v
                v == Tri.UNKNOWN || v == old -> old
                k.fact.noIsDangerous -> Tri.NO
                k.fact.yesIsDangerous -> Tri.YES
                else -> old
            }
        }
        val choices = LinkedHashMap(s.choices)
        for ((c, o) in heard.choices) choices.putIfAbsent(c, o)
        val hazards = s.hazards + heard.hazards
        var subjects = s.subjects
        if (heard.patients.isNotEmpty()) subjects = refine(subjects, heard.patients, facts)
        return s.copy(
            facts = facts, choices = choices, hazards = hazards, subjects = subjects,
            medical = s.medical || hazards.any { it in medicalHazards },
            rescue = s.rescue || hazards.any { it in rescueHazards } || choices[Choice.LOCATION] == "rubble",
            people = heard.people ?: s.people, landmark = heard.landmark?.takeIf { it.isNotBlank() } ?: s.landmark,
            placeView = heard.advice ?: s.placeView,
        )
    }

    /** Keep the people the model says need medical help (plus any already asked about); add ones it names that rules missed. */
    private fun refine(subjects: List<Subject>, patients: Set<Relation>, facts: Map<FactKey, Tri>): List<Subject> {
        val kept = subjects.filter { it.relation in patients || facts.keys.any { k -> k.fact.scope == Scope.PERSON && k.subject == it.id } }
        val named = kept.mapNotNull { it.relation }.toSet()
        var nextId = (kept.maxOfOrNull { it.id } ?: 0)
        val added = patients.filter { it !in named }.map { Subject(++nextId, it) }
        // Ids stay what facts were recorded under; positions in the list do not matter.
        return (kept + added).take(Subjects.MAX).ifEmpty { subjects }
    }

    fun result(s: TriageState): TriageResult {
        val f = s.facts
        val h = s.hazards
        val loc = s.choices[Choice.LOCATION]
        val depth = s.choices[Choice.WATER_DEPTH]
        fun any(fact: Fact, v: Tri) = f.any { (k, x) -> k.fact == fact && x == v }

        val categories = buildList {
            if (s.medical) add(Category.MEDICAL)
            if (s.rescue) add(Category.RESCUE)
        }.ifEmpty { listOf(Category.OTHER) }

        val critical = any(Fact.BREATHING, Tri.NO) || any(Fact.CONSCIOUS, Tri.NO) || any(Fact.HEAVY_BLEEDING, Tri.YES) ||
            any(Fact.LIVE_WIRE, Tri.YES) || any(Fact.GAS_SMELL, Tri.YES) || (s.rescue && any(Fact.WATER_RISING, Tri.YES)) ||
            (s.rescue && any(Fact.SLOPE_MOVING, Tri.YES)) || depth in setOf("chest", "over_head") || loc == "rubble" ||
            h.any { it in setOf(Hazard.NOT_BREATHING, Hazard.UNCONSCIOUS, Hazard.SNAKE, Hazard.LABOUR, Hazard.TRAPPED) }
        val urgency = when { critical -> 3; s.medical || s.rescue -> 2; else -> 1 }

        val vuln = buildSet {
            if (any(Fact.HEAVY_BLEEDING, Tri.YES) || (Hazard.BLEEDING in h && !any(Fact.HEAVY_BLEEDING, Tri.NO))) add(VulnFlag.INJURED)
            if (Hazard.TRAPPED in h || loc == "rubble") add(VulnFlag.TRAPPED)
            if (Hazard.LABOUR in h) add(VulnFlag.PREGNANT)
            if (s.subjects.any { it.relation == Relation.BABY || it.relation == Relation.CHILD }) add(VulnFlag.CHILD)
            if (s.subjects.any { it.relation == Relation.GRANDMOTHER || it.relation == Relation.GRANDFATHER }) add(VulnFlag.ELDERLY)
        }

        // The wire has room for three answers. Several patients: the worst one speaks for all ("anyone unresponsive").
        fun yesNo(v: Tri) = NeedAnswerValue.of(v)
        val answers = buildList {
            when {
                any(Fact.CONSCIOUS, Tri.NO) -> add(NeedAnswer(1, 2))
                any(Fact.CONSCIOUS, Tri.YES) -> add(NeedAnswer(1, 1))
            }
            f[FactKey(Fact.ROAD_REACHABLE, 0)]?.let { v -> yesNo(v)?.let { add(NeedAnswer(2, it)) } }
            f[FactKey(Fact.WATER_RISING, 0)]?.let { v -> yesNo(v)?.let { add(NeedAnswer(3, it)) } }
            when (loc) {
                "roof", "upper_floor" -> add(NeedAnswer(4, 1))
                "ground_floor", "basement", "outdoors", "vehicle", "rubble" -> add(NeedAnswer(4, 2))
            }
        }.sortedBy { it.questionId }.take(3)

        return TriageResult(
            categories = categories, urgency = urgency, vuln = vuln, people = s.people, landmark = s.landmark,
            answers = answers, summary = summary(s), advisories = advisories(s), places = places(s), facts = f, choices = s.choices, hazards = h,
            subjects = s.subjects,
        )
    }

    private object NeedAnswerValue {
        /** QuestionBank yes/no values: yes 1, no 2; "don't know" is not sent. */
        fun of(v: Tri): Int? = when (v) { Tri.YES -> 1; Tri.NO -> 2; Tri.UNKNOWN -> null }
    }

    /** English words for the control room, most dangerous first, cut to the wire limit on a part boundary. */
    private fun summary(s: TriageState): String {
        val f = s.facts
        val h = s.hazards
        val loc = s.choices[Choice.LOCATION]
        val multi = s.patients.size > 1
        val parts = ArrayList<String>()
        for (sub in s.patients) {
            val p = if (multi) "${sub.relation?.key ?: "person ${sub.id}"} " else ""
            val first = sub.id == s.patients.first().id
            if (f[FactKey(Fact.BREATHING, sub.id)] == Tri.NO || (Hazard.NOT_BREATHING in h && first)) parts += "${p}not breathing"
            else if (f[FactKey(Fact.CONSCIOUS, sub.id)] == Tri.NO || (Hazard.UNCONSCIOUS in h && first)) parts += "${p}unresponsive"
            else if (f[FactKey(Fact.HEAVY_BLEEDING, sub.id)] == Tri.YES) parts += "${p}heavy bleeding"
            else if (Hazard.BLEEDING in h && first && f[FactKey(Fact.HEAVY_BLEEDING, sub.id)] != Tri.NO) parts += "${p}bleeding"
        }
        if (Hazard.SNAKE in h) parts += "snake bite"
        if (Hazard.LABOUR in h) parts += "in labour"
        if (f[FactKey(Fact.GAS_SMELL, 0)] == Tri.YES) parts += "gas smell"
        if (f[FactKey(Fact.LIVE_WIRE, 0)] == Tri.YES || Hazard.ELECTRIC in h) parts += "live wire near"
        when (loc) {
            "rubble" -> parts += "under rubble"
            "roof" -> parts += "on roof"
            "upper_floor" -> parts += when (s.choices[Choice.FLOOR]) { "floor_1" -> "1 floor up"; "floor_2" -> "2 floors up"; "floor_3plus" -> "3+ floors up"; else -> "upper floor" }
            "ground_floor" -> parts += "ground floor"
            "basement" -> parts += "basement"
            "vehicle" -> parts += "in vehicle"
            "outdoors" -> parts += "outdoors"
        }
        if (Hazard.TRAPPED in h && loc != "rubble") parts += "trapped"
        if (f[FactKey(Fact.WATER_RISING, 0)] == Tri.YES) parts += "water rising"
        s.choices[Choice.WATER_DEPTH]?.let { parts += "water ${it.replace('_', ' ')}" }
        if (f[FactKey(Fact.BUILDING_UNSAFE, 0)] == Tri.YES) parts += "building unsafe"
        if (f[FactKey(Fact.SLOPE_MOVING, 0)] == Tri.YES) parts += "slope moving"
        if (f[FactKey(Fact.SHELTER_HOLDING, 0)] == Tri.NO) parts += "shelter failing"
        if (f[FactKey(Fact.ROAD_REACHABLE, 0)] == Tri.NO) parts += "no road access"

        val sb = StringBuilder()
        for (p in parts) {
            val next = if (sb.isEmpty()) p else "$sb, $p"
            if (next.toByteArray(Charsets.UTF_8).size > 40) continue
            sb.setLength(0); sb.append(next)
        }
        return sb.toString()
    }

    private fun places(s: TriageState): List<PlaceLine> {
        val map = s.map ?: return emptyList()
        return PlaceAdvisor.choose(PlaceAdvisor.allowed(s), s.placeView).mapNotNull { a ->
            when (a) {
                PlaceAdvice.GO_SHELTER -> map.shelter
                PlaceAdvice.HOSPITAL -> map.hospital
                PlaceAdvice.AWAY_FROM_WATER -> map.water
            }?.let { PlaceLine(a, it) }
        }
    }

    private fun advisories(s: TriageState): List<TriageBank.Advisory> {
        val f = s.facts
        val h = s.hazards
        fun any(fact: Fact, v: Tri) = f.any { (k, x) -> k.fact == fact && x == v }
        val ids = LinkedHashSet<TriageBank.AdvisoryId>()
        val notBreathing = any(Fact.BREATHING, Tri.NO) || Hazard.NOT_BREATHING in h
        if (notBreathing) ids += TriageBank.AdvisoryId.CPR
        if (!notBreathing && (any(Fact.CONSCIOUS, Tri.NO) || Hazard.UNCONSCIOUS in h)) ids += TriageBank.AdvisoryId.RECOVERY
        if (any(Fact.HEAVY_BLEEDING, Tri.YES) || (Hazard.BLEEDING in h && !any(Fact.HEAVY_BLEEDING, Tri.NO))) ids += TriageBank.AdvisoryId.BLEEDING
        if (Hazard.SNAKE in h) ids += TriageBank.AdvisoryId.SNAKE
        if (Hazard.LABOUR in h) ids += TriageBank.AdvisoryId.LABOUR
        if (Hazard.TRAPPED in h || s.choices[Choice.LOCATION] == "rubble") ids += TriageBank.AdvisoryId.TRAPPED
        if (any(Fact.GAS_SMELL, Tri.YES)) ids += TriageBank.AdvisoryId.GAS
        if (any(Fact.BUILDING_UNSAFE, Tri.YES)) ids += TriageBank.AdvisoryId.UNSAFE_BUILDING
        if (any(Fact.LIVE_WIRE, Tri.YES) || Hazard.ELECTRIC in h) ids += TriageBank.AdvisoryId.ELECTRIC
        if (any(Fact.WATER_RISING, Tri.YES) || Hazard.FLOOD in h) ids += TriageBank.AdvisoryId.FLOOD
        if (Hazard.CYCLONE in h || any(Fact.SHELTER_HOLDING, Tri.NO)) ids += TriageBank.AdvisoryId.CYCLONE
        if (Hazard.LANDSLIDE in h || any(Fact.SLOPE_MOVING, Tri.YES)) ids += TriageBank.AdvisoryId.LANDSLIDE
        return ids.mapNotNull { TriageBank.advisories[it] }.sortedByDescending { it.priority }
    }
}
