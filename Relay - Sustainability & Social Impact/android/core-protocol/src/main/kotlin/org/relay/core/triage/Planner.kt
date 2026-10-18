package org.relay.core.triage

import org.relay.core.payload.Lang

/** One question the phone can ask: a yes/no [fact] about [subject], or a [choice] (subject 0). */
data class Ask(val fact: Fact? = null, val choice: Choice? = null, val subject: Int = 0) {
    init { require((fact == null) != (choice == null)) }

    /** The id in the model's menu and replies: `conscious@2`, `water_rising`, `location`. */
    val id: String get() = fact?.let { FactKey(it, if (it.scope == Scope.PERSON) subject.coerceAtLeast(1) else 0).id } ?: choice!!.key

    companion object {
        fun fact(f: Fact, subject: Int = 1) = Ask(fact = f, subject = if (f.scope == Scope.SCENE) 0 else subject)
        fun choice(c: Choice) = Ask(choice = c)

        fun parse(id: String?): Ask? {
            val raw = id?.trim()?.lowercase() ?: return null
            Choice.fromKey(raw)?.let { return choice(it) }
            val k = FactKey.parse(raw) ?: return null
            return fact(k.fact, k.subject)
        }
    }
}

/** A question that is allowed right now. [mandatory] ones cannot be skipped or reordered behind optional ones. */
data class MenuItem(val ask: Ask, val priority: Int, val mandatory: Boolean)

/**
 * The decision architecture: from the case so far, which questions may be asked now, and which one goes next.
 *
 * Code decides what is ALLOWED (the [menu]): disaster-specific questions only inside the control room's freshness window,
 * per-person questions for every patient, a question budget, no repeats, and "stop and give first aid" when someone is not
 * breathing. The model, when there is one, decides the ORDER among the allowed non-mandatory questions and may say it has
 * heard enough ([pick]); it can never add a question that is not on the menu or skip a mandatory one.
 */
object Planner {
    private class Spec(
        val fact: Fact? = null, val choice: Choice? = null, val priority: Int, val mandatory: Boolean = false,
        val applies: (TriageState, Int) -> Boolean,
    )

    private fun f(fact: Fact, priority: Int, mandatory: Boolean = false, applies: (TriageState, Int) -> Boolean) =
        Spec(fact = fact, priority = priority, mandatory = mandatory, applies = applies)
    private fun c(choice: Choice, priority: Int, mandatory: Boolean = false, applies: (TriageState, Int) -> Boolean) =
        Spec(choice = choice, priority = priority, mandatory = mandatory, applies = applies)

    private val specs = listOf(
        c(Choice.PATIENTS, 110, mandatory = true) { s, _ -> s.medical && s.subjects.isEmpty() && s.peopleHint >= 2 },
        f(Fact.CONSCIOUS, 100, mandatory = true) { s, _ -> s.medical },
        f(Fact.BREATHING, 95, mandatory = true) { s, sub -> s.medical && s.facts[FactKey(Fact.CONSCIOUS, sub)] == Tri.NO },
        f(Fact.HEAVY_BLEEDING, 80) { s, sub -> s.medical && s.facts[FactKey(Fact.BREATHING, sub)] != Tri.NO },
        // Where the person is: asked of anyone who needs getting out, in any situation, worded for the disaster when one is known.
        c(Choice.LOCATION, 93) { s, _ -> s.rescue },
        c(Choice.FLOOR, 92) { s, _ -> s.choices[Choice.LOCATION] == "upper_floor" },
        f(Fact.ANYONE_HURT, 85) { s, _ -> s.rescue && !s.medical },
        f(Fact.ROAD_REACHABLE, 30) { s, _ -> s.medical },
        // Disaster-specific: the gate in [menu] keeps these off unless the control room declared the disaster.
        f(Fact.GAS_SMELL, 91) { s, _ -> s.rescue || s.medical },
        f(Fact.LIVE_WIRE, 90) { s, _ -> (s.rescue || s.medical) && Hazard.ELECTRIC !in s.hazards },
        f(Fact.SLOPE_MOVING, 90) { s, _ -> s.rescue },
        f(Fact.BUILDING_UNSAFE, 89) { s, _ -> s.rescue },
        f(Fact.WATER_RISING, 88) { s, _ -> s.rescue },
        f(Fact.SHELTER_HOLDING, 85) { s, _ -> s.rescue },
        c(Choice.WATER_DEPTH, 84) { s, _ -> s.rescue },
        f(Fact.STILL_SHAKING, 60) { s, _ -> s.rescue },
    )

    /** Questions in one conversation: 5 for one patient (6 when getting out is part of it), 3 more for each further patient, never above 12. */
    fun budget(s: TriageState): Int = minOf(12, 5 + (if (s.rescue) 1 else 0) + 3 * (s.patients.size - 1))

    private fun allowedBy(disasters: Set<DisasterType>, s: TriageState) = disasters.isEmpty() || disasters.any { it in s.disasters }

    fun menu(s: TriageState): List<MenuItem> {
        if (s.asked.size >= budget(s)) return emptyList()
        // Someone is not breathing: the next minute belongs to chest pushes, not questions.
        if (s.facts.any { (k, v) -> k.fact == Fact.BREATHING && v == Tri.NO }) return emptyList()
        val out = ArrayList<MenuItem>()
        for (spec in specs) {
            spec.fact?.let { fact ->
                if (!allowedBy(fact.disasters, s)) return@let
                val subjects = if (fact.scope == Scope.PERSON) s.patients.map { it.id } else listOf(0)
                for (sub in subjects) {
                    val ask = Ask.fact(fact, sub)
                    if (ask.id in s.asked || s.facts[FactKey(fact, sub)] != null) continue
                    if (spec.applies(s, sub)) out += MenuItem(ask, spec.priority, spec.mandatory)
                }
            }
            spec.choice?.let { choice ->
                if (!allowedBy(choice.disasters, s)) return@let
                if (choice.key in s.asked || s.choices[choice] != null) return@let
                if (spec.applies(s, 0)) out += MenuItem(Ask.choice(choice), spec.priority, spec.mandatory)
            }
        }
        // Highest priority first; across people the same question goes to each in turn (everyone awake? then everyone breathing?).
        return out.sortedWith(compareByDescending<MenuItem> { it.mandatory }.thenByDescending { it.priority }.thenBy { it.ask.subject })
    }

    /** The options offered for a choice in this situation (and the order they are shown). */
    fun options(choice: Choice, s: TriageState): List<String> = when (choice) {
        Choice.PATIENTS -> listOf("one", "two", "three_plus")
        Choice.FLOOR -> listOf("floor_1", "floor_2", "floor_3plus")
        Choice.WATER_DEPTH -> listOf("ankle", "knee", "waist", "chest", "over_head")
        Choice.LOCATION -> when {
            DisasterType.FLOOD in s.disasters -> listOf("roof", "upper_floor", "ground_floor", "vehicle", "outdoors")
            DisasterType.EARTHQUAKE in s.disasters -> listOf("rubble", "upper_floor", "ground_floor", "outdoors", "vehicle")
            DisasterType.LANDSLIDE in s.disasters -> listOf("rubble", "ground_floor", "outdoors", "vehicle")
            DisasterType.CYCLONE in s.disasters -> listOf("roof", "upper_floor", "ground_floor", "outdoors", "vehicle")
            else -> listOf("roof", "upper_floor", "ground_floor", "basement", "outdoors", "vehicle", "rubble")
        }
    }

    /**
     * The next question, or null to end the conversation. [suggestion] / [done] are the model's view: an id from the menu
     * is used unless a mandatory question is waiting (and then only a mandatory one is accepted); "done" is honoured only
     * when nothing mandatory is waiting. With no usable suggestion the menu's first item goes.
     */
    fun pick(s: TriageState, suggestion: String? = null, done: Boolean = false): Ask? {
        val menu = menu(s)
        if (menu.isEmpty()) return null
        val mandatory = menu.filter { it.mandatory }
        if (mandatory.isEmpty() && done) return null
        val pool = mandatory.ifEmpty { menu }
        val wanted = Ask.parse(suggestion)
        return (pool.firstOrNull { it.ask == wanted } ?: pool.first()).ask
    }

    /** The question as the person reads it, in [lang]. */
    fun text(ask: Ask, s: TriageState, lang: Lang): String =
        ask.fact?.let { TriageBank.question(it, ask.subject, s.patients, lang) } ?: TriageBank.question(ask.choice!!, lang)
}
