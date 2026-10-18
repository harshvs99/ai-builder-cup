package org.relay.core.ready

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A position on the earth (the phone's last known fix, or a shelter). */
data class GeoPoint(val lat: Double, val lon: Double)

/** A shelter with its distance from the phone, when the phone knows where it is. */
data class ShelterRow(val shelter: ReadyShelter, val distanceKm: Double?)

/** A pin on the offline mini map, as fractions (0..1) of its width and height; y grows downwards. */
data class MapPin(val shelterId: String, val x: Double, val y: Double, val selected: Boolean)

/**
 * Which shelters the phone shows and how (contract v2 section 4). Production builds show only a list an officer verified
 * (`shelters_source == "published"`); a demo list shows only in demo builds, with a "Demo" badge. Never invents data: a list
 * with no shelters is empty, and a phone with no pack shows nothing.
 */
object ShelterLogic {
    /** The shelters this build may show from [pack]. */
    fun visible(pack: ReadyPack?, demoBuild: Boolean): List<ReadyShelter> {
        if (pack == null) return emptyList()
        return when {
            pack.isPublished -> pack.shelters.filter { !it.demo }
            demoBuild -> pack.shelters
            else -> emptyList()
        }
    }

    /** True for a row that needs the "Demo" badge. */
    fun isDemo(pack: ReadyPack?, s: ReadyShelter): Boolean = s.demo || pack?.isPublished == false

    /** Nearest first when [from] is known (ties and unknown positions by name), else by name. */
    fun sorted(shelters: List<ReadyShelter>, from: GeoPoint?): List<ShelterRow> {
        val rows = shelters.map { ShelterRow(it, from?.let { f -> distanceKm(f.lat, f.lon, it.lat, it.lon) }) }
        return rows.sortedWith(compareBy<ShelterRow, Double?>(nullsLast()) { it.distanceKm }.thenBy { it.shelter.nameEn.lowercase() })
    }

    /** Great-circle distance in km (haversine). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0088
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** Coordinates as shown and copied: "19.81200, 85.82800". */
    fun coordinatesText(s: ReadyShelter): String = String.format(java.util.Locale.ROOT, "%.5f, %.5f", s.lat, s.lon)

    /** The Android maps intent: `geo:lat,lon?q=lat,lon(name)`. The name is percent-encoded and its brackets are dropped. */
    fun geoUri(s: ReadyShelter): String {
        val name = java.net.URLEncoder.encode(s.nameEn.replace("(", " ").replace(")", " ").trim(), "UTF-8").replace("+", "%20")
        return String.format(java.util.Locale.ROOT, "geo:%.5f,%.5f?q=%.5f,%.5f(%s)", s.lat, s.lon, s.lat, s.lon, name)
    }

    /** The sentence "Speak" reads: the name, and the distance when known. */
    fun spokenLine(row: ShelterRow, kmWord: (Double) -> String): String =
        row.shelter.name + (row.distanceKm?.let { ", " + kmWord(it) } ?: "")

    /**
     * Pins for the mini map that is drawn without map tiles (no network, no bundled imagery): the shelters scaled into the box
     * that holds them all, with a margin of [margin] on each side. One shelter sits in the middle.
     */
    fun pins(shelters: List<ReadyShelter>, selectedId: String?, margin: Double = 0.12): List<MapPin> {
        if (shelters.isEmpty()) return emptyList()
        val minLat = shelters.minOf { it.lat }; val maxLat = shelters.maxOf { it.lat }
        val minLon = shelters.minOf { it.lon }; val maxLon = shelters.maxOf { it.lon }
        val midLat = (minLat + maxLat) / 2
        val kx = cos(Math.toRadians(midLat)) // a degree of longitude is shorter away from the equator
        val w = max((maxLon - minLon) * kx, 1e-6); val h = max(maxLat - minLat, 1e-6)
        val scale = (1 - 2 * margin) / max(w, h)
        return shelters.map { s ->
            val x = 0.5 + ((s.lon - (minLon + maxLon) / 2) * kx) * scale
            val y = 0.5 - (s.lat - midLat) * scale
            MapPin(s.id, x, y, s.id == selectedId)
        }
    }
}

/** `GET /v1/public/shelters?district=` (no key): the district's published, officer-verified list, for a phone with signal and no pack. */
object PublicShelters {
    /**
     * Turns the response into a pack-shaped body (no warning, no checklist, source `published`) plus its parsed form, so one stored copy
     * serves the list, the detail and the Ready footer. Null for anything that is not a non-empty published list.
     */
    fun toPack(json: String?, nowSec: Long): Pair<String, ReadyPack>? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val district = o.getString("district")
            val published = o.optLong("published_at", 0)
            val items = o.getJSONArray("shelters")
            val shelters = JSONArray()
            for (i in 0 until items.length()) {
                val s = items.optJSONObject(i) ?: continue
                if (!s.has("lat") || !s.has("lon") || s.isNull("lat") || s.isNull("lon")) continue // never plot a shelter without a position
                val name = s.optString("name").ifBlank { continue }
                shelters.put(
                    JSONObject().put("id", s.optString("id")).put("name", s.optString("name_local").ifBlank { name }).put("name_en", name)
                        .put("kind", s.optString("kind", "other")).put("lat", s.getDouble("lat")).put("lon", s.getDouble("lon"))
                        .put("capacity", if (s.has("capacity") && !s.isNull("capacity")) s.getInt("capacity") else JSONObject.NULL)
                        .put("facilities", JSONArray()).put("block", s.optString("address").ifBlank { JSONObject.NULL }).put("demo", false),
                )
            }
            if (shelters.length() == 0) return null
            val pack = JSONObject().put("district", district).put("lang", "en").put("generated_at", nowSec).put("version", "public-$published")
                .put("warning", JSONObject.NULL).put("shelters", shelters).put("shelters_source", "published")
                .put("shelters_published_at", published).put("checklist", JSONArray()).put("checklist_lang", "en")
            val raw = pack.toString()
            ReadyJson.parsePack(raw)?.let { raw to it }
        } catch (e: JSONException) {
            null
        }
    }
}
