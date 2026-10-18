package org.relay.app

import android.content.Context
import org.relay.core.payload.Broadcast
import org.relay.core.payload.BroadcastKind
import org.relay.core.triage.DisasterContext
import org.relay.core.triage.DisasterGate
import org.relay.core.triage.DisasterType

/**
 * Disasters the control room has declared, kept on the phone. Fed by the broadcast channel (a declaration arrives over the
 * mesh and is carried here) and by the developer setter; read by triage, which asks disaster-specific questions only
 * while a declaration is inside the [DisasterGate] window. Entries older than the window are dropped on every write.
 *
 * The control room switches a disaster off with an all-clear, without waiting for the 24 h to run out. Broadcasts reach
 * a phone in any order (a gateway, a neighbour, the replay at start-up), so the time of the latest all-clear per disaster
 * is kept: a declaration issued at or before it is ignored whenever it arrives, and one issued after it switches the
 * disaster back on.
 */
class DisasterStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relay_disaster", Context.MODE_PRIVATE)

    fun all(): List<DisasterContext> = decode(prefs.getString(KEY, "").orEmpty())

    /** Adds or refreshes a declaration of the same type: the newest one for a type wins. */
    @Synchronized fun put(ctx: DisasterContext, nowSec: Long = System.currentTimeMillis() / 1000) {
        prefs.edit().putString(KEY, encode(withDeclaration(all(), ctx, nowSec, clearedAt()))).apply()
    }

    /** Latest all-clear per disaster still worth remembering (unix seconds). */
    fun clearedAt(): Map<DisasterType, Long> = decodeCleared(prefs.getString(KEY_CLEARED, "").orEmpty())

    /**
     * A control-room broadcast arrived (or was replayed from the mesh store at start-up): a declaration is added with its
     * area; a "cleared" ends every declaration of that disaster issued at or before it. Idempotent.
     */
    @Synchronized fun apply(b: Broadcast, nowSec: Long = System.currentTimeMillis() / 1000) {
        if (!b.isLive(nowSec)) return
        when (b.kind) {
            BroadcastKind.DISASTER -> put(
                DisasterContext(b.disaster, b.issuedAt, if (b.radiusKm > 0) DisasterContext.Area(b.latE5, b.lonE5, b.radiusKm) else null),
                nowSec,
            )
            BroadcastKind.DISASTER_CLEARED -> prefs.edit()
                .putString(KEY, encode(afterCleared(all(), b.disaster, b.issuedAt)))
                .putString(KEY_CLEARED, encodeCleared(withCleared(clearedAt(), b.disaster, b.issuedAt, nowSec)))
                .apply()
        }
    }

    @Synchronized fun clear() { prefs.edit().remove(KEY).remove(KEY_CLEARED).apply() }

    /** [here] is the phone's last known position (degrees ×1e5) so an area-limited declaration can be checked; null = unknown. */
    fun active(nowSec: Long = System.currentTimeMillis() / 1000, here: Pair<Int, Int>? = null): DisasterGate.Active =
        DisasterGate.active(all(), nowSec, here)

    /** Hours since each live declaration was issued, by type key (for the model's prompt). */
    fun agesHours(nowSec: Long = System.currentTimeMillis() / 1000): Map<String, Long> =
        all().filter { it.type in active(nowSec).types }.associate { it.type.key to ((nowSec - it.issuedAtSec) / 3600).coerceAtLeast(0) }

    companion object {
        private const val KEY = "declared"
        private const val KEY_CLEARED = "cleared"
        @Volatile private var instance: DisasterStore? = null
        fun get(ctx: Context): DisasterStore = instance ?: synchronized(this) { instance ?: DisasterStore(ctx).also { instance = it } }

        /**
         * [list] plus [ctx]: the newest declaration per type wins, and anything past the 24 h window is dropped. A
         * declaration issued at or before the latest all-clear of its type ([cleared]) is ignored: it was switched off.
         */
        internal fun withDeclaration(
            list: List<DisasterContext>, ctx: DisasterContext, nowSec: Long, cleared: Map<DisasterType, Long> = emptyMap(),
        ): List<DisasterContext> {
            if (ctx.issuedAtSec <= (cleared[ctx.type] ?: Long.MIN_VALUE)) return list.filter { nowSec - it.issuedAtSec <= DisasterGate.MAX_AGE_SEC }
            val kept = list.filter { it.type != ctx.type || it.issuedAtSec > ctx.issuedAtSec }
            return (kept + ctx.takeIf { c -> kept.none { it.type == c.type } }).filterNotNull()
                .filter { nowSec - it.issuedAtSec <= DisasterGate.MAX_AGE_SEC }
        }

        /** [list] without the declarations of [type] issued at or before [clearedAtSec]; a later declaration survives. */
        internal fun afterCleared(list: List<DisasterContext>, type: DisasterType, clearedAtSec: Long): List<DisasterContext> =
            list.filterNot { it.type == type && it.issuedAtSec <= clearedAtSec }

        /** [cleared] with an all-clear of [type] at [atSec]; the latest per type wins, ones past any declaration's life are dropped. */
        internal fun withCleared(cleared: Map<DisasterType, Long>, type: DisasterType, atSec: Long, nowSec: Long): Map<DisasterType, Long> =
            (cleared + (type to maxOf(atSec, cleared[type] ?: Long.MIN_VALUE)))
                .filterValues { nowSec - it <= DisasterGate.MAX_AGE_SEC + DisasterGate.CLOCK_SKEW_SEC }

        internal fun encodeCleared(m: Map<DisasterType, Long>) = m.entries.joinToString(",") { "${it.key.key}:${it.value}" }
        internal fun decodeCleared(s: String): Map<DisasterType, Long> = s.split(',').mapNotNull { part ->
            val f = part.split(':')
            if (f.size != 2) return@mapNotNull null
            (DisasterType.fromKey(f[0]) ?: return@mapNotNull null) to (f[1].toLongOrNull() ?: return@mapNotNull null)
        }.toMap()

        // type:issued or type:issued:latE5:lonE5:radiusKm
        internal fun encode(l: List<DisasterContext>) = l.joinToString(",") {
            "${it.type.key}:${it.issuedAtSec}" + (it.area?.let { a -> ":${a.latE5}:${a.lonE5}:${a.radiusKm}" } ?: "")
        }
        internal fun decode(s: String): List<DisasterContext> = s.split(',').mapNotNull { part ->
            val f = part.split(':')
            if (f.size != 2 && f.size != 5) return@mapNotNull null
            val type = DisasterType.fromKey(f[0]) ?: return@mapNotNull null
            val at = f[1].toLongOrNull() ?: return@mapNotNull null
            val area = if (f.size == 5) DisasterContext.Area(
                f[2].toIntOrNull() ?: return@mapNotNull null, f[3].toIntOrNull() ?: return@mapNotNull null, f[4].toIntOrNull() ?: return@mapNotNull null,
            ) else null
            DisasterContext(type, at, area)
        }
    }
}
