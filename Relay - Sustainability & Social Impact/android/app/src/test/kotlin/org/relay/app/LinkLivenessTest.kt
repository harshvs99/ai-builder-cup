package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ble.BleConstants
import org.relay.app.ble.LinkHealth

class LinkLivenessTest {
    @Test fun timeoutIsThreeResyncIntervals() {
        assertEquals(90_000L, LinkHealth.LIVENESS_TIMEOUT_MS)
        assertEquals(3 * BleConstants.RESYNC_INTERVAL_MS, LinkHealth.LIVENESS_TIMEOUT_MS)
    }

    @Test fun silentLinkIsClosedOnlyAfterTheTimeout() {
        var clock = 1_000L
        val lastRx = clock
        assertNull(LinkHealth.livenessAbortReason(lastRx, clock))
        clock += LinkHealth.LIVENESS_TIMEOUT_MS - 1
        assertNull(LinkHealth.livenessAbortReason(lastRx, clock))
        clock += 1
        val reason = LinkHealth.livenessAbortReason(lastRx, clock)
        assertNotNull(reason)
        assertTrue(reason!!.contains("90s"))
    }

    @Test fun anyFrameResetsTheWatchdog() {
        var clock = 0L
        var lastRx = 0L
        // One inventory every 30 s keeps a healthy link alive indefinitely.
        repeat(20) {
            clock += BleConstants.RESYNC_INTERVAL_MS
            assertNull(LinkHealth.livenessAbortReason(lastRx, clock))
            lastRx = clock
        }
        // Two lost resyncs are tolerated; the third silent interval closes it.
        clock += 2 * BleConstants.RESYNC_INTERVAL_MS
        assertNull(LinkHealth.livenessAbortReason(lastRx, clock))
        clock += BleConstants.RESYNC_INTERVAL_MS
        assertNotNull(LinkHealth.livenessAbortReason(lastRx, clock))
    }

    @Test fun missingServiceIsRetriedExactlyOnce() {
        assertEquals(1_500L, LinkHealth.discoveryRetryDelayMs(0))
        assertNull("second miss gives up", LinkHealth.discoveryRetryDelayMs(1))
        assertNull(LinkHealth.discoveryRetryDelayMs(5))
    }

    @Test fun serviceChangedReconnectIsShortNotBackoff() {
        assertEquals(1_000L, LinkHealth.SERVICE_CHANGED_RECONNECT_MS)
        assertTrue(LinkHealth.SERVICE_CHANGED_RECONNECT_MS < BleConstants.BACKOFF_MIN_MS)
    }
}
