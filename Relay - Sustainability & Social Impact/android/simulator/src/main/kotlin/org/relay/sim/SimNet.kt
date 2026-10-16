package org.relay.sim

import org.relay.core.frame.Frame
import org.relay.core.gossip.Clock
import org.relay.core.gossip.GossipListener
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.InMemoryPacketStore
import org.relay.core.gossip.LinkSender
import org.relay.core.gossip.Scheduler
import org.relay.core.payload.Ack
import org.relay.core.payload.Need
import java.util.PriorityQueue
import java.util.Random

/**
 * Discrete-event network in virtual time. Every [SimNode] wraps a real [GossipNode]; the only
 * simulated parts are the clock, the jitter scheduler and the links (fixed latency per link,
 * optional random loss applied to every frame).
 */
class SimNet(seed: Long, var lossProbability: Double = 0.0, private val latencyMin: Long = 20, private val latencyMax: Long = 80) {
    companion object { const val BASE_MS = 1_790_000_000_000L }

    private class Ev(val t: Long, val seq: Long, val task: () -> Unit)

    private val queue = PriorityQueue<Ev>(compareBy<Ev>({ it.t }, { it.seq }))
    private var seq = 0L
    private val rnd = Random(seed)
    private val links = HashMap<Pair<String, String>, Long>()

    var now: Long = BASE_MS
        private set
    var transmissions = 0L
        private set
    var lost = 0L
        private set
    val nodes = LinkedHashMap<String, SimNode>()

    val clock = object : Clock { override fun nowMs() = now }
    val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, task: () -> Unit) { at(now + delayMs, task) }
    }

    fun at(t: Long, task: () -> Unit) { queue.add(Ev(t, seq++, task)) }
    fun after(delayMs: Long, task: () -> Unit) = at(now + delayMs, task)

    fun addNode(name: String): SimNode = nodes.getOrPut(name) {
        val id = ByteArray(8).also { rnd.nextBytes(it) }
        SimNode(name, this, id, rnd.nextLong())
    }

    fun connect(a: String, b: String) {
        val na = addNode(a); val nb = addNode(b)
        val lat = latencyMin + rnd.nextInt((latencyMax - latencyMin + 1).toInt())
        links[a to b] = lat; links[b to a] = lat
        na.node.linkUp(b); nb.node.linkUp(a)
    }

    fun disconnect(a: String, b: String) {
        links.remove(a to b); links.remove(b to a)
        nodes[a]?.node?.linkDown(b); nodes[b]?.node?.linkDown(a)
    }

    internal fun transmit(from: String, to: String, bytes: ByteArray) {
        val lat = links[from to to] ?: return // link gone: frame never leaves
        transmissions++
        if (lossProbability > 0 && rnd.nextDouble() < lossProbability) { lost++; return }
        val peer = nodes[to] ?: return
        at(now + lat) {
            // a link torn down while the frame was in flight loses it
            if (links.containsKey(to to from)) peer.node.onFrame(from, bytes)
        }
    }

    /** Process events until none remain (or [maxEvents] is exceeded: protects against storms). */
    fun runUntilIdle(maxEvents: Long = 20_000_000) {
        var n = 0L
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            if (e.t > now) now = e.t
            e.task()
            if (++n > maxEvents) error("event storm: more than $maxEvents events")
        }
    }

    /** Move virtual time forward by [ms], running everything scheduled until then. */
    fun advance(ms: Long) {
        val end = now + ms
        while (queue.isNotEmpty() && queue.peek().t <= end) {
            val e = queue.poll()
            if (e.t > now) now = e.t
            e.task()
        }
        now = end
    }

    fun resyncAll() { for (n in nodes.values) n.node.resync() }

    fun duplicateReceptions(): Long = nodes.values.sumOf { it.node.stats().duplicates }
}

class SimNode(val name: String, private val net: SimNet, val id: ByteArray, seed: Long) : LinkSender, GossipListener {
    var onNeedFn: (Need, Frame) -> Unit = { _, _ -> }
    var onAckForMeFn: (Ack) -> Unit = {}
    val node = GossipNode(id, this, net.clock, net.scheduler, InMemoryPacketStore(), Random(seed), this)

    override fun send(linkId: String, frame: ByteArray) { net.transmit(name, linkId, frame) }
    override fun onNeed(need: Need, frame: Frame, fromLink: String?) = onNeedFn(need, frame)
    override fun onAckForMe(ack: Ack, frame: Frame) = onAckForMeFn(ack)
}
