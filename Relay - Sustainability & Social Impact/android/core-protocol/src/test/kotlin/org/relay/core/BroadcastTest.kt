package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.ids.NodeId
import org.relay.core.payload.Broadcast
import org.relay.core.payload.BroadcastCodec
import org.relay.core.payload.BroadcastKind
import org.relay.core.triage.DisasterContext
import org.relay.core.triage.DisasterGate
import org.relay.core.triage.DisasterType

class BroadcastTest {
    private val hour = 3600_000L
    private fun bc(issuedAtMs: Long = T0, type: DisasterType = DisasterType.FLOOD, kind: BroadcastKind = BroadcastKind.DISASTER) =
        Broadcast(NodeId.random(), kind, type, issuedAtMs / 1000)

    private fun frame(b: Broadcast, ttl: Int = 7, ts: Long = T0): ByteArray =
        FrameCodec.encode(Frame(type = FrameTypes.BROADCAST, ttl = ttl, timestampMs = ts, senderId = NodeId.random(), payload = BroadcastCodec.encode(b)))

    // ------------------------------------------------------------ codec
    @Test fun codecRoundTripsAndIsTwentyFiveBytes() {
        for (t in DisasterType.entries) for (k in BroadcastKind.entries) {
            val b = Broadcast(NodeId.random(), k, t, 1_790_000_000L, 2029612, 8582451, 40)
            val p = BroadcastCodec.encode(b)
            assertEquals(25, p.size)
            assertEquals(b, BroadcastCodec.decode(p))
        }
        val edge = Broadcast(NodeId.random(), BroadcastKind.DISASTER, DisasterType.LANDSLIDE, 0xFFFFFFFFL, -9_000_000, 18_000_000, 0xFFFF)
        assertEquals(edge, BroadcastCodec.decode(BroadcastCodec.encode(edge)))
    }

    @Test fun decoderRejectsWhatTheSpecSaysToDrop() {
        val good = BroadcastCodec.encode(bc())
        assertNotNull(BroadcastCodec.decode(good))
        assertNull(BroadcastCodec.decode(good.copyOf().also { it[0] = 2 }))
        assertNull(BroadcastCodec.decode(good.copyOf(24)))
        assertNull(BroadcastCodec.decode(good + byteArrayOf(0)))
        assertNull(BroadcastCodec.decode(good.copyOf().also { it[9] = 0 }))   // kind 0
        assertNull(BroadcastCodec.decode(good.copyOf().also { it[9] = 3 }))   // kind 3
        assertNull(BroadcastCodec.decode(good.copyOf().also { it[10] = 4 }))  // disaster 4
        assertNull(BroadcastCodec.decode(good.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(15, 9_000_001) }))
    }

    @Test fun encoderRefusesInvalidFields() {
        for (bad in listOf(bc().copy(id = ByteArray(7)), bc().copy(radiusKm = 70_000), bc().copy(issuedAt = -1), bc().copy(latE5 = 9_000_001))) {
            try { BroadcastCodec.encode(bad); error("expected IllegalArgumentException for $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun lifeIsTwentyFourHoursFromIssue() {
        val b = bc(T0)
        assertTrue(b.isLive(T0 / 1000))
        assertTrue(b.isLive(T0 / 1000 + 24 * 3600))
        assertFalse(b.isLive(T0 / 1000 + 24 * 3600 + 1))
        assertTrue(b.isLive(T0 / 1000 - 300))   // drift
        assertFalse(b.isLive(T0 / 1000 - 3600)) // from the future
    }

    // ------------------------------------------------------------ gossip: gateways catch it, offline phones carry it
    @Test fun aGatewayOriginatesAndEveryConnectedPhoneStoresAndRelaysIt() {
        val net = TestNet()
        net.chain(listOf("gw", "a", "b", "c"))
        val b = bc()
        assertNotNull(net.node("gw").node.originateBroadcast(b))
        net.runUntilIdle()
        for (n in listOf("gw", "a", "b", "c")) {
            assertEquals(n, listOf(b), net.node(n).broadcasts)
            assertEquals(listOf(b), net.node(n).node.storedBroadcasts().map { it.first })
        }
    }

    @Test fun twoGatewaysOriginatingTheSameBroadcastDoNotDuplicateAnything() {
        val net = TestNet()
        net.chain(listOf("g1", "a", "g2"))
        val b = bc()
        net.node("g1").node.originateBroadcast(b)
        net.runUntilIdle()
        assertNull(net.node("g2").node.originateBroadcast(b)) // already holds it: nothing new to send
        net.runUntilIdle()
        assertEquals(1, net.node("a").broadcasts.size)
        assertEquals(1, net.node("g2").broadcasts.size)
    }

    @Test fun anOfflinePhoneThatLaterMeetsACarrierGetsIt() {
        val net = TestNet()
        net.chain(listOf("gw", "carrier"))
        net.node("gw").node.originateBroadcast(bc())
        net.runUntilIdle()
        // the carrier walks away from the gateway and meets a phone that never saw it
        net.disconnect("gw", "carrier")
        net.node("far")
        net.connect("carrier", "far")
        net.runUntilIdle()
        assertEquals(1, net.node("far").broadcasts.size)
    }

    @Test fun aPhoneThatMissedItOnTheFloodGetsItFromTheInventoryDiff() {
        val net = TestNet()
        net.node("gw"); net.node("late")
        net.node("gw").node.originateBroadcast(bc())
        net.runUntilIdle()
        net.connect("gw", "late")
        net.runUntilIdle()
        assertEquals(1, net.node("late").broadcasts.size)
    }

    @Test fun expiredBroadcastsAreNotStoredNorRelayed() {
        val net = TestNet()
        net.chain(listOf("a", "b", "c"))
        val old = bc(issuedAtMs = T0 - 25 * hour)
        net.node("a").node.onFrame("b", frame(old, ts = T0))  // arrives from a neighbour, but it is 25 h old
        net.runUntilIdle()
        assertTrue(net.node("a").broadcasts.isEmpty())
        assertTrue(net.node("a").node.storedBroadcasts().isEmpty())
        assertTrue(net.node("c").broadcasts.isEmpty())
        assertNull(net.node("a").node.originateBroadcast(old))
    }

    @Test fun aStoredBroadcastLeavesTheStoreAtTwentyFourHoursNotSeventyTwo() {
        val net = TestNet()
        net.node("a").node.originateBroadcast(bc())
        net.runUntilIdle()
        assertEquals(1, net.node("a").node.storedBroadcasts().size)
        net.runFor(23 * hour)
        net.node("a").node.expire()
        assertEquals(1, net.node("a").node.storedBroadcasts().size)
        net.runFor(2 * hour)
        net.node("a").node.expire()
        assertEquals(0, net.node("a").node.storedBroadcasts().size)
        assertTrue(net.node("a").node.stats().broadcastsDelivered == 1L)
    }

    @Test fun lifeCountsFromIssueNotFromWhenAPhoneGotIt() {
        val net = TestNet()
        net.node("a")
        val b = bc(issuedAtMs = T0 - 20 * hour)           // issued 20 h ago, reaches this phone only now
        net.node("a").node.onFrame("x", frame(b))
        net.runUntilIdle()
        assertEquals(1, net.node("a").node.storedBroadcasts().size)
        net.runFor(5 * hour)                              // 25 h after issue
        net.node("a").node.expire()
        assertEquals(0, net.node("a").node.storedBroadcasts().size)
    }

    @Test fun anOlderBuildRelaysAnUnknownVersionOnceAndNeverStoresIt() {
        val net = TestNet()
        net.chain(listOf("a", "b"))
        val b = bc()
        val v2 = BroadcastCodec.encode(b).also { it[0] = 2 }
        val raw = FrameCodec.encode(Frame(type = FrameTypes.BROADCAST, ttl = 7, timestampMs = T0, senderId = NodeId.random(), payload = v2))
        net.node("a").node.onFrame("b", raw)
        net.runUntilIdle()
        assertTrue(net.node("a").broadcasts.isEmpty())
        assertTrue(net.node("a").node.storedBroadcasts().isEmpty())
    }

    // ------------------------------------------------------------ the gate that reads them
    @Test fun anAreaOnlyHidesADeclarationWhenThePhoneKnowsItIsOutside() {
        val nowSec = T0 / 1000
        val here = 2029612 to 8582451                          // Bhubaneswar
        val near = DisasterContext(DisasterType.FLOOD, nowSec - 3600, DisasterContext.Area(2030000, 8580000, 30))
        val far = DisasterContext(DisasterType.FLOOD, nowSec - 3600, DisasterContext.Area(1960000, 8480000, 30)) // ~120 km away
        assertTrue(DisasterGate.active(listOf(near), nowSec, here).types.isNotEmpty())
        assertTrue(DisasterGate.active(listOf(far), nowSec, here).types.isEmpty())
        assertTrue(DisasterGate.active(listOf(far), nowSec, null).types.isNotEmpty())                  // position unknown: keep it
        assertTrue(DisasterGate.active(listOf(far.copy(area = null)), nowSec, here).types.isNotEmpty()) // whole-area
        assertTrue(DisasterGate.active(listOf(far.copy(area = DisasterContext.Area(1960000, 8480000, 0))), nowSec, here).types.isNotEmpty())
    }
}
