package org.relay.app.location

import org.relay.core.gossip.RelayFix

/**
 * Bridge from the app's [Fix] to the core protocol's [RelayFix] for LOC_HINT (SPEC 5.5, mesh-assisted location).
 * Pure Kotlin, no Android types.
 *
 * What a background relay can know about its position: [LocationProvider] only has a fix while something is
 * listening. [LocationProvider.start] opens a [LocationProvider.WINDOW_MS] (30 s) listening window and returns false
 * without the location permission or with system Location off, so a relay phone usually has a fix only while the app
 * is visible or another app is requesting location. The mesh service's foreground-service type is not `location` and
 * ACCESS_BACKGROUND_LOCATION is not requested, so Android will not deliver updates to the service in the background.
 * Hinting from the background would need both; that is deliberately not changed here (manifest permissions are
 * untouched). `LocHintResponder` handles the "no fix right now" case by calling `requestFix` and retrying briefly.
 */
object HintFix {
    /** [f] as a [RelayFix]. A fix without a timestamp (atMs <= 0) counts as taken at [nowMs]. */
    fun toRelayFix(f: Fix, nowMs: Long): RelayFix =
        RelayFix(f.lat, f.lon, f.accuracyM, if (f.atMs > 0L) f.atMs else nowMs)

    /** The current fix as a [RelayFix], or null when there is none or it is too old ([FixPolicy.isUsable]). */
    fun current(fix: Fix?, nowMs: Long): RelayFix? =
        fix?.takeIf { FixPolicy.isUsable(it, nowMs) }?.let { toRelayFix(it, nowMs) }

    /** [current] for the live [LocationProvider.fix] value. */
    fun fromProvider(nowMs: Long = System.currentTimeMillis()): RelayFix? = current(LocationProvider.fix.value, nowMs)
}
