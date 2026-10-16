package org.relay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.app.ble.Allowlist
import org.relay.app.ble.Backoff
import org.relay.app.ble.LinkRegistry
import org.relay.app.ble.LinkRegistry.Decision
import org.relay.app.ble.LinkRole
import org.relay.app.ble.ScanThrottle
import org.relay.app.location.LocAccuracy
import org.relay.core.ids.Hex

class BleLogicTest {
    // ---------------------------------------------------------------- backoff
    @Test fun backoffDoublesFrom2sTo60sCap() {
        val b = Backoff()
        val delays = (1..8).map { b.onFailure("aa:bb", 0) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L, 60_000L), delays)
    }

    @Test fun backoffGatesAttemptsPerMacAndResetsOnSuccess() {
        val b = Backoff()
        assertTrue(b.canAttempt("m1", 0))
        b.onFailure("m1", 1_000)
        assertFalse(b.canAttempt("m1", 2_999))
        assertTrue(b.canAttempt("m1", 3_000))
        assertTrue("other MACs are unaffected", b.canAttempt("m2", 1_001))
        b.onFailure("m1", 3_000) // second failure: 4 s
        assertFalse(b.canAttempt("m1", 6_999))
        assertTrue(b.canAttempt("m1", 7_000))
        b.onSuccess("m1")
        assertTrue(b.canAttempt("m1", 3_001))
        assertEquals(2_000L, b.onFailure("m1", 0))
    }

    @Test fun blockUntilDoesNotCountAsFailureAndNeverShortens() {
        val b = Backoff()
        b.blockUntil("m", 60_000)
        assertFalse(b.canAttempt("m", 59_999)); assertTrue(b.canAttempt("m", 60_000))
        assertEquals(0, b.failures("m"))
        b.onFailure("m", 0) // would only block until 2 s; the longer block stays
        assertFalse(b.canAttempt("m", 30_000))
    }

    // ---------------------------------------------------------------- allowlist
    @Test fun emptyAllowlistAcceptsEveryone() {
        assertTrue(Allowlist.matches("", "a1b2c3d4e5f60718"))
        assertTrue(Allowlist.matches("  , ", "a1b2c3d4e5f60718"))
    }

    @Test fun allowlistMatchesPrefixesCaseInsensitively() {
        val csv = "A1B2, 99ff ,0011223344"
        assertTrue(Allowlist.matches(csv, "a1b2c3d4e5f60718"))
        assertTrue(Allowlist.matches(csv, "99ff000000000000"))
        assertTrue(Allowlist.matches(csv, "0011223344556677"))
        assertFalse(Allowlist.matches(csv, "a1b3c3d4e5f60718"))
        assertFalse(Allowlist.matches(csv, "ff99000000000000"))
        assertEquals(listOf("a1b2", "99ff", "0011223344"), Allowlist.parse(csv))
    }

    // ---------------------------------------------------------------- scan throttle
    @Test fun scanThrottleAllowsFiveStartsPer30Seconds() {
        val t = ScanThrottle()
        repeat(5) { assertTrue(t.tryAcquire(it * 1_000L)) }
        assertFalse(t.tryAcquire(5_000))
        assertEquals(26_000L, t.waitMs(4_000)) // oldest start at 0 -> allowed again at 30 s
        assertFalse(t.tryAcquire(29_999))
        assertTrue(t.tryAcquire(30_000))
        assertFalse("the window is full again", t.tryAcquire(30_001))
    }

    // ---------------------------------------------------------------- duplicate links
    private val low = ByteArray(8) { 0x10 }
    private val high = ByteArray(8) { 0x80.toByte() }

    @Test fun lowerIdNodeKeepsItsCentralLinkAndClosesPeripheral() {
        // I am the LOWER id: I should be central on the kept link.
        val r = LinkRegistry(low)
        r.add("c:AA", "AA", LinkRole.CENTRAL); r.add("p:BB", "BB", LinkRole.PERIPHERAL)
        assertEquals(Decision.Keep, r.onHello("c:AA", high))
        assertEquals(Decision.CloseThis("duplicate link to ${Hex.encode(high).take(8)}; keeping central side"), r.onHello("p:BB", high))
    }

    @Test fun lowerIdNodeClosesOlderPeripheralWhenCentralHelloArrivesSecond() {
        val r = LinkRegistry(low)
        r.add("p:BB", "BB", LinkRole.PERIPHERAL); r.add("c:AA", "AA", LinkRole.CENTRAL)
        assertEquals(Decision.Keep, r.onHello("p:BB", high))
        assertEquals(Decision.CloseOther("p:BB"), r.onHello("c:AA", high))
    }

    @Test fun higherIdNodeKeepsPeripheralAndClosesCentral() {
        // I am the HIGHER id: the peer (lower) should be the central, so I keep my peripheral link.
        val r = LinkRegistry(high)
        r.add("c:AA", "AA", LinkRole.CENTRAL); r.add("p:BB", "BB", LinkRole.PERIPHERAL)
        assertEquals(Decision.Keep, r.onHello("p:BB", low))
        assertEquals(Decision.CloseThis("duplicate link to ${Hex.encode(low).take(8)}; keeping peripheral side"), r.onHello("c:AA", low))
    }

    @Test fun bothSidesAgreeOnTheSurvivingLink() {
        // Underlying links: L1 = A central -> B peripheral, L2 = B central -> A peripheral.
        // A sees: c:L1 (central), p:L2 (peripheral). B sees: p:L1 (peripheral), c:L2 (central).
        val a = LinkRegistry(low); val b = LinkRegistry(high)
        a.add("c:L1", "L1", LinkRole.CENTRAL); a.add("p:L2", "L2", LinkRole.PERIPHERAL)
        b.add("p:L1", "L1", LinkRole.PERIPHERAL); b.add("c:L2", "L2", LinkRole.CENTRAL)
        val closedByA = mutableSetOf<String>(); val closedByB = mutableSetOf<String>()
        fun apply(d: Decision, thisLink: String, into: MutableSet<String>) = when (d) {
            is Decision.CloseThis -> into += thisLink
            is Decision.CloseOther -> into += d.otherLinkId
            Decision.Keep -> false
        }
        apply(a.onHello("c:L1", high), "c:L1", closedByA); apply(a.onHello("p:L2", high), "p:L2", closedByA)
        apply(b.onHello("p:L1", low), "p:L1", closedByB); apply(b.onHello("c:L2", low), "c:L2", closedByB)
        assertEquals(setOf("p:L2"), closedByA) // L2 dies on A's side
        assertEquals(setOf("c:L2"), closedByB) // and on B's side: the same underlying link
    }

    @Test fun twoLinksOfTheSameRoleKeepTheOlder() {
        val r = LinkRegistry(low)
        r.add("c:AA", "AA", LinkRole.CENTRAL); r.add("c:CC", "CC", LinkRole.CENTRAL) // peer's MAC rotated
        assertEquals(Decision.Keep, r.onHello("c:AA", high))
        assertTrue(r.onHello("c:CC", high) is Decision.CloseThis)
    }

    @Test fun differentNodesAreNotDuplicates() {
        val r = LinkRegistry(low)
        r.add("c:AA", "AA", LinkRole.CENTRAL); r.add("p:BB", "BB", LinkRole.PERIPHERAL)
        assertEquals(Decision.Keep, r.onHello("c:AA", ByteArray(8) { 0x55 }))
        assertEquals(Decision.Keep, r.onHello("p:BB", ByteArray(8) { 0x66 }))
    }

    @Test fun ownNodeIdIsClosed() {
        val r = LinkRegistry(low)
        r.add("c:AA", "AA", LinkRole.CENTRAL)
        assertTrue(r.onHello("c:AA", low) is Decision.CloseThis)
    }

    @Test fun shouldConnectAvoidsMacsAndNodesWeAlreadyHaveALinkTo() {
        val r = LinkRegistry(low)
        assertTrue(r.shouldConnect("AA"))
        r.add("p:AA", "AA", LinkRole.PERIPHERAL)
        assertFalse("live link on that MAC", r.shouldConnect("AA"))
        r.add("c:CC", "CC", LinkRole.CENTRAL); r.onHello("c:CC", high)
        r.remove("c:CC")
        assertTrue("link gone, may reconnect", r.shouldConnect("CC"))
        r.add("p:DD", "DD", LinkRole.PERIPHERAL); r.onHello("p:DD", high) // same node now reachable via another MAC
        assertFalse("CC maps to a node we are already linked to", r.shouldConnect("CC"))
    }

    @Test fun linkCapsCountCorrectly() {
        val r = LinkRegistry(low)
        repeat(3) { r.add("c:$it", "$it", LinkRole.CENTRAL) }
        repeat(2) { r.add("p:$it", "p$it", LinkRole.PERIPHERAL) }
        assertEquals(5, r.total()); assertEquals(3, r.centralCount())
        r.remove("c:0"); assertEquals(4, r.total())
    }

    // ---------------------------------------------------------------- loc_acc buckets
    @Test fun locAccBucketsFollowSpec() {
        assertEquals(1, LocAccuracy.bucket(0f)); assertEquals(1, LocAccuracy.bucket(9.9f))
        assertEquals(2, LocAccuracy.bucket(10f)); assertEquals(2, LocAccuracy.bucket(49.9f))
        assertEquals(3, LocAccuracy.bucket(50f)); assertEquals(3, LocAccuracy.bucket(199f))
        assertEquals(4, LocAccuracy.bucket(200f)); assertEquals(4, LocAccuracy.bucket(999f))
        assertEquals(5, LocAccuracy.bucket(1000f)); assertEquals(5, LocAccuracy.bucket(50_000f))
        assertEquals(5, LocAccuracy.bucket(null)); assertEquals(5, LocAccuracy.bucket(Float.NaN))
    }
}
