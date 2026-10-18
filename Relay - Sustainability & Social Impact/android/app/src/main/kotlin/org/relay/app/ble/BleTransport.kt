package org.relay.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import org.relay.core.gossip.LinkSender
import org.relay.core.ids.Hex

/**
 * Relay's own BLE GATT mesh transport (SPEC section 2). Every node is both a GATT server (advertising,
 * "p:<mac>" links) and a GATT client (scanning, "c:<mac>" links).
 *
 * Threading: all state is owned by one HandlerThread ([handler]). Framework callbacks arrive on binder
 * threads, copy what they need and post to the handler; [send] posts too. That keeps the state machine
 * single-threaded without locks, and means the gossip engine can call [send] from any thread.
 *
 * Every framework call is preceded by a runtime-permission check ([BlePermissions]) and wrapped so a
 * SecurityException (permission revoked mid-flight) degrades to a log line rather than a crash.
 */
class BleTransport(
    context: Context,
    private val myNodeId: ByteArray,
    private val events: Events,
    private val allowlist: () -> String,
    private val recentPeers: RecentPeers = RecentPeers(),
    /** Current scan duty mode (battery / foreground aware); read on every tick. */
    private val duty: () -> DutyCycle.Mode = { DutyCycle.Mode.NORMAL },
) : LinkSender {

    interface Events {
        fun onLinkUp(linkId: String)
        fun onLinkDown(linkId: String)
        fun onFrame(linkId: String, frame: ByteArray)
        fun onLog(msg: String)
    }

    /** Point-in-time view for the diagnostics screen. */
    data class Info(
        val running: Boolean,
        val scanning: Boolean,
        val advertising: Boolean,
        val centralLinks: Int,
        val peripheralLinks: Int,
        val connecting: String?,
        val dutyMode: DutyCycle.Mode = DutyCycle.Mode.NORMAL,
    )

    private val ctx: Context = context.applicationContext
    private val btManager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val thread = HandlerThread("relay-ble").also { it.start() }
    private val handler = Handler(thread.looper)

    // ---- state owned by the handler thread
    @Volatile private var running = false
    @Volatile private var scanning = false
    @Volatile private var advertising = false
    @Volatile private var diagCentral = 0
    @Volatile private var diagPeripheral = 0
    @Volatile private var diagConnecting: String? = null

    private val registry = LinkRegistry(myNodeId)
    private val backoff = Backoff()
    private val scanThrottle = ScanThrottle()
    private val ignored = HashMap<String, Long>() // mac or "n:<node id hex>" -> ignore-until (elapsedRealtime)
    private val seenAt = HashMap<String, LongArray>() // node id hex -> [first, last] scan-hint times (elapsedRealtime), connect tie-break
    private val central = HashMap<String, CentralConn>()
    private val peripheral = HashMap<String, PeriphConn>()
    private var connectingMac: String? = null
    /** MAC -> discovery retries already made (reset when the link comes up or we give up); see [LinkHealth.discoveryRetryDelayMs]. */
    private val discoveryRetries = HashMap<String, Int>()
    private var server: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null
    private var serviceReady = false
    private var lastHousekeeping = 0L
    /** Scanner may not start before this time (duty-cycle idle, throttle, failure retry). */
    private var scanNotBefore = 0L
    private var scanStartedAt = 0L
    /** Until this time, scan results that are not recent peers wait while a recent peer is unlinked. */
    private var preferUntil = 0L

    private fun now(): Long = SystemClock.elapsedRealtime()
    private fun log(msg: String) = events.onLog(msg)

    // ================================================================== lifecycle

    fun start() { handler.post { startInternal() } }

    /**
     * Stop because Bluetooth went off. The adapter restart resets the stack's scan accounting [assumption], so the
     * app-side throttle is reset too: otherwise a Bluetooth toggle storm would leave scanning blocked for 30 s.
     */
    fun stop() { handler.post { stopInternal("stop requested"); scanThrottle.reset() } }

    /** Stop and release the worker thread. The transport cannot be restarted afterwards. */
    fun shutdown() {
        handler.post {
            stopInternal("shutdown")
            thread.quitSafely()
        }
    }

    fun info(): Info = Info(running, scanning, advertising, diagCentral, diagPeripheral, diagConnecting, duty())

    private fun updateDiag() {
        diagCentral = central.size
        diagPeripheral = peripheral.size
        diagConnecting = connectingMac
    }

    private fun startInternal() {
        if (running) return
        val adapter = btManager?.adapter
        if (adapter == null || !BlePermissions.hasBle(ctx)) { log("BLE: no Bluetooth LE hardware"); return }
        if (!adapter.isEnabled) { log("BLE: Bluetooth is off; waiting"); return }
        if (!BlePermissions.canRunMesh(ctx)) {
            log("BLE: missing permissions ${BlePermissions.missingShort(ctx)}; waiting")
            return
        }
        running = true
        log("BLE: starting transport")
        closeServer() // never leak a server from an earlier run
        scanNotBefore = 0
        preferUntil = now() + RecentPeers.PREFER_WINDOW_MS
        openServer()
        startScan()
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    private fun stopInternal(reason: String) {
        if (!running && central.isEmpty() && peripheral.isEmpty() && server == null) return
        log("BLE: stopping transport ($reason)")
        running = false
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(readvertiseRunnable)
        stopScan()
        stopAdvertising()
        for (c in central.values.toList()) teardownCentral(c, "transport stopped", backoffFailure = false, immediateClose = true)
        for (p in peripheral.values.toList()) teardownPeripheral(p, "transport stopped")
        closeServer()
        registry.clear()
        ignored.clear()
        seenAt.clear()
        discoveryRetries.clear()
        backoff.clear() // a restarted transport starts clean: stale per-MAC delays would block the re-link
        scanNotBefore = 0
        connectingMac = null
        serviceReady = false
        updateDiag()
    }

    // ================================================================== LinkSender

    override fun send(linkId: String, frame: ByteArray) {
        handler.post { sendInternal(linkId, frame) }
    }

    private fun sendInternal(linkId: String, frame: ByteArray) {
        if (!running) return
        val mac = linkId.substring(2)
        if (linkId.startsWith("c:")) {
            val c = central[mac]
            if (c == null || c.state != SetupPhase.UP) { log("send: no live link $linkId; frame dropped"); return }
            if (frame.size > c.mtu - BleConstants.ATT_OVERHEAD) {
                log("send: frame ${frame.size}B exceeds mtu ${c.mtu}-3 on $linkId; dropped"); return
            }
            val ch = c.characteristic ?: return
            c.queue.enqueue(WriteOp(c.gatt, ch, frame))
        } else {
            val p = peripheral[mac]
            val srv = server
            val ch = serverChar
            if (p == null || !p.up || srv == null || ch == null) { log("send: no live link $linkId; frame dropped"); return }
            if (frame.size > p.mtu - BleConstants.ATT_OVERHEAD) {
                log("send: frame ${frame.size}B exceeds mtu ${p.mtu}-3 on $linkId; dropped"); return
            }
            p.queue.enqueue(NotifyOp(srv, p.device, ch, frame))
        }
    }

    /** Called (from any thread) when a peer's HELLO arrived: applies the allowlist and the duplicate-link rule. */
    fun onPeerHello(linkId: String, nodeId: ByteArray) { handler.post { handleHello(linkId, nodeId) } }

    private fun handleHello(linkId: String, nodeId: ByteArray) {
        if (!running) return
        val link = registry.get(linkId) ?: return
        val hex = Hex.encode(nodeId)
        if (!Allowlist.matches(allowlist(), hex)) {
            log("allowlist: node $hex on $linkId not allowed; closing and ignoring ${link.mac} for ${BleConstants.ALLOWLIST_IGNORE_MS / 1000}s")
            ignored[link.mac] = now() + BleConstants.ALLOWLIST_IGNORE_MS
            ignored["n:$hex"] = now() + BleConstants.ALLOWLIST_IGNORE_MS
            closeLink(linkId, "allowlist")
            return
        }
        when (val d = registry.onHello(linkId, nodeId)) {
            LinkRegistry.Decision.Keep -> recentPeers.record(link.mac, hex)
            is LinkRegistry.Decision.CloseThis -> {
                log("duplicate-link: closing $linkId (${d.reason})")
                backoff.blockUntil(link.mac, now() + BleConstants.DUPLICATE_IGNORE_MS)
                closeLink(linkId, "duplicate")
            }
            is LinkRegistry.Decision.CloseOther -> {
                log("duplicate-link: closing ${d.otherLinkId} (keeping $linkId)")
                registry.get(d.otherLinkId)?.let { backoff.blockUntil(it.mac, now() + BleConstants.DUPLICATE_IGNORE_MS) }
                closeLink(d.otherLinkId, "duplicate")
            }
        }
    }

    private fun closeLink(linkId: String, reason: String) {
        val mac = linkId.substring(2)
        if (linkId.startsWith("c:")) central[mac]?.let { teardownCentral(it, reason, backoffFailure = false) }
        else peripheral[mac]?.let { teardownPeripheral(it, reason) }
    }

    // ================================================================== housekeeping tick

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            try { tick() } catch (e: Exception) { log("BLE tick error: $e") }
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        val t = now()
        for (c in central.values.toList()) {
            c.queue.tick()
            val abort = LinkHealth.setupAbortReason(c.state, t - c.stateSince, t - c.createdAt, c.queue.consecutiveTimeouts)
            if (abort != null) { teardownCentral(c, abort, backoffFailure = true); continue }
            if (c.queue.consecutiveTimeouts >= 3) { teardownCentral(c, "3 consecutive GATT timeouts", backoffFailure = true); continue }
            if (c.state == SetupPhase.UP) {
                val dead = LinkHealth.livenessAbortReason(c.lastRxAt, t)
                if (dead != null) teardownCentral(c, dead, backoffFailure = true, refreshCache = true)
            }
        }
        for (p in peripheral.values.toList()) {
            if (!p.up && t - p.connectedAt > BleConstants.PERIPHERAL_SUBSCRIBE_TIMEOUT_MS) {
                teardownPeripheral(p, "central never subscribed"); continue
            }
            p.queue.tick()
            if (p.queue.consecutiveTimeouts >= 3) { teardownPeripheral(p, "3 consecutive notify timeouts"); continue }
            if (p.up) {
                val dead = LinkHealth.livenessAbortReason(p.lastRxAt, t)
                if (dead != null) teardownPeripheral(p, dead)
            }
        }
        if (t - lastHousekeeping >= HOUSEKEEPING_MS) {
            lastHousekeeping = t
            backoff.prune(t)
            ignored.entries.removeAll { it.value <= t }
            if (seenAt.size > 64) seenAt.clear()
            if (serviceReady && !advertising) startAdvertising()
        }
        driveScan(t)
        updateDiag()
    }

    // ================================================================== scanning (central role)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val hint = nodeHint(result)
            handler.post { maybeConnect(dev, hint) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            val items = results.mapNotNull { r -> r.device?.let { it to nodeHint(r) } }
            handler.post { for ((d, h) in items) maybeConnect(d, h) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                log("BLE: scan failed code=$errorCode")
                if (errorCode == ALREADY_STARTED_SCAN) { scanning = true; return@post }
                scheduleScanRetry(10_000)
            }
        }
    }

    private fun scheduleScanRetry(delayMs: Long) { scanNotBefore = now() + delayMs }

    /** Duty cycle: end the scan window when its time is up, start the next one when the idle time is over. */
    private fun driveScan(t: Long) {
        val mode = duty()
        if (scanning) {
            val window = mode.scanMs ?: return
            if (t - scanStartedAt >= window) {
                stopScan()
                scanNotBefore = t + mode.idleMs
            }
        } else if (t >= scanNotBefore || mode.scanMs == null) {
            if (mode.scanMs == null) scanNotBefore = 0
            startScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!running || scanning) return
        if (!BlePermissions.hasScan(ctx)) { log("BLE: no scan permission"); scheduleScanRetry(10_000); return }
        val scanner = btManager?.adapter?.bluetoothLeScanner ?: run { log("BLE: no scanner"); scheduleScanRetry(10_000); return }
        val t = now()
        if (!scanThrottle.tryAcquire(t)) {
            val wait = scanThrottle.waitMs(t) + 250
            log("BLE: scan start throttled (5 per 30s); retrying in ${wait}ms")
            scheduleScanRetry(wait)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            scanStartedAt = t
            log("BLE: scan started (${duty().label})")
        } catch (e: SecurityException) {
            log("BLE: scan start denied: $e")
            scheduleScanRetry(10_000)
        } catch (e: Exception) {
            log("BLE: scan start error: $e")
            scheduleScanRetry(10_000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        if (!BlePermissions.hasScan(ctx)) return
        try { btManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: Exception) { log("BLE: stopScan: $e") }
    }

    /** The advertiser's node id from the scan response ([BleConstants.MANUFACTURER_ID]), or null if absent. */
    private fun nodeHint(result: ScanResult): ByteArray? =
        result.scanRecord?.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID)?.takeIf { it.size == NODE_ID_BYTES }

    private fun maybeConnect(device: BluetoothDevice, hint: ByteArray? = null) {
        if (!running) return
        val mac = device.address ?: return
        if (hint != null && !acceptHint(hint)) return
        val hintHex = hint?.let { Hex.encode(it) }
        if (recentPeers.shouldDefer(now(), preferUntil, mac, hintHex) { registry.isNodeConnected(it) }) return
        if (connectingMac != null) return // one connection attempt at a time
        if (central.containsKey(mac)) return
        if (central.size >= BleConstants.MAX_CENTRAL_LINKS) return
        if (registry.total() + pendingCount() >= BleConstants.MAX_TOTAL_LINKS) return
        if ((ignored[mac] ?: 0L) > now()) return
        if (!registry.shouldConnect(mac)) return
        if (peripheral.containsKey(mac)) return
        val t = now()
        if (!backoff.canAttempt(mac, t)) return
        connectTo(device)
    }

    /**
     * Pre-connection filter on the advertiser's node id. Advertising addresses are resolvable private
     * addresses that change whenever advertising restarts, so MAC-based checks alone cannot recognise a peer
     * we are already linked to. Returns false to skip this advertiser.
     */
    private fun acceptHint(hint: ByteArray): Boolean {
        val hex = Hex.encode(hint)
        val t = now()
        if (hint.contentEquals(myNodeId)) return false
        // [first, last]: a gap of more than 10 s means the peer went away and came back, so the tie-break starts over.
        val seen = seenAt.getOrPut(hex) { longArrayOf(t, t) }
        if (t - seen[1] > HINT_GAP_MS) seen[0] = t
        seen[1] = t
        if (registry.isNodeConnected(hex)) return false
        if ((ignored["n:$hex"] ?: 0L) > t) return false
        if (!Allowlist.matches(allowlist(), hex)) return false
        // Both sides see each other at about the same time. The lower node id (the SPEC section 2 central) connects
        // at once; the higher one waits a moment so a duplicate link is the exception rather than the rule.
        if (Hex.encode(myNodeId) > hex && t - seen[0] < HIGHER_ID_CONNECT_DELAY_MS) return false
        return true
    }

    private fun pendingCount(): Int = central.values.count { it.state != SetupPhase.UP } + peripheral.values.count { !it.up }

    // ================================================================== central role connections

    private inner class CentralConn(val mac: String, val gatt: BluetoothGatt) {
        val linkId = "c:$mac"
        var state = SetupPhase.CONNECTING
            set(v) { field = v; stateSince = now() }
        val createdAt = now()
        var stateSince = createdAt
        var mtu = BleConstants.DEFAULT_MTU
        var characteristic: BluetoothGattCharacteristic? = null
        /** Last time any frame arrived on this link (liveness watchdog); set again when the link comes up. */
        var lastRxAt = createdAt
        val queue = GattOpQueue(now = ::now, log = { m -> log("[$linkId] $m") })
        /** Notifications that raced ahead of the CCCD write callback; replayed right after linkUp. */
        val earlyFrames = ArrayList<ByteArray>()
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        val mac = device.address
        if (!BlePermissions.hasConnect(ctx)) return
        log("BLE: connecting to $mac (central ${central.size + 1}/${BleConstants.MAX_CENTRAL_LINKS})")
        val gatt = try {
            device.connectGatt(ctx, false, centralCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("BLE: connectGatt denied: $e"); null
        } catch (e: Exception) {
            log("BLE: connectGatt error: $e"); null
        }
        if (gatt == null) { backoff.onFailure(mac, now()); return }
        central[mac] = CentralConn(mac, gatt)
        connectingMac = mac
        updateDiag()
    }

    private val centralCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post { onCentralConnectionState(gatt, status, newState) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { onCentralMtu(gatt, mtu, status) }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post { onCentralServices(gatt, status) }
        }

        // API 31+: the peer's GATT database changed (e.g. its app process restarted and re-added the service).
        // On older releases the framework never calls this, so the liveness watchdog is the only net there.
        override fun onServiceChanged(gatt: BluetoothGatt) {
            handler.post { onCentralServiceChanged(gatt) }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post { onCentralDescriptorWrite(gatt, status) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post { onCentralWriteDone(gatt, status) }
        }

        // API 33+: the framework calls this overload (its default would call the deprecated one).
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            handler.post { onCentralFrame(gatt, copy) }
        }

        @Deprecated("Deprecated in API 33; used below it")
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return // delivered via the 3-arg overload
            val copy = characteristic.value?.copyOf() ?: return
            handler.post { onCentralFrame(gatt, copy) }
        }
    }

    private fun connFor(gatt: BluetoothGatt): CentralConn? {
        val c = central[gatt.device.address]
        return if (c != null && c.gatt === gatt) c else null
    }

    private fun onCentralConnectionState(gatt: BluetoothGatt, status: Int, newState: Int) {
        val c = connFor(gatt)
        if (c == null) {
            // A gatt we already tore down (or never tracked): make sure it is released.
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_CONNECTED) {
                safeClose(gatt)
            }
            return
        }
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            if (c.state != SetupPhase.CONNECTING) return
            c.state = SetupPhase.MTU
            c.queue.enqueue(MtuOp(gatt))
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            teardownCentral(c, "connection state status=$status newState=$newState", backoffFailure = c.state != SetupPhase.UP)
        }
    }

    private fun onCentralMtu(gatt: BluetoothGatt, mtu: Int, status: Int) {
        val c = connFor(gatt) ?: return
        c.queue.onComplete()
        if (c.state != SetupPhase.MTU) return
        c.mtu = mtu
        log("BLE: ${c.linkId} mtu=$mtu status=$status")
        if (status != BluetoothGatt.GATT_SUCCESS || mtu < BleConstants.MIN_MTU) {
            log("BLE: ${c.linkId} negotiated mtu=$mtu status=$status (< ${BleConstants.MIN_MTU}); disconnecting")
            teardownCentral(c, "mtu too small", backoffFailure = true)
            return
        }
        c.state = SetupPhase.DISCOVERING
        c.queue.enqueue(DiscoverOp(gatt))
    }

    private fun onCentralServices(gatt: BluetoothGatt, status: Int) {
        val c = connFor(gatt) ?: return
        c.queue.onComplete()
        if (c.state != SetupPhase.DISCOVERING) return
        val ch = if (status == BluetoothGatt.GATT_SUCCESS) {
            gatt.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.CHAR_UUID)
        } else null
        if (ch == null) {
            val retries = discoveryRetries[c.mac] ?: 0
            val retryIn = LinkHealth.discoveryRetryDelayMs(retries)
            if (retryIn != null) {
                discoveryRetries[c.mac] = retries + 1
                log("BLE: ${c.linkId} relay service missing (status=$status); refreshing GATT cache, retrying once in ${retryIn}ms")
                teardownCentral(c, "relay service missing (status=$status)", backoffFailure = false, refreshCache = true, reconnectAfterMs = retryIn, skipBackoff = true)
            } else {
                discoveryRetries.remove(c.mac)
                log("BLE: ${c.linkId} relay service still missing after retry; giving up")
                teardownCentral(c, "relay service missing (status=$status)", backoffFailure = true, refreshCache = true)
            }
            return
        }
        c.characteristic = ch
        c.state = SetupPhase.SUBSCRIBING
        c.queue.enqueue(SubscribeOp(gatt, ch))
    }

    private fun onCentralDescriptorWrite(gatt: BluetoothGatt, status: Int) {
        val c = connFor(gatt) ?: return
        c.queue.onComplete()
        if (c.state != SetupPhase.SUBSCRIBING) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            teardownCentral(c, "CCCD write failed status=$status", backoffFailure = true)
            return
        }
        c.state = SetupPhase.UP
        c.lastRxAt = now()
        discoveryRetries.remove(c.mac)
        if (connectingMac == c.mac) connectingMac = null
        backoff.onSuccess(c.mac)
        registry.add(c.linkId, c.mac, LinkRole.CENTRAL)
        updateDiag()
        events.onLinkUp(c.linkId)
        for (f in c.earlyFrames) events.onFrame(c.linkId, f)
        c.earlyFrames.clear()
    }

    private fun onCentralWriteDone(gatt: BluetoothGatt, status: Int) {
        val c = connFor(gatt) ?: return
        if (status != BluetoothGatt.GATT_SUCCESS) log("[${c.linkId}] write completed with status=$status")
        c.queue.onComplete()
    }

    private fun onCentralFrame(gatt: BluetoothGatt, value: ByteArray) {
        val c = connFor(gatt) ?: return
        c.lastRxAt = now()
        when (c.state) {
            SetupPhase.UP -> events.onFrame(c.linkId, value)
            SetupPhase.SUBSCRIBING -> if (c.earlyFrames.size < 64) c.earlyFrames.add(value)
            else -> {}
        }
    }

    /** The peer's GATT database changed under an open connection: our cached handles are stale, so drop the link and re-connect. */
    private fun onCentralServiceChanged(gatt: BluetoothGatt) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val c = connFor(gatt)
        if (c == null) { log("BLE: service changed on untracked ${gatt.device.address}; ignoring"); return }
        log("BLE: service changed on ${c.linkId} (peer restarted?); closing, refreshing GATT cache, reconnecting in ${LinkHealth.SERVICE_CHANGED_RECONNECT_MS}ms")
        teardownCentral(c, "service changed", backoffFailure = false, refreshCache = true, reconnectAfterMs = LinkHealth.SERVICE_CHANGED_RECONNECT_MS, skipBackoff = true)
    }

    /** One scheduled re-connect to [device] (service changed / discovery retry); skipped if anything else got there first. */
    private fun reconnectCentral(device: BluetoothDevice) {
        val mac = device.address ?: return
        if (!running) return
        val why = when {
            central.containsKey(mac) -> "already connecting"
            connectingMac != null -> "another connect in progress"
            central.size >= BleConstants.MAX_CENTRAL_LINKS || registry.total() + pendingCount() >= BleConstants.MAX_TOTAL_LINKS -> "link cap"
            registry.hasLinkToMac(mac) || peripheral.containsKey(mac) -> "already linked"
            else -> null
        }
        if (why != null) { log("BLE: scheduled reconnect to $mac skipped ($why); scan will find it"); return }
        log("BLE: scheduled reconnect to $mac")
        connectTo(device)
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        // BluetoothGatt.refresh() is hidden API: it drops the stack's cached service table so the next discovery is fresh.
        try {
            val ok = gatt.javaClass.getMethod("refresh").invoke(gatt) as? Boolean
            log("BLE: gatt cache refresh on ${gatt.device.address} -> $ok")
        } catch (e: Throwable) {
            log("BLE: gatt cache refresh unavailable: $e")
        }
    }

    /**
     * Remove a central connection and release its BluetoothGatt. [backoffFailure] applies the 2 s..60 s
     * reconnect backoff for that MAC (always used for connect/setup failures such as status 133).
     * [skipBackoff] suppresses even the backoff an established link normally earns; [refreshCache] drops the
     * GATT cache first; [reconnectAfterMs] schedules one fresh connect to the same device.
     */
    private fun teardownCentral(
        c: CentralConn, reason: String, backoffFailure: Boolean, immediateClose: Boolean = false,
        refreshCache: Boolean = false, reconnectAfterMs: Long? = null, skipBackoff: Boolean = false,
    ) {
        if (central[c.mac] !== c) return
        central.remove(c.mac)
        val wasUp = c.state == SetupPhase.UP
        c.queue.clear()
        if (connectingMac == c.mac) connectingMac = null
        if (wasUp) {
            val node = registry.get(c.linkId)?.nodeIdHex
            registry.remove(c.linkId)
            events.onLinkDown(c.linkId)
            if (running && node != null && recentPeers.all().any { it.nodeIdHex == node }) preferUntil = now() + RecentPeers.PREFER_WINDOW_MS
        }
        val delay = if (!skipBackoff && (backoffFailure || wasUp)) backoff.onFailure(c.mac, now()) else 0L
        log("BLE: closing ${c.linkId} ($reason)" + if (delay > 0) "; retry in ${delay}ms" else "")
        if (refreshCache) refreshGattCache(c.gatt)
        disconnectAndClose(c.gatt, immediateClose)
        if (reconnectAfterMs != null && running) {
            val device = c.gatt.device
            handler.postDelayed({ reconnectCentral(device) }, reconnectAfterMs)
        }
        updateDiag()
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAndClose(gatt: BluetoothGatt, immediate: Boolean) {
        if (!BlePermissions.hasConnect(ctx)) return
        try {
            if (immediate) { gatt.close(); return }
            gatt.disconnect()
        } catch (e: Exception) { log("BLE: gatt.disconnect: $e") }
        // Close on the disconnect callback when it comes (see onCentralConnectionState), or after 2 s regardless,
        // so a BluetoothGatt is never leaked even if the callback is lost.
        handler.postDelayed({ safeClose(gatt) }, 2_000)
    }

    @SuppressLint("MissingPermission")
    private fun safeClose(gatt: BluetoothGatt) {
        if (!BlePermissions.hasConnect(ctx)) return
        try { gatt.close() } catch (e: Exception) { log("BLE: gatt.close: $e") }
    }

    // ---- central GATT operations (serialised through CentralConn.queue)

    private inner class MtuOp(private val gatt: BluetoothGatt) : GattOp {
        override val label = "requestMtu"

        @SuppressLint("MissingPermission")
        override fun start(): StartResult {
            if (!BlePermissions.hasConnect(ctx)) return StartResult.FAILED
            return try {
                if (gatt.requestMtu(BleConstants.REQUESTED_MTU)) StartResult.STARTED else StartResult.FAILED
            } catch (e: SecurityException) { StartResult.FAILED }
        }
    }

    private inner class DiscoverOp(private val gatt: BluetoothGatt) : GattOp {
        override val label = "discoverServices"

        @SuppressLint("MissingPermission")
        override fun start(): StartResult {
            if (!BlePermissions.hasConnect(ctx)) return StartResult.FAILED
            return try {
                if (gatt.discoverServices()) StartResult.STARTED else StartResult.FAILED
            } catch (e: SecurityException) { StartResult.FAILED }
        }
    }

    private inner class SubscribeOp(private val gatt: BluetoothGatt, private val ch: BluetoothGattCharacteristic) : GattOp {
        override val label = "enableNotifications"

        @SuppressLint("MissingPermission")
        override fun start(): StartResult {
            if (!BlePermissions.hasConnect(ctx)) return StartResult.FAILED
            return try {
                if (!gatt.setCharacteristicNotification(ch, true)) return StartResult.FAILED
                val cccd = ch.getDescriptor(BleConstants.CCCD_UUID) ?: return StartResult.FAILED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val r = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    when (r) {
                        BluetoothStatusCodes.SUCCESS -> StartResult.STARTED
                        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> StartResult.RETRY
                        else -> StartResult.FAILED
                    }
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        if (gatt.writeDescriptor(cccd)) StartResult.STARTED else StartResult.RETRY
                    }
                }
            } catch (e: SecurityException) { StartResult.FAILED }
        }
    }

    private inner class WriteOp(
        private val gatt: BluetoothGatt,
        private val ch: BluetoothGattCharacteristic,
        private val data: ByteArray,
    ) : GattOp {
        override val label = "write(${data.size}B)"

        @SuppressLint("MissingPermission")
        override fun start(): StartResult {
            if (!BlePermissions.hasConnect(ctx)) return StartResult.FAILED
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    when (gatt.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)) {
                        BluetoothStatusCodes.SUCCESS -> StartResult.STARTED
                        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> StartResult.RETRY
                        else -> StartResult.FAILED
                    }
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        ch.value = data
                        if (gatt.writeCharacteristic(ch)) StartResult.STARTED else StartResult.RETRY
                    }
                }
            } catch (e: SecurityException) { StartResult.FAILED }
        }
    }

    // ================================================================== peripheral role (GATT server)

    private inner class PeriphConn(val device: BluetoothDevice) {
        val mac: String = device.address
        val linkId = "p:$mac"
        var mtu = BleConstants.DEFAULT_MTU
        var up = false
        val connectedAt = now()
        /** Last time any frame arrived on this link (liveness watchdog); set again when the link comes up. */
        var lastRxAt = connectedAt
        val queue = GattOpQueue(now = ::now, log = { m -> log("[$linkId] $m") })
        val earlyFrames = ArrayList<ByteArray>()
    }

    @SuppressLint("MissingPermission")
    private fun openServer() {
        if (!BlePermissions.hasConnect(ctx)) return
        val mgr = btManager ?: return
        try {
            val srv = mgr.openGattServer(ctx, serverCallback)
            if (srv == null) { log("BLE: openGattServer returned null"); return }
            val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val ch = BluetoothGattCharacteristic(
                BleConstants.CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            ch.addDescriptor(
                BluetoothGattDescriptor(
                    BleConstants.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            service.addCharacteristic(ch)
            server = srv
            serverChar = ch
            if (!srv.addService(service)) {
                log("BLE: addService failed to start")
                closeServer()
            }
        } catch (e: SecurityException) {
            log("BLE: GATT server denied: $e")
        } catch (e: Exception) {
            log("BLE: GATT server error: $e")
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeServer() {
        val srv = server ?: return
        server = null
        serverChar = null
        if (!BlePermissions.hasConnect(ctx)) return
        try { srv.close() } catch (e: Exception) { log("BLE: server.close: $e") }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log("BLE: GATT service added")
                    serviceReady = true
                    startAdvertising()
                } else {
                    log("BLE: onServiceAdded failed status=$status")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post { onServerConnectionState(device, status, newState) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                val p = peripheral[device.address]
                if (p != null) { p.mtu = mtu; log("BLE: ${p.linkId} mtu=$mtu") }
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            handler.post { respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null) }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            handler.post {
                val subscribed = peripheral[device.address]?.up == true
                val value = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, if (offset <= value.size) value.copyOfRange(offset, value.size) else null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val copy = value?.copyOf()
            handler.post { onServerDescriptorWrite(device, requestId, descriptor, responseNeeded, offset, copy) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val copy = value?.copyOf()
            handler.post {
                var status = BluetoothGatt.GATT_SUCCESS
                if (preparedWrite || offset != 0) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                } else if (characteristic.uuid != BleConstants.CHAR_UUID || copy == null) {
                    status = BluetoothGatt.GATT_FAILURE
                } else {
                    val p = peripheral[device.address]
                    if (p != null) {
                        p.lastRxAt = now()
                        if (p.up) events.onFrame(p.linkId, copy)
                        else if (p.earlyFrames.size < 64) p.earlyFrames.add(copy)
                    }
                }
                if (responseNeeded) respond(device, requestId, status, offset, null)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            handler.post { respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post {
                val p = peripheral[device.address] ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) log("[${p.linkId}] notification completed with status=$status")
                p.queue.onComplete()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        val srv = server ?: return
        if (!BlePermissions.hasConnect(ctx)) return
        try { srv.sendResponse(device, requestId, status, offset, value) } catch (e: Exception) { log("BLE: sendResponse: $e") }
    }

    private fun onServerConnectionState(device: BluetoothDevice, status: Int, newState: Int) {
        val mac = device.address
        if (newState == BluetoothProfile.STATE_CONNECTED) {
            if (!running) return
            val tooMany = registry.total() + pendingCount() >= BleConstants.MAX_TOTAL_LINKS
            if (tooMany || (ignored[mac] ?: 0L) > now()) {
                log("BLE: refusing incoming connection from $mac (${if (tooMany) "link cap" else "ignored"})")
                cancelServerConnection(device)
                return
            }
            peripheral.getOrPut(mac) { PeriphConn(device) }
            updateDiag()
            log("BLE: incoming connection from $mac")
        } else {
            val p = peripheral[mac]
            if (p != null) teardownPeripheral(p, "disconnected status=$status")
        }
        scheduleReadvertise()
    }

    private fun onServerDescriptorWrite(
        device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
        responseNeeded: Boolean, offset: Int, value: ByteArray?,
    ) {
        val p = peripheral[device.address]
        var status = BluetoothGatt.GATT_SUCCESS
        var becameUp = false
        var wentDown = false
        var closeForMtu = false
        if (descriptor.uuid != BleConstants.CCCD_UUID || p == null || value == null || offset != 0) {
            status = BluetoothGatt.GATT_FAILURE
        } else if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
            if (!p.up) {
                if (p.mtu < BleConstants.MIN_MTU) closeForMtu = true
                else { p.up = true; becameUp = true; p.lastRxAt = now() }
            }
        } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
            wentDown = p.up
        } else {
            status = BluetoothGatt.GATT_FAILURE
        }
        // Respond first, then announce the link, so the central has processed the CCCD response (and set
        // up its own side) before our first notification (HELLO) can reach it.
        if (responseNeeded) respond(device, requestId, status, offset, null)
        if (becameUp && p != null) {
            registry.add(p.linkId, p.mac, LinkRole.PERIPHERAL)
            updateDiag()
            events.onLinkUp(p.linkId)
            for (f in p.earlyFrames) events.onFrame(p.linkId, f)
            p.earlyFrames.clear()
        }
        if (wentDown && p != null) teardownPeripheral(p, "central disabled notifications")
        if (closeForMtu && p != null) {
            log("BLE: ${p.linkId} mtu=${p.mtu} < ${BleConstants.MIN_MTU}; closing")
            teardownPeripheral(p, "mtu too small")
        }
    }

    private fun teardownPeripheral(p: PeriphConn, reason: String) {
        if (peripheral[p.mac] !== p) return
        peripheral.remove(p.mac)
        p.queue.clear()
        if (p.up) {
            val node = registry.get(p.linkId)?.nodeIdHex
            registry.remove(p.linkId)
            events.onLinkDown(p.linkId)
            if (running && node != null && recentPeers.all().any { it.nodeIdHex == node }) preferUntil = now() + RecentPeers.PREFER_WINDOW_MS
        }
        log("BLE: closing ${p.linkId} ($reason)")
        cancelServerConnection(p.device)
        updateDiag()
    }

    @SuppressLint("MissingPermission")
    private fun cancelServerConnection(device: BluetoothDevice) {
        val srv = server ?: return
        if (!BlePermissions.hasConnect(ctx)) return
        try { srv.cancelConnection(device) } catch (e: Exception) { log("BLE: cancelConnection: $e") }
    }

    private inner class NotifyOp(
        private val srv: BluetoothGattServer,
        private val device: BluetoothDevice,
        private val ch: BluetoothGattCharacteristic,
        private val data: ByteArray,
    ) : GattOp {
        override val label = "notify(${data.size}B)"

        @SuppressLint("MissingPermission")
        override fun start(): StartResult {
            if (!BlePermissions.hasConnect(ctx)) return StartResult.FAILED
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    when (srv.notifyCharacteristicChanged(device, ch, false, data)) {
                        BluetoothStatusCodes.SUCCESS -> StartResult.STARTED
                        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> StartResult.RETRY
                        else -> StartResult.FAILED
                    }
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        ch.value = data
                        if (srv.notifyCharacteristicChanged(device, ch, false)) StartResult.STARTED else StartResult.RETRY
                    }
                }
            } catch (e: SecurityException) { StartResult.FAILED }
        }
    }

    // ================================================================== advertising

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            handler.post { advertising = true; log("BLE: advertising started") }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) { advertising = true; return@post }
                advertising = false
                log("BLE: advertising failed code=$errorCode; retrying in 5s")
                handler.postDelayed({ if (running && serviceReady && !advertising) startAdvertising() }, 5_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (!running || !serviceReady) return
        if (!BlePermissions.hasAdvertise(ctx)) { log("BLE: no advertise permission"); return }
        val adv = btManager?.adapter?.bluetoothLeAdvertiser ?: run { log("BLE: no advertiser (peripheral role unsupported)"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        // The 31-byte advertisement is full with the 128-bit service UUID; the node id hint rides in the scan response.
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(BleConstants.MANUFACTURER_ID, myNodeId)
            .build()
        try {
            adv.startAdvertising(settings, data, scanResponse, advCallback)
        } catch (e: SecurityException) {
            log("BLE: advertise denied: $e")
        } catch (e: Exception) {
            log("BLE: advertise error: $e")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val wasAdvertising = advertising
        advertising = false
        if (!wasAdvertising || !BlePermissions.hasAdvertise(ctx)) return
        try { btManager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback) } catch (e: Exception) { log("BLE: stopAdvertising: $e") }
    }

    private val readvertiseRunnable = Runnable {
        if (running && serviceReady) {
            // Many chipsets stop connectable advertising when a central connects; restart so others can still find us.
            stopAdvertising()
            startAdvertising()
        }
    }

    private fun scheduleReadvertise() {
        handler.removeCallbacks(readvertiseRunnable)
        handler.postDelayed(readvertiseRunnable, 500)
    }

    private companion object {
        const val TICK_MS = 500L
        const val HOUSEKEEPING_MS = 15_000L
        const val NODE_ID_BYTES = 8
        const val HIGHER_ID_CONNECT_DELAY_MS = 3_000L
        const val HINT_GAP_MS = 10_000L
        const val ALREADY_STARTED_SCAN = ScanCallback.SCAN_FAILED_ALREADY_STARTED
    }
}