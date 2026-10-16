package org.relay.core.payload

import kotlin.math.abs
import kotlin.math.floor

/** Degrees <-> 1e-5 degree integers. */
object Geo {
    /**
     * `floor(abs(v * 100000.0) + 0.5) * sign`, in Double arithmetic, exactly as the Python reference
     * encoder (`int(floor(abs(v) + 0.5)) * (1 if v >= 0 else -1)`). Deliberately not Math.round.
     * NaN maps to 0; values outside the i32 range are clamped.
     */
    fun toE5(deg: Double): Int {
        if (deg.isNaN()) return 0
        val v = deg * 100000.0
        val mag = floor(abs(v) + 0.5)
        val signed = if (v >= 0) mag else -mag
        return when {
            signed >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
            signed <= Int.MIN_VALUE.toDouble() -> Int.MIN_VALUE
            else -> signed.toInt()
        }
    }

    fun fromE5(e5: Int): Double = e5 / 100000.0
}
