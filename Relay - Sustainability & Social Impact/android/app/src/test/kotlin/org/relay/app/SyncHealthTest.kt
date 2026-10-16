package org.relay.app

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.gateway.HealthKind
import org.relay.app.gateway.SyncClient
import org.relay.app.gateway.SyncHealth
import org.relay.app.gateway.SyncHealthText
import java.net.InetSocketAddress

class SyncHealthTest {
    private val url = "http://100.116.13.37:8080"
    private val host = "100.116.13.37:8080"

    @Test fun startsWithNoAttempt() {
        val h = SyncHealth()
        assertNull(h.lastOk)
        assertEquals(HealthKind.PENDING, SyncHealthText.line(h, true, host, 1_000).kind)
    }

    @Test fun successThenFailureThenRecovery() {
        var h = SyncHealth().record(true, null, 10_000, url)
        assertEquals(true, h.lastOk); assertEquals(10_000L, h.lastOkAtMs); assertEquals(host, h.host); assertEquals(0, h.consecutiveFailures)

        h = h.record(false, "SocketTimeoutException: failed to connect to /100.116.13.37 (port 8080)", 20_000, url)
        assertEquals(false, h.lastOk)
        assertEquals("timed out", h.error)
        assertEquals(10_000L, h.lastOkAtMs) // last good time is kept
        assertEquals(1, h.consecutiveFailures)
        assertEquals(20_000L, h.failingSinceMs)

        h = h.record(false, "SocketTimeoutException: x", 30_000, url)
        assertEquals(2, h.consecutiveFailures)
        assertEquals(20_000L, h.failingSinceMs) // the failure run started at the first failure

        h = h.record(true, null, 40_000, url)
        assertEquals(true, h.lastOk); assertNull(h.error); assertEquals(0, h.consecutiveFailures); assertEquals(0L, h.failingSinceMs)
        assertEquals(40_000L, h.lastOkAtMs)
    }

    @Test fun firstEverFailureStartsFailureRun() {
        val h = SyncHealth().record(false, "boom", 5_000, url)
        assertEquals(5_000L, h.failingSinceMs)
        assertEquals(1, h.consecutiveFailures)
    }

    @Test fun shortErrors() {
        assertEquals("timed out", SyncHealth.shortError("SocketTimeoutException: failed to connect to /10.0.2.2 (port 8080) from /10.1.1.1 (port 4) after 5000ms"))
        assertEquals("unknown host", SyncHealth.shortError("UnknownHostException: Unable to resolve host \"x\": No address associated with hostname"))
        assertEquals("connection refused", SyncHealth.shortError("ConnectException: failed to connect to /1.2.3.4 (port 8080): connect failed: ECONNREFUSED (Connection refused)"))
        assertEquals("network unreachable", SyncHealth.shortError("ConnectException: connect failed: ENETUNREACH (Network is unreachable)"))
        assertEquals("cleartext HTTP blocked", SyncHealth.shortError("IOException: Cleartext HTTP traffic to x not permitted"))
        assertTrue(SyncHealth.shortError("HTTP 401: {\"detail\":\"bad key\"}").contains("401"))
        assertEquals("HTTP 500: boom", SyncHealth.shortError("HTTP 500: boom"))
        assertEquals("unknown error", SyncHealth.shortError(null))
        assertEquals("unknown error", SyncHealth.shortError(" "))
        assertEquals(60, SyncHealth.shortError("x".repeat(200)).length)
    }

    @Test fun linesForEachState() {
        val ok = SyncHealth().record(true, null, 100_000, url)
        val l = SyncHealthText.line(ok, true, host, 112_000)
        assertEquals(HealthKind.OK, l.kind)
        assertEquals("Gateway ✓ synced 12 s ago", l.text)

        val bad = ok.record(false, "SocketTimeoutException", 120_000, url)
        val b = SyncHealthText.line(bad, true, host, 125_000)
        assertEquals(HealthKind.FAILING, b.kind)
        assertEquals("Gateway can't reach backend ($host) — check Settings / Tailscale", b.text)
        assertEquals("timed out · failing for 5 s", SyncHealthText.failureDetail(bad, 125_000))
        assertNull(SyncHealthText.failureDetail(ok, 125_000))

        assertEquals(HealthKind.NOT_GATEWAY, SyncHealthText.line(ok, false, host, 112_000).kind)
    }

    @Test fun healthForADifferentBackendIsIgnored() {
        val bad = SyncHealth().record(false, "SocketTimeoutException", 1_000, "http://10.0.2.2:8080")
        assertEquals(HealthKind.PENDING, SyncHealthText.line(bad, true, host, 2_000).kind)
    }

    @Test fun agoFormatting() {
        assertEquals("0 s ago", SyncHealthText.ago(-5))
        assertEquals("59 s ago", SyncHealthText.ago(59_999))
        assertEquals("1 min ago", SyncHealthText.ago(60_000))
        assertEquals("2 h ago", SyncHealthText.ago(2 * 3_600_000L + 5))
    }

    @Test fun probeAgainstFakeServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/health") { ex ->
            val b = "{\"ok\":true}".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.createContext("/bad/v1/health") { ex -> ex.sendResponseHeaders(503, -1); ex.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val ok = SyncClient().probe("$base/", 2_000)
            assertTrue(ok.detail, ok.ok)
            val bad = SyncClient().probe("$base/bad", 2_000)
            assertFalse(bad.ok); assertEquals("HTTP 503", bad.detail)
        } finally { server.stop(0) }
        val closed = SyncClient().probe("http://127.0.0.1:1", 1_000)
        assertFalse(closed.ok); assertNotNull(closed.detail)
        assertFalse(SyncClient().probe("not a url", 500).ok)
    }
}
