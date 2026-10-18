package org.relay.core.triage

/** The kinds of disaster the control room can declare for an area. Disaster-specific questions are keyed to these. */
enum class DisasterType(val key: String) {
    FLOOD("flood"), CYCLONE("cyclone"), EARTHQUAKE("earthquake"), LANDSLIDE("landslide");

    companion object {
        fun fromKey(k: String?): DisasterType? = entries.firstOrNull { it.key.equals(k?.trim(), ignoreCase = true) }
    }
}

/**
 * What the control room said is happening: [type], and when it said so ([issuedAtSec], unix seconds). The phone never
 * infers a disaster from what a person says; only a declaration like this switches disaster-specific questions on.
 */
data class DisasterContext(
    val type: DisasterType,
    val issuedAtSec: Long,
    /** Where it applies: null = the whole control-room area. */
    val area: Area? = null,
) {
    /** A disc: centre in degrees ×1e5 (as on the wire) and radius in km. */
    data class Area(val latE5: Int, val lonE5: Int, val radiusKm: Int)
}

/**
 * The freshness rule: a declaration counts from the moment it is issued until [MAX_AGE_SEC] later. Past that, nothing
 * disaster-specific is asked: after a day the situation on the ground may have changed, and a wrong premise ("is the water
 * still rising?" in a place that has dried out) wastes the one minute a person has. Between [FRESH_SEC] and [MAX_AGE_SEC]
 * the declaration still counts but is marked [Active.aging], so the model and the questions can confirm before relying on it.
 */
object DisasterGate {
    const val FRESH_SEC = 12L * 3600
    const val MAX_AGE_SEC = 24L * 3600
    /** A declaration stamped slightly in the future (clock drift between phones) still counts. */
    const val CLOCK_SKEW_SEC = 10L * 60

    data class Active(val types: Set<DisasterType>, val aging: Boolean) {
        companion object { val NONE = Active(emptySet(), false) }
    }

    /**
     * [here] is the phone's last known position (degrees ×1e5) or null. A declaration with an area is skipped only when
     * the phone knows it is outside it: not knowing where it is never hides a declaration.
     */
    fun active(contexts: Collection<DisasterContext>, nowSec: Long, here: Pair<Int, Int>? = null): Active {
        val live = contexts.filter {
            val age = nowSec - it.issuedAtSec
            age in -CLOCK_SKEW_SEC..MAX_AGE_SEC && (here == null || it.area == null || it.area.radiusKm == 0 || withinKm(it.area, here))
        }
        if (live.isEmpty()) return Active.NONE
        // "Aging" only when every live declaration is past the fresh window: one fresh declaration vouches for the area.
        return Active(live.mapTo(java.util.EnumSet.noneOf(DisasterType::class.java)) { it.type }, live.all { nowSec - it.issuedAtSec > FRESH_SEC })
    }

    private fun withinKm(a: DisasterContext.Area, here: Pair<Int, Int>): Boolean {
        val lat1 = Math.toRadians(a.latE5 / 1e5); val lat2 = Math.toRadians(here.first / 1e5)
        val dLat = lat2 - lat1; val dLon = Math.toRadians((here.second - a.lonE5) / 1e5)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2).let { it * it }
        return 6371.0 * 2 * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0))) <= a.radiusKm
    }
}
