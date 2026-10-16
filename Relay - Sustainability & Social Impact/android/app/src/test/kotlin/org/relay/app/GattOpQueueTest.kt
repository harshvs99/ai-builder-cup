package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ble.GattOp
import org.relay.app.ble.GattOpQueue
import org.relay.app.ble.StartResult

class GattOpQueueTest {
    private class FakeOp(override val label: String, val started: MutableList<String>, var result: StartResult = StartResult.STARTED) : GattOp {
        override fun start(): StartResult { started += label; return result }
    }

    private var clock = 0L
    private fun queue(timeout: Long = 5_000, maxQueued: Int = 512, maxRetries: Int = 3) =
        GattOpQueue(timeoutMs = timeout, maxQueued = maxQueued, maxRetries = maxRetries, now = { clock })

    @Test fun onlyOneOperationIsInFlightAndOrderIsPreserved() {
        val started = ArrayList<String>()
        val q = queue()
        q.enqueue(FakeOp("a", started)); q.enqueue(FakeOp("b", started)); q.enqueue(FakeOp("c", started))
        assertEquals(listOf("a"), started)
        assertTrue(q.inFlight())
        q.onComplete(); assertEquals(listOf("a", "b"), started)
        q.onComplete(); assertEquals(listOf("a", "b", "c"), started)
        q.onComplete(); assertFalse(q.inFlight())
        assertEquals(0, q.size())
    }

    @Test fun completionWithNothingInFlightIsIgnored() {
        val q = queue()
        assertFalse(q.onComplete())
    }

    @Test fun stuckOperationTimesOutAndQueueAdvances() {
        val started = ArrayList<String>()
        val q = queue(timeout = 5_000)
        q.enqueue(FakeOp("stuck", started)); q.enqueue(FakeOp("next", started))
        clock = 4_999; q.tick(); assertEquals(listOf("stuck"), started)
        clock = 5_000; q.tick(); assertEquals(listOf("stuck", "next"), started)
        assertEquals(1, q.consecutiveTimeouts)
        assertEquals(1, q.totalTimeouts)
        q.onComplete()
        assertEquals("a real completion resets the consecutive counter", 0, q.consecutiveTimeouts)
        assertEquals(1, q.totalTimeouts)
    }

    @Test fun consecutiveTimeoutsAccumulate() {
        val started = ArrayList<String>()
        val q = queue(timeout = 1_000)
        repeat(3) { q.enqueue(FakeOp("op$it", started)) }
        repeat(3) { clock += 1_000; q.tick() }
        assertEquals(3, q.consecutiveTimeouts)
    }

    @Test fun failedStartIsSkippedImmediately() {
        val started = ArrayList<String>()
        val q = queue()
        q.enqueue(FakeOp("bad", started, StartResult.FAILED)); q.enqueue(FakeOp("good", started))
        assertEquals(listOf("bad", "good"), started)
        assertEquals(1, q.dropped)
        assertTrue(q.inFlight())
    }

    @Test fun busyOperationIsRetriedOnTickThenDropped() {
        val started = ArrayList<String>()
        val q = queue(maxRetries = 2)
        val busy = FakeOp("busy", started, StartResult.RETRY)
        q.enqueue(busy); q.enqueue(FakeOp("after", started))
        assertEquals(listOf("busy"), started)
        q.tick(); assertEquals(listOf("busy", "busy"), started)
        busy.result = StartResult.STARTED
        q.tick(); assertEquals("busy succeeds on its third attempt", listOf("busy", "busy", "busy"), started)
        q.onComplete(); assertEquals(listOf("busy", "busy", "busy", "after"), started)
    }

    @Test fun busyBeyondRetryLimitIsDroppedAndNextRuns() {
        val started = ArrayList<String>()
        val q = queue(maxRetries = 1)
        q.enqueue(FakeOp("busy", started, StartResult.RETRY)); q.enqueue(FakeOp("after", started))
        q.tick() // retry 1 -> still busy -> retries exceeded -> dropped, "after" starts
        assertEquals(listOf("busy", "busy", "after"), started)
        assertEquals(1, q.dropped)
    }

    @Test fun synchronousCompletionInsideStartDoesNotWedgeTheQueue() {
        val started = ArrayList<String>()
        lateinit var q: GattOpQueue
        q = queue()
        q.enqueue(object : GattOp {
            override val label = "sync"
            override fun start(): StartResult { started += label; q.onComplete(); return StartResult.STARTED }
        })
        q.enqueue(FakeOp("next", started))
        assertEquals(listOf("sync", "next"), started)
    }

    @Test fun fullQueueRejectsNewOperations() {
        val started = ArrayList<String>()
        val q = queue(maxQueued = 2)
        assertTrue(q.enqueue(FakeOp("running", started)))
        assertTrue(q.enqueue(FakeOp("q1", started)))
        assertTrue(q.enqueue(FakeOp("q2", started)))
        assertFalse(q.enqueue(FakeOp("q3", started)))
        assertEquals(1, q.dropped)
    }

    @Test fun clearForgetsEverything() {
        val started = ArrayList<String>()
        val q = queue()
        q.enqueue(FakeOp("a", started)); q.enqueue(FakeOp("b", started))
        q.clear()
        assertEquals(0, q.size())
        q.enqueue(FakeOp("c", started))
        assertEquals(listOf("a", "c"), started)
    }

    @Test fun enqueueBurstWhileBusyDoesNotBurnTheRetryBudget() {
        val started = ArrayList<String>()
        val q = queue(maxRetries = 2)
        val busy = FakeOp("busy", started, StartResult.RETRY)
        q.enqueue(busy)
        repeat(20) { q.enqueue(FakeOp("f$it", started)) }
        assertEquals("retries only happen on tick()", listOf("busy"), started)
        assertEquals(0, q.dropped)
        busy.result = StartResult.STARTED
        q.tick()
        assertEquals(listOf("busy", "busy"), started)
    }
}
