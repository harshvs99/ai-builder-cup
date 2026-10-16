package org.relay.sim

import org.relay.core.ids.Hex
import org.relay.core.ids.NodeId
import org.relay.core.payload.Ack
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.Need
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

data class Outcome(
    val name: String,
    val description: String,
    val needsSent: Int,
    val needsFloodOnly: Int,
    val needsFinal: Int,
    val acksFloodOnly: Int,
    val acksFinal: Int,
    val needLatencies: List<Long>,
    val ackLatencies: List<Long>,
    val resyncRounds: Int,
    val transmissions: Long,
    val framesLost: Long,
    val duplicateReceptions: Long,
    val virtualMs: Long,
) {
    val pass: Boolean get() = needsFinal == needsSent && acksFinal == needsSent

    fun render(): String {
        fun pct(n: Int) = String.format(Locale.ROOT, "%.1f%%", 100.0 * n / maxOf(needsSent, 1))
        fun lat(l: List<Long>) = if (l.isEmpty()) "n/a" else
            String.format(Locale.ROOT, "mean=%.0f ms p95=%d ms", l.average(), percentile(l, 95))
        return buildString {
            appendLine("== scenario $name: $description")
            appendLine("   NEED delivery : $needsFinal/$needsSent (${pct(needsFinal)})   flood-only $needsFloodOnly/$needsSent   latency ${lat(needLatencies)}")
            appendLine("   ACK  delivery : $acksFinal/$needsSent (${pct(acksFinal)})   flood-only $acksFloodOnly/$needsSent   latency ${lat(ackLatencies)}")
            appendLine("   resync rounds : $resyncRounds   transmissions: $transmissions (lost $framesLost)   duplicate receptions: $duplicateReceptions   virtual time: ${virtualMs / 1000.0} s")
            append("   result        : ${if (pass) "PASS" else "FAIL"}")
        }
    }
}

fun percentile(values: List<Long>, p: Int): Long {
    if (values.isEmpty()) return 0
    val s = values.sorted()
    val rank = ceil(p / 100.0 * s.size).toInt().coerceIn(1, s.size)
    return s[rank - 1]
}

/** Wires one origin and one gateway into a [SimNet] and tracks delivery + latency. */
private class Harness(val net: SimNet, val origin: SimNode, val gateway: SimNode, val needs: Int) {
    private val needSentAt = HashMap<String, Long>()
    private val needAtGateway = HashMap<String, Long>()
    private val ackSentAt = HashMap<String, Long>()
    private val ackAtOrigin = HashMap<String, Long>()
    private var ticket = 100L

    init {
        gateway.node.isGateway = true
        gateway.onNeedFn = { need, _ ->
            val key = Hex.encode(need.msgId)
            if (!needAtGateway.containsKey(key)) {
                needAtGateway[key] = net.now
                net.after(50) {
                    ackSentAt[key] = net.now
                    gateway.node.originateAck(Ack(NodeId.random(), need.msgId, need.originNode, ++ticket,
                        AckStatus.RECEIVED, Ack.ETA_UNKNOWN, "request #$ticket received"))
                }
            }
        }
        origin.onAckForMeFn = { ack ->
            val key = Hex.encode(ack.refMsgId)
            if (!ackAtOrigin.containsKey(key)) ackAtOrigin[key] = net.now
        }
    }

    fun originate(spacingMs: Long = 1000) {
        for (i in 0 until needs) {
            net.after(i * spacingMs) {
                val need = Need(NodeId.random(), origin.id, net.now / 1000, Category.MEDICAL, 3, 1, 0, 2029610 + i, 8582450 + i, 1,
                    Lang.EN, "insulin", "school", "needs insulin #$i")
                needSentAt[Hex.encode(need.msgId)] = net.now
                origin.node.originateNeed(need)
            }
        }
    }

    val complete: Boolean get() = needAtGateway.size == needs && ackAtOrigin.size == needs

    fun outcome(name: String, desc: String, floodNeeds: Int, floodAcks: Int, rounds: Int, startMs: Long) = Outcome(
        name, desc, needs, floodNeeds, needAtGateway.size, floodAcks, ackAtOrigin.size,
        needAtGateway.map { (k, t) -> t - (needSentAt[k] ?: t) },
        ackAtOrigin.map { (k, t) -> t - (ackSentAt[k] ?: t) },
        rounds, net.transmissions, net.lost, net.duplicateReceptions(), net.now - startMs,
    )

    fun floodOnlyCounts() = needAtGateway.size to ackAtOrigin.size
}

object Scenarios {
    private const val NEEDS = 3

    /** Flood, then run INVENTORY resync rounds (every 5 virtual seconds) until delivered or [maxRounds]. */
    private fun settle(h: Harness, name: String, desc: String, maxRounds: Int, startMs: Long): Outcome {
        h.net.runUntilIdle()
        val (fn, fa) = h.floodOnlyCounts()
        var rounds = 0
        while (!h.complete && rounds < maxRounds) {
            h.net.advance(5_000)
            h.net.resyncAll()
            h.net.runUntilIdle()
            rounds++
        }
        return h.outcome(name, desc, fn, fa, rounds, startMs)
    }

    fun chain(nodes: Int, seed: Long = 42): Outcome {
        val net = SimNet(seed)
        val names = (0 until nodes).map { "n$it" }
        for (i in 0 until nodes - 1) net.connect(names[i], names[i + 1])
        val h = Harness(net, net.addNode(names.first()), net.addNode(names.last()), NEEDS)
        val start = net.now
        h.originate()
        return settle(h, "chain", "$nodes nodes in a line, ${nodes - 1} hops, origin -> gateway at the far end", 40, start)
    }

    fun grid(nodes: Int, seed: Long = 42): Outcome {
        val net = SimNet(seed)
        var rows = sqrt(nodes.toDouble()).toInt().coerceAtLeast(1)
        while (nodes % rows != 0) rows--
        val cols = nodes / rows
        fun n(r: Int, c: Int) = "g${r}_$c"
        for (r in 0 until rows) for (c in 0 until cols) {
            net.addNode(n(r, c))
            if (c > 0) net.connect(n(r, c - 1), n(r, c))
            if (r > 0) net.connect(n(r - 1, c), n(r, c))
        }
        val h = Harness(net, net.addNode(n(0, 0)), net.addNode(n(rows - 1, cols - 1)), NEEDS)
        val start = net.now
        h.originate()
        return settle(h, "grid", "$nodes nodes as ${rows}x$cols grid, corner to opposite corner (${rows + cols - 2} hops)", 60, start)
    }

    fun loss(nodes: Int, loss: Double, seed: Long = 42): Outcome {
        val net = SimNet(seed, lossProbability = loss)
        val names = (0 until nodes).map { "n$it" }
        for (i in 0 until nodes - 1) net.connect(names[i], names[i + 1])
        val h = Harness(net, net.addNode(names.first()), net.addNode(names.last()), NEEDS)
        val start = net.now
        h.originate()
        return settle(h, "loss", "$nodes-node chain, ${(loss * 100).toInt()}% frame loss on every link", 200, start)
    }

    /** Two clusters that never touch; a "walker" phone carries NEEDs A->B and ACKs B->A. */
    fun partition(seed: Long = 42): Outcome {
        val net = SimNet(seed)
        net.connect("a0", "a1"); net.connect("a1", "a2")
        net.connect("b0", "b1"); net.connect("b1", "b2")
        val walker = net.addNode("walker")
        val h = Harness(net, net.addNode("a0"), net.addNode("b2"), NEEDS)
        val start = net.now
        h.originate()
        // walker route: cluster A (10-20 s), cluster B (21-40 s), back to cluster A (60-80 s)
        net.at(start + 10_000) { net.connect("walker", "a2") }
        net.at(start + 20_000) { net.disconnect("walker", "a2") }
        net.at(start + 21_000) { net.connect("walker", "b0") }
        net.at(start + 40_000) { net.disconnect("walker", "b0") }
        net.at(start + 60_000) { net.connect("walker", "a2") }
        net.at(start + 80_000) { net.disconnect("walker", "a2") }
        net.runUntilIdle()
        val (fn, fa) = h.floodOnlyCounts()
        check(walker.name == "walker")
        return h.outcome("partition", "clusters A(3) and B(3) never linked; walker visits A, then B, then A", fn, fa, 0, start)
    }
}
