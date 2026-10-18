package org.relay.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.LocHintResponder
import org.relay.core.gossip.RelayFix
import org.relay.core.ids.NodeId
import org.relay.core.payload.Geo
import org.relay.core.payload.Hello
import org.relay.core.payload.HelloCodec
import org.relay.core.payload.LocHint
import org.relay.core.payload.LocHintCodec

class LocHintTest {
    private fun hint(ref: ByteArray = NodeId.random(), relay: ByteArray = NodeId.random(), acc: Int = 2, rssi: Int = -70) =
        LocHint(ref, relay, Geo.toE5(20.29601), Geo.toE5(85.82433), acc, rssi, T0 / 1000)

    private fun hintFrame(h: LocHint, ttl: Int = 7, sender: ByteArray = h.relayNode, payload: ByteArray = LocHintCodec.encode(h)): ByteArray =
        FrameCodec.encode(Frame(type = FrameTypes.LOC_HINT, ttl = ttl, timestampMs = T0, senderId = sender, payload = payload))

    // ------------------------------------------------------------ codec

    @Test fun codecRoundTripsAndIsExactlyThirtyOneBytes() {
        for (acc in 1..4) for (rssi in listOf(-128, -127, -67, 0, 127)) {
            val h = hint(acc = acc, rssi = rssi)
            val p = LocHintCodec.encode(h)
            assertEquals(31, p.size)
            assertEquals(h, LocHintCodec.decode(p))
        }
        val h = hint().copy(latE5 = -9_000_000, lonE5 = 18_000_000, observedAt = 0xFFFFFFFFL)
        assertEquals(h, LocHintCodec.decode(LocHintCodec.encode(h)))
    }

    @Test fun decoderRejectsWhatTheSpecSaysToDrop() {
        val good = LocHintCodec.encode(hint())
        assertNotNull(LocHintCodec.decode(good))
        assertNull(LocHintCodec.decode(good.also { }.copyOf().also { it[0] = 2 }))
        assertNull(LocHintCodec.decode(good.copyOf(30)))
        assertNull(LocHintCodec.decode(good + byteArrayOf(0)))
        assertNull(LocHintCodec.decode(good.copyOf().also { it[25] = 0 }))
        assertNull(LocHintCodec.decode(good.copyOf().also { it[25] = 5 }))
        assertNull(LocHintCodec.decode(LocHintCodec.encode(hint().copy(latE5 = 0)).copyOf().also {
            java.nio.ByteBuffer.wrap(it).putInt(17, 9_000_001)
        }))
        assertNull(LocHintCodec.decode(good.copyOf().also { java.nio.ByteBuffer.wrap(it).putInt(21, -18_000_001) }))
    }

    @Test fun encoderRefusesInvalidFields() {
        for (bad in listOf(hint(acc = 0), hint(acc = 5), hint().copy(rssiDbm = 128), hint().copy(latE5 = 9_000_001),
            hint().copy(observedAt = -1), hint(ref = ByteArray(7)))) {
            try { LocHintCodec.encode(bad); error("expected IllegalArgumentException for $bad") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun accuracyBuckets() {
        assertEquals(1, LocHintCodec.bucketFor(0f)); assertEquals(1, LocHintCodec.bucketFor(10f))
        assertEquals(2, LocHintCodec.bucketFor(10.1f)); assertEquals(2, LocHintCodec.bucketFor(25f))
        assertEquals(3, LocHintCodec.bucketFor(50f)); assertEquals(4, LocHintCodec.bucketFor(100f))
        assertNull(LocHintCodec.bucketFor(100.1f)); assertNull(LocHintCodec.bucketFor(Float.NaN)); assertNull(LocHintCodec.bucketFor(-1f))
        assertEquals(listOf(10, 25, 50, 100), (1..4).map { hint(acc = it).accuracyMaxM })
    }

    @Test fun keyIsDerivedPerNeedAndRelay() {
        val ref = NodeId.random(); val relay = NodeId.random()
        val k = LocHintCodec.key(ref, relay)
        assertEquals(8, k.size)
        assertArrayEquals(k, LocHintCodec.key(ref, relay))
        assertFalse(k.contentEquals(LocHintCodec.key(ref, NodeId.random())))
        assertFalse(k.contentEquals(LocHintCodec.key(NodeId.random(), relay)))
        // timestamp, position and rssi do not matter: re-sending an updated hint is the same hint
        assertEquals(hint(ref, relay, rssi = -50).keyHex, hint(ref, relay, rssi = -90).keyHex)
    }

    // ------------------------------------------------------------ gossip

    @Test fun hintIsStoredDeliveredFloodedAndNotRepeated() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val hint = hint()
        val raw = hintFrame(hint)
        h.node.onFrame("L1", raw); h.runTasks()
        assertEquals(listOf(hint), h.hints)
        assertEquals(1, h.node.stats().locHintsDelivered)
        assertEquals(1, h.node.storedLocHints().size)
        val out = h.sentTo("L2", FrameTypes.LOC_HINT).single()
        assertEquals(6, out.ttl)
        assertArrayEquals(raw.copyOfRange(14, raw.size), FrameCodec.encode(out).copyOfRange(14, raw.size)) // payload+sender untouched
        assertEquals(0, h.sentTo("L1", FrameTypes.LOC_HINT).size)
        h.clearSent(); h.node.onFrame("L2", raw); h.runTasks()
        assertTrue(h.sent.isEmpty()); assertEquals(1, h.hints.size); assertEquals(1, h.node.stats().duplicates)
    }

    @Test fun hintWithRelayDifferentFromSenderIsRejectedAndNotRelayed() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        h.node.onFrame("L1", hintFrame(hint(), sender = NodeId.random())); h.runTasks()
        assertTrue(h.hints.isEmpty() && h.sent.isEmpty() && h.node.storedLocHints().isEmpty())
        assertEquals(1, h.node.stats().framesRejected)
        // invalid payloads are dropped too
        h.node.onFrame("L1", hintFrame(hint(), payload = LocHintCodec.encode(hint()).copyOf(30))); h.runTasks()
        assertTrue(h.sent.isEmpty()); assertEquals(2, h.node.stats().framesRejected)
    }

    @Test fun unknownHintPayloadVersionIsRelayedOnceAsOpaque() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val hint = hint()
        val raw = hintFrame(hint, payload = LocHintCodec.encode(hint).also { it[0] = 2 })
        h.node.onFrame("L1", raw); h.runTasks()
        assertTrue(h.hints.isEmpty() && h.node.storedLocHints().isEmpty())
        assertEquals(1, h.sentTo("L2", FrameTypes.LOC_HINT).size)
    }

    @Test fun ttlOneHintIsStoredButNotFlooded() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        h.node.onFrame("L1", hintFrame(hint(), ttl = 1)); h.runTasks()
        assertEquals(1, h.hints.size); assertTrue(h.sent.isEmpty())
    }

    @Test fun originateLocHintFloodsWithInitialTtlAndOnlyOncePerNeed() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.clearSent()
        val mine = hint(relay = h.id)
        val f = h.node.originateLocHint(mine)
        assertNotNull(f); h.runTasks()
        val out = h.sentTo("L1", FrameTypes.LOC_HINT).single()
        assertEquals(7, out.ttl); assertArrayEquals(h.id, out.senderId); assertNull(out.recipientId)
        assertEquals(listOf(mine), h.hints)
        // a second hint for the same NEED from this node (even with a better position) is refused
        h.clearSent()
        assertNull(h.node.originateLocHint(mine.copy(acc = 1, rssiDbm = -40))); h.runTasks()
        assertTrue(h.sent.isEmpty()); assertEquals(1, h.hints.size)
        try { h.node.originateLocHint(hint()); error("relay_node must be this node") } catch (_: IllegalArgumentException) {}
    }

    @Test fun inventoryAdvertisesHintsAndAPeerThatLacksOneGetsIt() {
        val net = TestNet()
        net.connect("a", "b")
        val b = net.node("b")
        // a hint arrives at b from nowhere: b stores it; a is then connected and lacks it
        val a = net.node("a")
        net.runUntilIdle()
        val relay = NodeId.random()
        b.node.onFrame("a", hintFrame(hint(relay = relay), ttl = 1))   // ttl 1: not flooded, only stored
        net.runUntilIdle()
        assertEquals(1, b.locHints.size); assertTrue(a.locHints.isEmpty())
        net.resyncRound()
        assertEquals("a got the hint through the inventory diff", 1, a.locHints.size)
        net.resyncRound()
        assertEquals(1, a.locHints.size)
    }

    @Test fun hintsExpireAfter72HoursFromFirstStored() {
        val h = SoloHarness()
        h.node.linkUp("L1")
        h.node.onFrame("L1", hintFrame(hint()))
        assertEquals(1, h.node.storedLocHints().size)
        h.now += 71L * 3600 * 1000; h.node.expire(); assertEquals(1, h.node.storedLocHints().size)
        h.now += 2L * 3600 * 1000; h.node.expire(); assertEquals(0, h.node.storedLocHints().size)
    }

    // ------------------------------------------------------------ responder

    private class Rig(
        var enabled: Boolean = true,
        var fix: RelayFix? = null,
        var rssi: Int? = -64,
    ) {
        val h = SoloHarness()
        val requests = ArrayList<Long>()
        val logs = ArrayList<String>()
        val responder = LocHintResponder(
            node = h.node,
            clock = object : org.relay.core.gossip.Clock { override fun nowMs() = h.now },
            scheduler = object : org.relay.core.gossip.Scheduler {
                override fun schedule(delayMs: Long, task: () -> Unit) { h.tasks += delayMs to task }
            },
            enabled = { enabled }, fix = { fix }, rssi = { rssi }, requestFix = { requests += h.now }, log = { logs += it },
        )
        val origin = NodeId.random()

        fun fixNow(accuracyM: Float = 20f, ageMs: Long = 0, lat: Double = 20.29601, lon: Double = 85.82433) =
            RelayFix(lat, lon, accuracyM, h.now - ageMs)

        /** Bring up link [id] and say HELLO as [peer]. */
        fun link(id: String, peer: ByteArray) {
            h.node.linkUp(id)
            h.node.onFrame(id, FrameCodec.encode(Frame(type = FrameTypes.HELLO, ttl = 1, timestampMs = h.now,
                senderId = peer, payload = HelloCodec.encode(Hello(peer, 0, 0xFF, 1)))))
        }

        fun need(locAcc: Int = 0, origin: ByteArray = this.origin) = sampleNeed(origin).copy(locAcc = locAcc, latE5 = 0, lonE5 = 0)

        /** Deliver [n] on [link] through the node and give the responder the callback, as the app does. */
        fun receive(link: String, n: org.relay.core.payload.Need) {
            h.needs.clear()
            h.node.onFrame(link, needFrameBytes(n, ttl = 7, sender = n.originNode, ts = h.now))
            h.needs.forEach { responder.onNeed(it.first, link) }
        }

        fun hints(): List<LocHint> = h.node.storedLocHints().map { it.first }
    }

    @Test fun emitsOneHintForANoFixNeedHeardDirectlyFromItsOrigin() {
        val r = Rig(); r.fix = r.fixNow(accuracyM = 30f)
        r.link("L1", r.origin)
        val need = r.need()
        r.receive("L1", need)
        val hint = r.hints().single()
        assertArrayEquals(need.msgId, hint.refMsgId); assertArrayEquals(r.h.id, hint.relayNode)
        assertEquals(3, hint.acc); assertEquals(-64, hint.rssiDbm)
        assertEquals(Geo.toE5(20.29601), hint.latE5); assertEquals(Geo.toE5(85.82433), hint.lonE5)
        assertEquals(r.h.now / 1000, hint.observedAt)
        assertTrue(r.requests.isEmpty())
        // hearing the same NEED again (resync) neither re-delivers nor re-hints
        r.receive("L1", need)
        assertEquals(1, r.hints().size)
    }

    @Test fun nothingWhenSwitchedOff() {
        val r = Rig(enabled = false); r.fix = r.fixNow()
        r.link("L1", r.origin); r.receive("L1", r.need()); r.h.runTasks()
        assertTrue(r.hints().isEmpty()); assertTrue(r.requests.isEmpty())
    }

    @Test fun switchingOffWhileWaitingForAFixCancelsTheHint() {
        val r = Rig(); r.link("L1", r.origin); r.receive("L1", r.need())
        assertEquals(1, r.requests.size)
        r.enabled = false; r.fix = r.fixNow()
        r.h.runTasks()
        assertTrue(r.hints().isEmpty())
    }

    @Test fun nothingForANeedThatHasAFix() {
        val r = Rig(); r.fix = r.fixNow()
        r.link("L1", r.origin)
        for (acc in 1..5) r.receive("L1", r.need(locAcc = acc))
        assertTrue(r.hints().isEmpty())
    }

    @Test fun nothingUnlessTheLinkPeerIsTheOrigin() {
        val r = Rig(); r.fix = r.fixNow()
        val other = NodeId.random()
        r.link("L1", other)                 // the NEED names r.origin but came from another phone
        r.receive("L1", r.need())
        assertTrue("relayed NEED", r.hints().isEmpty())
        r.link("L2", NodeId.random())       // frame.sender_id == origin but the link's HELLO is someone else
        r.receive("L2", r.need())
        assertTrue(r.hints().isEmpty())
        r.h.node.linkUp("L3")               // link that never said HELLO
        r.receive("L3", r.need())
        assertTrue(r.hints().isEmpty())
        r.receive("nowhere", r.need())      // unknown link
        assertTrue(r.hints().isEmpty())
        r.h.needs.clear()
        r.responder.onNeed(r.need(), null)  // locally originated
        assertTrue(r.hints().isEmpty())
    }

    @Test fun nothingWithAnImpreciseOrOldFix() {
        val r = Rig(); r.link("L1", r.origin)
        r.fix = r.fixNow(accuracyM = 100.5f)
        r.receive("L1", r.need())
        assertTrue(r.hints().isEmpty())
        // a 100 m fix is the worst allowed
        val r2 = Rig(); r2.link("L1", r2.origin); r2.fix = r2.fixNow(accuracyM = 100f)
        r2.receive("L1", r2.need()); assertEquals(4, r2.hints().single().acc)
        // older than two minutes is not where the phone is now
        val r3 = Rig(); r3.link("L1", r3.origin); r3.fix = r3.fixNow(ageMs = 120_001)
        r3.receive("L1", r3.need()); r3.h.runTasks(); assertTrue(r3.hints().isEmpty())
        val r4 = Rig(); r4.link("L1", r4.origin); r4.fix = r4.fixNow(ageMs = 119_000)
        r4.receive("L1", r4.need()); assertEquals(1, r4.hints().size)
    }

    @Test fun waitsForAFixThenEmitsOrGivesUp() {
        val r = Rig(); r.link("L1", r.origin)
        val need = r.need()
        r.receive("L1", need)
        assertTrue(r.hints().isEmpty()); assertEquals(1, r.requests.size)      // asked the platform for a fix, once
        r.h.now += 4_000
        r.fix = r.fixNow(accuracyM = 15f)                                    // GPS came up
        r.h.runTasks()
        val hint = r.hints().single()
        assertEquals(2, hint.acc)
        assertEquals("observed_at is when the NEED arrived", T0 / 1000, hint.observedAt)
        assertEquals(1, r.requests.size)

        val g = Rig(); g.link("L1", g.origin); g.receive("L1", g.need())
        g.h.now += 40_000; g.h.runTasks()                                     // never got a fix
        assertTrue(g.hints().isEmpty()); assertTrue(g.logs.any { "skipped" in it })
    }

    @Test fun unknownRssiIsMinus128AndExtremesAreClamped() {
        val r = Rig(rssi = null); r.fix = r.fixNow(); r.link("L1", r.origin)
        r.receive("L1", r.need()); assertEquals(LocHintCodec.RSSI_UNKNOWN, r.hints().single().rssiDbm)
        val r2 = Rig(rssi = -300); r2.fix = r2.fixNow(); r2.link("L1", r2.origin)
        r2.receive("L1", r2.need()); assertEquals(-127, r2.hints().single().rssiDbm)
    }

    @Test fun badFixCoordinatesNeverProduceAHint() {
        val r = Rig(); r.link("L1", r.origin); r.fix = r.fixNow(lat = Double.NaN)
        r.receive("L1", r.need()); r.h.runTasks()
        assertTrue(r.hints().isEmpty())
        val r2 = Rig(); r2.link("L1", r2.origin); r2.fix = r2.fixNow(lat = 123.0)
        r2.receive("L1", r2.need()); r2.h.runTasks(); assertTrue(r2.hints().isEmpty())
    }
}
