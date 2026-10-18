package org.relay.app.location

import android.content.Context
import org.json.JSONObject
import org.relay.app.store.AtomicFiles
import org.relay.core.payload.Geo
import org.relay.core.payload.Lang
import org.relay.core.triage.MapContext
import org.relay.core.triage.MapPlace
import org.relay.core.triage.PlaceKind
import java.io.File

/** A map pack as kept on the phone: the places, the circle it covers and when it was fetched. */
data class MapPackData(
    val places: List<MapPlace>,
    val centreE5: Pair<Int, Int>,
    val radiusKm: Int,
    /** Epoch ms this phone fetched it. */
    val fetchedAtMs: Long,
)

/**
 * The offline map pack: shelters, hospitals and rivers around where this phone usually is, fetched from the backend
 * (`GET /v1/mappack`) in normal times by [MapPackUpdater] and kept in app storage. Triage uses it to name the nearest
 * shelter or hospital and the water to keep away from, with no network. No pack (never fetched, or fetched somewhere
 * else) = triage works as before, without place advice.
 */
object MapPack {
    private const val FILE = "mappack.json"

    /** A fix older than this is still good for "the shelter is 3 km north" (people on foot do not move far). */
    const val MAX_FIX_AGE_MS = 30 * 60_000L

    @Volatile private var cached: MapPackData? = null
    @Volatile private var loaded = false

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** The stored pack, or null when there is none. */
    fun stored(ctx: Context): MapPackData? {
        if (loaded) return cached
        synchronized(this) {
            if (!loaded) {
                cached = AtomicFiles.readOrNull(file(ctx))?.let { parse(it.toString(Charsets.UTF_8)) }
                loaded = true
            }
            return cached
        }
    }

    /** Keep a pack the backend sent ([json], its response body). False when it does not parse: the old pack stays. */
    fun save(ctx: Context, json: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val withTime = try { JSONObject(json).put("fetched_at_ms", nowMs).toString() } catch (_: Exception) { return false }
        val data = parse(withTime) ?: return false
        synchronized(this) {
            AtomicFiles.write(file(ctx), withTime.toByteArray(Charsets.UTF_8))
            cached = data
            loaded = true
        }
        return true
    }

    /** What the stored pack says around [fix]; null without a usable fix or a pack. */
    fun around(ctx: Context, fix: Fix?, nowMs: Long = System.currentTimeMillis()): MapContext? {
        if (fix == null || (fix.atMs > 0 && nowMs - fix.atMs > MAX_FIX_AGE_MS)) return null
        val pack = stored(ctx) ?: return null
        return MapContext.around(pack.places, Geo.toE5(fix.lat) to Geo.toE5(fix.lon), fix.accuracyM)
    }

    /** A pack; malformed places are skipped, a malformed pack is null. Never throws. */
    fun parse(json: String): MapPackData? = try {
        val o = JSONObject(json)
        val centre = o.getJSONObject("centre")
        val arr = o.getJSONArray("places")
        val places = (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = PlaceKind.fromKey(p.optString("kind")) ?: return@mapNotNull null
            val name = p.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!p.has("lat_e5") || !p.has("lon_e5")) return@mapNotNull null
            val lat = p.optInt("lat_e5"); val lon = p.optInt("lon_e5")
            if (lat !in -9_000_000..9_000_000 || lon !in -18_000_000..18_000_000) return@mapNotNull null
            val names = p.optJSONObject("names")?.let { n ->
                n.keys().asSequence().mapNotNull { k -> Lang.fromTag(k)?.let { it to n.optString(k) } }
                    .filter { it.second.isNotBlank() }.toMap()
            }.orEmpty()
            MapPlace(name, kind, lat, lon, names)
        }
        MapPackData(
            places, Geo.toE5(centre.getDouble("lat")) to Geo.toE5(centre.getDouble("lon")),
            o.getInt("radius_km"), o.optLong("fetched_at_ms", 0L),
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * When to fetch a pack. Never during a declared disaster in this phone's area: the network then carries requests for
 * help, and a phone that has no pack by then works without one. Otherwise: no pack yet, a pack older than [MAX_AGE_MS],
 * or a phone that has moved well outside the pack's circle. Failed attempts are spaced out.
 */
object MapPackPolicy {
    const val MAX_AGE_MS = 30L * 24 * 3600_000
    const val RETRY_NO_PACK_MS = 15 * 60_000L
    const val RETRY_HAVE_PACK_MS = 6 * 3600_000L
    const val RADIUS_KM = 30

    fun shouldFetch(nowMs: Long, inCrisis: Boolean, here: Pair<Int, Int>?, pack: MapPackData?, lastAttemptMs: Long): Boolean {
        if (inCrisis || here == null) return false
        if (nowMs - lastAttemptMs < (if (pack == null) RETRY_NO_PACK_MS else RETRY_HAVE_PACK_MS)) return false
        if (pack == null || nowMs - pack.fetchedAtMs > MAX_AGE_MS) return true
        // Moved: refetch once the phone is past half the pack's radius, so the pack still covers the surroundings.
        return MapContext.distanceM(here, pack.centreE5) > pack.radiusKm * 1000.0 / 2
    }
}
