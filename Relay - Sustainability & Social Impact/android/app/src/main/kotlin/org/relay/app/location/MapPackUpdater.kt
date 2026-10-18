package org.relay.app.location

import android.content.Context
import org.relay.app.DisasterStore
import org.relay.app.MeshRepository
import org.relay.core.payload.Geo
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches the offline map pack in normal times: on app start, when [MapPackPolicy] says so (no declared disaster here, a
 * known position, no pack or a stale or far-away one). Quiet by design: any failure leaves the old pack (or none) and is
 * retried later. Needs location permission only to know where "here" is; without it nothing is fetched.
 */
object MapPackUpdater {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "mappack").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private const val PREFS = "relay_mappack"
    private const val KEY_LAST_ATTEMPT = "last_attempt_ms"

    /** Fetch in the background if the policy allows; [force] skips the retry spacing (the "Update now" button). */
    fun maybeUpdate(ctx: Context, force: Boolean = false) {
        val app = ctx.applicationContext
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try { run(app, force) } catch (_: Exception) { } finally { running.set(false) }
        }
    }

    /** True while a disaster is declared for this phone's area: no map downloads then. */
    fun inCrisis(ctx: Context, here: Pair<Int, Int>?): Boolean = DisasterStore.get(ctx).active(here = here).types.isNotEmpty()

    private fun run(ctx: Context, force: Boolean) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = if (force) 0L else prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        // Too soon to try again: do not even switch the location on.
        val spacing = if (MapPack.stored(ctx) == null) MapPackPolicy.RETRY_NO_PACK_MS else MapPackPolicy.RETRY_HAVE_PACK_MS
        if (now - last < spacing) return
        // The kept fix, else start a short listening window (it seeds from the last known location at once).
        if (LocationProvider.fix.value == null) LocationProvider.start(ctx)
        Thread.sleep(if (LocationProvider.fix.value == null) 5_000 else 0)
        val fix = LocationProvider.fix.value ?: return
        val here = Geo.toE5(fix.lat) to Geo.toE5(fix.lon)
        if (!MapPackPolicy.shouldFetch(now, inCrisis(ctx, here), here, MapPack.stored(ctx), last)) return
        prefs.edit().putLong(KEY_LAST_ATTEMPT, now).apply()
        val settings = MeshRepository.settings.current
        val body = fetch(settings.backendUrl, settings.deviceKey, fix.lat, fix.lon) ?: return
        MapPack.save(ctx, body, now)
    }

    private fun fetch(baseUrl: String, deviceKey: String, lat: Double, lon: Double): String? {
        val q = String.format(Locale.ROOT, "lat=%.5f&lon=%.5f&radius_km=%d", lat, lon, MapPackPolicy.RADIUS_KM)
        val url = try { URL(baseUrl.trim().trimEnd('/') + "/v1/mappack?" + q) } catch (_: Exception) { return null }
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 10_000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Relay-Key", deviceKey)
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.takeIf { it.length <= 512 * 1024 }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
