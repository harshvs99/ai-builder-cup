package org.relay.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SimulatorTest {

    @Test fun chainScenarioReaches100PercentAndNeedsResyncBeyondTtl() {
        val o = Scenarios.chain(10)
        assertTrue(o.render(), o.pass)
        assertTrue("flood alone cannot cross 9 hops with TTL 7", o.needsFloodOnly < o.needsSent)
        assertTrue(o.resyncRounds > 0)
    }

    @Test fun shortChainNeedsNoResync() {
        val o = Scenarios.chain(6)
        assertTrue(o.render(), o.pass)
        assertEquals(0, o.resyncRounds)
        assertEquals(o.needsSent, o.needsFloodOnly)
    }

    @Test fun gridPartitionAndLossScenariosPass() {
        assertTrue(Scenarios.grid(50).render(), Scenarios.grid(50).pass)
        assertTrue(Scenarios.partition().render(), Scenarios.partition().pass)
        val loss = Scenarios.loss(10, 0.2)
        assertTrue(loss.render(), loss.pass)
        assertTrue(loss.framesLost > 0)
    }

    @Test fun scenarioIsDeterministicForASeed() {
        val a = Scenarios.loss(10, 0.2, seed = 7); val b = Scenarios.loss(10, 0.2, seed = 7)
        assertEquals(a.transmissions, b.transmissions); assertEquals(a.needLatencies.sorted(), b.needLatencies.sorted())
    }

    @Test fun scenarioAllCliPath() {
        val lines = ArrayList<String>()
        assertTrue(runScenarios("all", emptyMap(), lines::add))
        assertTrue(lines.last().contains("4/4"))
        assertFalse(runScenarios("loss", mapOf("loss" to "1.0", "nodes" to "4")) {}) // total loss can never deliver
    }

    private fun capture(): Pair<ByteArrayOutputStream, PrintStream> {
        val b = ByteArrayOutputStream(); return b to PrintStream(b, true, "UTF-8")
    }

    @Test fun e2eAgainstFakeBackendWithApprove() {
        FakeBackend().use { backend ->
            val (buf, out) = capture()
            val r = E2e.run(E2eConfig(backend.baseUrl, approve = true, lang = Lang.OR, pollMs = 100, timeoutMs = 15_000, out = out))
            val log = buf.toString("UTF-8")
            assertTrue(log, r.success)
            assertEquals(1L, r.ticketNo)
            assertNotNull(r.receivedText); assertTrue(r.dispatchedText!!.contains("dispatched"))
            // the backend saw exactly the NEED that A built from the text
            val need = backend.needs.single()
            assertEquals(Category.MEDICAL, need.category)
            assertEquals(3, need.urgency)
            assertEquals("insulin", need.item); assertEquals("school", need.landmark)
            assertEquals(Lang.OR, need.lang)
            assertEquals(2029610, need.latE5); assertEquals(8582450, need.lonE5)
            // hop-by-hop narration in order: A -> B -> C -> D, then back as ACKs
            fun idx(s: String) = log.indexOf(s).also { assertTrue("missing '$s' in:\n$log", it >= 0) }
            assertTrue(idx("[A] sent NEED") < idx("[B] relayed NEED"))
            assertTrue(idx("[B] relayed NEED") < idx("[C] relayed NEED"))
            assertTrue(idx("[C] relayed NEED") < idx("[D] gateway received NEED"))
            assertTrue(idx("POST /v1/sync") < idx("[C] relayed ACK"))
            assertTrue(log.contains("ticket_no=1"))
            assertTrue(log.contains("approve -> HTTP 200"))
            assertTrue(log.contains("ttl=6")) // B forwards with ttl-1
        }
    }

    @Test fun e2eWithoutApproveStopsAtReceived() {
        FakeBackend().use { backend ->
            val (_, out) = capture()
            val r = E2e.run(E2eConfig(backend.baseUrl, approve = false, text = "hum 5 log chhat par fase hain, mandir ke paas",
                pollMs = 100, timeoutMs = 15_000, out = out))
            assertTrue(r.success); assertEquals(null, r.dispatchedText)
            val need = backend.needs.single()
            assertEquals(Category.RESCUE, need.category); assertEquals(5, need.people); assertEquals(Lang.HI, need.lang)
        }
    }

    @Test fun e2eTimesOutWithAClearReasonWhenBackendIsDown() {
        FakeBackend().use { backend ->
            backend.failSync = true
            val (buf, out) = capture()
            val r = E2e.run(E2eConfig(backend.baseUrl, pollMs = 100, timeoutMs = 1_500, out = out))
            assertFalse(r.success)
            assertTrue(r.reason, r.reason.contains("could not reach the backend"))
            assertTrue(buf.toString("UTF-8").contains("sync failed"))
            assertTrue(backend.syncCalls > 0)
        }
    }

    @Test fun e2eRejectsWrongRelayKey() {
        FakeBackend(relayKey = "other").use { backend ->
            val (_, out) = capture()
            val r = E2e.run(E2eConfig(backend.baseUrl, pollMs = 100, timeoutMs = 1_200, out = out))
            assertFalse(r.success); assertTrue(r.reason, r.reason.contains("HTTP 401"))
        }
    }

    @Test fun e2eReportsUnreachableBackend() {
        val (_, out) = capture()
        val r = E2e.run(E2eConfig("http://127.0.0.1:1", pollMs = 100, timeoutMs = 1_200, out = out))
        assertFalse(r.success); assertTrue(r.reason, r.reason.contains("could not reach the backend"))
    }
}
