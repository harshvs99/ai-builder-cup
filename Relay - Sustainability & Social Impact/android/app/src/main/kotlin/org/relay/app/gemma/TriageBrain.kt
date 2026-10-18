package org.relay.app.gemma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.relay.core.payload.Lang
import org.relay.core.payload.Utf8
import org.relay.core.triage.Ask
import org.relay.core.triage.Choice
import org.relay.core.triage.Fact
import org.relay.core.triage.FactKey
import org.relay.core.triage.Hazard
import org.relay.core.triage.Interpretation
import org.relay.core.triage.Options
import org.relay.core.triage.PlaceAdvice
import org.relay.core.triage.PlaceAdvisor
import org.relay.core.triage.Planner
import org.relay.core.triage.Relation
import org.relay.core.triage.Tri
import org.relay.core.triage.TriageState

/**
 * The model's two jobs in a triage conversation, done in one call per turn: READ what the person just said (facts, a place,
 * who is hurt, dangers named in passing) and ADVISE which allowed question to ask next. It never writes a question or
 * advice (those are fixed text in `TriageBank`), and code decides what is allowed (`Planner`): a suggestion outside the menu
 * is ignored, a mandatory question cannot be skipped, and its reading is merged with the rules', the more alarming one winning.
 */
interface TriageBrain {
    /** Loads the model if it may be loaded (enough RAM, file present). False = rules only for this conversation. */
    suspend fun warmUp(): Boolean

    /** Null when the model is not available or gave nothing usable: the caller carries on with the rules alone. */
    suspend fun interpret(utterance: String, asked: Ask?, state: TriageState, lang: Lang): Interpretation?
}

object TriagePrompt {
    /** The decision architecture, as the model sees it. Kept short: a phone-sized model follows a few firm rules, not an essay. */
    val SYSTEM = """
You are the reading-and-planning brain of an offline emergency triage helper on a phone. A person in a disaster is being asked short questions by voice. You do NOT write questions and you do NOT give advice: fixed texts do that. You do three things and output ONLY one JSON object (no prose, no markdown):

1. READ what the person just said, in any language or code-mix.
2. CHOOSE which question from the MENU to ask next, or say you are done.
3. CHOOSE which place advice from the ADVICE MENU fits this person, most useful first.

Output schema (omit a key when unsupported by the words):
{"answer":"<yes|no|unknown, or an option key when the question was a choice>",
 "facts":{"<fact id>":"yes|no|unknown"},
 "choices":{"<choice>":"<option key>"},
 "hazards":[subset of "NOT_BREATHING","UNCONSCIOUS","BLEEDING","SNAKE","LABOUR","ELECTRIC","FLOOD","TRAPPED","CYCLONE","LANDSLIDE"],
 "patients":[relations of people who need medical help, from "MOTHER","FATHER","WIFE","HUSBAND","SON","DAUGHTER","CHILD","BABY","GRANDMOTHER","GRANDFATHER","BROTHER","SISTER"],
 "people":<headcount who need rescue or help, or 0>,
 "landmark":"<bare English place noun or empty>",
 "next":"<an id from the MENU, or empty>",
 "done":<true when nothing on the MENU would change what rescuers do>,
 "advice":[ids from the ADVICE MENU, most useful first; leave out any that does not fit]}

Reading rules:
- A fact id is the name from the MENU, with @N for a person (conscious@2 = the second person). Put a fact only when the words support it, even if it was not the question asked ("he is not breathing" -> breathing@1 no).
- When several people are named, keep their facts apart: "mother is awake, son is not" -> conscious@1 yes, conscious@2 no. Use the numbering under PEOPLE.
- "answer" is the reply to the question just asked, by meaning: yes / no / unknown ("I don't know", unclear). For a choice, the option key that fits; if none fits, "unknown".
- Never invent a danger. Never assume a disaster that is not declared below.

Planning rules (how to choose "next"):
1. Order of care: life first. Awake and breathing for every hurt person, then bleeding, then the place and the surroundings.
2. Ask about each person separately; do not merge two people into one question.
3. Pick only ids that appear in the MENU, and prefer the ones marked MUST first. Never repeat a question that is already known.
4. Skip a question the person has already answered in passing. Prefer the question whose answer would change what rescuers do.
5. Ask disaster-specific questions only if they are on the MENU: they are there only because the control room declared that disaster. Without a declaration, never bring up floods, quakes, storms or landslides yourself.
6. If the person sounds confused or says they cannot talk, set done to true.
7. MAP is what the phone's offline map shows around the person. Use it to judge what matters: close to a river in a declared flood, ask about the water early. Never name a place yourself.

Advice rules (how to choose "advice"):
- Only ids on the ADVICE MENU. Its items are already safe for this person to hear; you choose which ones fit and the order.
- Leave one out when the person's words make it pointless or wrong: they are already at the shelter, they already left the river bank, they say they cannot leave the house, the injured person is being carried to the hospital.
- Put first the one that changes what they do in the next minutes. With an empty ADVICE MENU output "advice":[].

Example:
CASE: patients: 1 your mother; 2 your son. Known: none. Disaster: none declared.
MENU: conscious@1 [MUST] Is your mother awake and answering you? | conscious@2 [MUST] Is your son awake and answering you? | location Where exactly are you?
Question asked: (first description)
Person said: "my mother fell and is not moving, my son is fine but crying, we are on the roof"
Output: {"facts":{"conscious@1":"no"},"choices":{"location":"roof"},"hazards":["UNCONSCIOUS"],"patients":["MOTHER"],"people":2,"landmark":"","next":"breathing@1","done":false,"advice":[]}
""".trim()

    /** The per-turn message: the case so far, the allowed questions, what was asked, what was said. */
    fun turn(utterance: String, asked: Ask?, state: TriageState, langHint: String, issuedAgesH: Map<String, Long> = emptyMap()): String {
        val patients = state.patients.joinToString("; ") { sub ->
            "${sub.id} ${sub.relation?.key?.let { r -> "your $r" } ?: if (state.patients.size > 1) "person ${sub.id}" else "the person"}"
        }
        val known = (state.facts.entries.map { "${it.key.id}=${it.value.key}" } + state.choices.entries.map { "${it.key.key}=${it.value}" })
            .joinToString(", ").ifEmpty { "none" }
        val disaster = if (state.disasters.isEmpty()) "none declared"
        else state.disasters.joinToString(", ") { d ->
            val age = issuedAgesH[d.key]
            d.key + (age?.let { " (declared $it h ago)" } ?: "")
        } + if (state.aging) ". The declaration is more than 12 hours old: the situation may have changed; confirm before relying on it." else ""
        val menu = Planner.menu(state).joinToString(" | ") { m ->
            val q = Planner.text(m.ask, state, Lang.EN)
            val opts = m.ask.choice?.let { c -> " options: " + Planner.options(c, state).joinToString("/") } ?: ""
            "${m.ask.id}${if (m.mandatory) " [MUST]" else ""} $q$opts"
        }.ifEmpty { "(empty: you are done)" }
        val map = state.map?.promptLine() ?: "no offline map or no position"
        val advice = PlaceAdvisor.allowed(state).joinToString(" | ") { a ->
            "${a.key}: " + when (a) {
                PlaceAdvice.GO_SHELTER -> "tell them where the nearest shelter is, to go if they can move safely"
                PlaceAdvice.HOSPITAL -> "tell them where the nearest hospital is"
                PlaceAdvice.AWAY_FROM_WATER -> "tell them to move away from the nearby river or sea, to higher ground"
            }
        }.ifEmpty { "(empty)" }
        val q = asked?.let { a ->
            val opts = a.choice?.let { c -> " (options: " + Planner.options(c, state).joinToString(", ") + ")" } ?: ""
            "Question asked: \"${Planner.text(a, state, Lang.EN)}\" (${a.id})$opts"
        } ?: "Question asked: (first description)"
        return "CASE: patients: $patients. Known: $known. Disaster: $disaster. Questions asked so far: ${state.asked.size}.\n" +
            "MAP: $map\nMENU: $menu\nADVICE MENU: $advice\n$q\nPerson said (language hint: $langHint): \"${utterance.replace('"', '\'')}\"\nOutput:"
    }

    /** Parses the reply; null when there is no usable JSON. Unknown keys, ids or option keys are ignored, never trusted. */
    fun parse(raw: String, asked: Ask?): Interpretation? {
        val json = GemmaNeedParser.extractJsonObject(raw) ?: return null
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        val facts = LinkedHashMap<FactKey, Tri>()
        o.optJSONObject("facts")?.let { fo ->
            for (k in fo.keys()) {
                val key = FactKey.parse(k) ?: continue
                Tri.fromKey(fo.optString(k, ""))?.let { facts[key] = it }
            }
        }
        val choices = LinkedHashMap<Choice, String>()
        o.optJSONObject("choices")?.let { co ->
            for (k in co.keys()) {
                val c = Choice.fromKey(k) ?: continue
                val opt = co.optString(k, "").trim().lowercase()
                if (Options.all(c).any { it.key == opt }) choices[c] = opt
            }
        }
        val answer = o.optString("answer", "").trim().lowercase()
        asked?.fact?.let { f -> Tri.fromKey(answer)?.let { facts.putIfAbsent(FactKey(f, asked.subject), it) } }
        asked?.choice?.let { c -> if (Options.all(c).any { it.key == answer }) choices.putIfAbsent(c, answer) }
        val hazards = LinkedHashSet<Hazard>()
        o.optJSONArray("hazards")?.let { a ->
            for (i in 0 until a.length()) Hazard.entries.firstOrNull { it.name == a.optString(i, "").trim().uppercase() }?.let { hazards += it }
        }
        val patients = LinkedHashSet<Relation>()
        o.optJSONArray("patients")?.let { a -> for (i in 0 until a.length()) Relation.fromKey(a.optString(i, ""))?.let { patients += it } }
        val people = o.optInt("people", 0).takeIf { it in 1..255 }
        val landmark = Utf8.truncate(o.optString("landmark", "").trim(), 24).takeIf { it.isNotBlank() }
        val next = o.optString("next", "").trim().takeIf { it.isNotEmpty() && Ask.parse(it) != null }
        val done = o.optBoolean("done", false)
        // Absent = no view (keep the last one); present, even empty, = the model's current pick. Unknown ids are dropped later.
        val advice = o.optJSONArray("advice")?.let { a -> (0 until a.length()).map { a.optString(it, "").trim().lowercase() }.filter { it.isNotEmpty() } }
        if (facts.isEmpty() && choices.isEmpty() && hazards.isEmpty() && patients.isEmpty() && people == null && landmark == null &&
            next == null && !done && advice == null) return null
        return Interpretation(facts, hazards, people, landmark, choices, patients, next, done, advice)
    }
}

/** Gemma 4 on this phone through [GemmaRunner]. The model is loaded by the runner on first use, only when triage starts. */
class GemmaTriageBrain(private val runner: GemmaRunner, private val declaredAgesH: () -> Map<String, Long> = { emptyMap() }) : TriageBrain {
    override suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) { runner.ensureLoaded().ok }

    override suspend fun interpret(utterance: String, asked: Ask?, state: TriageState, lang: Lang): Interpretation? =
        withContext(Dispatchers.IO) {
            val run = runner.runCustom(TriagePrompt.SYSTEM, TriagePrompt.turn(utterance, asked, state, lang.tag, issuedAgesH = declaredAgesH()), maxTokens = 350)
            if (run.error != null) null else TriagePrompt.parse(run.raw, asked)
        }
}
