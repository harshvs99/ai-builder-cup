package org.relay.app.ble

/**
 * Mesh hours remaining from battery percentage: a deliberately simple drain model.
 *
 * ASSUMPTIONS (not measured; replace with the 2-hour drain test in PLAN.md section 11):
 *  - phone idle with screen off, radios quiet: [BASE_PCT_PER_HOUR] % of battery per hour
 *  - connectable advertising always on: [ADVERTISE_PCT_PER_HOUR]
 *  - scanning at full duty: [SCAN_PCT_PER_HOUR_FULL], scaled linearly by the mode's duty fraction
 * Resulting drain: continuous 4.0 %/h (25 h from full), 3 s / 12 s 1.92 %/h (52 h), low-battery mode 1.66 %/h.
 * Connection traffic is not modelled (the mesh is idle most of the time).
 */
object MeshEnergy {
    const val BASE_PCT_PER_HOUR = 1.0
    const val ADVERTISE_PCT_PER_HOUR = 0.4
    const val SCAN_PCT_PER_HOUR_FULL = 2.6

    fun drainPctPerHour(mode: DutyCycle.Mode): Double =
        BASE_PCT_PER_HOUR + ADVERTISE_PCT_PER_HOUR + SCAN_PCT_PER_HOUR_FULL * mode.dutyFraction

    /** Hours until empty, or null when charging or the battery level is unknown. */
    fun hoursRemaining(batteryPct: Int, charging: Boolean, mode: DutyCycle.Mode): Double? {
        if (charging || batteryPct < 0) return null
        return batteryPct.coerceAtMost(100) / drainPctPerHour(mode)
    }
}
