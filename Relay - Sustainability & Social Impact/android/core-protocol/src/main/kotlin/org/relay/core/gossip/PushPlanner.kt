package org.relay.core.gossip

import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes

/**
 * Orders and caps the frames pushed to a peer after an INVENTORY diff, so one link-up carries what matters
 * most inside a bounded burst and the rest follows later. Local policy only: nothing here is on the wire.
 *
 * Order: ACKs addressed to the peer itself, control-room broadcasts, other ACKs (they close the loop for a villager), then NEEDs, then
 * control-room announcements (small, and a rescue request must not wait behind them), then LOC_HINTs (they refine a location,
 * they never gate a rescue). Each group is newest first. The batch is cut at [budgetBytes] of raw frame bytes, but always
 * holds at least one frame so progress is guaranteed.
 */
object PushPlanner {
    /** About two ATT MTUs of payload (2 x (517 - 3)): a handful of frames per burst. */
    const val DEFAULT_BUDGET_BYTES = 2 * (517 - 3)

    class Plan(val batch: List<StoredPacket>, val rest: List<StoredPacket>)

    fun order(candidates: List<StoredPacket>, peerNodeId: ByteArray?): List<StoredPacket> {
        fun group(p: StoredPacket): Int = when {
            p.type == FrameTypes.ACK && peerNodeId != null && recipientIs(p, peerNodeId) -> 0
            // a broadcast is tiny (about 50 bytes on the wire) and switches on the right questions for every later request
            p.type == FrameTypes.BROADCAST -> 1
            p.type == FrameTypes.ACK -> 2
            p.type == FrameTypes.NEED -> 3
            p.type == FrameTypes.ANNOUNCEMENT -> 4
            else -> 5
        }
        return candidates.sortedWith(compareBy<StoredPacket> { group(it) }.thenByDescending { it.expiryBasisMs })
    }

    fun plan(candidates: List<StoredPacket>, peerNodeId: ByteArray?, budgetBytes: Int = DEFAULT_BUDGET_BYTES): Plan {
        val ordered = order(candidates, peerNodeId)
        var used = 0
        var n = 0
        for (p in ordered) {
            if (n > 0 && used + p.raw.size > budgetBytes) break
            used += p.raw.size
            n++
        }
        return Plan(ordered.subList(0, n), ordered.subList(n, ordered.size))
    }

    private fun recipientIs(p: StoredPacket, node: ByteArray): Boolean =
        FrameCodec.decode(p.raw)?.recipientId?.contentEquals(node) == true
}
