package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.triage.Choice
import org.relay.core.triage.Compass
import org.relay.core.triage.DisasterGate
import org.relay.core.triage.DisasterType
import org.relay.core.triage.Fact
import org.relay.core.triage.Interpretation
import org.relay.core.triage.MapContext
import org.relay.core.triage.MapPlace
import org.relay.core.triage.PlaceAdvice
import org.relay.core.triage.PlaceAdvisor
import org.relay.core.triage.PlaceKind
import org.relay.core.triage.Tri
import org.relay.core.triage.TriageBank
import org.relay.core.triage.TriageEngine

class PlacesTest {
    // From the Puri gazetteer (backend/app/data/gazetteer_puri.json).
    private val shelter = MapPlace("gop cyclone shelter", PlaceKind.SHELTER, 2_009_000, 8_611_000, mapOf(Lang.OR to "ଗୋପ ବାତ୍ୟା ଆଶ୍ରୟସ୍ଥଳ"))
    private val river = MapPlace("devi river embankment", PlaceKind.WATER, 2_006_000, 8_613_000)
    private val hospital = MapPlace("district headquarters hospital puri", PlaceKind.HOSPITAL, 1_981_200, 8_582_800)
    private val pack = listOf(shelter, river, hospital)
    private val here = 2_006_500 to 8_612_000 // a village between the shelter and the river

    private val flood = DisasterGate.Active(setOf(DisasterType.FLOOD), aging = false)
    private val cyclone = DisasterGate.Active(setOf(DisasterType.CYCLONE), aging = false)

    private fun map() = MapContext.around(pack, here)!!

    @Test fun `nearest places come with a rounded distance and a compass direction`() {
        val m = map()
        assertEquals(shelter, m.shelter!!.place)
        assertEquals(Compass.N, m.shelter!!.bearing)
        assertEquals(3000, m.shelter!!.distanceM)
        assertEquals(river, m.water!!.place)
        assertEquals(Compass.SE, m.water!!.bearing)
        assertEquals(1000, m.water!!.distanceM)
        assertEquals(hospital, m.hospital!!.place)
        assertTrue(m.promptLine().startsWith("nearest shelter: gop cyclone shelter, 3 km north"))
    }

    @Test fun `no position, a rough one or nothing in reach gives no map`() {
        assertNull(MapContext.around(pack, null))
        assertNull(MapContext.around(pack, here, accuracyM = 2_500f))
        val far = MapContext.around(pack, 2_861_400 to 7_720_900)!!
        assertTrue(far.isEmpty)
        assertEquals("nothing nearby on the offline map", far.promptLine())
    }

    @Test fun `distances are rounded so a phone fix does not sound precise`() {
        assertEquals(50, MapContext.roundForSpeech(12.0))
        assertEquals(350, MapContext.roundForSpeech(337.0))
        assertEquals(1500, MapContext.roundForSpeech(1_620.0))
        assertEquals(12000, MapContext.roundForSpeech(12_400.0))
        assertEquals("1.5 kilometres", TriageBank.distanceText(1500, Lang.EN))
        assertEquals("350 मीटर", TriageBank.distanceText(350, Lang.HI))
    }

    @Test fun `in a declared flood a person who can move hears to keep away from the river and where the shelter is`() {
        val s = TriageEngine.start("we need rescue, water is coming in", listOf(Category.RESCUE), Lang.EN, disasters = flood, map = map())
        val r = TriageEngine.result(TriageEngine.recordChoice(s, Choice.LOCATION, "ground_floor"))
        assertEquals(listOf(PlaceAdvice.AWAY_FROM_WATER, PlaceAdvice.GO_SHELTER), r.places.map { it.advice })
        assertEquals(
            "Devi River Embankment is about 1 kilometre to the south-east. Move away from it, to higher ground. Do not walk along the bank or through moving water.",
            r.places[0].text(Lang.EN),
        )
        assertTrue(r.places[1].text(Lang.OR).startsWith("ମ୍ୟାପରେ ସବୁଠାରୁ ନିକଟ ଆଶ୍ରୟସ୍ଥଳ: ଗୋପ ବାତ୍ୟା ଆଶ୍ରୟସ୍ଥଳ, ପ୍ରାୟ 3 କିଲୋମିଟର ଉତ୍ତର ଦିଗରେ"))
    }

    @Test fun `nobody is sent walking from a roof in a flood, from rubble, or away from someone not breathing`() {
        val roof = TriageEngine.start("help", listOf(Category.RESCUE), Lang.EN, disasters = flood, map = map())
        assertEquals(emptyList<PlaceAdvice>(), PlaceAdvisor.allowed(TriageEngine.recordChoice(roof, Choice.LOCATION, "roof")))
        val rubble = TriageEngine.start("we are under the rubble", listOf(Category.RESCUE), Lang.EN, disasters = cyclone, map = map())
        assertEquals(emptyList<PlaceAdvice>(), PlaceAdvisor.allowed(rubble))
        val cpr = TriageEngine.start("my father is not breathing", listOf(Category.MEDICAL), Lang.EN, disasters = flood, map = map())
        assertEquals(emptyList<PlaceAdvice>(), TriageEngine.result(cpr).places.map { it.advice })
        val wire = TriageEngine.record(TriageEngine.start("help", listOf(Category.RESCUE), Lang.EN, disasters = flood, map = map()), Fact.LIVE_WIRE, Tri.YES)
        assertTrue(PlaceAdvisor.mustStay(wire))
    }

    @Test fun `the hospital is named for a medical request, and no disaster means no shelter or river advice`() {
        val s = TriageEngine.start("my son cut his leg, bleeding a lot", listOf(Category.MEDICAL), Lang.EN, map = map())
        assertEquals(listOf(PlaceAdvice.HOSPITAL), TriageEngine.result(s).places.map { it.advice })
        // First aid is still first and untouched.
        assertEquals(TriageBank.AdvisoryId.BLEEDING, TriageEngine.result(s).advisories.first().id)
    }

    @Test fun `the model picks and orders allowed advice, and cannot add any`() {
        val s0 = TriageEngine.start("we need rescue", listOf(Category.RESCUE), Lang.EN, disasters = flood, map = map())
        val s = TriageEngine.recordChoice(s0, Choice.LOCATION, "ground_floor")
        // "We are already at the shelter": the model drops go_shelter, and asks for hospital (not allowed: nobody is hurt).
        val heard = TriageEngine.absorb(s, Interpretation(advice = listOf("hospital", "away_from_water", "made_up")))
        assertEquals(listOf(PlaceAdvice.AWAY_FROM_WATER), TriageEngine.result(heard).places.map { it.advice })
        // A later turn without an advice view keeps the last one; no view at all gives every allowed one.
        assertEquals(listOf("hospital", "away_from_water", "made_up"), TriageEngine.absorb(heard, Interpretation()).placeView)
        assertEquals(PlaceAdvisor.allowed(s), PlaceAdvisor.choose(PlaceAdvisor.allowed(s), null))
    }

    @Test fun `without a map nothing changes`() {
        val s = TriageEngine.start("we need rescue", listOf(Category.RESCUE), Lang.EN, disasters = flood)
        assertEquals(emptyList<PlaceAdvice>(), TriageEngine.result(s).places.map { it.advice })
    }
}
