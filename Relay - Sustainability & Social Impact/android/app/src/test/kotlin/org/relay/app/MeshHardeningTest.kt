package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ble.Backoff
import org.relay.app.ble.BleConstants
import org.relay.app.ble.DutyCycle
import org.relay.app.ble.GattOp
import org.relay.app.ble.GattOpQueue
import org.relay.app.ble.LinkHealth
import org.relay.app.ble.LinkRegistry
import org.relay.app.ble.LinkRole
import org.relay.app.ble.MeshEnergy
import org.relay.app.ble.RecentPeers
import org.relay.app.ble.ScanThrottle
import org.relay.app.ble.SetupPhase
import org.relay.app.ble.StartResult

class MeshHardeningTest {
    // ---------------------------------------------------------------- backoff after a requestMtu timeout
    @Test fun mtuTimeoutAbortsSetupAtOnceAndBackoffStartsAt2s() {
        var clock = 0L
        val q = GattOpQueue(timeoutMs = BleConstants.OP_TIMEOUT_MS, now = { clock })
        q.enqueue(object : GattOp { override val label = "requestMtu"; override fun start() = StartResult.STARTED })
        clock = 4_999; q.tick()
        assertNull(LinkHealth.setupAbortReason(SetupPhase.MTU, 4_999, 4_999, q.consecutiveTimeouts))
        clock = 5_000; q.tick()
        // The timeout is detected on the 500 ms tick, not after the old 25 s setup deadline.
        val reason = LinkHealth.setupAbortReason(SetupPhase.MTU, 5_000, 5_000, q.consecutiveTimeouts)
        assertNotNull(reason)
        val b = Backoff()
        assertEquals("first reconnect delay", 2_000L, b.onFailure("aa", clock))
        assertFalse(b.canAttempt("aa", clock + 1_999)); assertTrue(b.canAttempt("aa", clock + 2_000))
    }

    @Test fun repeatedFailuresStayExponentialAndCapped() {
        val b = Backoff()
        val d = (1..9).map { b.onFailure("aa", 0) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L, 60_000L), d)
        assertEquals(BleConstants.BACKOFF_MIN_MS, 2_000L)
    }

    @Test fun clearedBackoffLetsARestartedTransportReconnectAtOnce() {
        val b = Backoff()
        repeat(5) { b.onFailure("aa", 0) } // five toggles' worth of failures
        assertFalse(b.canAttempt("aa", 1_000))
        b.clear()
        assertTrue(b.canAttempt("aa", 0))
        assertEquals(2_000L, b.onFailure("aa", 0))
    }

    @Test fun connectAndSetupDeadlines() {
        assertNull(LinkHealth.setupAbortReason(SetupPhase.CONNECTING, 9_000, 9_000, 0))
        assertEquals("connect timeout", LinkHealth.setupAbortReason(SetupPhase.CONNECTING, 10_001, 10_001, 0))
        assertNull(LinkHealth.setupAbortReason(SetupPhase.DISCOVERING, 12_000, 24_000, 0))
        assertNotNull(LinkHealth.setupAbortReason(SetupPhase.DISCOVERING, 1_000, 25_001, 0))
        assertNull("a live link is never aborted by setup rules", LinkHealth.setupAbortReason(SetupPhase.UP, 99_000, 99_000, 5))
    }

    // ---------------------------------------------------------------- cleanup paths
    private class Op(override val label: String, val log: MutableList<String>) : GattOp {
        override fun start(): StartResult { log += label; return StartResult.STARTED }
    }

    @Test fun clearedQueueIsFullyReusable() {
        var clock = 0L
        val log = ArrayList<String>()
        val q = GattOpQueue(timeoutMs = 1_000, now = { clock })
        q.enqueue(Op("a", log)); q.enqueue(Op("b", log))
        q.clear()
        assertEquals(0, q.size()); assertFalse(q.inFlight())
        q.enqueue(Op("c", log))
        assertEquals(listOf("a", "c"), log)
        assertTrue(q.inFlight())
    }

    @Test fun clearedQueueAfterBusyRetryStillPumps() {
        val log = ArrayList<String>()
        var result = StartResult.RETRY
        val q = GattOpQueue(maxRetries = 8, now = { 0L })
        q.enqueue(object : GattOp { override val label = "busy"; override fun start(): StartResult { log += label; return result } })
        q.clear() // waitingForTick must not survive a clear, or the next link's first op would hang until a tick
        result = StartResult.STARTED
        q.enqueue(Op("fresh", log))
        assertEquals(listOf("busy", "fresh"), log)
    }

    @Test fun macToNodeMapIsBounded() {
        val r = LinkRegistry(ByteArray(8) { 1 })
        repeat(LinkRegistry.MAX_MAC_ENTRIES * 3) { i ->
            val mac = "m$i"
            r.add("c:$mac", mac, LinkRole.CENTRAL)
            r.onHello("c:$mac", ByteArray(8) { (i + 10).toByte() }.also { it[0] = (i shr 8).toByte(); it[1] = i.toByte(); it[7] = 9 })
            r.remove("c:$mac")
        }
        assertTrue(r.knownMacCount() <= LinkRegistry.MAX_MAC_ENTRIES)
        r.clear()
        assertEquals(0, r.knownMacCount())
    }

    @Test fun scanRssiIsKeptPerMacForCentralLinksOnlyAndBounded() {
        val r = LinkRegistry(ByteArray(8) { 1 })
        assertNull(r.rssiFor("c:aa"))
        r.noteScanRssi("aa", -71)
        r.noteScanRssi("bb", 127)   // "unavailable" is ignored
        r.noteScanRssi("cc", -200)  // out of range is ignored
        assertEquals(-71, r.rssiFor("c:aa"))
        assertNull("peripheral links were never scanned", r.rssiFor("p:aa"))
        assertNull(r.rssiFor("c:bb")); assertNull(r.rssiFor("c:cc"))
        r.noteScanRssi("aa", -60) // latest scan wins
        assertEquals(-60, r.rssiFor("c:aa"))

        repeat(LinkRegistry.MAX_MAC_ENTRIES * 3) { r.noteScanRssi("m$it", -50) }
        assertNull("oldest entries are evicted", r.rssiFor("c:aa"))
        assertEquals(-50, r.rssiFor("c:m${LinkRegistry.MAX_MAC_ENTRIES * 3 - 1}"))
        r.clear()
        assertNull(r.rssiFor("c:m${LinkRegistry.MAX_MAC_ENTRIES * 3 - 1}"))
    }

    @Test fun throttleResetAfterBluetoothToggleStormAllowsScanAgain() {
        val t = ScanThrottle()
        repeat(5) { assertTrue(t.tryAcquire(it * 1_000L)) } // five toggles in five seconds
        assertFalse(t.tryAcquire(6_000))
        t.reset()
        assertTrue(t.tryAcquire(6_000))
    }

    // ---------------------------------------------------------------- recent peers
    @Test fun keepsOnlyTheLastThreeDistinctNodesNewestFirst() {
        val changes = ArrayList<List<RecentPeers.Peer>>()
        val r = RecentPeers { changes += it }
        r.record("m1", "n1"); r.record("m2", "n2"); r.record("m3", "n3"); r.record("m4", "n4")
        assertEquals(listOf("n4", "n3", "n2"), r.all().map { it.nodeIdHex })
        r.record("m9", "n2") // same node, new (rotated) address: moves to the front, no duplicate
        assertEquals(listOf("n2", "n4", "n3"), r.all().map { it.nodeIdHex })
        assertEquals("m9", r.all().first().mac)
        val n = changes.size
        r.record("m9", "n2")
        assertEquals("recording the same newest peer again persists nothing", n, changes.size)
    }

    @Test fun persistenceRoundTripAndGarbageTolerance() {
        val peers = listOf(RecentPeers.Peer("AA:BB:CC:DD:EE:01", "0011223344556677"), RecentPeers.Peer("AA:BB:CC:DD:EE:02", "8899aabbccddeeff"))
        assertEquals(peers, RecentPeers.parse(RecentPeers.serialize(peers)))
        assertEquals(emptyList<RecentPeers.Peer>(), RecentPeers.parse(null))
        assertEquals(emptyList<RecentPeers.Peer>(), RecentPeers.parse("junk;;a,b,c;,"))
        assertEquals(3, RecentPeers(List(5) { RecentPeers.Peer("m$it", "n$it") }).all().size)
    }

    @Test fun nonRecentAdvertisersWaitOnlyInsideTheWindowAndWhileARecentPeerIsUnlinked() {
        val r = RecentPeers(listOf(RecentPeers.Peer("m1", "n1"), RecentPeers.Peer("m2", "n2")))
        val linked = setOf<String>()
        assertFalse("recent peer is never deferred", r.shouldDefer(1_000, 4_000, "x", "n1") { it in linked })
        assertTrue("stranger waits", r.shouldDefer(1_000, 4_000, "x", "n9") { it in linked })
        assertFalse("window over", r.shouldDefer(4_000, 4_000, "x", "n9") { it in linked })
        assertTrue("one recent peer still missing", r.shouldDefer(1_000, 4_000, "x", "n9") { it == "n1" })
        assertFalse("all recent peers linked", r.shouldDefer(1_000, 4_000, "x", "n9") { true })
        assertFalse("no hint: matched by address", r.shouldDefer(1_000, 4_000, "m2", null) { false })
        assertTrue(r.shouldDefer(1_000, 4_000, "zz", null) { false })
        assertFalse("nothing remembered, nothing deferred", RecentPeers().shouldDefer(0, 4_000, "x", "n9") { false })
    }

    // ---------------------------------------------------------------- duty cycle and hours
    @Test fun dutyModeByBatteryChargingAndForeground() {
        assertEquals(DutyCycle.Mode.NORMAL, DutyCycle.choose(80, false, false))
        assertEquals(DutyCycle.Mode.CONTINUOUS, DutyCycle.choose(80, false, true))
        assertEquals(DutyCycle.Mode.LOW_BATTERY, DutyCycle.choose(19, false, false))
        assertEquals("low battery wins over foreground", DutyCycle.Mode.LOW_BATTERY, DutyCycle.choose(19, false, true))
        assertEquals("20% is not low", DutyCycle.Mode.NORMAL, DutyCycle.choose(20, false, false))
        assertEquals("charging is never low", DutyCycle.Mode.NORMAL, DutyCycle.choose(5, true, false))
        assertEquals("unknown level is not low", DutyCycle.Mode.NORMAL, DutyCycle.choose(-1, false, false))
    }

    @Test fun dutyWindowsAreAsSpecifiedAndRespectTheScanStartLimit() {
        assertEquals(3_000L, DutyCycle.Mode.NORMAL.scanMs); assertEquals(12_000L, DutyCycle.Mode.NORMAL.idleMs)
        assertEquals(30_000L, DutyCycle.Mode.LOW_BATTERY.scanMs!! + DutyCycle.Mode.LOW_BATTERY.idleMs)
        assertNull(DutyCycle.Mode.CONTINUOUS.scanMs)
        for (m in DutyCycle.Mode.values()) {
            assertTrue("${m.name} uses ${m.startsPer30s} starts per 30 s", m.startsPer30s <= BleConstants.SCAN_STARTS_PER_WINDOW)
        }
        // Replay a minute of NORMAL cycling through the real throttle: never refused.
        val t = ScanThrottle(); var now = 0L
        repeat(8) { assertTrue(t.tryAcquire(now)); now += 15_000 }
    }

    @Test fun meshHoursEstimate() {
        assertEquals(4.0, MeshEnergy.drainPctPerHour(DutyCycle.Mode.CONTINUOUS), 1e-9)
        assertEquals(1.92, MeshEnergy.drainPctPerHour(DutyCycle.Mode.NORMAL), 1e-9)
        assertEquals(1.66, MeshEnergy.drainPctPerHour(DutyCycle.Mode.LOW_BATTERY), 1e-9)
        assertEquals(25.0, MeshEnergy.hoursRemaining(100, false, DutyCycle.Mode.CONTINUOUS)!!, 1e-9)
        assertEquals(50.0 / 1.92, MeshEnergy.hoursRemaining(50, false, DutyCycle.Mode.NORMAL)!!, 1e-9)
        assertEquals(0.0, MeshEnergy.hoursRemaining(0, false, DutyCycle.Mode.NORMAL)!!, 1e-9)
        assertNull("charging", MeshEnergy.hoursRemaining(50, true, DutyCycle.Mode.NORMAL))
        assertNull("unknown level", MeshEnergy.hoursRemaining(-1, false, DutyCycle.Mode.NORMAL))
        assertTrue("lower duty means more hours", MeshEnergy.hoursRemaining(18, false, DutyCycle.Mode.LOW_BATTERY)!! > 18 / MeshEnergy.drainPctPerHour(DutyCycle.Mode.NORMAL))
    }
}
