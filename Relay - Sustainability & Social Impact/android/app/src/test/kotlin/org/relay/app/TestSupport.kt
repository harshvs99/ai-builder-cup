package org.relay.app

import org.relay.core.gossip.GossipListener
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.InMemoryPacketStore
import org.relay.core.gossip.LinkSender
import org.relay.core.gossip.PacketStore
import org.relay.core.gossip.Scheduler
import org.relay.core.gossip.WallClock
import org.relay.core.payload.Ack
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.LocHint
import org.relay.core.payload.Need
import java.util.Random

object TestSupport {
    fun id(seed: Int): ByteArray = ByteArray(8) { (seed + it).toByte() }

    fun need(msgSeed: Int, origin: ByteArray = id(0x70), item: String = "insulin") = Need(
        msgId = id(msgSeed), originNode = origin, createdAt = System.currentTimeMillis() / 1000,
        category = Category.MEDICAL, urgency = 3, people = 1, vulnFlags = 1, latE5 = 2029612, lonE5 = 8582451,
        locAcc = 2, lang = Lang.EN, item = item, landmark = "school", summary = "needs $item",
    )

    fun ack(ackSeed: Int, ref: ByteArray, target: ByteArray, ticket: Long = 7, status: AckStatus = AckStatus.RECEIVED, text: String = "received") =
        Ack(id(ackSeed), ref, target, ticket, status, 0xFFFF, text)

    /** A LOC_HINT for the NEED [refSeed] from relay phone [relay]; originate it on a node whose id is [relay]. */
    fun hint(refSeed: Int, relay: ByteArray, acc: Int = 3, rssi: Int = -70) = LocHint(
        refMsgId = id(refSeed), relayNode = relay, latE5 = 2029612, lonE5 = 8582451, acc = acc,
        rssiDbm = rssi, observedAt = System.currentTimeMillis() / 1000,
    )

    class Recorder : GossipListener {
        val acksSeen = ArrayList<Ack>()
        val acksForMe = ArrayList<Ack>()
        val needs = ArrayList<Need>()
        override fun onNeed(need: Need, frame: org.relay.core.frame.Frame, fromLink: String?) { needs += need }
        override fun onAckSeen(ack: Ack, frame: org.relay.core.frame.Frame) { acksSeen += ack }
        override fun onAckForMe(ack: Ack, frame: org.relay.core.frame.Frame) { acksForMe += ack }
    }

    val noSender = object : LinkSender { override fun send(linkId: String, frame: ByteArray) {} }
    val noScheduler = object : Scheduler { override fun schedule(delayMs: Long, task: () -> Unit) {} }

    fun node(
        nodeId: ByteArray = id(0x10),
        store: PacketStore = InMemoryPacketStore(),
        listener: GossipListener = Recorder(),
    ) = GossipNode(nodeId, noSender, WallClock, noScheduler, store, Random(1), listener)
}
