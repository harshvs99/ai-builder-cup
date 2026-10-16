package org.relay.app.gateway

import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.PacketStore
import org.relay.core.payload.AckCodec

/** Outcome of one sync attempt, for the UI. */
data class SyncOutcome(
    val ok: Boolean,
    val uploaded: Int = 0,
    val accepted: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
    val acksOriginated: Int = 0,
    val error: String? = null,
)

/**
 * One gateway sync round: upload stored NEED frames not yet uploaded, then wrap any returned ACK payloads
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
    /** True if more NEED frames are waiting after the last round (caller may re-run immediately). */
    @Volatile var moreWaiting: Boolean = false
        private set

    fun pendingNeedKeys(): List<String> =
        store.all().filter { it.type == FrameTypes.NEED && !state.isUploaded(it.key) }.map { it.key }

    @Synchronized
    fun syncOnce(): SyncOutcome {
        val pending = store.all().filter { it.type == FrameTypes.NEED && !state.isUploaded(it.key) }
        val batch = pending.take(maxFramesPerSync)
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
            // Only after ACKs are safely in the store do we advance the cursor / mark frames uploaded.
            state.markUploaded(batch.map { it.key })
            state.setAckCursor(resp.ackCursor)
            log("sync ok frames=${batch.size} accepted=${resp.accepted} duplicates=${resp.duplicates} " +
                "rejected=${resp.rejected} acks=$originated ack_cursor=${resp.ackCursor}")
            SyncOutcome(true, batch.size, resp.accepted, resp.duplicates, resp.rejected, originated)
        } catch (e: Exception) {
            moreWaiting = false
            val msg = (e as? SyncException)?.message ?: "${e.javaClass.simpleName}: ${e.message}"
            log("sync failed: $msg (will retry)")
            SyncOutcome(false, error = msg)
        }
    }
}
