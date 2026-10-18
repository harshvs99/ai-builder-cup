package org.relay.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.relay.app.ble.BleTransport
import org.relay.app.ble.DutyCycle
import org.relay.app.gateway.GatewayState
import org.relay.app.gateway.SyncHealth
import org.relay.app.store.FilePacketStore
import org.relay.app.store.MyNeed
import org.relay.app.store.MyNeedsStore
import org.relay.core.gossip.GossipStats
import org.relay.core.ids.Hex
import org.relay.core.ids.NodeId
import org.relay.core.payload.Need
import org.relay.core.payload.NeedCodec
import java.io.File
import java.util.Base64

const val LOG_TAG = "RelayMesh"

data class PeerInfo(
    val linkId: String,
    /** null until the peer's HELLO arrives */
    val nodeIdHex: String?,
    val isGateway: Boolean,
    val hops: Int,
)

data class MeshStatus(
    val serviceRunning: Boolean = false,
    val bluetoothOn: Boolean = false,
    val peers: List<PeerInfo> = emptyList(),
    val isGateway: Boolean = false,
    val hopsToGateway: Int = 0xFF,
    val storedNeeds: Int = 0,
    val storedAcks: Int = 0,
    val stats: GossipStats = GossipStats(),
    val transport: BleTransport.Info? = null,
    val internetAvailable: Boolean = false,
    val lastSyncOkAtMs: Long = 0,
    val lastSyncError: String? = null,
    val pendingUploads: Int = 0,
    /** Outcome of the gateway's most recent backend sync (meaningful while [isGateway]). */
    val syncHealth: SyncHealth = SyncHealth(),
    /** Battery level 0..100, or -1 when unknown. UI: show next to the mesh-hours estimate. */
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    /** Current scan duty mode; `scanMode.label` is a ready-to-show description. */
    val scanMode: DutyCycle.Mode = DutyCycle.Mode.NORMAL,
    /** Estimated mesh hours left at the current duty mode; null while charging or battery unknown. */
    val meshHoursRemaining: Double? = null,
    /** Last peers with a successful exchange (node id hex), newest first. */
    val recentPeerIds: List<String> = emptyList(),
    /** Stored LOC_HINT frames (mesh-assisted location, SPEC 5.5). */
    val storedHints: Int = 0,
)

/**
 * Process-wide hub between the foreground service (writer) and the UI (reader). Owns the persistent
 * stores so they exist whether or not the service is currently running.
 */
object MeshRepository {
    private const val MAX_LOG = 100

    private var initialised = false
    private lateinit var appContext: Context

    lateinit var settings: AppSettings
        private set
    lateinit var packetStore: FilePacketStore
        private set
    lateinit var myNeedsStore: MyNeedsStore
        private set
    lateinit var gatewayState: GatewayState
        private set
    lateinit var nodeId: ByteArray
        private set

    val nodeIdHex: String get() = Hex.encode(nodeId)

    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> get() = _status

    private val _myNeeds = MutableStateFlow<List<MyNeed>>(emptyList())
    val myNeeds: StateFlow<List<MyNeed>> get() = _myNeeds

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> get() = _log

    private val logBuffer = ArrayDeque<String>()

    /** Set by [RelayService] while it runs. */
    @Volatile var engine: MeshEngine? = null

    /** NEEDs submitted while the service was not running; the engine drains them on start. */
    private val outbox = ArrayList<Need>()

    @Synchronized fun init(ctx: Context) {
        if (initialised) return
        appContext = ctx.applicationContext
        val dir = appContext.filesDir
        settings = AppSettings(appContext)
        nodeId = loadOrCreateNodeId(appContext)
        packetStore = FilePacketStore(File(dir, "packets.bin"), flushDelayMs = 300, log = { log("store: $it") })
        myNeedsStore = MyNeedsStore(File(dir, "my_needs.json"))
        gatewayState = GatewayState(File(dir, "gateway_state.json"))
        _myNeeds.value = myNeedsStore.list()
        initialised = true
        log("init: node id $nodeIdHex, ${packetStore.size()} stored packet(s), ${_myNeeds.value.size} own need(s)")
    }

    private fun loadOrCreateNodeId(ctx: Context): ByteArray {
        val prefs = ctx.getSharedPreferences("relay_identity", Context.MODE_PRIVATE)
        val existing = prefs.getString("node_id", null)?.let { Hex.decodeOrNull(it) }
        if (existing != null && existing.size == NodeId.SIZE) return existing
        val fresh = NodeId.random()
        // commit(): the id must hit disk before it is ever put on the air.
        prefs.edit().putString("node_id", Hex.encode(fresh)).commit()
        return fresh
    }

    fun log(msg: String) {
        Log.i(LOG_TAG, msg)
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()) + " " + msg
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG) logBuffer.removeFirst()
            _log.value = logBuffer.toList()
        }
    }

    fun publishStatus(s: MeshStatus) { _status.value = s }

    fun refreshMyNeeds() { _myNeeds.value = myNeedsStore.list() }

    // ---------------------------------------------------------------- need submission

    /** Record [need] under "my needs" and push it into the mesh (now, or when the service starts). */
    fun submitNeed(need: Need, ctx: Context? = null) {
        val mine = MyNeed(
            msgIdHex = need.msgIdHex,
            createdAtMs = need.createdAt * 1000L,
            category = need.category,
            urgency = need.urgency,
            people = need.people,
            item = need.item,
            landmark = need.landmark,
            summary = need.summary,
            lang = need.lang,
            vulnFlags = need.vulnFlags,
            answers = need.answers.associate { it.questionId to it.value },
            sentToMeshAtMs = 0,
            needPayloadB64 = Base64.getEncoder().encodeToString(NeedCodec.encode(need)),
        )
        myNeedsStore.add(mine)
        refreshMyNeeds()
        val e = engine
        if (e != null) {
            e.originate(need)
        } else {
            synchronized(outbox) { outbox.add(need) }
            log("service not running; NEED ${need.msgIdHex} queued until it starts")
            ctx?.let { startService(it) }
        }
    }

    /**
     * The user stopped sharing request [msgIdHex]: mark it cancelled and stop relaying its NEED from this phone
     * (drop it from the packet store and the not-yet-started outbox). There is no cancel frame on the wire, so
     * neighbours and the control room may still have it. Returns false for an unknown id.
     */
    fun cancel(msgIdHex: String): Boolean {
        val updated = myNeedsStore.update(msgIdHex) { it.cancel() } ?: return false
        synchronized(outbox) { outbox.removeAll { it.msgIdHex == msgIdHex } }
        packetStore.remove(msgIdHex)
        packetStore.flush()
        refreshMyNeeds()
        log("cancelled own NEED $msgIdHex (stage=${updated.stage}); no longer relayed from this phone")
        return true
    }

    /** Stamp "uploaded" on own NEEDs the gateway state now lists as uploaded (this phone was the gateway). */
    fun noteUploads() {
        var changed = false
        for (m in myNeedsStore.list()) {
            if (m.uploadedAtMs == 0L && gatewayState.isUploaded(m.msgIdHex)) {
                myNeedsStore.update(m.msgIdHex) { it.markUploaded() }
                changed = true
            }
        }
        if (changed) refreshMyNeeds()
    }

    fun drainOutbox(): List<Need> = synchronized(outbox) { val l = ArrayList(outbox); outbox.clear(); l }

    fun startService(ctx: Context): Boolean = try {
        ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java))
        true
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when the app is in the background
        log("could not start RelayService: $e")
        false
    }

    fun clearStore() {
        packetStore.clear()
        packetStore.flush()
        gatewayState.clearUploaded()
        log("store cleared")
    }

    fun clearMyNeeds() {
        myNeedsStore.clear()
        refreshMyNeeds()
    }
}
