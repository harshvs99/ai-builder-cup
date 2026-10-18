package org.relay.app.gateway

import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.PacketStore
import org.relay.core.payload.AckCodec
import org.relay.core.payload.BroadcastCodec

/** Outcome of one sync attempt, for the UI. */
data class SyncOutcome(
    val ok: Boolean,
    val uploaded: Int = 0,
    val accepted: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
    val acksOriginated: Int = 0,
    /** Control-room broadcasts this round put into the mesh for the first time (already-held ones are not counted). */
    val broadcastsOriginated: Int = 0,
    val error: String? = null,
    /** How many of the [uploaded] frames were LOC_HINTs (the rest are NEEDs). */
    val hintsUploaded: Int = 0,
    /** Backend's count of accepted hints, when its response had a `hints` object; null otherwise. */
    val hintsAccepted: Int? = null,
)

/**
 * One gateway sync round: upload stored NEED frames and then stored LOC_HINT frames (SPEC 5.5) not yet uploaded
 * (NEEDs first, together capped by [maxFramesPerSync]), then wrap any returned ACK payloads
 * and originate them into the mesh. Pure Kotlin so it runs on the JVM against a fake HTTP server.
 *
 * Failure semantics: any error leaves [state] untouched, so the same frames go out again next round.
 */
class GatewaySyncer(
    private val node: GossipNode,
    private val store: PacketStore,
    private val state: GatewayState,
    private val client: SyncClient,
    private val config: () -> SyncConfig,
    private val log: (String) -> Unit = {},
    private val maxFramesPerSync: Int = 100,
) {
    /** True if more NEED or LOC_HINT frames are waiting after the last round (caller may re-run immediately). */
    @Volatile var moreWaiting: Boolean = false
        private set

    /** Keys of stored NEED frames not yet uploaded (LOC_HINTs are deliberately not counted here). */
    fun pendingNeedKeys(): List<String> =
        store.all().filter { it.type == FrameTypes.NEED && !state.isUploaded(it.key) }.map { it.key }

    @Synchronized
    fun syncOnce(): SyncOutcome {
        val stored = store.all()
        val pendingNeeds = stored.filter { it.type == FrameTypes.NEED && !state.isUploaded(it.key) }
        val pendingHints = stored.filter { it.type == FrameTypes.LOC_HINT && !state.isUploaded(it.key) }
        // NEEDs go first so a flood of hints can never delay a request for help.
        val pending = pendingNeeds + pendingHints
        val batch = pending.take(maxFramesPerSync)
        val hintsInBatch = batch.count { it.type == FrameTypes.LOC_HINT }
        moreWaiting = pending.size > batch.size
        return try {
            val resp = client.sync(config(), node.nodeId, batch.map { it.raw }, state.ackCursor())
            var originated = 0
            for (payload in resp.acks) {
                val ack = AckCodec.decode(payload)
                if (ack == null) { log("sync: backend returned an undecodable ACK payload; skipped"); continue }
                node.originateAck(ack)
                log("originating ACK ref=${org.relay.core.ids.Hex.encode(ack.refMsgId)} ticket=#${ack.ticketNo} status=${ack.status} -> node ${org.relay.core.ids.Hex.encode(ack.targetNode)}")
                originated++
            }
            // Every sync hands back every live broadcast; originating is idempotent, so each gateway just puts them all in.
            var bcasts = 0
            for (payload in resp.broadcasts) {
                val b = BroadcastCodec.decode(payload)
                if (b == null) { log("sync: backend returned an undecodable BROADCAST payload; skipped"); continue }
                if (node.originateBroadcast(b) != null) {
                    log("originating BROADCAST ${b.idHex} ${b.kind} ${b.disaster.key}")
                    bcasts++
                }
            }
            // Only after ACKs are safely in the store do we advance the cursor / mark frames uploaded.
            state.markUploaded(batch.map { it.key })
            state.setAckCursor(resp.ackCursor)
            val hintNote = if (hintsInBatch > 0 || resp.hints != null) {
                " hints=$hintsInBatch hints_accepted=${resp.hints?.accepted ?: "?"}"
            } else ""
            log("sync ok frames=${batch.size} accepted=${resp.accepted} duplicates=${resp.duplicates} " +
                "rejected=${resp.rejected} acks=$originated ack_cursor=${resp.ackCursor}$hintNote")
            SyncOutcome(
                true, batch.size, resp.accepted, resp.duplicates, resp.rejected, originated, broadcastsOriginated = bcasts,
                hintsUploaded = hintsInBatch, hintsAccepted = resp.hints?.accepted,
            )
        } catch (e: Exception) {
            moreWaiting = false
            val msg = (e as? SyncException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
            log("sync failed: $msg (will retry)")
            SyncOutcome(false, error = msg)
        }
    }
}
