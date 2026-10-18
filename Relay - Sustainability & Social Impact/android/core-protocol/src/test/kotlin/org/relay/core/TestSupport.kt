package org.relay.core

import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.Clock
import org.relay.core.gossip.GossipListener
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.LinkSender
import org.relay.core.gossip.Scheduler
import org.relay.core.ids.Hex
import org.relay.core.ids.NodeId
import org.relay.core.payload.Ack
import org.relay.core.payload.Announcement
import org.relay.core.payload.Broadcast
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.LocHint
import org.relay.core.payload.Need
import java.util.PriorityQueue
import java.util.Random

const val T0 = 1_790_000_000_000L

fun sampleNeed(
    originNode: ByteArray,
    msgId: ByteArray = NodeId.random(),
    createdAtSec: Long = T0 / 1000,
    summary: String = "needs insulin",
): Need = Need(
    msgId = msgId, originNode = originNode, createdAt = createdAtSec, category = Category.MEDICAL,
    urgency = 3, people = 1, vulnFlags = 0, latE5 = 2029612, lonE5 = 8582451, locAcc = 1, lang = Lang.EN,
    item = "insulin", landmark = "school", summary = summary,
)

fun sampleAck(need: Need, ticket: Long = 1, status: AckStatus = AckStatus.RECEIVED): Ack = Ack(
    ackId = NodeId.random(), refMsgId = need.msgId, targetNode = need.originNode, ticketNo = ticket,
    status = status, etaMin = Ack.ETA_UNKNOWN, text = "received #$ticket",
)

/** Discrete-event virtual-time network of GossipNodes (latency per link, optional loss). */
class TestNet(seed: Long = 1) {
    private class Ev(val t: Long, val seq: Long, val task: () -> Unit)

    private val queue = PriorityQueue<Ev>(compareBy<Ev>({ it.t }, { it.seq }))
    private var seq = 0L
    var now = T0
        private set
    private val rnd = Random(seed)
    val nodes = LinkedHashMap<String, N>()
    private val links = HashMap<Pair<String, String>, Long>() // (from,to) -> latency
    var lossProbability = 0.0

    val clock = object : Clock { override fun nowMs() = now }
    val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, task: () -> Unit) { at(now + delayMs, task) }
    }

    fun at(t: Long, task: () -> Unit) { queue.add(Ev(t, seq++, task)) }

    inner class N(val name: String) : LinkSender, GossipListener {
        val id: ByteArray = ByteArray(8).also { rnd.nextBytes(it) }
        val needsDelivered = ArrayList<Need>()
        val acksForMe = ArrayList<Ack>()
        val acksSeen = ArrayList<Ack>()
        val locHints = ArrayList<LocHint>()
        val broadcasts = ArrayList<Broadcast>()
        val announcements = ArrayList<Announcement>()
        val sentFrames = ArrayList<Pair<String, ByteArray>>()
        var onNeedHook: (Need) -> Unit = {}
        val node = GossipNode(id, this, clock, scheduler, random = Random(rnd.nextLong()), listener = this)

        override fun send(linkId: String, frame: ByteArray) {
            sentFrames += linkId to frame
            val peer = nodes[linkId] ?: return
            val lat = links[name to linkId] ?: return
            if (lossProbability > 0 && rnd.nextDouble() < lossProbability) return
            at(now + lat) {
                if (links.containsKey(linkId to name)) peer.node.onFrame(name, frame)
            }
        }

        override fun onNeed(need: Need, frame: Frame, fromLink: String?) {
            needsDelivered += need
            onNeedHook(need)
        }
        override fun onAckForMe(ack: Ack, frame: Frame) { acksForMe += ack }
        override fun onAckSeen(ack: Ack, frame: Frame) { acksSeen += ack }
        override fun onLocHint(hint: LocHint, frame: Frame, fromLink: String?) { locHints += hint }
        override fun onBroadcast(broadcast: Broadcast, frame: Frame, fromLink: String?) { broadcasts += broadcast }
        override fun onAnnouncement(announcement: Announcement, frame: Frame, fromLink: String?) { announcements += announcement }
    }

    fun node(name: String): N = nodes.getOrPut(name) { N(name) }

    fun connect(a: String, b: String, latencyMs: Long = 30) {
        val na = node(a); val nb = node(b)
        links[a to b] = latencyMs; links[b to a] = latencyMs
        na.node.linkUp(b); nb.node.linkUp(a)
    }

    fun disconnect(a: String, b: String) {
        links.remove(a to b); links.remove(b to a)
        nodes[a]?.node?.linkDown(b); nodes[b]?.node?.linkDown(a)
    }

    fun chain(names: List<String>) { for (i in 0 until names.size - 1) connect(names[i], names[i + 1]) }

    fun runUntilIdle(maxEvents: Int = 2_000_000) {
        var n = 0
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            now = maxOf(now, e.t)
            e.task()
            if (++n > maxEvents) error("event storm")
        }
    }

    fun runFor(ms: Long) {
        val end = now + ms
        while (queue.isNotEmpty() && queue.peek().t <= end) {
            val e = queue.poll(); now = maxOf(now, e.t); e.task()
        }
        now = end
    }

    fun resyncRound() {
        for (n in nodes.values) n.node.resync()
        runUntilIdle()
    }
}

/** Single-node harness with a manual scheduler, for protocol-rule unit tests. */
class SoloHarness(val nowMs: Long = T0) {
    val id = NodeId.random()
    var now = nowMs
    val sent = ArrayList<Pair<String, ByteArray>>()
    val tasks = ArrayList<Pair<Long, () -> Unit>>()
    val needs = ArrayList<Pair<Need, Frame>>()
    val acksForMe = ArrayList<Ack>()
    val acksSeen = ArrayList<Ack>()
    val hints = ArrayList<LocHint>()
    val logs = ArrayList<String>()
    val node = GossipNode(
        id,
        object : LinkSender { override fun send(linkId: String, frame: ByteArray) { sent += linkId to frame } },
        object : Clock { override fun nowMs() = now },
        object : Scheduler { override fun schedule(delayMs: Long, task: () -> Unit) { tasks += delayMs to task } },
        random = Random(7),
        listener = object : GossipListener {
            override fun onNeed(need: Need, frame: Frame, fromLink: String?) { needs += need to frame }
            override fun onAckForMe(ack: Ack, frame: Frame) { acksForMe += ack }
            override fun onAckSeen(ack: Ack, frame: Frame) { acksSeen += ack }
            override fun onLocHint(hint: LocHint, frame: Frame, fromLink: String?) { hints += hint }
            override fun onLog(msg: String) { logs += msg }
        },
    )

    fun runTasks() { while (tasks.isNotEmpty()) { val t = tasks.toList(); tasks.clear(); t.forEach { it.second() } } }
    fun sentTo(link: String, type: Int? = null): List<Frame> =
        sent.filter { it.first == link }.mapNotNull { FrameCodec.decode(it.second) }.filter { type == null || it.type == type }
    fun clearSent() = sent.clear()
}

fun needFrameBytes(need: Need, ttl: Int = 7, sender: ByteArray = need.originNode, ts: Long = T0): ByteArray =
    FrameCodec.encode(Frame(type = FrameTypes.NEED, ttl = ttl, timestampMs = ts, senderId = sender,
        payload = org.relay.core.payload.NeedCodec.encode(need)))

fun hex(b: ByteArray) = Hex.encode(b)
