package org.relay.core.gossip

import org.relay.core.ids.Hex
import org.relay.core.payload.Geo
import org.relay.core.payload.LocHint
import org.relay.core.payload.LocHintCodec
import org.relay.core.payload.Need

/** This phone's own position fix. [atMs] is when it was taken (epoch ms). */
data class RelayFix(val lat: Double, val lon: Double, val accuracyM: Float, val atMs: Long)

/**
 * Decides when this phone puts a LOC_HINT (SPEC 5.5) into the mesh. Feed it every NEED the gossip node delivers
 * ([onNeed], from [GossipListener.onNeed]); it emits at most one hint per NEED, and only when ALL of these hold:
 *
 *  - [enabled] ("Help locate neighbours") is on, checked again at the moment of emission;
 *  - the NEED has `loc_acc == 0` (its requester has no fix);
 *  - it arrived directly from its origin: the HELLO of the link it came in on carries `origin_node`;
 *  - [fix] returns a fix taken within [MAX_FIX_AGE_MS] with accuracy <= 100 m.
 *
 * With no good fix yet it calls [requestFix] once (the platform may start a short location listen) and checks again
 * after each of [retryDelaysMs], so a phone whose GPS was idle can still help; then it gives up for that NEED.
 * RSSI and observed_at are taken when the NEED arrives, not when the hint is finally sent.
 */
class LocHintResponder(
    private val node: GossipNode,
    private val clock: Clock,
    private val scheduler: Scheduler,
    private val enabled: () -> Boolean,
    private val fix: () -> RelayFix?,
    /** Signal strength of the link [linkId] in dBm, or null when unknown. */
    private val rssi: (linkId: String) -> Int? = { null },
    private val requestFix: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
) {
    companion object {
        /** A fix older than this is not "where the phone is now". Same as the app's FixPolicy.MAX_AGE_MS. */
        const val MAX_FIX_AGE_MS = 120_000L
        /** 3 s + 7 s + 20 s: the hint goes out within 30 s of the NEED or not at all. */
        val DEFAULT_RETRY_DELAYS_MS = listOf(3_000L, 7_000L, 20_000L)
    }

    /** Call for each NEED newly delivered by the node. [fromLink] is null for a NEED originated on this phone. */
    fun onNeed(need: Need, fromLink: String?) {
        if (fromLink == null || need.locAcc != 0 || !enabled()) return
        val peer = node.peers()[fromLink] ?: return
        if (!peer.nodeId.contentEquals(need.originNode) || need.originNode.contentEquals(node.nodeId)) return
        val observedAt = clock.nowMs() / 1000
        val link = rssi(fromLink)?.coerceIn(-127, 127) ?: LocHintCodec.RSSI_UNKNOWN
        attempt(need, observedAt, link, 0)
    }

    private fun attempt(need: Need, observedAt: Long, rssiDbm: Int, tries: Int) {
        if (!enabled()) return
        val now = clock.nowMs()
        val f = fix()
        val bucket = f?.takeIf { now - it.atMs in 0..MAX_FIX_AGE_MS && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
            ?.let { LocHintCodec.bucketFor(it.accuracyM) }
        if (f != null && bucket != null) {
            val hint = LocHint(
                refMsgId = need.msgId, relayNode = node.nodeId,
                latE5 = Geo.toE5(f.lat), lonE5 = Geo.toE5(f.lon),
                acc = bucket, rssiDbm = rssiDbm, observedAt = observedAt,
            )
            if (node.originateLocHint(hint) != null) {
                log("LOC_HINT sent for ${need.msgIdHex}: acc<=${hint.accuracyMaxM} m rssi=${if (rssiDbm == LocHintCodec.RSSI_UNKNOWN) "?" else rssiDbm}")
            }
            return
        }
        if (tries == 0) requestFix()
        val delay = retryDelaysMs.getOrNull(tries)
        if (delay == null) {
            log("no usable fix to hint ${Hex.encode(need.msgId)}; skipped")
            return
        }
        scheduler.schedule(delay) { attempt(need, observedAt, rssiDbm, tries + 1) }
    }
}
