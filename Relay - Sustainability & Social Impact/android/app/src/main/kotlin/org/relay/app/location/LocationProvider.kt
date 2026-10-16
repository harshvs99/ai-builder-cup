package org.relay.app.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.relay.app.ble.BlePermissions

/**
 * Location through the framework [LocationManager] (no Google Play Services). While [start]ed it listens to GPS
 * (and NETWORK when enabled), keeps the best recent fix in [fix] and stops by itself after [WINDOW_MS] or once the
 * fix is good enough. Call [start]/[stop]/[reset] from the main thread (they re-post themselves if not).
 */
object LocationProvider {
    const val WINDOW_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> get() = _fix

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> get() = _listening

    private var manager: LocationManager? = null
    private val stopTask = Runnable { stop() }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = accept(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Required on API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun Location.toFix(): Fix = Fix(
        latitude, longitude, if (hasAccuracy()) accuracy else Float.NaN,
        if (time > 0) time else System.currentTimeMillis(),
    )

    private fun accept(loc: Location) {
        val now = System.currentTimeMillis()
        val candidate = loc.toFix()
        if (FixPolicy.isBetter(_fix.value, candidate, now)) _fix.value = candidate
        if (FixPolicy.isGoodEnough(candidate)) stop()
    }

    /** Whether the system Location toggle is on (all providers). */
    fun isSystemEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try { LocationManagerCompat.isLocationEnabled(lm) } catch (_: Exception) { false }
    }

    /**
     * Begin (or extend) a [WINDOW_MS] listening window and seed [fix] from a recent last-known location.
     * Returns false when there is no permission or Location is off (nothing is registered then).
     */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = ctx.applicationContext
            handler.post { start(app) }
            return true
        }
        if (!BlePermissions.hasLocation(ctx) || !isSystemEnabled(ctx)) return false
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        manager = lm
        val now = System.currentTimeMillis()

        _fix.value?.let { if (!FixPolicy.isUsable(it, now)) _fix.value = null }
        if (!_listening.value) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { p ->
                try { lm.isProviderEnabled(p) } catch (_: Exception) { false }
            }
            for (p in providers) {
                val last = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null }
                if (last != null) {
                    val f = last.toFix()
                    if (FixPolicy.isUsable(f, now) && FixPolicy.isBetter(_fix.value, f, now)) _fix.value = f
                }
            }
            var registered = 0
            for (p in providers) {
                try {
                    lm.requestLocationUpdates(p, 1_000L, 0f, listener, Looper.getMainLooper())
                    registered++
                } catch (_: Exception) {
                    // SecurityException (permission revoked) / IllegalArgumentException (no such provider)
                }
            }
            if (registered == 0) { stop(); return false }
            _listening.value = true
        }
        handler.removeCallbacks(stopTask)
        handler.postDelayed(stopTask, WINDOW_MS)
        return true
    }

    /** Stop listening (idempotent). The last [fix] is kept until [reset]. */
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { stop() }; return }
        handler.removeCallbacks(stopTask)
        try { manager?.removeUpdates(listener) } catch (_: Exception) {}
        _listening.value = false
    }

    /** Forget the kept fix (after a NEED was sent, so the next one does not reuse it). */
    fun reset() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { reset() }; return }
        _fix.value = null
    }
}
