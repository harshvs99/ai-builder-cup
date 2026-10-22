package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.relay.core.ready.GeoPoint
import org.relay.core.ready.PackQuery
import org.relay.core.ready.PublicShelters
import org.relay.core.ready.ReadyApi
import org.relay.core.ready.ReadyJson
import org.relay.core.ready.ReadyPack
import org.relay.core.ready.ReadyRefresher
import org.relay.core.ready.ReadyShelter
import org.relay.core.ready.ReadyStore
import org.relay.core.ready.RefreshInput
import org.relay.core.ready.Household
import org.relay.core.ready.ShelterLogic

/** Shelter list rules (contract v2 section 4). Mirrored by Swift `ShelterTests`. */
class ShelterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(id: String, name: String, lat: Double, lon: Double, demo: Boolean = false) =
        ReadyShelter(id, name, name, "school", lat, lon, 100, emptyList(), null, demo)

    private fun pack(source: String, vararg s: ReadyShelter) = ReadyPack("puri", "en", 1, "v", null, s.toList(), source, null, emptyList(), "en")

    private val near = sh("n", "Near School", 19.812, 85.828)
    private val far = sh("f", "Far Camp", 19.9, 85.9)

    @Test fun productionBuildsShowOnlyAPublishedList() {
        val published = pack("published", near, far)
        assertEquals(2, ShelterLogic.visible(published, demoBuild = false).size)
        assertEquals(2, ShelterLogic.visible(published, demoBuild = true).size)
        val demo = pack("demo", near.copy(demo = true), far.copy(demo = true))
        assertTrue(ShelterLogic.visible(demo, demoBuild = false).isEmpty())
        assertEquals(2, ShelterLogic.visible(demo, demoBuild = true).size)
        assertTrue(ShelterLogic.visible(null, demoBuild = true).isEmpty()) // no pack, nothing invented
        // a demo-flagged entry inside a published list is not shown in a production build
        assertEquals(listOf("f"), ShelterLogic.visible(pack("published", near.copy(demo = true), far), false).map { it.id })
    }

    @Test fun demoRowsGetTheBadge() {
        assertTrue(ShelterLogic.isDemo(pack("demo", near), near))
        assertTrue(ShelterLogic.isDemo(pack("published", near), near.copy(demo = true)))
        assertFalse(ShelterLogic.isDemo(pack("published", near), near))
    }

    @Test fun listIsNearestFirstWhenThePhoneKnowsWhereItIsElseByName() {
        val here = GeoPoint(19.81, 85.83)
        val rows = ShelterLogic.sorted(listOf(far, near), here)
        assertEquals(listOf("n", "f"), rows.map { it.shelter.id })
        assertTrue(rows[0].distanceKm!! < 1.0 && rows[1].distanceKm!! > 10.0)
        val byName = ShelterLogic.sorted(listOf(near, far), null)
        assertEquals(listOf("f", "n"), byName.map { it.shelter.id }) // "Far Camp" < "Near School"
        assertTrue(byName.all { it.distanceKm == null })
    }

    @Test fun distanceIsGreatCircleKilometres() {
        assertEquals(0.0, ShelterLogic.distanceKm(19.8, 85.8, 19.8, 85.8), 1e-9)
        assertEquals(111.19, ShelterLogic.distanceKm(0.0, 0.0, 1.0, 0.0), 0.05)
        assertEquals(1311.5, ShelterLogic.distanceKm(19.81, 85.83, 28.614, 77.209), 5.0) // Puri to Delhi
    }

    @Test fun coordinatesAndDirectionsAreBuiltFromTheShelter() {
        val s = sh("n", "Zilla School (Puri)", 19.812, 85.828)
        assertEquals("19.81200, 85.82800", ShelterLogic.coordinatesText(s))
        assertEquals("geo:19.81200,85.82800?q=19.81200,85.82800(Zilla%20School%20%20Puri)", ShelterLogic.geoUri(s))
        assertEquals("Near School, 1.2 km", ShelterLogic.spokenLine(ShelterLogic.sorted(listOf(near), null)[0].copy(distanceKm = 1.2)) { "%.1f km".format(it) })
        assertEquals("Near School", ShelterLogic.spokenLine(ShelterLogic.sorted(listOf(near), null)[0]) { "x" })
    }

    @Test fun miniMapPinsFitInsideTheMarginAndKeepTheirRelativePositions() {
        assertTrue(ShelterLogic.pins(emptyList(), null).isEmpty())
        val one = ShelterLogic.pins(listOf(near), "n").single()
        assertEquals(0.5, one.x, 1e-9); assertEquals(0.5, one.y, 1e-9); assertTrue(one.selected)
        val pins = ShelterLogic.pins(listOf(near, far), "f")
        assertTrue(pins.all { it.x in 0.11..0.89 && it.y in 0.11..0.89 })
        val pn = pins[0]; val pf = pins[1]
        assertTrue(pf.x > pn.x) // further east is further right
        assertTrue(pf.y < pn.y) // further north is higher up
        assertFalse(pn.selected); assertTrue(pf.selected)
    }

    // ------------------------------------------------------------ the public list
    private val publicJson = """{"district":"puri","published_at":1792300000,"shelters":[
        {"id":"a","name":"Zilla School","name_local":"ଜିଲ୍ଲା ସ୍କୁଲ","kind":"school","address":"Station Rd","lat":19.807,"lon":85.835,"capacity":600,"source_url":"https://x"},
        {"id":"b","name":"No position","kind":"school","lat":null,"lon":null},
        {"id":"c","name":"Hospital","kind":"hospital","lat":19.818,"lon":85.824}],"source_note":"Verified"}"""

    @Test fun thePublicListBecomesAPackWithoutAWarningAndSkipsEntriesWithoutAPosition() {
        val (raw, p) = PublicShelters.toPack(publicJson, 1792340000)!!
        assertEquals("puri", p.district); assertNull(p.warning); assertTrue(p.isPublished); assertEquals(1792300000L, p.sheltersPublishedAt)
        assertEquals(listOf("a", "c"), p.shelters.map { it.id })
        assertEquals("ଜିଲ୍ଲା ସ୍କୁଲ", p.shelters[0].name); assertEquals("Zilla School", p.shelters[0].nameEn); assertEquals("Station Rd", p.shelters[0].block)
        assertEquals(600, p.shelters[0].capacity); assertNull(p.shelters[1].capacity)
        assertEquals("public-1792300000", p.version)
        assertNotNull(ReadyJson.parsePack(raw))
        assertNull(PublicShelters.toPack(null, 0)); assertNull(PublicShelters.toPack("garbage", 0))
        assertNull(PublicShelters.toPack("""{"district":"puri","published_at":1,"shelters":[]}""", 0))
    }

    private class Api(var publicBody: String?) : ReadyApi {
        var asked = ArrayList<String>()
        override fun pack(query: PackQuery, lang: String): String = error("not used")
        override fun plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household): String = error("not used")
        override fun audio(audioUrl: String): ByteArray? = null
        override fun publicShelters(district: String): String? { asked += district; return publicBody }
    }

    private val input = RefreshInput(true, null, null, "en", Household())

    @Test fun aPhoneWithSignalAndADistrictButNoPackStoresThePublishedList() {
        val store = ReadyStore(tmp.newFolder())
        val api = Api(publicJson)
        val r = ReadyRefresher(api, store) { 1_792_340_000_000L }
        assertFalse(r.refreshPublicShelters(input)) // no district known yet
        store.setDistrict("puri")
        assertFalse(r.refreshPublicShelters(input.copy(connected = false)))
        assertTrue(r.refreshPublicShelters(input))
        assertEquals(listOf("puri"), api.asked)
        val snap = store.load()
        assertEquals(2, snap.pack!!.shelters.size); assertEquals(0L, snap.lastPackFetchMs) // not counted as a pack fetch
        assertFalse(r.refreshPublicShelters(input)) // a pack is stored now: nothing to do
    }

    @Test fun nothingPublishedYetStoresNothing() {
        val store = ReadyStore(tmp.newFolder()); store.setDistrict("puri")
        assertFalse(ReadyRefresher(Api(null), store).refreshPublicShelters(input))
        assertNull(store.load().pack)
    }
}
