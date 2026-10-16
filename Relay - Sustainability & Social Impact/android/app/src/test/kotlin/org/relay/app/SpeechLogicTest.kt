package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.SpeechLogic.Kind
import org.relay.core.payload.Lang

class SpeechLogicTest {
    private fun err(code: Int, offline: Boolean, online: Boolean, retried: Boolean = false, lang: Lang = Lang.EN) =
        SpeechLogic.onError(code, lang, offline, online, retried)

    @Test fun offlineFlagOnlyWithoutInternet() {
        assertTrue(SpeechLogic.preferOffline(online = false))
        assertFalse(SpeechLogic.preferOffline(online = true))
    }

    @Test fun languageTags() {
        assertEquals("en-IN", SpeechLogic.recognizerTag(Lang.EN))
        assertEquals("hi-IN", SpeechLogic.recognizerTag(Lang.HI))
        assertEquals("or-IN", SpeechLogic.recognizerTag(Lang.OR))
        assertEquals("fil-PH", SpeechLogic.recognizerTag(Lang.TL))
        assertEquals("fil-PH", SpeechLogic.recognizerTag(Lang.WAR))
        assertEquals("fil-PH", SpeechLogic.recognizerTag(Lang.CEB))
    }

    @Test fun fallbackNoteOnlyForWarayAndCebuano() {
        assertNull(SpeechLogic.fallbackNote(Lang.EN))
        assertNull(SpeechLogic.fallbackNote(Lang.TL))
        assertTrue(SpeechLogic.fallbackNote(Lang.WAR)!!.contains("Filipino"))
        assertTrue(SpeechLogic.fallbackNote(Lang.CEB)!!.contains("Cebuano"))
    }

    @Test fun offlinePackMissingWhileOnlineRetriesOnline() {
        for (code in listOf(12, 13, 1, 2)) assertEquals("code $code", Kind.RETRY_ONLINE, err(code, offline = true, online = true).kind)
    }

    @Test fun onlyOneRetry() {
        val d = err(13, offline = true, online = true, retried = true)
        assertEquals(Kind.LANGUAGE_UNAVAILABLE, d.kind)
        assertTrue(d.offerVoiceSettings)
    }

    @Test fun offlineWithNoPackShowsOfflineMessage() {
        val d = err(13, offline = true, online = false, lang = Lang.HI)
        assertEquals(Kind.LANGUAGE_UNAVAILABLE, d.kind)
        assertEquals("Voice for Hindi isn't available on this phone offline — type your request, or download the Hindi voice pack (needs internet).", d.message)
        assertTrue(d.offerVoiceSettings)
    }

    @Test fun onlineAttemptFailingShowsNonOfflineMessage() {
        val d = err(13, offline = false, online = true)
        assertEquals(Kind.LANGUAGE_UNAVAILABLE, d.kind)
        assertFalse(d.message!!.contains("offline"))
        assertTrue(d.message!!.contains("English"))
    }

    @Test fun noSpeechAndBusyAndPermission() {
        assertEquals(Kind.NO_SPEECH, err(7, true, false).kind)
        assertEquals(Kind.NO_SPEECH, err(6, false, true).kind)
        assertEquals(Kind.BUSY, err(8, false, true).kind)
        assertEquals(Kind.BUSY, err(10, false, true).kind)
        assertEquals(Kind.PERMISSION, err(9, false, true).kind)
    }

    @Test fun noSpeechNeverRetriesOnline() {
        assertEquals(Kind.NO_SPEECH, err(7, offline = true, online = true).kind)
    }

    @Test fun otherErrorsFailCleanlyWithMessage() {
        val a = err(3, false, true); assertEquals(Kind.FAILED, a.kind); assertTrue(a.message!!.isNotBlank())
        val c = err(5, false, true); assertEquals(Kind.FAILED, c.kind); assertTrue(c.message!!.contains("5"))
    }

    @Test fun everyErrorCodeYieldsAUserVisibleOutcome() {
        for (code in 1..15) for (off in listOf(true, false)) for (on in listOf(true, false)) {
            val d = err(code, off, on)
            assertTrue("$code/$off/$on", d.kind == Kind.RETRY_ONLINE || !d.message.isNullOrBlank())
        }
    }
}
