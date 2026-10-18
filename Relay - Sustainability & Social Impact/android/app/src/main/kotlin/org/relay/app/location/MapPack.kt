package org.relay.app.location

import android.content.Context
import org.json.JSONObject
import org.relay.core.payload.Geo
import org.relay.core.payload.Lang
import org.relay.core.triage.MapContext
import org.relay.core.triage.MapPlace
import org.relay.core.triage.PlaceKind

/**
 * The offline map pack bundled with the app (`assets/mappack.json`, built by `scripts/gen_mappack.py` from the backend
 * gazetteer): shelters, hospitals and rivers, with their positions. Triage uses it to name the nearest shelter or
 * hospital and the water to keep away from; it works with no network at all.
 */
object MapPack {
    private const val ASSET = "mappack.json"

    /** A fix older than this is still good for "the shelter is 3 km north" (people on foot do not move far). */
    const val MAX_FIX_AGE_MS = 30 * 60_000L

    @Volatile private var cached: List<MapPlace>? = null

    fun places(ctx: Context): List<MapPlace> = cached ?: synchronized(this) {
        cached ?: (try {
            ctx.applicationContext.assets.open(ASSET).use { parse(it.readBytes().toString(Charsets.UTF_8)) }
        } catch (_: Exception) {
            emptyList()
        }).also { cached = it }
    }

    /** What the map says around [fix]; null without a usable fix or a pack. */
    fun around(ctx: Context, fix: Fix?, nowMs: Long = System.currentTimeMillis()): MapContext? {
        if (fix == null || (fix.atMs > 0 && nowMs - fix.atMs > MAX_FIX_AGE_MS)) return null
        return MapContext.around(places(ctx), Geo.toE5(fix.lat) to Geo.toE5(fix.lon), fix.accuracyM)
    }

    /** Places in a pack; malformed entries are skipped, a malformed pack is empty. Never throws. */
    fun parse(json: String): List<MapPlace> = try {
        val arr = JSONObject(json).optJSONArray("places")
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = PlaceKind.fromKey(o.optString("kind")) ?: return@mapNotNull null
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (!o.has("lat_e5") || !o.has("lon_e5")) return@mapNotNull null
            val lat = o.optInt("lat_e5"); val lon = o.optInt("lon_e5")
            if (lat !in -9_000_000..9_000_000 || lon !in -18_000_000..18_000_000) return@mapNotNull null
            val names = o.optJSONObject("names")?.let { n ->
                n.keys().asSequence().mapNotNull { k -> Lang.entries.firstOrNull { it.tag == k }?.let { it to n.optString(k) } }
                    .filter { it.second.isNotBlank() }.toMap()
            }.orEmpty()
            MapPlace(name, kind, lat, lon, names)
        }
    } catch (_: Exception) {
        emptyList()
    }
}
