package org.relay.core.triage

import org.relay.core.payload.Lang

/** The kinds of place on the phone's offline map that change what a person in danger should do. */
enum class PlaceKind(val key: String) {
    /** A cyclone or flood shelter: a strong, raised building meant for this. */
    SHELTER("shelter"),
    HOSPITAL("hospital"),
    /** A river, embankment, canal or the sea: where flood water comes from. */
    WATER("water");

    companion object {
        fun fromKey(k: String?): PlaceKind? = entries.firstOrNull { it.key.equals(k?.trim(), ignoreCase = true) }
    }
}

/**
 * One place on the offline map pack. [name] is English, lower case as in the gazetteer; [names] holds the same place in
 * other scripts when the pack has them (spoken to a Hindi or Odia speaker in their own script).
 */
data class MapPlace(val name: String, val kind: PlaceKind, val latE5: Int, val lonE5: Int, val names: Map<Lang, String> = emptyMap()) {
    fun nameIn(lang: Lang): String = names[lang] ?: name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
}

enum class Compass(val key: String) { N("north"), NE("north-east"), E("east"), SE("south-east"), S("south"), SW("south-west"), W("west"), NW("north-west") }

/** A place seen from the person: how far ([distanceM], already rounded for speech) and which way. */
data class PlaceRef(val place: MapPlace, val distanceM: Int, val bearing: Compass)

/**
 * What the offline map says about where the person is: the nearest shelter, hospital and water within reach. Built by
 * code from the phone's own position and the bundled map pack; the model sees it as context and never invents a place.
 */
data class MapContext(val shelter: PlaceRef? = null, val hospital: PlaceRef? = null, val water: PlaceRef? = null) {
    val isEmpty: Boolean get() = shelter == null && hospital == null && water == null

    /** One line for the model's prompt, in English. */
    fun promptLine(): String = listOfNotNull(
        shelter?.let { "nearest shelter: ${it.place.name}, ${spokenDistance(it.distanceM)} ${it.bearing.key}" },
        hospital?.let { "nearest hospital: ${it.place.name}, ${spokenDistance(it.distanceM)} ${it.bearing.key}" },
        water?.let { "nearest river/embankment/sea: ${it.place.name}, ${spokenDistance(it.distanceM)} ${it.bearing.key}" },
    ).joinToString("; ").ifEmpty { "nothing nearby on the offline map" }

    companion object {
        /** How far each kind is worth mentioning at all. */
        val REACH_M = mapOf(PlaceKind.SHELTER to 15_000, PlaceKind.HOSPITAL to 50_000, PlaceKind.WATER to 2_000)

        /** A position worse than this is too rough to say "300 m north-east": no map context then. */
        const val MAX_ACCURACY_M = 1_000f

        /** [hereE5] is the person's position (degrees ×1e5). Null when the position is unknown or too rough. */
        fun around(places: List<MapPlace>, hereE5: Pair<Int, Int>?, accuracyM: Float = Float.NaN): MapContext? {
            if (hereE5 == null || (!accuracyM.isNaN() && accuracyM > MAX_ACCURACY_M)) return null
            fun nearest(kind: PlaceKind): PlaceRef? = places.asSequence().filter { it.kind == kind }
                .map { it to PlaceGeo.distanceM(hereE5, it.latE5 to it.lonE5) }
                .filter { it.second <= REACH_M.getValue(kind) }
                .minByOrNull { it.second }
                ?.let { (p, d) -> PlaceRef(p, roundForSpeech(d), PlaceGeo.compass(hereE5, p.latE5 to p.lonE5)) }
            return MapContext(nearest(PlaceKind.SHELTER), nearest(PlaceKind.HOSPITAL), nearest(PlaceKind.WATER))
        }

        /** Under 1 km to 50 m, under 10 km to 0.5 km, beyond to whole km: no false precision from a phone fix. */
        fun roundForSpeech(m: Double): Int = when {
            m < 1_000 -> maxOf(50, (Math.round(m / 50) * 50).toInt())
            m < 10_000 -> (Math.round(m / 500) * 500).toInt()
            else -> (Math.round(m / 1000) * 1000).toInt()
        }

        fun spokenDistance(m: Int): String = if (m < 1000) "$m m" else if (m % 1000 == 0) "${m / 1000} km" else "${m / 1000}.${m % 1000 / 100} km"
    }
}

internal object PlaceGeo {
    fun distanceM(a: Pair<Int, Int>, b: Pair<Int, Int>): Double {
        val lat1 = Math.toRadians(a.first / 1e5); val lat2 = Math.toRadians(b.first / 1e5)
        val dLat = lat2 - lat1; val dLon = Math.toRadians((b.second - a.second) / 1e5)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2).let { it * it }
        return 6_371_000.0 * 2 * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun compass(from: Pair<Int, Int>, to: Pair<Int, Int>): Compass {
        val lat1 = Math.toRadians(from.first / 1e5); val lat2 = Math.toRadians(to.first / 1e5)
        val dLon = Math.toRadians((to.second - from.second) / 1e5)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        val deg = (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
        return Compass.entries[((deg + 22.5) / 45).toInt() % 8]
    }
}

/** Advice that names a place from the map. Supplementary: first aid always comes first and is never replaced by these. */
enum class PlaceAdvice(val key: String) {
    GO_SHELTER("go_shelter"), HOSPITAL("hospital"), AWAY_FROM_WATER("away_from_water");

    companion object {
        fun fromKey(k: String?): PlaceAdvice? = entries.firstOrNull { it.key.equals(k?.trim(), ignoreCase = true) }
    }
}

/**
 * Which place advice is allowed, decided by code: telling someone to walk to a shelter is wrong if they are under rubble,
 * on a roof above rising water, or kneeling beside someone who is not breathing. The model (when there is one) then picks
 * which of the allowed ones fit what the person said and in what order ([choose]); it cannot add one that is not allowed.
 */
object PlaceAdvisor {
    /** Should not be told to go anywhere: moving would put them or the person they are helping in more danger. */
    fun mustStay(s: TriageState): Boolean {
        val f = s.facts
        fun any(fact: Fact, v: Tri) = f.any { (k, x) -> k.fact == fact && x == v }
        val loc = s.choices[Choice.LOCATION]
        val depth = s.choices[Choice.WATER_DEPTH]
        val waterAround = any(Fact.WATER_RISING, Tri.YES) || DisasterType.FLOOD in s.disasters || Hazard.FLOOD in s.hazards
        return any(Fact.BREATHING, Tri.NO) || any(Fact.CONSCIOUS, Tri.NO) ||
            Hazard.NOT_BREATHING in s.hazards || Hazard.UNCONSCIOUS in s.hazards || Hazard.TRAPPED in s.hazards ||
            loc == "rubble" || depth == "chest" || depth == "over_head" ||
            any(Fact.LIVE_WIRE, Tri.YES) || Hazard.ELECTRIC in s.hazards || any(Fact.SLOPE_MOVING, Tri.YES) ||
            (waterAround && (loc == "roof" || loc == "upper_floor" || loc == "vehicle"))
    }

    /** The place advice allowed in [s], in default order. Empty without map context. */
    fun allowed(s: TriageState): List<PlaceAdvice> {
        val map = s.map ?: return emptyList()
        val f = s.facts
        fun any(fact: Fact, v: Tri) = f.any { (k, x) -> k.fact == fact && x == v }
        val stay = mustStay(s)
        val flood = DisasterType.FLOOD in s.disasters || Hazard.FLOOD in s.hazards || any(Fact.WATER_RISING, Tri.YES)
        val storm = DisasterType.CYCLONE in s.disasters || Hazard.CYCLONE in s.hazards
        val shelterFailing = any(Fact.SHELTER_HOLDING, Tri.NO) || any(Fact.BUILDING_UNSAFE, Tri.YES)
        return buildList {
            if (map.water != null && flood && !stay) add(PlaceAdvice.AWAY_FROM_WATER)
            if (map.shelter != null && !stay && (flood || storm || shelterFailing)) add(PlaceAdvice.GO_SHELTER)
            if (map.hospital != null && s.medical && !any(Fact.BREATHING, Tri.NO) && Hazard.NOT_BREATHING !in s.hazards) add(PlaceAdvice.HOSPITAL)
        }
    }

    /**
     * The model's pick ([picked] keys, most useful first; null = the model gave no view) applied to [allowed]: unknown or
     * disallowed keys are ignored. With no view, everything allowed is given in default order.
     */
    fun choose(allowed: List<PlaceAdvice>, picked: List<String>?): List<PlaceAdvice> =
        if (picked == null) allowed else picked.mapNotNull { PlaceAdvice.fromKey(it) }.filter { it in allowed }.distinct()
}

/** A place advice ready to show: the advice, the place it names, and the text in a language ([TriageBank.placeText]). */
data class PlaceLine(val advice: PlaceAdvice, val ref: PlaceRef) {
    fun text(lang: Lang): String = TriageBank.placeText(advice, ref, lang)
}
