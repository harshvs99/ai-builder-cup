package org.relay.app.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** A position fix. [atMs] is the epoch ms the fix was taken (0 if unknown). */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val atMs: Long = 0L) {
    val locAcc: Int get() = LocAccuracy.bucket(accuracyM)
}

/** Pure rules for which fix to keep. No Android types. */
object FixPolicy {
    /** A fix older than this is not offered as the current position (also the cap for last-known seeds). */
    const val MAX_AGE_MS = 120_000L

    /** A kept fix older than this is replaced by any fresh one, even a less accurate one. */
    const val STALE_AFTER_MS = 45_000L

    /** Accuracy at which listening stops early: it already maps to the best loc_acc bucket (< 10 m). */
    const val GOOD_ENOUGH_M = 10f

    private fun acc(f: Fix) = if (f.accuracyM.isNaN() || f.accuracyM < 0f) Float.MAX_VALUE else f.accuracyM

    fun isUsable(f: Fix, nowMs: Long): Boolean = f.atMs <= 0L || nowMs - f.atMs <= MAX_AGE_MS

    fun isGoodEnough(f: Fix): Boolean = acc(f) < GOOD_ENOUGH_M

    /** True when [candidate] should replace [current]. */
    fun isBetter(current: Fix?, candidate: Fix, nowMs: Long): Boolean {
        if (current == null) return true
        if (current.atMs > 0 && nowMs - current.atMs > STALE_AFTER_MS) return true
        return acc(candidate) <= acc(current)
    }
}

/** What the confirm card shows about location. */
enum class LocUi { NO_PERMISSION, OFF, ACQUIRING, READY }

object LocationState {
    fun ui(hasPermission: Boolean, systemEnabled: Boolean, fix: Fix?, nowMs: Long): LocUi {
        if (fix != null && FixPolicy.isUsable(fix, nowMs)) return LocUi.READY
        if (!hasPermission) return LocUi.NO_PERMISSION
        if (!systemEnabled) return LocUi.OFF
        return LocUi.ACQUIRING
    }

    fun accuracyText(accuracyM: Float): String = when {
        accuracyM.isNaN() || accuracyM < 0f -> "accuracy unknown"
        accuracyM >= 1000f -> "±%.1f km".format(java.util.Locale.ROOT, accuracyM / 1000f)
        else -> "±${accuracyM.roundToInt()} m"
    }

    fun label(ui: LocUi, fix: Fix?): String = when (ui) {
        LocUi.READY -> "📍 " + accuracyText(fix?.accuracyM ?: Float.NaN)
        LocUi.ACQUIRING -> "📍 Getting location…"
        LocUi.OFF -> "📍 Location is off"
        LocUi.NO_PERMISSION -> "📍 No permission"
    }

    /** Shown under the location line when the NEED would go out without coordinates. */
    fun fallbackNote(ui: LocUi): String? = if (ui == LocUi.READY) null else "Rescuers will rely on the landmark"

    /** Only an in-progress acquisition is worth waiting for; off / no permission cannot produce a fix. */
    fun shouldWaitForFix(ui: LocUi): Boolean = ui == LocUi.ACQUIRING

    /** True when a stopped tracker should be restarted for a visible confirm card. */
    fun shouldRestart(ui: LocUi, listening: Boolean, cardVisible: Boolean): Boolean =
        cardVisible && ui == LocUi.ACQUIRING && !listening
}

object FixWait {
    const val DEFAULT_TIMEOUT_MS = 10_000L

    /**
     * Returns the first non-null fix emitted by [fixes] (the current value counts), or null when [timeoutMs]
     * passes or [skip] becomes true ("Send now"). Never blocks longer than [timeoutMs].
     */
    suspend fun await(fixes: Flow<Fix?>, skip: Flow<Boolean>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Fix? =
        withTimeoutOrNull(timeoutMs) {
            combine(fixes, skip) { f, s -> f to s }.first { (f, s) -> f != null || s }.first
        }
}
