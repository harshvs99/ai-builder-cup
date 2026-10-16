package org.relay.app

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import org.relay.app.ble.BlePermissions
import org.relay.app.ble.BleTransport
import org.relay.app.gateway.GatewayManager
import org.relay.app.gateway.GatewaySyncer
import org.relay.app.gateway.SyncClient
import org.relay.app.gateway.SyncConfig
import org.relay.app.gateway.SyncHealth
import org.relay.app.gateway.SyncOutcome
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.ExecutorScheduler
import org.relay.core.gossip.GossipListener
import org.relay.core.gossip.GossipNode
import org.relay.core.gossip.LinkSender
import org.relay.core.gossip.WallClock
import org.relay.core.ids.Hex
import org.relay.core.payload.Ack
import org.relay.core.payload.Hello
import org.relay.core.payload.Need
import org.relay.core.payload.NeedCodec
import java.util.Random
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The running mesh: owns the singleton [GossipNode], the BLE transport, the gateway and the timers.
 * Created and destroyed by [RelayService]; everything the UI needs is published through [MeshRepository].
 */
class MeshEngine(context: Context) : LinkSender, GossipListener, BleTransport.Events {
    private val ctx = context.applicationContext
    private val repo = MeshRepository
    private val nodeId = repo.nodeId
    private val store = repo.packetStore

    private val executor = ScheduledThreadPoolExecutor(2) { r -> Thread(r, "relay-engine").also { it.isDaemon = true } }
        .also { it.removeOnCancelPolicy = true }

    private val transport = BleTransport(ctx, nodeId, this) { repo.settings.current.allowlist }

    val node = GossipNode(
        nodeId = nodeId,
        sender = this,
        clock = WallClock,
        scheduler = ExecutorScheduler(executor),
        store = store,
        random = Random(),
        listener = this,
        appVersion = 1,
    )

    private val syncer = GatewaySyncer(
        node, store, repo.gatewayState, SyncClient(),
        config = { repo.settings.current.let { SyncConfig(it.backendUrl, it.deviceKey) } },
        log = { repo.log(it) },
    )

    private var lastOutcome: SyncOutcome? = null
    @Volatile private var lastSyncOkAt = 0L
    @Volatile private var lastSyncError: String? = null
    @Volatile private var syncHealth = SyncHealth()
    @Volatile private var lastBackendUrl = repo.settings.current.backendUrl
    @Volatile private var internet = false
    @Volatile private var running = false

    private val gateway = GatewayManager(
        ctx, node, syncer, executor,
        pretendOffline = { repo.settings.current.pretendOffline },
        log = { repo.log(it) },
        onChange = { net, outcome ->
            internet = net
            if (outcome != null) {
                val now = System.currentTimeMillis()
                if (outcome.ok) { lastSyncOkAt = now; lastSyncError = null } else lastSyncError = outcome.error
                syncHealth = syncHealth.record(outcome.ok, outcome.error, now, repo.settings.current.backendUrl)
            }
            publish()
        },
    )

    private val settingsListener: (Settings) -> Unit = { s ->
        if (s.backendUrl != lastBackendUrl) {
            // New backend: the old outcome says nothing about it. Retry right away so Home shows the result.
            lastBackendUrl = s.backendUrl
            syncHealth = SyncHealth()
            lastSyncError = null
            gateway.kick()
        }
        gateway.refresh()
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    repo.log("Bluetooth turned off; stopping transport")
                    transport.stop()
                }
                BluetoothAdapter.STATE_ON -> {
                    repo.log("Bluetooth turned on; restarting transport")
                    transport.start()
                }
            }
            publish()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (running) return
        running = true
        repo.log("engine: starting (node ${Hex.encode(nodeId)})")
        ContextCompat.registerReceiver(ctx, btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        repo.settings.addListener(settingsListener)
        transport.start()
        gateway.start()
        // SPEC section 6.6: re-send INVENTORY on every live link every 30 s. Hourly expiry of 72 h-old packets.
        executor.scheduleWithFixedDelay({ node.resync() }, 30, 30, TimeUnit.SECONDS)
        executor.scheduleWithFixedDelay({ node.expire() }, 1, 1, TimeUnit.HOURS)
        executor.scheduleWithFixedDelay({ publish() }, 1, 1, TimeUnit.SECONDS)
        executor.execute { resendOwnNeeds() }
        publish()
    }

    /** Re-checks permissions / Bluetooth and starts the transport if it was waiting (idempotent). */
    fun ensureTransport() { transport.start() }

    fun stop() {
        if (!running) return
        running = false
        repo.log("engine: stopping")
        repo.settings.removeListener(settingsListener)
        try { ctx.unregisterReceiver(btReceiver) } catch (_: Exception) {}
        gateway.stop()
        transport.shutdown()
        executor.shutdownNow()
        store.flush()
    }

    /** Own NEEDs that never made it into the store (service was down when they were created) go out now. */
    private fun resendOwnNeeds() {
        val toSend = ArrayList<Need>()
        for (n in repo.drainOutbox()) toSend += n
        for (m in repo.myNeedsStore.list()) {
            if (m.stage != org.relay.app.store.MyNeed.Stage.QUEUED) continue
            if (System.currentTimeMillis() - m.createdAtMs > 72L * 3600 * 1000) continue
            val need = try {
                NeedCodec.decode(java.util.Base64.getDecoder().decode(m.needPayloadB64))
            } catch (_: Exception) { null } ?: continue
            if (toSend.none { it.msgIdHex == need.msgIdHex }) toSend += need
        }
        for (n in toSend) originate(n)
    }

    fun originate(need: Need) {
        node.originateNeed(need)
        repo.log("originated NEED ${need.msgIdHex} cat=${need.category} urgency=${need.urgency} item=\"${need.item}\"")
        publish()
    }

    fun resyncNow() {
        repo.log("resync requested")
        node.resync()
        gateway.kick()
    }

    // ---------------------------------------------------------------- LinkSender (gossip -> BLE)

    override fun send(linkId: String, frame: ByteArray) {
        noteOwnNeedSent(frame)
        transport.send(linkId, frame)
    }

    /** Mark "Sent to mesh" the first time one of our own NEED frames is handed to a link. */
    private fun noteOwnNeedSent(raw: ByteArray) {
        if (raw.size < 2 || (raw[1].toInt() and 0xFF) != FrameTypes.NEED) return
        val f = FrameCodec.decode(raw) ?: return
        if (!f.senderId.contentEquals(nodeId)) return
        val need = NeedCodec.decode(f.payload) ?: return
        val updated = repo.myNeedsStore.update(need.msgIdHex) { if (it.sentToMesh) it else it.copy(sentToMesh = true) }
        if (updated != null) repo.refreshMyNeeds()
    }

    // ---------------------------------------------------------------- BleTransport.Events (BLE -> gossip)

    override fun onLinkUp(linkId: String) {
        repo.log("linkUp $linkId")
        node.linkUp(linkId)
        publish()
    }

    override fun onLinkDown(linkId: String) {
        repo.log("linkDown $linkId")
        node.linkDown(linkId)
        publish()
    }

    override fun onFrame(linkId: String, frame: ByteArray) = node.onFrame(linkId, frame)

    override fun onLog(msg: String) = repo.log(msg)

    // ---------------------------------------------------------------- GossipListener

    override fun onNeed(need: Need, frame: Frame, fromLink: String?) {
        repo.log("NEED delivered ${need.msgIdHex} from=${fromLink ?: "local"} ttl=${frame.ttl} cat=${need.category} urgency=${need.urgency} item=\"${need.item}\"")
        gateway.kick()
        publish()
    }

    override fun onAckForMe(ack: Ack, frame: Frame) {
        val id = Hex.encode(ack.refMsgId)
        repo.log("ACK for me ref=$id ticket=#${ack.ticketNo} status=${ack.status} text=\"${ack.text}\"")
        val updated = repo.myNeedsStore.update(id) { it.withAck(ack.status, ack.ticketNo, ack.text, ack.etaMin) }
        if (updated == null) repo.log("ACK refers to an unknown own NEED $id") else repo.refreshMyNeeds()
        publish()
    }

    override fun onPeerHello(linkId: String, hello: Hello) {
        repo.log("HELLO from ${Hex.encode(hello.nodeId)} via $linkId gateway=${hello.isGateway} hops=${hello.hopsToGateway}")
        transport.onPeerHello(linkId, hello.nodeId)
        publish()
    }

    // ---------------------------------------------------------------- status snapshot for the UI

    fun publish() {
        if (!running) return
        try {
            val hellos = node.peers()
            val peers = node.linkIds().map { id ->
                val h = hellos[id]
                PeerInfo(id, h?.let { Hex.encode(it.nodeId) }, h?.isGateway == true, h?.hopsToGateway ?: 0xFF)
            }
            val all = store.all()
            repo.publishStatus(
                MeshStatus(
                    serviceRunning = true,
                    bluetoothOn = BlePermissions.isBluetoothOn(ctx),
                    peers = peers,
                    isGateway = node.isGateway,
                    hopsToGateway = node.hopsToGateway,
                    storedNeeds = all.count { it.type == FrameTypes.NEED },
                    storedAcks = all.count { it.type == FrameTypes.ACK },
                    stats = node.stats(),
                    transport = transport.info(),
                    internetAvailable = internet,
                    lastSyncOkAtMs = lastSyncOkAt,
                    lastSyncError = lastSyncError,
                    pendingUploads = if (node.isGateway) syncer.pendingNeedKeys().size else 0,
                    syncHealth = syncHealth,
                ),
            )
        } catch (e: Exception) {
            repo.log("publish failed: $e")
        }
    }
}
