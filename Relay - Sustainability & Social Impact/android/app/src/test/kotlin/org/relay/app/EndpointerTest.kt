package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.gemma.Endpointer
import org.relay.app.gemma.Endpointer.Verdict
import org.relay.core.payload.Lang

class EndpointerTest {
    /** Feeds one level per 100 ms and returns the first non-LISTENING verdict with its time. */
    private fun run(ep: Endpointer, levels: List<Float>): Pair<Verdict, Long> {
        var t = 0L
        for (l in levels) { val v = ep.update(l, t); if (v != Verdict.LISTENING) return v to t; t += 100 }
        return Verdict.LISTENING to t
    }
    private fun quiet(n: Int) = List(n) { 0.01f }
    private fun talk(n: Int) = List(n) { 0.3f }

    @Test fun endsAfterTheConfiguredQuietFollowingSpeech() {
        val (v, t) = run(Endpointer(3_500), quiet(5) + talk(20) + quiet(60))
        assertEquals(Verdict.END_SPEECH, v)
        assertTrue("ended at $t ms", t in 5_500L..6_200L)   // speech ends at 2 500 + 2 000, plus 3 500 of quiet
    }

    @Test fun aPauseShorterThanTheLimitDoesNotEndIt() {
        val ep = Endpointer(6_000)
        val (v, _) = run(ep, quiet(5) + talk(10) + quiet(50) + talk(10) + quiet(10))   // 5 s pause, then more speech
        assertEquals(Verdict.LISTENING, v)
        assertTrue(ep.heardSpeech)
    }

    @Test fun nothingSaidGivesUp() {
        val (v, t) = run(Endpointer(3_500, noSpeechMs = 8_000), quiet(200))
        assertEquals(Verdict.END_NOTHING, v)
        assertTrue(t in 8_000L..8_200L)
    }

    @Test fun neverRunsPastTheModelsClipLimit() {
        val (v, t) = run(Endpointer(3_500), talk(400))
        assertEquals(Verdict.END_MAX, v)
        assertTrue(t <= 15_000)
    }

    @Test fun aNoisyRoomIsNotSpeech() {
        // steady 0.08 hiss: the floor is learned from it and it never counts as talking
        val ep = Endpointer(3_500, noSpeechMs = 8_000)
        val (v, _) = run(ep, List(200) { 0.08f })
        assertEquals(Verdict.END_NOTHING, v)
        assertFalse(ep.heardSpeech)
        assertTrue(ep.threshold() > 0.08f)
    }

    @Test fun gemmaIsOnlyUsedForALanguageGapOnAPhoneThatCanRunIt() {
        assertTrue(SpeechLogic.useGemmaForSpeech(SpeechLogic.Kind.LANGUAGE_UNAVAILABLE, true))
        assertFalse(SpeechLogic.useGemmaForSpeech(SpeechLogic.Kind.LANGUAGE_UNAVAILABLE, false))
        for (k in SpeechLogic.Kind.entries.filter { it != SpeechLogic.Kind.LANGUAGE_UNAVAILABLE }) assertFalse("$k", SpeechLogic.useGemmaForSpeech(k, true))
    }

    @Test fun onlyMeasuredLanguagesAreTrustedWithoutACheck() {
        assertFalse(SpeechLogic.gemmaNote(Lang.EN).contains("check"))
        assertFalse(SpeechLogic.gemmaNote(Lang.HI).contains("check"))
        assertTrue(SpeechLogic.gemmaNote(Lang.OR).contains("check the text"))
        assertTrue(SpeechLogic.gemmaNote(Lang.WAR).contains("Waray"))
    }
}
