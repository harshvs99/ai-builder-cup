package org.relay.core.gossip

import org.relay.core.payload.Ack
import org.relay.core.payload.Hello
import org.relay.core.payload.Need
import org.relay.core.frame.Frame
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Sends one whole frame over one link. Called OUTSIDE the engine lock, possibly from any thread
 * that drives the engine; implementations should be non-blocking (enqueue, don't wait for BLE).
 * The byte array is owned by the callee (a fresh copy per call).
 */
interface LinkSender { fun send(linkId: String, frame: ByteArray) }

interface Clock { fun nowMs(): Long }

/** Runs [task] once after [delayMs]. The simulator implements this with virtual time. */
interface Scheduler { fun schedule(delayMs: Long, task: () -> Unit) }

/** Callbacks are invoked outside the engine lock, so a listener may call back into the node. */
interface GossipListener {
    fun onNeed(need: Need, frame: Frame, fromLink: String?) {}
    fun onAckForMe(ack: Ack, frame: Frame) {}
    fun onAckSeen(ack: Ack, frame: Frame) {}
    fun onPeerHello(linkId: String, hello: Hello) {}
    fun onLog(msg: String) {}
}

object WallClock : Clock { override fun nowMs(): Long = System.currentTimeMillis() }

/** [Scheduler] backed by a real [ScheduledExecutorService] (Android app, real-time simulator). */
class ExecutorScheduler(private val executor: ScheduledExecutorService) : Scheduler {
    override fun schedule(delayMs: Long, task: () -> Unit) {
        try {
            executor.schedule({
                try { task() } catch (_: Throwable) { /* never kill the executor thread */ }
            }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // executor shut down: node is being torn down
        }
    }
}

/** Bookkeeping for the duplicate-link rule (SPEC section 2). */
object DuplicateLinkPolicy {
    /**
     * When two links reach the same node id, keep the link where the node with the numerically
     * lower id (unsigned, big-endian) is the CENTRAL. Returns true iff THIS node ([myId]) should be
     * the central on the kept link, i.e. `myId < peerId`. If you are central on a duplicate link
     * and this returns false, close that link; if you are peripheral and it returns true, the peer
     * is expected to close its side (close yours too if it lingers).
     */
    fun keepAsCentral(myId: ByteArray, peerId: ByteArray): Boolean =
        org.relay.core.ids.NodeId.compare(myId, peerId) < 0
}

/** One stored NEED/ACK frame. [raw] is the encoded frame as received (or originated). */
class StoredPacket(
    val key: String,
    val type: Int,
    val raw: ByteArray,
    /** NEED: created_at in ms. ACK: first-stored time in ms. Expiry is `basis + 72 h`. */
    val expiryBasisMs: Long,
)

/** Persistence seam. The engine serialises all calls under its own lock. */
interface PacketStore {
    fun put(packet: StoredPacket)
    fun get(key: String): StoredPacket?
    fun contains(key: String): Boolean
    /** Keys in insertion order (oldest first). */
    fun allKeys(): List<String>
    fun remove(key: String)
    /** Removes every packet whose `expiryBasisMs < cutoffMs`; returns the removed keys. */
    fun expire(cutoffMs: Long): List<String>
    fun all(): List<StoredPacket> = allKeys().mapNotNull { get(it) }
}

class InMemoryPacketStore : PacketStore {
    private val map = LinkedHashMap<String, StoredPacket>()

    @Synchronized override fun put(packet: StoredPacket) { map[packet.key] = packet }
    @Synchronized override fun get(key: String): StoredPacket? = map[key]
    @Synchronized override fun contains(key: String): Boolean = map.containsKey(key)
    @Synchronized override fun allKeys(): List<String> = map.keys.toList()
    @Synchronized override fun remove(key: String) { map.remove(key) }
    @Synchronized override fun all(): List<StoredPacket> = map.values.toList()
    @Synchronized override fun expire(cutoffMs: Long): List<String> {
        val dead = map.values.filter { it.expiryBasisMs < cutoffMs }.map { it.key }
        dead.forEach { map.remove(it) }
        return dead
    }
}

/** Counters, for diagnostics and the simulator. */
data class GossipStats(
    val framesReceived: Long = 0,
    val framesRejected: Long = 0,
    /** NEED/ACK/opaque frames dropped because their key was already known. */
    val duplicates: Long = 0,
    val framesSent: Long = 0,
    val needsDelivered: Long = 0,
    val acksDelivered: Long = 0,
)
