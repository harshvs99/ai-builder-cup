package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.gemma.TriagePrompt
import org.relay.app.location.MapPack
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
import java.io.File

class MapAdviceTest {
    private val pack = """
        {"version":1,"places":[
          {"name":"gop cyclone shelter","kind":"shelter","lat_e5":2009000,"lon_e5":8611000,"names":{"or":"ଗୋପ ବାତ୍ୟା ଆଶ୍ରୟସ୍ଥଳ","xx":"?"}},
          {"name":"devi river embankment","kind":"water","lat_e5":2006000,"lon_e5":8613000},
          {"name":"no kind","lat_e5":1,"lon_e5":1},
          {"name":"bad","kind":"hospital","lat_e5":99999999,"lon_e5":1},
          {"name":"","kind":"hospital","lat_e5":1,"lon_e5":1}
        ]}
    """.trimIndent()

    @Test fun `the pack keeps well-formed places with their scripts and skips the rest`() {
        val places = MapPack.parse(pack)
        assertEquals(listOf("gop cyclone shelter", "devi river embankment"), places.map { it.name })
        assertEquals(PlaceKind.SHELTER, places[0].kind)
        assertEquals(mapOf(Lang.OR to "ଗୋପ ବାତ୍ୟା ଆଶ୍ରୟସ୍ଥଳ"), places[0].names)
        assertEquals(emptyList<Any>(), MapPack.parse("not json"))
        assertEquals(emptyList<Any>(), MapPack.parse("{}"))
    }

    @Test fun `the bundled pack parses and has shelters, a hospital and rivers`() {
        val f = listOf(File("src/main/assets/mappack.json"), File("app/src/main/assets/mappack.json")).firstOrNull { it.exists() }
            ?: return // run from another directory: covered by the parse test above
        val kinds = MapPack.parse(f.readText()).groupBy { it.kind }
        assertTrue(kinds.getValue(PlaceKind.SHELTER).size >= 3)
        assertTrue(kinds.getValue(PlaceKind.HOSPITAL).isNotEmpty())
        assertTrue(kinds.getValue(PlaceKind.WATER).isNotEmpty())
    }

    private fun state() = TriageEngine.recordChoice(
        TriageEngine.start(
            "we need rescue", listOf(Category.RESCUE), Lang.EN,
            disasters = DisasterGate.Active(setOf(DisasterType.FLOOD), aging = false),
            map = MapContext.around(MapPack.parse(pack), 2_006_500 to 8_612_000),
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
