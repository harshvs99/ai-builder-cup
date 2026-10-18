package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.Clock
import org.relay.core.gossip.GossipListener
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.InMemoryPacketStore
import org.relay.core.gossip.LinkSender
import org.relay.core.gossip.PushPlanner
import org.relay.core.gossip.Scheduler
import org.relay.core.gossip.StoredPacket
import org.relay.core.ids.Hex
import org.relay.core.ids.NodeId
import org.relay.core.payload.AckCodec
import org.relay.core.payload.Inventory
import org.relay.core.payload.InventoryCodec
import org.relay.core.payload.Need
import org.relay.core.payload.NeedCodec
import java.util.Random

class PushPlannerTest {
    private val peer = NodeId.random()
    private val other = NodeId.random()

    private fun needPkt(createdAtSec: Long): StoredPacket {
        val n = sampleNeed(NodeId.random(), createdAtSec = createdAtSec)
        return StoredPacket(n.msgIdHex, FrameTypes.NEED, needFrameBytes(n, ts = T0), n.createdAt * 1000L)
    }

    private fun ackPkt(target: ByteArray, firstStoredMs: Long): StoredPacket {
        val n = sampleNeed(target)
        val a = sampleAck(n)
        val f = Frame(type = FrameTypes.ACK, ttl = 7, timestampMs = T0, senderId = NodeId.random(), recipientId = target, payload = AckCodec.encode(a))
        return StoredPacket(a.ackIdHex, FrameTypes.ACK, FrameCodec.encode(f), firstStoredMs)
    }

    @Test fun acksFirstThenNeedsNewestFirst() {
        val oldNeed = needPkt(1_000); val newNeed = needPkt(2_000)
        val ackOther = ackPkt(other, T0); val ackPeer = ackPkt(peer, T0 - 10_000)
        val order = PushPlanner.order(listOf(oldNeed, ackOther, newNeed, ackPeer), peer)
        assertEquals(listOf(ackPeer.key, ackOther.key, newNeed.key, oldNeed.key), order.map { it.key })
    }

    @Test fun newestAckFirstWithinTheAckGroup() {
        val a1 = ackPkt(other, T0); val a2 = ackPkt(other, T0 + 5_000)
        assertEquals(listOf(a2.key, a1.key), PushPlanner.order(listOf(a1, a2), null).map { it.key })
    }

    @Test fun batchIsCutAtTheByteBudgetAndKeepsOrder() {
        val pkts = (1..10).map { needPkt(1_000L + it) }
        val size = pkts[0].raw.size
        val plan = PushPlanner.plan(pkts, peer, budgetBytes = size * 3 + 1)
        assertEquals(3, plan.batch.size)
        assertEquals(7, plan.rest.size)
        assertEquals("newest first", pkts.last().key, plan.batch.first().key)
        assertEquals(pkts.map { it.key }.toSet(), (plan.batch + plan.rest).map { it.key }.toSet())
    }

    @Test fun alwaysPushesAtLeastOneFrame() {
        val plan = PushPlanner.plan(listOf(needPkt(1), needPkt(2)), peer, budgetBytes = 1)
        assertEquals(1, plan.batch.size); assertEquals(1, plan.rest.size)
    }

    @Test fun defaultBudgetIsAboutTwoMtus() {
        assertEquals(1028, PushPlanner.DEFAULT_BUDGET_BYTES)
        val plan = PushPlanner.plan((1..30).map { needPkt(1_000L + it) }, peer)
        val bytes = plan.batch.sumOf { it.raw.size }
        assertTrue("batch of $bytes B within budget", bytes <= PushPlanner.DEFAULT_BUDGET_BYTES)
        assertTrue(plan.batch.size >= 5)
    }

    // ------------------------------------------------------------ through GossipNode

    private class Rig(budget: Int) {
        var now = T0
        val sent = ArrayList<Pair<String, ByteArray>>()
        val tasks = ArrayList<Pair<Long, () -> Unit>>()
        val node = GossipNode(
            NodeId.random(),
            object : LinkSender { override fun send(linkId: String, frame: ByteArray) { sent += linkId to frame } },
            object : Clock { override fun nowMs() = now },
            object : Scheduler { override fun schedule(delayMs: Long, task: () -> Unit) { tasks += delayMs to task } },
            InMemoryPacketStore(), Random(3), object : GossipListener {},
            pushBudgetBytes = budget, pushContinueMs = 2_000,
        )
        fun pushedNeeds(link: String): List<Need> = sent.filter { it.first == link }
            .mapNotNull { FrameCodec.decode(it.second) }.filter { it.type == FrameTypes.NEED }.mapNotNull { NeedCodec.decode(it.payload) }
        fun runTasks() { val t = tasks.toList(); tasks.clear(); t.forEach { it.second() } }
        fun peerInventory(link: String, ids: List<ByteArray>) {
            val inv = Frame(type = FrameTypes.INVENTORY, ttl = 1, timestampMs = now, senderId = NodeId.random(),
                payload = InventoryCodec.encode(Inventory(0, 1, ids)))
            node.onFrame(link, FrameCodec.encode(inv))
        }
    }

    @Test fun cappedDiffPushesNewestFirstThenContinuesWithoutWaitingForResync() {
        val rig = Rig(budget = 2 * needFrameBytes(sampleNeed(NodeId.random())).size + 1)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        val needs = (1..6).map { sampleNeed(NodeId.random(), createdAtSec = T0 / 1000 + it) }
        for (n in needs) rig.node.onFrame("L1", needFrameBytes(n))
        rig.runTasks(); rig.sent.clear()

        rig.peerInventory("L2", emptyList())
        val first = rig.pushedNeeds("L2")
        assertEquals("first burst is capped at two frames", 2, first.size)
        assertEquals("newest first", needs.last().msgIdHex, first.first().msgIdHex)
        assertEquals("a continuation is scheduled at 2 s", 2_000L, rig.tasks.single().first)

        var guard = 0
        while (rig.tasks.isNotEmpty() && guard++ < 20) rig.runTasks()
        val all = rig.pushedNeeds("L2")
        assertEquals("every frame arrives exactly once", needs.map { it.msgIdHex }.toSet(), all.map { it.msgIdHex }.toSet())
        assertEquals(needs.size, all.size)
        assertEquals("order is newest first overall", needs.reversed().map { it.msgIdHex }, all.map { it.msgIdHex })
    }

    @Test fun continuationStopsWhenTheLinkGoesDown() {
        val rig = Rig(budget = 1)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        repeat(4) { rig.node.onFrame("L1", needFrameBytes(sampleNeed(NodeId.random(), createdAtSec = T0 / 1000 + it))) }
        rig.runTasks(); rig.sent.clear()
        rig.peerInventory("L2", emptyList())
        val before = rig.pushedNeeds("L2").size
        rig.node.linkDown("L2")
        rig.runTasks()
        assertEquals(before, rig.pushedNeeds("L2").size)
    }

    @Test fun unlimitedBudgetKeepsTheOldPushEverythingBehaviour() {
        val rig = Rig(budget = Int.MAX_VALUE)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        repeat(30) { rig.node.onFrame("L1", needFrameBytes(sampleNeed(NodeId.random(), createdAtSec = T0 / 1000 + it))) }
        rig.runTasks(); rig.sent.clear()
        rig.peerInventory("L2", emptyList())
        assertEquals(30, rig.pushedNeeds("L2").size)
        assertTrue(rig.tasks.isEmpty())
    }

    @Test fun ackForPeerGoesBeforeNeedsInTheFirstBurst() {
        val rig = Rig(budget = 1)
        rig.node.linkUp("L1"); rig.node.linkUp("L2")
        // Learn the peer's id through its HELLO so the planner can recognise ACKs addressed to it.
        val peerId = NodeId.random()
        val hello = Frame(type = FrameTypes.HELLO, ttl = 1, timestampMs = T0, senderId = peerId,
            payload = org.relay.core.payload.HelloCodec.encode(org.relay.core.payload.Hello(peerId, 0, 0xFF, 1)))
        rig.node.onFrame("L2", FrameCodec.encode(hello))
        repeat(3) { rig.node.onFrame("L1", needFrameBytes(sampleNeed(NodeId.random(), createdAtSec = T0 / 1000 + it))) }
        val n = sampleNeed(peerId); val ack = sampleAck(n)
        val af = Frame(type = FrameTypes.ACK, ttl = 7, timestampMs = T0, senderId = NodeId.random(), recipientId = peerId, payload = AckCodec.encode(ack))
        rig.node.onFrame("L1", FrameCodec.encode(af))
        rig.runTasks(); rig.sent.clear()
        rig.peerInventory("L2", emptyList())
        val firstType = rig.sent.filter { it.first == "L2" }.mapNotNull { FrameCodec.decode(it.second) }.first { it.type != FrameTypes.HELLO && it.type != FrameTypes.INVENTORY }.type
        assertEquals(FrameTypes.ACK, firstType)
        assertEquals(Hex.encode(ack.ackId).length, 16)
    }
}
