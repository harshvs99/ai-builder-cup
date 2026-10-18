package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.store.MyNeed
import org.relay.app.store.RequestTimeline
import org.relay.app.store.RequestTimeline.Key
import org.relay.app.store.RequestTimeline.State
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

class RequestTimelineTest {
    private val t0 = 1_700_000_000_000L
    private fun need() = MyNeed(
        msgIdHex = "aabbccdd", createdAtMs = t0, category = Category.WATER, urgency = 2, people = 6, item = "water",
        landmark = "school", summary = "pump broken", lang = Lang.EN, needPayloadB64 = "",
    )

    private fun keys(n: MyNeed, now: Long = t0 + 10_000_000) = RequestTimeline.steps(n, now).map { it.key }

    @Test fun freshRequestHasOnlySavedAndItIsCurrent() {
        val s = RequestTimeline.steps(need(), t0)
        assertEquals(listOf(Key.SAVED), s.map { it.key })
        assertEquals(State.CURRENT, s.single().state)
        assertEquals(t0, s.single().atMs)
    }

    @Test fun sentToMeshAddsNeighbourStep() {
        val n = need().markSentToMesh(t0 + 5_000)
        val s = RequestTimeline.steps(n, t0 + 6_000)
        assertEquals(listOf(Key.SAVED, Key.PASSED_TO_NEIGHBOUR), s.map { it.key })
        assertEquals(listOf(State.DONE, State.CURRENT), s.map { it.state })
        assertEquals(t0 + 5_000, s[1].atMs)
    }

    @Test fun uploadedByThisPhoneReachesGatewayWithoutAnAck() {
        val n = need().markSentToMesh(t0 + 1_000).markUploaded(t0 + 9_000)
        val s = RequestTimeline.steps(n, t0 + 10_000)
        assertEquals(listOf(Key.SAVED, Key.PASSED_TO_NEIGHBOUR, Key.REACHED_GATEWAY), s.map { it.key })
        assertEquals(t0 + 9_000, s.last().atMs)
    }

    @Test fun receivedAckImpliesNeighbourAndGatewayEvenWithoutTimestamps() {
        val n = need().withAck(AckStatus.RECEIVED, 7, "got it", 0xFFFF, nowMs = t0 + 60_000)
        val s = RequestTimeline.steps(n, t0 + 61_000)
        assertEquals(listOf(Key.SAVED, Key.PASSED_TO_NEIGHBOUR, Key.REACHED_GATEWAY, Key.SEEN_BY_CONTROL_ROOM), s.map { it.key })
        assertEquals(0L, s[1].atMs) // reached, time not recorded
        assertEquals(t0 + 60_000, s[2].atMs) // falls back to the ack time
        assertEquals(2, s.last().urgency)
        assertEquals(State.CURRENT, s.last().state)
    }

    @Test fun dispatchedCarriesEta() {
        val n = need().withAck(AckStatus.RECEIVED, 7, "r", 0xFFFF, t0 + 1_000)
            .withAck(AckStatus.DISPATCHED, 7, "d", 45, t0 + 2_000)
        val s = RequestTimeline.steps(n, t0 + 3_000)
        assertEquals(Key.HELP_COMING, s.last().key)
        assertEquals(t0 + 2_000 + 45 * 60_000L, s.last().etaAtMs)
        assertFalse(s.last().etaOverdue)
        assertFalse(s.last().live)
        assertTrue(RequestTimeline.steps(n, t0 + 2_000 + 46 * 60_000L).last().etaOverdue)
    }

    @Test fun onTheWayIsLiveAndKeepsEtaWhenAckHasNone() {
        val n = need().withAck(AckStatus.DISPATCHED, 7, "d", 30, t0 + 2_000)
            .withAck(AckStatus.ON_THE_WAY, 7, "w", 0xFFFF, t0 + 3_000)
        assertEquals(MyNeed.Stage.ON_THE_WAY, n.stage)
        val last = RequestTimeline.steps(n, t0 + 4_000).last()
        assertEquals(Key.TEAM_ON_THE_WAY, last.key)
        assertTrue(last.live)
        assertEquals(t0 + 2_000 + 30 * 60_000L, last.etaAtMs)
        assertEquals(t0 + 3_000, last.atMs)
    }

    @Test fun resolvedHasAllStepsDoneAndNoCurrent() {
        val n = need().markSentToMesh(t0 + 1)
            .withAck(AckStatus.RECEIVED, 7, "r", 0xFFFF, t0 + 2)
            .withAck(AckStatus.DISPATCHED, 7, "d", 20, t0 + 3)
            .withAck(AckStatus.ON_THE_WAY, 7, "w", 10, t0 + 4)
            .withAck(AckStatus.RESOLVED, 7, "done", 0xFFFF, t0 + 5)
        assertEquals(MyNeed.Stage.RESOLVED, n.stage)
        assertTrue(n.isClosed)
        val s = RequestTimeline.steps(n, t0 + 6)
        assertEquals(Key.entries, s.map { it.key })
        assertTrue(s.all { it.state == State.DONE })
        assertFalse(s.any { it.live })
        assertEquals(t0 + 5, s.last().atMs)
        assertEquals(Key.DELIVERED, RequestTimeline.latest(n, t0 + 6).key)
    }

    @Test fun cancelledKeepsReachedStepsButNothingIsCurrent() {
        val n = need().markSentToMesh(t0 + 1).cancel(t0 + 2)
        assertTrue(n.isClosed)
        val s = RequestTimeline.steps(n, t0 + 3)
        assertEquals(listOf(Key.SAVED, Key.PASSED_TO_NEIGHBOUR), s.map { it.key })
        assertTrue(s.all { it.state == State.DONE })
    }

    @Test fun timestampsAreSetOnlyTheFirstTime() {
        val n = need().markSentToMesh(100).markSentToMesh(200).markUploaded(300).markUploaded(400)
        assertEquals(100L, n.sentToMeshAtMs)
        assertEquals(300L, n.uploadedAtMs)
        val a = need().withAck(AckStatus.RECEIVED, 1, "a", 0xFFFF, 10).withAck(AckStatus.RECEIVED, 1, "a", 0xFFFF, 20)
        assertEquals(10L, a.receivedAtMs)
        assertNull(keys(need()).firstOrNull { it != Key.SAVED })
    }
}
