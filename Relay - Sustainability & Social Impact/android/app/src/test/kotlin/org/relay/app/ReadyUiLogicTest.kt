package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.ready.PlanPlayer
import org.relay.core.payload.Lang
import org.relay.core.ready.WarningLevel
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadyUiLogicTest {
    private fun wav(rate: Int = 24000, channels: Int = 1, bits: Int = 16, pcm: ByteArray = ByteArray(480), extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) ByteArray(8 + 4).also { "LIST".toByteArray().copyInto(it); ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 4) } else ByteArray(0)
        val b = ByteBuffer.allocate(44 + extra.size + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * bits / 8)
            .putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        b.put(extra)
        b.put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }

    @Test fun planPlayerReadsThePcm16Mono24kWavTheBackendMakes() {
        val w = PlanPlayer.parse(wav())!!
        assertEquals(24000, w.rate); assertEquals(480, w.pcm.size)
    }

    @Test fun planPlayerSkipsOtherChunksAndTruncatedData() {
        assertEquals(480, PlanPlayer.parse(wav(extraChunk = true))!!.pcm.size)
        val cut = wav().copyOf(44 + 100)
        assertEquals(100, PlanPlayer.parse(cut)!!.pcm.size) // the header promises more than the file holds: play what is there
    }

    @Test fun planPlayerRefusesWhatItCannotPlayAndTheCallerFallsBackToSpeech() {
        assertNull(PlanPlayer.parse(ByteArray(10)))
        assertNull(PlanPlayer.parse(wav(channels = 2)))
        assertNull(PlanPlayer.parse(wav(bits = 8)))
        assertNull(PlanPlayer.parse(wav(pcm = ByteArray(0))))
        assertNull(PlanPlayer.parse("RIFFxxxxWAVEjunk".toByteArray() + ByteArray(40)))
    }

    @Test fun readyStringsAreEnglishAndHindiAndHiddenLanguagesShowEnglish() {
        val en = stringsFor(Lang.EN).ready; val hi = stringsFor(Lang.HI).ready
        assertEquals("Get ready", en.getReady); assertNotEquals(en.getReady, hi.getReady)
        for (l in listOf(Lang.OR, Lang.TL, Lang.WAR)) assertEquals(en.getReady, stringsFor(l).ready.getReady)
        assertEquals("Landfall in about 6 h", en.landfallIn(6))
        assertEquals("Cyclone warning for Puri", en.notificationTitle(en.districtName("puri")))
        assertEquals("Your plan is ready.", en.notificationText)
        assertEquals("Delhi", en.districtName("delhi")); assertEquals("Foo", en.districtName("foo"))
    }

    @Test fun theBannerIsReadAsOneSentence() {
        val r = stringsFor(Lang.EN).ready
        assertEquals("Warning. Cyclone Fani. Landfall in about 6 h. Get ready", r.bannerDescription(WarningLevel.WARNING, "Cyclone Fani", 6))
        assertEquals("Alert. Evacuate. Get ready", r.bannerDescription(WarningLevel.ALERT, "Evacuate", null))
    }

    @Test fun householdIsPartOfSettingsAndDefaultsToOnePerson() {
        assertEquals(1, Settings().household.people)
        assertTrue(!Settings().householdSet)
        assertNotNull(Settings().copy(household = org.relay.core.ready.Household(people = 4), householdSet = true))
    }
}
