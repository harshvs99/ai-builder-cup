package org.relay.core.triage

import org.relay.core.nlu.Lex
import org.relay.core.nlu.normalizeText

/** An answer to a yes/no triage question. */
enum class Tri(val key: String) {
    YES("yes"), NO("no"), UNKNOWN("unknown");

    companion object {
        fun fromKey(k: String?): Tri? = entries.firstOrNull { it.key.equals(k?.trim(), ignoreCase = true) }
    }
}

/** Whether a fact is about one person ("is your mother awake") or the place they are in ("is the water rising"). */
enum class Scope { PERSON, SCENE }

/**
 * The yes/no things triage can find out. [disasters] empty = asked in any situation; otherwise asked only while the control
 * room has declared one of those disasters ([DisasterGate]). [bankId] is the `QuestionBank` question the answer also goes
 * out on (the wire carries at most 3 answers, SPEC 5.1); the rest only steer the conversation and the advice.
 */
enum class Fact(val key: String, val scope: Scope, val bankId: Int? = null, val disasters: Set<DisasterType> = emptySet()) {
    CONSCIOUS("conscious", Scope.PERSON, 1),
    BREATHING("breathing", Scope.PERSON),
    HEAVY_BLEEDING("heavy_bleeding", Scope.PERSON),
    ANYONE_HURT("anyone_hurt", Scope.SCENE),
    ROAD_REACHABLE("road_reachable", Scope.SCENE, 2),
    WATER_RISING("water_rising", Scope.SCENE, 3, setOf(DisasterType.FLOOD)),
    LIVE_WIRE("live_wire", Scope.SCENE, null, setOf(DisasterType.FLOOD, DisasterType.CYCLONE, DisasterType.EARTHQUAKE)),
    GAS_SMELL("gas_smell", Scope.SCENE, null, setOf(DisasterType.EARTHQUAKE)),
    BUILDING_UNSAFE("building_unsafe", Scope.SCENE, null, setOf(DisasterType.EARTHQUAKE)),
    STILL_SHAKING("still_shaking", Scope.SCENE, null, setOf(DisasterType.EARTHQUAKE)),
    SHELTER_HOLDING("shelter_holding", Scope.SCENE, null, setOf(DisasterType.CYCLONE)),
    SLOPE_MOVING("slope_moving", Scope.SCENE, null, setOf(DisasterType.LANDSLIDE));

    /** Facts where "no" is the dangerous answer; a disagreement between rules and model resolves to the dangerous one. */
    val noIsDangerous: Boolean get() = this == CONSCIOUS || this == BREATHING || this == SHELTER_HOLDING
    val yesIsDangerous: Boolean get() = !noIsDangerous && this != ROAD_REACHABLE

    companion object {
        fun fromKey(k: String?): Fact? = entries.firstOrNull { it.key.equals(k?.trim(), ignoreCase = true) }
    }
}

/** A fact about one subject (0 for scene facts). */
data class FactKey(val fact: Fact, val subject: Int = if (fact.scope == Scope.SCENE) 0 else 1) {
    /** The id used in the model's menu and replies: `conscious@2`, `water_rising`. */
    val id: String get() = if (fact.scope == Scope.SCENE) fact.key else "${fact.key}@$subject"

    companion object {
        fun parse(id: String?): FactKey? {
            val raw = id?.trim()?.lowercase() ?: return null
            val name = raw.substringBefore('@')
            val fact = Fact.fromKey(name) ?: return null
            if (fact.scope == Scope.SCENE) return FactKey(fact)
            val sub = if ('@' in raw) raw.substringAfter('@').toIntOrNull() ?: return null else 1
            return if (sub in 1..Subjects.MAX) FactKey(fact, sub) else null
        }
    }
}

/** Questions with more than two answers. Options are chosen by the person; each has a stable [Option.key]. */
enum class Choice(val key: String, val disasters: Set<DisasterType> = emptySet()) {
    PATIENTS("patients"),
    LOCATION("location"),
    FLOOR("floor"),
    WATER_DEPTH("water_depth", setOf(DisasterType.FLOOD));

    companion object {
        fun fromKey(k: String?): Choice? = entries.firstOrNull { it.key.equals(k?.trim(), ignoreCase = true) }
    }
}

/** One answer to a [Choice]. [lex] recognises it in speech (any of the app's languages, code-mixed). */
class Option internal constructor(val key: String, internal val lex: Lex)

object Options {
    private fun o(key: String, vararg terms: String) = Option(key, Lex(*terms))

    // Order matters: the first option whose words appear wins, so the more specific ones come first.
    private val location = listOf(
        o("rubble", "rubble", "debris", "buried", "collapsed", "malba*", "मलबा*", "मलबे*", "naipit*", "guho*"),
        o("basement", "basement", "cellar", "underground", "तहखाना", "तहख़ाना"),
        o("roof", "roof*", "terrace", "rooftop", "chhat*", "chat", "छत", "ଛାତ*", "bubong*"),
        o("ground_floor", "~ground\\s+floor", "downstairs", "inside the house", "inside house", "zameen*", "जमीन", "ज़मीन", "भूतल", "ଭୂମି*", "ଭୂତଳ*", "ibabang palapag", "sa lupa"),
        o("upper_floor", "~(?:upper|first|second|third|fourth|top|next)\\s+floor", "upstairs", "terrace floor", "~(?:1st|2nd|3rd|4th)\\s+floor", "मंज़िल", "मंजिल", "ମହଲା*", "itaas na palapag", "palapag"),
        o("vehicle", "car", "bus", "truck", "vehicle", "tractor", "boat", "gaadi*", "gadi*", "गाड़ी", "गाड़ी*", "naav*", "नाव", "ନାଆ", "ଗାଡ଼ି*", "sasakyan", "bangka"),
        o("outdoors", "outside", "outdoor*", "open", "field*", "road", "tree", "bahar", "khet*", "बाहर", "खेत*", "सड़क", "पेड़", "ବାହାର*", "ଖେତ*", "labas", "kalsada", "puno"),
    )
    private val floor = listOf(
        o("floor_3plus", "third", "3rd", "three", "fourth", "4th", "four", "higher", "top", "teesri", "तीसरी", "तीन", "ତିନି*", "pangatlo", "tatlo"),
        o("floor_2", "second", "2nd", "two", "doosri", "dusri", "दूसरी", "दो", "ଦ୍ୱିତୀୟ", "ଦୁଇ*", "pangalawa", "dalawa"),
        o("floor_1", "first", "1st", "one", "pehli", "पहली", "एक", "ପ୍ରଥମ", "ଏକ", "una", "isa"),
    )
    private val waterDepth = listOf(
        o("over_head", "~over\\s+(?:my\\s+)?head", "~above\\s+(?:my\\s+)?head", "head", "deep", "sir*", "सिर*", "ମୁଣ୍ଡ*", "ulo", "lubog"),
        o("chest", "chest", "neck", "seena", "seene", "सीने", "छाती", "ଛାତି*", "dibdib", "leeg"),
        o("waist", "waist", "kamar", "कमर", "ଅଣ୍ଟା*", "baywang"),
        o("knee", "knee*", "ghutn*", "घुटने", "घुटनों", "ଆଣ୍ଠୁ*", "tuhod"),
        o("ankle", "ankle*", "takhn*", "टखने", "ଗୋଡ଼*", "bukung-bukong", "paa"),
    )
    private val patients = listOf(
        o("three_plus", "three", "3", "four", "4", "many", "several", "everyone", "teen", "तीन", "ତିନି*", "tatlo", "marami"),
        o("two", "two", "2", "both", "do", "दो", "ଦୁଇ*", "dalawa"),
        o("one", "one", "1", "just", "only", "single", "ek", "एक", "ଏକ", "isa"),
    )

    private val byChoice = mapOf(Choice.LOCATION to location, Choice.FLOOR to floor, Choice.WATER_DEPTH to waterDepth, Choice.PATIENTS to patients)

    /** All options of [c], in matching order. Which of them are offered in a situation is [QuestionCatalog.options]. */
    fun all(c: Choice): List<Option> = byChoice.getValue(c)

    /** The option of [c] that [text] names, restricted to [offered] keys; null if none (or text is empty). */
    fun detect(c: Choice, text: String, offered: Collection<String>? = null): String? {
        val t = try { normalizeText(text) } catch (_: Exception) { return null }
        if (t.isEmpty()) return null
        return all(c).firstOrNull { (offered == null || it.key in offered) && it.lex.matches(t) }?.key
    }
}

// ====================================================================== people

enum class Relation(val key: String) {
    MOTHER("mother"), FATHER("father"), WIFE("wife"), HUSBAND("husband"), SON("son"), DAUGHTER("daughter"),
    CHILD("child"), BABY("baby"), GRANDMOTHER("grandmother"), GRANDFATHER("grandfather"), BROTHER("brother"), SISTER("sister");

    companion object {
        fun fromKey(k: String?): Relation? = entries.firstOrNull { it.key.equals(k?.trim(), ignoreCase = true) }
    }
}

/** A person triage asks about. [relation] is how the speaker referred to them; null = "the first person", "the second person". */
data class Subject(val id: Int, val relation: Relation? = null)

object Subjects {
    /** Questioned one by one: beyond this, the rest are one group on the ticket (people count) and not questioned. */
    const val MAX = 3

    private val lexicon: List<Pair<Relation, Lex>> = listOf(
        Relation.MOTHER to Lex("mother", "mom", "mum", "mummy", "maa", "माँ", "मां", "माता", "ମା", "ମାଆ", "ina", "nanay"),
        Relation.FATHER to Lex("father", "dad", "daddy", "papa", "baap", "पिता", "पापा", "बाप", "ବାପା", "ବାପ", "ama", "tatay"),
        Relation.WIFE to Lex("wife", "biwi", "patni", "पत्नी", "बीवी", "ପତ୍ନୀ"),
        Relation.HUSBAND to Lex("husband", "pati", "पति", "ସ୍ୱାମୀ"),
        Relation.SON to Lex("son", "beta", "बेटा", "ପୁଅ", "~anak\\s+na\\s+lalaki"),
        Relation.DAUGHTER to Lex("daughter", "beti", "बेटी", "ଝିଅ", "~anak\\s+na\\s+babae"),
        Relation.BABY to Lex("baby", "infant", "newborn", "shishu", "शिशु", "ଶିଶୁ", "sanggol"),
        Relation.CHILD to Lex("child", "kid", "bachcha", "bachha", "बच्चा", "ପିଲା", "anak", "bata"),
        Relation.GRANDMOTHER to Lex("grandmother", "grandma", "dadi", "nani", "दादी", "नानी", "ଜେଜେମା", "lola"),
        Relation.GRANDFATHER to Lex("grandfather", "grandpa", "dada", "दादा", "नाना", "ଜେଜେବାପା", "lolo"),
        Relation.BROTHER to Lex("brother", "bhai", "भाई", "ଭାଇ", "kuya"),
        Relation.SISTER to Lex("sister", "behen", "behan", "बहन", "ଭଉଣୀ"),
    )

    /** Relations named in [text], in the order spoken, each once. */
    fun relations(text: String): List<Relation> {
        val t = try { normalizeText(text) } catch (_: Exception) { return emptyList() }
        return lexicon.mapNotNull { (rel, lex) -> lex.find(t)?.let { rel to it.range.first } }.sortedBy { it.second }.map { it.first }
    }

    /** Subjects for the relations spoken (at most [MAX]); none named = none (one unnamed patient is assumed until told otherwise). */
    fun fromText(text: String): List<Subject> = relations(text).take(MAX).mapIndexed { i, r -> Subject(i + 1, r) }

    fun ordinal(count: Int): List<Subject> = (1..count.coerceIn(1, MAX)).map { Subject(it) }
}
