package org.relay.app.ble

/**
 * Scan duty cycle. Advertising is always on; only the scanner is cycled.
 *
 * Choice (kept deliberately simple): below [LOW_BATTERY_PCT] and not charging, scan 3 s every 30 s; else with the
 * app in the foreground (any screen: the user is watching and the screen is the dominant drain anyway), scan
 * continuously; else scan 3 s / idle 12 s. The cycle is checked on the transport's 500 ms tick, so windows are
 * accurate to about half a second. Every mode stays far below Android's 5 scan starts per 30 s ([ScanThrottle]).
 */
object DutyCycle {
    const val LOW_BATTERY_PCT = 20

    enum class Mode(val label: String, val scanMs: Long?, val idleMs: Long) {
        /** Foreground: one long-lived scan. */
        CONTINUOUS("foreground, continuous scan", null, 0),
        NORMAL("scan 3 s / idle 12 s", 3_000, 12_000),
        LOW_BATTERY("battery under 20%: scan 3 s / idle 27 s", 3_000, 27_000);

        /** Fraction of time the scanner is on. */
        val dutyFraction: Double get() = if (scanMs == null) 1.0 else scanMs.toDouble() / (scanMs + idleMs)

        /** Scan starts per 30 s window (continuous counts as one). */
        val startsPer30s: Int get() = if (scanMs == null) 1 else Math.ceil(30_000.0 / (scanMs + idleMs)).toInt()
    }

    /** [batteryPct] < 0 means unknown, treated as not low. */
    fun choose(batteryPct: Int, charging: Boolean, foreground: Boolean): Mode = when {
        batteryPct in 0 until LOW_BATTERY_PCT && !charging -> Mode.LOW_BATTERY
        foreground -> Mode.CONTINUOUS
        else -> Mode.NORMAL
    }
}
