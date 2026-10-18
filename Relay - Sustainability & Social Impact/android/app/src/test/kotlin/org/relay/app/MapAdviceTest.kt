package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.gemma.TriagePrompt
import org.relay.app.location.MapPack
import org.relay.app.location.MapPackPolicy
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.triage.Ask
import org.relay.core.triage.Choice
import org.relay.core.triage.DisasterGate
import org.relay.core.triage.DisasterType
import org.relay.core.triage.Fact
import org.relay.core.triage.MapContext
import org.relay.core.triage.PlaceAdvice
import org.relay.core.triage.PlaceKind
import org.relay.core.triage.TriageEngine

class MapAdviceTest {
    private val pack = """
        {"version":1,"centre":{"lat":20.065,"lon":86.12},"radius_km":30,"generated_at":0,"fetched_at_ms":1000,"places":[
          {"name":"gop cyclone shelter","kind":"shelter","lat_e5":2009000,"lon_e5":8611000,"names":{"or":"ଗୋପ ବାତ୍ୟା ଆଶ୍ରୟସ୍ଥଳ","xx":"?"}},
          {"name":"devi river embankment","kind":"water","lat_e5":2006000,"lon_e5":8613000},
          {"name":"no kind","lat_e5":1,"lon_e5":1},
          {"name":"bad","kind":"hospital","lat_e5":99999999,"lon_e5":1},
          {"name":"","kind":"hospital","lat_e5":1,"lon_e5":1}
        ]}
    """.trimIndent()

    @Test fun `the pack keeps well-formed places with their scripts and skips the rest`() {
        val p = MapPack.parse(pack)!!
        assertEquals(listOf("gop cyclone shelter", "devi river embankment"), p.places.map { it.name })
        assertEquals(PlaceKind.SHELTER, p.places[0].kind)
        assertEquals(mapOf(Lang.OR to "ଗୋପ ବାତ୍ୟା ଆଶ୍ରୟସ୍ଥଳ"), p.places[0].names)
        assertEquals((2_006_500 to 8_612_000) to 30, p.centreE5 to p.radiusKm)
        assertEquals(1000L, p.fetchedAtMs)
        assertNull(MapPack.parse("not json"))
        assertNull(MapPack.parse("""{"places":[]}""")) // no centre: not a pack
    }

    // ---------------------------------------------------------------- when a phone fetches a pack

    private val day = 24 * 3600_000L
    private val home = 2_006_500 to 8_612_000
    private fun stored(fetchedAtMs: Long) = MapPack.parse(pack)!!.copy(fetchedAtMs = fetchedAtMs)

    @Test fun `a phone with no pack fetches one in normal times, never during a declared disaster`() {
        assertTrue(MapPackPolicy.shouldFetch(day, inCrisis = false, here = home, pack = null, lastAttemptMs = 0))
        assertFalse(MapPackPolicy.shouldFetch(day, inCrisis = true, here = home, pack = null, lastAttemptMs = 0))
        assertFalse(MapPackPolicy.shouldFetch(day, inCrisis = false, here = null, pack = null, lastAttemptMs = 0))
        // a failed attempt is retried after a quarter of an hour, not on every start
        assertFalse(MapPackPolicy.shouldFetch(day, false, home, null, lastAttemptMs = day - 60_000))
        assertTrue(MapPackPolicy.shouldFetch(day, false, home, null, lastAttemptMs = day - 16 * 60_000))
    }

    @Test fun `a pack is refreshed when it is a month old or the phone has moved away from it`() {
        val now = 40 * day
        assertFalse(MapPackPolicy.shouldFetch(now, false, home, stored(now - day), 0))
        assertTrue(MapPackPolicy.shouldFetch(now, false, home, stored(now - 31 * day), 0))
        assertTrue(MapPackPolicy.shouldFetch(now, false, 1_981_000 to 8_583_000, stored(now - day), 0)) // ~35 km away: Puri town
        assertFalse(MapPackPolicy.shouldFetch(now, false, 2_010_000 to 8_612_000, stored(now - day), 0)) // ~4 km: still covered
        assertFalse(MapPackPolicy.shouldFetch(now, true, 1_981_000 to 8_583_000, stored(now - day), 0)) // moved, but in a crisis
    }

    private fun state() = TriageEngine.recordChoice(
        TriageEngine.start(
            "we need rescue", listOf(Category.RESCUE), Lang.EN,
            disasters = DisasterGate.Active(setOf(DisasterType.FLOOD), aging = false),
            map = MapContext.around(MapPack.parse(pack)!!.places, 2_006_500 to 8_612_000),
        ),
        Choice.LOCATION, "ground_floor",
    )

    @Test fun `the model sees the map and only the allowed place advice`() {
        val turn = TriagePrompt.turn("we are fine", Ask.fact(Fact.WATER_RISING), state(), "en")
        assertTrue(turn, turn.contains("MAP: nearest shelter: gop cyclone shelter, 3 km north; nearest river/embankment/sea: devi river embankment, 1 km south-east"))
        assertTrue(turn, turn.contains("ADVICE MENU: away_from_water: "))
        assertTrue(turn, turn.contains("| go_shelter: "))
        assertTrue(!turn.contains("hospital:"))
        val noMap = TriagePrompt.turn("x", null, TriageEngine.start("help", listOf(Category.RESCUE), Lang.EN), "en")
        assertTrue(noMap.contains("MAP: no offline map or no position") && noMap.contains("ADVICE MENU: (empty)"))
    }

    @Test fun `the model's advice pick is read, an empty pick is a pick, and no key is no view`() {
        val ask = Ask.fact(Fact.WATER_RISING)
        assertEquals(listOf("go_shelter", "away_from_water"), TriagePrompt.parse("""{"answer":"no","advice":["Go_Shelter","away_from_water",""]}""", ask)!!.advice)
        assertEquals(emptyList<String>(), TriagePrompt.parse("""{"advice":[]}""", ask)!!.advice)
        assertNull(TriagePrompt.parse("""{"answer":"no"}""", ask)!!.advice)
        // Picked through the engine: only allowed ones survive, in the model's order.
        val heard = TriagePrompt.parse("""{"answer":"no","advice":["go_shelter","hospital","away_from_water"]}""", ask)!!
        val s = TriageEngine.record(state(), ask, heard.facts.getValue(org.relay.core.triage.FactKey(Fact.WATER_RISING)), heard)
        assertEquals(listOf(PlaceAdvice.GO_SHELTER, PlaceAdvice.AWAY_FROM_WATER), TriageEngine.result(s).places.map { it.advice })
    }
}
