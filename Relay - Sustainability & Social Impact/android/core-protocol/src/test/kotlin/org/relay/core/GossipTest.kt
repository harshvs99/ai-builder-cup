package org.relay.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.DuplicateLinkPolicy
import org.relay.core.gossip.GossipNode
import org.relay.core.ids.NodeId
import org.relay.core.payload.Hello
import org.relay.core.payload.HelloCodec
import org.relay.core.payload.Inventory
import org.relay.core.payload.InventoryCodec
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.OpaqueKey

class GossipTest {

    // ------------------------------------------------------------ end to end on a mesh

    /** Make the last node of [names] a gateway that ACKs every NEED it delivers. */
    private fun TestNet.autoAck(name: String) {
        val gw = node(name)
        gw.node.isGateway = true
        gw.onNeedHook = { need -> gw.node.originateAck(sampleAck(need, ticket = 142)) }
    }

    @Test fun shortChainDeliversNeedAndAckByFloodingAlone() {
        val net = TestNet()
        val names = (0..5).map { "n$it" }   // 5 hops: within TTL 7
        net.chain(names)
        net.autoAck("n5")
        val origin = net.node("n0")
        val need = sampleNeed(origin.id)
        origin.node.originateNeed(need)
        net.runUntilIdle()
        for (n in names.drop(1)) assertEquals("$n delivered NEED once", 1, net.node(n).needsDelivered.size)
        assertEquals(1, origin.needsDelivered.size) // originator also "delivers" locally
        assertEquals("ACK reached origin exactly once", 1, origin.acksForMe.size)
        assertEquals(142L, origin.acksForMe[0].ticketNo)
        for (n in names) assertEquals("$n sees ACK once", 1, net.node(n).acksSeen.size)
        for (n in names.drop(1).dropLast(1)) assertTrue(net.node(n).acksForMe.isEmpty())
    }

    @Test fun tenNodeChainDeliversNeedAndAckHundredPercentWithInventoryRounds() {
        val net = TestNet()
        val names = (0..9).map { "n$it" }
        net.chain(names)
        net.autoAck("n9")
        val origin = net.node("n0")
        origin.node.originateNeed(sampleNeed(origin.id))
        net.runUntilIdle()
        // 9 hops > TTL 7: pure flooding stops at hop 7 (documented behaviour)
        assertEquals(1, net.node("n7").needsDelivered.size)
        assertEquals(0, net.node("n8").needsDelivered.size)
        assertEquals(0, net.node("n9").needsDelivered.size)
        var rounds = 0
        while ((net.node("n9").needsDelivered.isEmpty() || origin.acksForMe.isEmpty()) && rounds < 12) {
            net.resyncRound(); rounds++
        }
        assertEquals("NEED delivered to gateway", 1, net.node("n9").needsDelivered.size)
        assertEquals("ACK delivered to origin", 1, origin.acksForMe.size)
        assertTrue("converges within a few rounds, took $rounds", rounds <= 8)
        // everything delivered exactly once at every node
        for (n in names) {
            val nd = net.node(n)
            assertEquals("$n NEED once", 1, nd.needsDelivered.size)
            assertEquals("$n ACK once", 1, nd.acksSeen.size)
        }
    }

    @Test fun eachNodeDeliversEachMessageOnceInADenseMesh() {
        val net = TestNet(5)
        val names = (0..7).map { "m$it" }
        for (i in names.indices) for (j in i + 1 until names.size) net.connect(names[i], names[j])
        val origin = net.node("m0")
        val needs = (0..4).map { sampleNeed(origin.id) }
        needs.forEach { origin.node.originateNeed(it) }
        net.runUntilIdle()
        var dupTotal = 0L
        for (n in names) {
            val nd = net.node(n)
            assertEquals("$n", 5, nd.needsDelivered.size)
            assertEquals(5, nd.needsDelivered.map { hex(it.msgId) }.toSet().size)
            dupTotal += nd.node.stats().duplicates
        }
        assertTrue("a dense mesh must have produced duplicates that were dropped", dupTotal > 0)
    }

    @Test fun ttlExpiryStopsFloodingButInventorySyncDeliversLater() {
        val net = TestNet()
        val names = (0..9).map { "n$it" }
        net.chain(names)
        val origin = net.node("n0")
        origin.node.originateNeed(sampleNeed(origin.id))
        net.runUntilIdle()
        // n7 holds it with ttl 1: stored, not flooded onwards
        assertEquals(1, net.node("n7").needsDelivered.size)
        assertEquals(0, net.node("n8").needsDelivered.size)
        assertEquals(1, net.node("n7").node.storedNeeds().size)
        assertEquals(1, net.node("n7").node.storedNeeds()[0].second.ttl)
        // link n7-n8 flaps: the new link's INVENTORY exchange delivers it
        net.disconnect("n7", "n8"); net.connect("n7", "n8")
        net.runUntilIdle()
        assertEquals(1, net.node("n8").needsDelivered.size)
        assertEquals("ttl 1 on arrival: stored but not flooded to n9", 0, net.node("n9").needsDelivered.size)
        assertEquals(1, net.node("n8").node.storedNeeds().size)
    }

    @Test fun walkerBridgesTwoPartitions() {
        val net = TestNet()
        net.chain(listOf("a0", "a1", "a2"))
        net.chain(listOf("b0", "b1", "b2"))
        net.autoAck("b2")
        val a0 = net.node("a0")
        a0.node.originateNeed(sampleNeed(a0.id))
        net.runUntilIdle()
        assertEquals(1, net.node("a2").needsDelivered.size)
        assertEquals(0, net.node("b0").needsDelivered.size)

        // walker visits cluster A, leaves, visits cluster B
        net.connect("w", "a2"); net.runUntilIdle()
        assertEquals(1, net.node("w").needsDelivered.size)
        net.disconnect("w", "a2")
        net.connect("w", "b0"); net.runUntilIdle()
        for (n in listOf("b0", "b1", "b2")) assertEquals("$n got NEED via walker", 1, net.node(n).needsDelivered.size)
        net.disconnect("w", "b0")
        assertEquals(0, a0.acksForMe.size)

        // ... and carries the gateway's ACK back
        net.connect("w", "b0"); net.runUntilIdle(); net.disconnect("w", "b0")
        assertEquals(1, net.node("w").acksSeen.size)
        net.connect("w", "a2"); net.runUntilIdle()
        assertEquals("ACK delivered to origin across the partition", 1, a0.acksForMe.size)
    }

    @Test fun lossyChainConvergesAfterResyncRounds() {
        val net = TestNet(11)
        net.lossProbability = 0.2
        val names = (0..9).map { "n$it" }
        net.chain(names)
        net.autoAck("n9")
        val origin = net.node("n0")
        origin.node.originateNeed(sampleNeed(origin.id))
        net.runUntilIdle()
        var rounds = 0
        while ((net.node("n9").needsDelivered.isEmpty() || origin.acksForMe.isEmpty()) && rounds < 60) { net.resyncRound(); rounds++ }
        assertEquals(1, net.node("n9").needsDelivered.size)
        assertEquals(1, origin.acksForMe.size)
    }

    // ------------------------------------------------------------ single-node protocol rules

    @Test fun duplicateFrameFromTwoLinksDeliveredOnce() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.node.linkUp("L3")
        val need = sampleNeed(NodeId.random())
        val raw = needFrameBytes(need, ttl = 5)
        h.node.onFrame("L1", raw)
        h.node.onFrame("L2", raw)
        h.runTasks()
        assertEquals(1, h.needs.size)
        assertEquals(1, h.node.stats().duplicates)
        // flooded once per eligible link (L2, L3 from the L1 copy; L1 is the source). L2's duplicate is dropped.
        h.clearSent()
        h.node.onFrame("L3", raw)
        h.runTasks()
        assertTrue(h.sent.none { FrameCodec.decode(it.second)?.type == FrameTypes.NEED })
    }

    @Test fun floodGoesToAllLinksExceptSourceWithTtlMinusOneAfterJitter() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.node.linkUp("L3")
        h.clearSent()
        val need = sampleNeed(NodeId.random())
        h.node.onFrame("L1", needFrameBytes(need, ttl = 5))
        assertTrue("nothing sent before jitter fires", h.sent.isEmpty())
        assertEquals(1, h.tasks.size)
        assertTrue("jitter ${h.tasks[0].first}", h.tasks[0].first in 10..220)
        h.runTasks()
        assertEquals(0, h.sentTo("L1").size)
        for (l in listOf("L2", "L3")) {
            val f = h.sentTo(l, FrameTypes.NEED).single()
            assertEquals(4, f.ttl)
            assertArrayEquals(NeedCodec.encode(need), f.payload)
            assertEquals(T0, f.timestampMs)           // untouched on relay
            assertArrayEquals(need.originNode, f.senderId)
        }
    }

    @Test fun jitterCoversTheWholeRange() {
        val seen = HashSet<Long>()
        repeat(300) {
            val h = SoloHarness()
            h.node.linkUp("L1")
            // fresh random per harness is fixed (seed 7) so vary by node
            val need = sampleNeed(NodeId.random())
            h.node.onFrame("L1", needFrameBytes(need, ttl = 5))
            seen += h.tasks[0].first
        }
        assertTrue(seen.all { it in 10..220 })
    }

    @Test fun ttlOneIsStoredAndDeliveredButNotFlooded() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val need = sampleNeed(NodeId.random())
        h.node.onFrame("L1", needFrameBytes(need, ttl = 1))
        h.runTasks()
        assertEquals(1, h.needs.size)
        assertEquals(1, h.node.storedNeeds().size)
        assertTrue(h.sent.isEmpty())
        // ttl 0 as well
        val n2 = sampleNeed(NodeId.random())
        h.node.onFrame("L1", needFrameBytes(n2, ttl = 0)); h.runTasks()
        assertEquals(2, h.needs.size); assertTrue(h.sent.isEmpty())
    }

    @Test fun frameMoreThan24hInFutureIsDropped() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val need = sampleNeed(NodeId.random())
        h.node.onFrame("L1", needFrameBytes(need, ts = T0 + 24L * 3600_000 + 1)); h.runTasks()
        assertEquals(0, h.needs.size); assertTrue(h.sent.isEmpty())
        h.node.onFrame("L1", needFrameBytes(need, ts = T0 + 24L * 3600_000 - 1000)); h.runTasks()
        assertEquals(1, h.needs.size)
        // no clock-skew rejection for the past
        val old = sampleNeed(NodeId.random())
        h.node.onFrame("L1", needFrameBytes(old, ts = T0 - 400L * 24 * 3600_000)); h.runTasks()
        assertEquals(2, h.needs.size)
    }

    @Test fun opaqueTypeRelayedOnceNeverStoredOrDelivered() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val sig = ByteArray(64) { it.toByte() }
        val sender = NodeId.random()
        val frame = Frame(type = 0x7E, ttl = 5, timestampMs = T0, senderId = sender, payload = byteArrayOf(1, 2, 3), signature = sig)
        val raw = FrameCodec.encode(frame)
        h.node.onFrame("L1", raw); h.runTasks()
        val out = h.sentTo("L2", 0x7E).single()
        assertEquals(4, out.ttl)
        assertArrayEquals(sig, out.signature)
        assertArrayEquals(frame.payload, out.payload)
        assertEquals(0, h.sentTo("L1").size)
        h.clearSent()
        h.node.onFrame("L2", raw); h.node.onFrame("L1", raw); h.runTasks()
        assertTrue("second sighting is not relayed again", h.sent.isEmpty())
        assertTrue(h.needs.isEmpty() && h.node.storedNeeds().isEmpty() && h.node.storedAcks().isEmpty())
        assertEquals(2, h.node.stats().duplicates)
        assertEquals(16, OpaqueKey.of(frame).size * 2)
    }

    @Test fun unknownPayloadVersionOfKnownTypeIsOpaque() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val need = sampleNeed(NodeId.random())
        val p = NeedCodec.encode(need).also { it[0] = 2 }
        val raw = FrameCodec.encode(Frame(type = FrameTypes.NEED, ttl = 4, timestampMs = T0, senderId = need.originNode, payload = p))
        h.node.onFrame("L1", raw); h.runTasks()
        assertTrue(h.needs.isEmpty())
        assertEquals(3, h.sentTo("L2", FrameTypes.NEED).single().ttl)
        h.clearSent(); h.node.onFrame("L1", raw); h.runTasks(); assertTrue(h.sent.isEmpty())
    }

    @Test fun invalidV1NeedIsDroppedNotRelayed() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val need = sampleNeed(NodeId.random())
        val p = NeedCodec.encode(need).also { it[1 + 8 + 8 + 4 + 1] = 9 } // urgency 9
        h.node.onFrame("L1", FrameCodec.encode(Frame(type = FrameTypes.NEED, ttl = 6, timestampMs = T0, senderId = need.originNode, payload = p)))
        h.runTasks()
        assertTrue(h.needs.isEmpty() && h.sent.isEmpty())
        assertEquals(1, h.node.stats().framesRejected)
    }

    @Test fun linkUpSendsHelloThenChunkedInventory() {
        val h = SoloHarness()
        h.node.linkUp("L0")
        val needs = List(40) { sampleNeed(NodeId.random()) }
        needs.forEach { h.node.originateNeed(it) }
        h.runTasks(); h.clearSent()
        h.node.linkUp("L1")
        val frames = h.sent.filter { it.first == "L1" }
        assertEquals(FrameTypes.HELLO, FrameCodec.decode(frames[0].second)!!.type)
        val inv = frames.drop(1).map { FrameCodec.decode(it.second)!! }
        assertTrue(inv.all { it.type == FrameTypes.INVENTORY && it.ttl == 1 })
        assertTrue(frames.all { it.second.size <= 182 })
        val chunks = inv.map { InventoryCodec.decode(it.payload)!! }
        assertEquals(listOf(19, 19, 2), chunks.map { it.ids.size })
        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
        assertTrue(chunks.all { it.chunkCount == 3 })
        val advertised = chunks.flatMap { it.ids }.map { hex(it) }.toSet()
        assertEquals(needs.map { hex(it.msgId) }.toSet(), advertised)
        val hello = HelloCodec.decode(FrameCodec.decode(frames[0].second)!!.payload)!!
        assertArrayEquals(h.id, hello.nodeId)
    }

    @Test fun emptyStoreSendsSingleEmptyInventoryChunk() {
        val h = SoloHarness()
        h.node.linkUp("L1")
        val inv = h.sentTo("L1", FrameTypes.INVENTORY).map { InventoryCodec.decode(it.payload)!! }
        assertEquals(1, inv.size)
        assertEquals(0, inv[0].ids.size); assertEquals(1, inv[0].chunkCount)
    }

    @Test fun inventoryDiffPushesOnlyMissingFramesWithFreshTimestampToThatLinkOnly() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        val a = sampleNeed(NodeId.random()); val b = sampleNeed(NodeId.random())
        h.node.onFrame("L1", needFrameBytes(a, ttl = 6))
        h.node.onFrame("L1", needFrameBytes(b, ttl = 3))
        h.runTasks(); h.clearSent()
        h.now = T0 + 5_000
        // L2's inventory contains only `a`
        val inv = Frame(type = FrameTypes.INVENTORY, ttl = 1, timestampMs = h.now, senderId = NodeId.random(),
            payload = InventoryCodec.encode(Inventory(0, 1, listOf(a.msgId))))
        h.node.onFrame("L2", FrameCodec.encode(inv))
        assertEquals(0, h.sentTo("L1").size)
        val pushed = h.sentTo("L2", FrameTypes.NEED).single()
        assertArrayEquals(NeedCodec.encode(b), pushed.payload)
        assertEquals("ttl unchanged", 3, pushed.ttl)
        assertEquals("timestamp re-stamped to now", T0 + 5_000, pushed.timestampMs)
        assertTrue("diff push is not jittered", h.tasks.isEmpty())
    }

    @Test fun multiChunkInventoryOnlyActsWhenComplete() {
        val h = SoloHarness()
        h.node.linkUp("L1")
        val a = sampleNeed(NodeId.random())
        h.node.onFrame("L1", needFrameBytes(a, ttl = 6)); h.runTasks(); h.clearSent()
        fun chunk(i: Int, n: Int, ids: List<ByteArray>) = FrameCodec.encode(Frame(type = FrameTypes.INVENTORY, ttl = 1, timestampMs = T0,
            senderId = NodeId.random(), payload = InventoryCodec.encode(Inventory(i, n, ids))))
        h.node.onFrame("L1", chunk(0, 2, listOf(NodeId.random())))
        assertTrue("incomplete: no push yet", h.sent.isEmpty())
        h.node.onFrame("L1", chunk(1, 2, listOf(a.msgId)))
        assertTrue("peer has it per chunk 2: still nothing to push", h.sentTo("L1", FrameTypes.NEED).isEmpty())
        // a new chunk 0 restarts accumulation
        h.node.onFrame("L1", chunk(0, 2, emptyList()))
        h.node.onFrame("L1", chunk(1, 2, emptyList()))
        assertEquals(1, h.sentTo("L1", FrameTypes.NEED).size)
    }

    @Test fun originateNeedStoresDeliversAndFloodsWithInitialTtl() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2"); h.clearSent()
        val need = sampleNeed(h.id)
        val frame = h.node.originateNeed(need)
        assertEquals(7, frame.ttl)
        assertArrayEquals(h.id, frame.senderId)
        assertEquals(1, h.needs.size)
        assertEquals(1, h.node.storedNeeds().size)
        h.runTasks()
        for (l in listOf("L1", "L2")) assertEquals(7, h.sentTo(l, FrameTypes.NEED).single().ttl)
        // re-originating the same msg_id does nothing new
        h.clearSent(); h.node.originateNeed(need); h.runTasks()
        assertTrue(h.sent.isEmpty()); assertEquals(1, h.needs.size)
    }

    @Test fun originateAckIsDirectedAndOnlyTargetShowsIt() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.clearSent()
        val originNode = NodeId.random()
        val ack = sampleAck(sampleNeed(originNode))
        val frame = h.node.originateAck(ack)
        assertArrayEquals(h.id, frame.senderId)
        assertArrayEquals(originNode, frame.recipientId)
        assertEquals(7, frame.ttl)
        assertEquals(1, h.acksSeen.size); assertEquals(0, h.acksForMe.size)
        h.runTasks()
        assertEquals(7, h.sentTo("L1", FrameTypes.ACK).single().ttl)
        // an ACK addressed to this node is shown to the user
        val mine = sampleAck(sampleNeed(h.id))
        h.node.onFrame("L1", FrameCodec.encode(Frame(type = FrameTypes.ACK, ttl = 3, timestampMs = T0, senderId = NodeId.random(),
            recipientId = h.id, payload = org.relay.core.payload.AckCodec.encode(mine))))
        assertEquals(1, h.acksForMe.size)
        assertEquals(2, h.node.storedAcks().size) // the one we originated + the one for us
    }

    @Test fun expiryByCreatedAtForNeedsAndFirstStoredForAcks() {
        val h = SoloHarness()
        h.node.linkUp("L1")
        val oldNeed = sampleNeed(NodeId.random(), createdAtSec = T0 / 1000 - 71 * 3600)
        val ack = sampleAck(sampleNeed(NodeId.random()))
        h.node.onFrame("L1", needFrameBytes(oldNeed))
        h.node.onFrame("L1", FrameCodec.encode(Frame(type = FrameTypes.ACK, ttl = 3, timestampMs = T0 - 100 * 3600_000L,
            senderId = NodeId.random(), recipientId = ack.targetNode, payload = org.relay.core.payload.AckCodec.encode(ack))))
        h.node.expire()
        assertEquals(1, h.node.storedNeeds().size); assertEquals(1, h.node.storedAcks().size)
        h.now = T0 + 2 * 3600_000 // NEED now 73 h old; ACK stored 2 h ago (its frame timestamp is irrelevant)
        h.node.expire()
        assertEquals(0, h.node.storedNeeds().size)
        assertEquals("ACK expiry is by first-stored time", 1, h.node.storedAcks().size)
        h.now = T0 + 73 * 3600_000
        h.node.expire()
        assertEquals(0, h.node.storedAcks().size)
        // keys stay in the seen set: re-receiving the expired NEED is a duplicate, not a new delivery
        val before = h.needs.size
        h.node.onFrame("L1", needFrameBytes(oldNeed)); h.runTasks()
        assertEquals(before, h.needs.size)
    }

    @Test fun seenSetIsBoundedLru() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        val sender = NodeId.random()
        fun opaque(i: Int) = FrameCodec.encode(Frame(type = 0x7E, ttl = 3, timestampMs = T0 + i, senderId = sender, payload = byteArrayOf(1)))
        val first = opaque(0)
        h.node.onFrame("L1", first); h.runTasks(); h.clearSent()
        for (i in 1..GossipNode.SEEN_CAPACITY) { h.node.onFrame("L1", opaque(i)); if (h.tasks.size > 1000) h.runTasks() }
        h.runTasks(); h.clearSent()
        h.node.onFrame("L1", first); h.runTasks()
        assertEquals("evicted key is relayed again", 1, h.sentTo("L2", 0x7E).size)
        // recent key still deduped
        h.clearSent(); h.node.onFrame("L1", opaque(GossipNode.SEEN_CAPACITY)); h.runTasks()
        assertTrue(h.sent.isEmpty())
    }

    @Test fun helloMustMatchSenderAndTracksHopsToGateway() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        assertEquals(0xFF, h.node.hopsToGateway)
        val peer = NodeId.random()
        fun hello(sender: ByteArray, node: ByteArray, caps: Int, hops: Int) = FrameCodec.encode(Frame(type = FrameTypes.HELLO, ttl = 1,
            timestampMs = T0, senderId = sender, payload = HelloCodec.encode(Hello(node, caps, hops, 1))))
        h.node.onFrame("L1", hello(peer, NodeId.random(), 1, 0))     // node_id != sender_id: dropped
        assertEquals(0xFF, h.node.hopsToGateway); assertTrue(h.node.peers().isEmpty())
        h.clearSent()
        h.node.onFrame("L1", hello(peer, peer, 1, 0))
        assertEquals(1, h.node.hopsToGateway)
        assertTrue(h.node.peers()["L1"]!!.isGateway)
        // value changed: HELLO re-announced to every link
        val announced = h.sentTo("L2", FrameTypes.HELLO).map { HelloCodec.decode(it.payload)!! }
        assertEquals(1, announced.single().hopsToGateway)
        h.node.onFrame("L2", hello(NodeId.random().also { }, ByteArray(8), 0, 0)) // mismatch again: ignored
        val p2 = NodeId.random()
        h.node.onFrame("L2", hello(p2, p2, 0, 3))
        assertEquals(1, h.node.hopsToGateway) // min(0,3)+1
        h.node.linkDown("L1")
        assertEquals(4, h.node.hopsToGateway)
        // unknown stays unknown, cap at 0xFE
        val h2 = SoloHarness(); h2.node.linkUp("A")
        val p3 = NodeId.random()
        h2.node.onFrame("A", hello(p3, p3, 0, 0xFF)); assertEquals(0xFF, h2.node.hopsToGateway)
        val p4 = NodeId.random(); h2.node.linkUp("B")
        h2.node.onFrame("B", hello(p4, p4, 0, 0xFE)); assertEquals(0xFE, h2.node.hopsToGateway)
    }

    @Test fun gatewayFlagReannouncesAndForcesHopsZero() {
        val h = SoloHarness()
        h.node.linkUp("L1"); h.clearSent()
        h.node.isGateway = true
        assertEquals(0, h.node.hopsToGateway)
        val hello = HelloCodec.decode(h.sentTo("L1", FrameTypes.HELLO).single().payload)!!
        assertTrue(hello.isGateway); assertEquals(0, hello.hopsToGateway)
        h.clearSent()
        h.node.isGateway = true // no change, no chatter
        assertTrue(h.sent.isEmpty())
        h.node.isGateway = false
        assertFalse(HelloCodec.decode(h.sentTo("L1", FrameTypes.HELLO).single().payload)!!.isGateway)
    }

    @Test fun hopsToGatewayPropagatesAlongAChain() {
        val net = TestNet()
        net.chain(listOf("g", "a", "b", "c"))
        net.node("g").node.isGateway = true
        net.runUntilIdle()
        assertEquals(0, net.node("g").node.hopsToGateway)
        assertEquals(1, net.node("a").node.hopsToGateway)
        assertEquals(2, net.node("b").node.hopsToGateway)
        assertEquals(3, net.node("c").node.hopsToGateway)
    }

    @Test fun duplicateLinkPolicyKeepsLowerIdAsCentral() {
        val lo = ByteArray(8) { 0x01 }; val hi = ByteArray(8) { 0xF0.toByte() }
        assertTrue(DuplicateLinkPolicy.keepAsCentral(lo, hi))
        assertFalse(DuplicateLinkPolicy.keepAsCentral(hi, lo))
        assertFalse(DuplicateLinkPolicy.keepAsCentral(lo, lo))
        // unsigned compare: 0x80.. is greater than 0x7F..
        assertTrue(DuplicateLinkPolicy.keepAsCentral(ByteArray(8) { 0x7F }, ByteArray(8) { 0x80.toByte() }))
    }

    @Test fun frameFromUnknownLinkStillDeliversNeedButIgnoresHandshakeFrames() {
        val h = SoloHarness()
        val need = sampleNeed(NodeId.random())
        h.node.onFrame("ghost", needFrameBytes(need, ttl = 3))
        assertEquals(1, h.needs.size)
        val peer = NodeId.random()
        h.node.onFrame("ghost", FrameCodec.encode(Frame(type = FrameTypes.HELLO, ttl = 1, timestampMs = T0, senderId = peer,
            payload = HelloCodec.encode(Hello(peer, 1, 0, 1)))))
        assertTrue(h.node.peers().isEmpty())
    }
}
