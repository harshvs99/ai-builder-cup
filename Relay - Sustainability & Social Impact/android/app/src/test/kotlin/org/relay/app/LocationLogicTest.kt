package org.relay.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.location.Fix
import org.relay.app.location.FixPolicy
import org.relay.app.location.FixWait
import org.relay.app.location.HintFix
import org.relay.app.location.LocUi
import org.relay.app.location.LocationState

class LocationLogicTest {
    private val now = 1_000_000L
    private fun fix(acc: Float, at: Long = now) = Fix(20.3, 85.8, acc, at)

    // ---- state machine
    @Test fun uiStates() {
        assertEquals(LocUi.NO_PERMISSION, LocationState.ui(false, true, null, now))
        assertEquals(LocUi.NO_PERMISSION, LocationState.ui(false, false, null, now)) // permission is the first thing to fix
        assertEquals(LocUi.OFF, LocationState.ui(true, false, null, now))
        assertEquals(LocUi.ACQUIRING, LocationState.ui(true, true, null, now))
        assertEquals(LocUi.READY, LocationState.ui(true, true, fix(8f), now))
    }

    @Test fun staleFixIsNotReady() {
        assertEquals(LocUi.ACQUIRING, LocationState.ui(true, true, fix(8f, now - FixPolicy.MAX_AGE_MS - 1), now))
        assertEquals(LocUi.READY, LocationState.ui(true, true, fix(8f, now - FixPolicy.MAX_AGE_MS), now))
    }

    @Test fun labels() {
        assertEquals("📍 ±8 m", LocationState.label(LocUi.READY, fix(8.4f)))
        assertEquals("📍 ±9 m", LocationState.label(LocUi.READY, fix(8.6f)))
        assertEquals("📍 ±1.5 km", LocationState.label(LocUi.READY, fix(1500f)))
        assertEquals("📍 accuracy unknown", LocationState.label(LocUi.READY, fix(Float.NaN)))
        assertEquals("📍 Getting location…", LocationState.label(LocUi.ACQUIRING, null))
        assertEquals("📍 Location is off", LocationState.label(LocUi.OFF, null))
        assertEquals("📍 No permission", LocationState.label(LocUi.NO_PERMISSION, null))
    }

    @Test fun fallbackNoteOnlyWithoutFix() {
        assertNull(LocationState.fallbackNote(LocUi.READY))
        for (u in listOf(LocUi.ACQUIRING, LocUi.OFF, LocUi.NO_PERMISSION)) assertEquals("Rescuers will rely on the landmark", LocationState.fallbackNote(u))
    }

    @Test fun onlyAcquiringIsWorthWaitingFor() {
        assertTrue(LocationState.shouldWaitForFix(LocUi.ACQUIRING))
        assertFalse(LocationState.shouldWaitForFix(LocUi.OFF))
        assertFalse(LocationState.shouldWaitForFix(LocUi.NO_PERMISSION))
        assertFalse(LocationState.shouldWaitForFix(LocUi.READY))
    }

    @Test fun restartRule() {
        assertTrue(LocationState.shouldRestart(LocUi.ACQUIRING, listening = false, cardVisible = true))
        assertFalse(LocationState.shouldRestart(LocUi.ACQUIRING, listening = true, cardVisible = true))
        assertFalse(LocationState.shouldRestart(LocUi.ACQUIRING, listening = false, cardVisible = false))
        assertFalse(LocationState.shouldRestart(LocUi.OFF, listening = false, cardVisible = true))
    }

    // ---- best-fix selection
    @Test fun firstFixAlwaysWins() = assertTrue(FixPolicy.isBetter(null, fix(5000f), now))

    @Test fun moreAccurateReplacesLessAccurate() {
        assertTrue(FixPolicy.isBetter(fix(300f), fix(20f), now))
        assertTrue(FixPolicy.isBetter(fix(20f), fix(20f), now)) // equal: newer wins
    }

    @Test fun lessAccurateDoesNotReplaceRecentGoodFix() {
        assertFalse(FixPolicy.isBetter(fix(8f, now - 10_000), fix(300f), now))
    }

    @Test fun staleFixIsReplacedByAnything() {
        assertTrue(FixPolicy.isBetter(fix(8f, now - FixPolicy.STALE_AFTER_MS - 1), fix(300f), now))
    }

    @Test fun unknownAccuracyIsWorstCase() {
        assertTrue(FixPolicy.isBetter(fix(Float.NaN), fix(900f), now))
        assertFalse(FixPolicy.isBetter(fix(900f), fix(Float.NaN), now))
    }

    @Test fun goodEnoughIsTheBestBucket() {
        assertTrue(FixPolicy.isGoodEnough(fix(9.9f)))
        assertFalse(FixPolicy.isGoodEnough(fix(10f)))
        assertFalse(FixPolicy.isGoodEnough(fix(Float.NaN)))
        assertEquals(1, fix(9.9f).locAcc)
    }

    // ---- bounded wait
    @Test fun waitReturnsImmediatelyWhenFixAlreadyThere() = runBlocking {
        val got = FixWait.await(MutableStateFlow<Fix?>(fix(8f)), MutableStateFlow(false), 2_000)
        assertEquals(8f, got!!.accuracyM, 0f)
    }

    @Test fun waitTimesOutWithNull() = runBlocking {
        val t0 = System.currentTimeMillis()
        val got = FixWait.await(MutableStateFlow<Fix?>(null), MutableStateFlow(false), 150)
        assertNull(got)
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
    }

    @Test fun waitReturnsFixThatArrivesLater() = runBlocking {
        val fixes = MutableStateFlow<Fix?>(null)
        CoroutineScope(Dispatchers.Default).launch { delay(80); fixes.value = fix(15f) }
        val got = FixWait.await(fixes, MutableStateFlow(false), 3_000)
        assertEquals(15f, got!!.accuracyM, 0f)
    }

    @Test fun sendNowSkipsTheWait() = runBlocking {
        val skip = MutableStateFlow(false)
        CoroutineScope(Dispatchers.Default).launch { delay(80); skip.value = true }
        val t0 = System.currentTimeMillis()
        val got = FixWait.await(MutableStateFlow<Fix?>(null), skip, 10_000)
        assertNull(got)
        assertTrue(System.currentTimeMillis() - t0 < 3_000)
    }

    @Test fun defaultWaitBudgetIsTenSeconds() = assertEquals(10_000L, FixWait.DEFAULT_TIMEOUT_MS)

    // ---- LOC_HINT fix bridge
    @Test fun hintFixConvertsAndKeepsOnlyUsableFixes() {
        val f = HintFix.current(fix(12f, at = now - 5_000), now)!!
        assertEquals(20.3, f.lat, 0.0); assertEquals(85.8, f.lon, 0.0)
        assertEquals(12f, f.accuracyM, 0f); assertEquals(now - 5_000, f.atMs)
        assertNull(HintFix.current(null, now))
        assertNull("older than FixPolicy.MAX_AGE_MS", HintFix.current(fix(12f, at = now - FixPolicy.MAX_AGE_MS - 1), now))
        assertEquals("fix without a timestamp counts as taken now", now, HintFix.current(fix(12f, at = 0L), now)!!.atMs)
    }
}
