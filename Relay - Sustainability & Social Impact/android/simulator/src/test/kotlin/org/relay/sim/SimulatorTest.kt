package org.relay.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.relay.core.ids.Hex
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

    // ------------------------------------------------------------ mesh-assisted location

    @Test fun locateOnlyRelaysNextToTheRequesterHintAndTheGatewayUploadsTheHints() {
        FakeBackend().use { backend ->
            val (buf, out) = capture()
            val r = LocateE2e.run(LocateConfig(backend.baseUrl, expectEstimate = false, pollMs = 100, timeoutMs = 15_000, out = out))
            assertTrue(buf.toString("UTF-8") + r.reason, r.success)
            // B1 and B2 heard A directly; C has a fix too but only relayed the NEED, so it stays silent
            assertEquals(listOf("B1", "B2"), r.hintRelays.sorted())
            val need = backend.needs.single()
            assertEquals(0, need.locAcc); assertEquals(0, need.latE5)
            assertEquals(2, backend.hints.size)
            for (h in backend.hints) {
                assertEquals(Hex.encode(need.msgId), h.refMsgIdHex)
                assertEquals(-60, h.rssiDbm) // the link to A; both relays read the same simulated signal
            }
            assertEquals(setOf(2, 3), backend.hints.map { it.acc }.toSet()) // 20 m -> <=25, 30 m -> <=50
            assertTrue(buf.toString("UTF-8"), buf.toString("UTF-8").contains("hints={\"accepted\":")) // answered in the sync response
        }
    }

    @Test fun locateWithTheSwitchOffSendsNoHintAtAll() {
        FakeBackend().use { backend ->
            val (_, out) = capture()
            val r = LocateE2e.run(LocateConfig(backend.baseUrl, helpLocate = false, expectEstimate = false, pollMs = 100, timeoutMs = 15_000, out = out))
            assertTrue(r.reason, r.success)
            assertTrue(r.hintRelays.isEmpty()); assertTrue(backend.hints.isEmpty())
            assertEquals(1, backend.needs.size) // the NEED itself still got through
        }
    }

    /** Starts the real Python backend (backend/.venv or `uv`) on a free port; null when it cannot run here. */
    private fun startRealBackend(): Pair<Process, String>? {
        val dir = java.io.File("../../backend").canonicalFile
        if (!dir.isDirectory) return null
        val py = java.io.File(dir, ".venv/bin/python")
        val cmd = if (py.canExecute()) listOf(py.path)
            else if (System.getenv("PATH").orEmpty().split(':').any { java.io.File(it, "uv").canExecute() }) listOf("uv", "run", "python")
            else return null
        val port = java.net.ServerSocket(0).use { it.localPort }
        val db = java.io.File.createTempFile("relay-sim", ".db").also { it.delete(); it.deleteOnExit() }
        val pb = ProcessBuilder(cmd + listOf("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "$port"))
            .directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.environment().putAll(mapOf("RELAY_ENV" to "dev", "RELAY_STORE" to "sqlite", "RELAY_DB" to db.path, "RELAY_DEVICE_KEY" to "dev-relay-key",
            "RELAY_OPERATOR_KEY" to "", "RELAY_PUBSUB_TOPIC" to ""))
        val p = pb.start()
        val base = "http://127.0.0.1:$port"
        val http = java.net.http.HttpClient.newHttpClient()
        repeat(120) {
            if (!p.isAlive) return null
            try {
                val resp = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("$base/healthz")).build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding())
                if (resp.statusCode() == 200) return p to base
            } catch (_: Exception) {}
            Thread.sleep(250)
        }
        p.destroyForcibly(); return null
    }

    @Test fun noFixNeedRelayedByPhonesWithAFixGivesTheRealBackendAMeshEstimate() {
        val started = startRealBackend()
        assumeTrue("real backend (backend/.venv or uv) not available", started != null)
        val (proc, base) = started!!
        try {
            val (buf, out) = capture()
            val r = LocateE2e.run(LocateConfig(base, pollMs = 150, timeoutMs = 30_000, out = out))
            val log = buf.toString("UTF-8")
            assertTrue(log + r.reason, r.success)
            assertEquals("mesh", r.geoSource); assertEquals(2, r.hintCount)
            assertEquals(listOf("B1", "B2"), r.hintRelays.sorted())
            // discs of 125 m and 150 m around relays ~46 m apart: the intersection is tighter than either, and holds the truth
            assertTrue("radius ${r.radiusM}", r.radiusM!! in 10 until 150)
            assertTrue("error ${r.errorM} vs radius ${r.radiusM}", r.errorM!! <= r.radiusM!!)
            assertTrue("error ${r.errorM}", r.errorM!! < 40)
            // the ticket is still "without location": an estimate, not a fix
            val ticket = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("$base/v1/tickets/${r.ticketNo}")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).body()
            val t = kotlinx.serialization.json.Json.parseToJsonElement(ticket).jsonObject
            assertEquals("false", t["has_location"]!!.jsonPrimitive.content)
            assertEquals("null", t["lat"]!!.jsonPrimitive.content)
        } finally { proc.destroyForcibly() }
    }

    @Test fun realBackendGetsNoEstimateWhenHelpLocateNeighboursIsOff() {
        val started = startRealBackend()
        assumeTrue("real backend (backend/.venv or uv) not available", started != null)
        val (proc, base) = started!!
        try {
            val (_, out) = capture()
            val r = LocateE2e.run(LocateConfig(base, helpLocate = false, pollMs = 150, timeoutMs = 30_000, out = out))
            assertTrue(r.reason, r.success)
            assertTrue(r.hintRelays.isEmpty()); assertEquals(null, r.geoSource)
        } finally { proc.destroyForcibly() }
    }
}
