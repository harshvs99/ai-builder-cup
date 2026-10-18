import Foundation
@preconcurrency import CoreBluetooth
import RelayCore
import RelayKit

/// Relay's own BLE GATT mesh transport on CoreBluetooth (SPEC section 2); the iOS counterpart of Android's
/// `BleTransport.kt`, which it mirrors state machine for state machine. Every node is both a GATT server
/// (`CBPeripheralManager`, advertising, `p:<id>` links) and a GATT client (`CBCentralManager`, scanning,
/// `c:<id>` links). `<id>` is the `CBPeripheral` / `CBCentral` identifier UUID string (iOS exposes no MACs).
///
/// Threading (why `@unchecked Sendable` is sound): ALL mutable state in this class and its extensions is confined to
/// `queue`, one dedicated serial `DispatchQueue`. Both managers are created with that queue, so every CoreBluetooth
/// delegate callback already runs on it; the public API (`start`, `stop`, `send`, `onPeerHello`, ...) only hops onto
/// it with `queue.async`. The two exceptions are `info()` and `setStateObserver`, which read/write small values under
/// `lock`. Never touch a stored property from another thread.
///
/// Files: this one holds lifecycle, send, housekeeping tick, scanning duty cycle and advertising;
/// `+Central.swift` the client role (connect, setup, write); `+Peripheral.swift` the server role (subscribe, notify).
///
/// iOS differences that shape the code (see docs/design/ios-build-plan.md "Binding constraints"):
///  - The manufacturer-data node-id hint cannot be set when advertising, so iPhones are found by service UUID only.
///    The hint of an Android advertiser is read when iOS delivers it (foreground); otherwise the HELLO-based
///    duplicate rule is the safety net.
///  - A peripheral cannot disconnect a central. Closing a `p:` link means: report it down, forget it, ignore that
///    central for a while. The remote's own duplicate rule closes its side.
///  - Background: scanning must name the service UUID (it does), and iOS coalesces/slows it. `DutyCycle.normal`
///    (= "not foreground") therefore keeps the scan running (iOS throttles it itself) instead of cycling 3 s / 12 s,
///    which would also drop the scan from State Preservation; only the low-battery mode cycles.
///  - State Preservation and Restoration is best effort: both managers carry restore identifiers and adopt what
///    `willRestoreState` hands back when the transport next starts.
final class CoreBluetoothTransport: NSObject, MeshTransport, @unchecked Sendable {
    // ---- constants
    static let centralRestoreId = "org.relay.ios.central"
    static let peripheralRestoreId = "org.relay.ios.peripheral"
    static let tickMs = 500
    static let housekeepingMs: Int64 = 15_000
    static let nodeIdBytes = 8
    static let higherIdConnectDelayMs: Int64 = 3_000
    static let hintGapMs: Int64 = 10_000

    let serviceUUID = CBUUID(string: BleConstants.serviceUuid)
    let charUUID = CBUUID(string: BleConstants.charUuid)

    // ---- the serial queue and everything confined to it
    let queue = DispatchQueue(label: "org.relay.ble", qos: .userInitiated)
    var events: (any MeshTransportEvents)?
    var env: MeshTransportEnvironment?
    var registry = LinkRegistry(myId: [])
    let backoff = Backoff()

    var wantRunning = false
    var running = false
    var shutDown = false
    var centralManager: CBCentralManager?
    var peripheralManager: CBPeripheralManager?
    var tickTimer: DispatchSourceTimer?

    /// `id` (peripheral/central identifier) or `n:<node id hex>` -> ignore-until (monotonic ms).
    var ignored: [String: Int64] = [:]
    /// node id hex -> (first, last) scan-hint times, for the connect tie-break.
    var seenAt: [String: (first: Int64, last: Int64)] = [:]
    var central: [String: CentralLink] = [:]
    var peripheral: [String: PeripheralLink] = [:]
    var connectingId: String?
    /// peripheral id -> discovery retries already made (reset when the link comes up or we give up); see
    /// `LinkHealth.discoveryRetryDelayMs`.
    var discoveryRetries: [String: Int] = [:]

    var serverService: CBMutableService?
    var serverChar: CBMutableCharacteristic?
    var serviceReady = false
    var advertising = false

    var scanning = false
    var scanStartedAt: Int64 = 0
    var scanNotBefore: Int64 = 0
    var scanDuplicates = false
    var preferUntil: Int64 = 0
    var lastHousekeeping: Int64 = 0

    var restoredPeripherals: [CBPeripheral] = []
    var restoredService: CBMutableService?

    // ---- the lock-guarded slice read from other threads
    private struct Diag {
        var running = false, scanning = false, advertising = false
        var central = 0, peripheral = 0
        var connecting: String?
    }
    private let lock = NSLock()
    private var diag = Diag()
    private var infoDuty: (@Sendable () -> DutyCycle.Mode)?
    private var stateObserver: (@Sendable (BluetoothRadioState) -> Void)?
    private var lastRadioState: BluetoothRadioState = .unknown
    /// The same `LinkRegistry` instance as `registry`, reachable from any thread (the registry is internally locked,
    /// but the `registry` property itself is queue-confined and replaced in `attach`). Serves `rssiFor`.
    private var rssiRegistry: LinkRegistry?

    override init() { super.init() }

    func now() -> Int64 { monotonicMs() }
    func log(_ msg: String) { events?.onLog(msg) }

    // ================================================================== MeshTransport

    func attach(events: any MeshTransportEvents, environment: MeshTransportEnvironment) {
        lock.lock(); infoDuty = environment.dutyMode; lock.unlock()
        queue.async {
            self.events = events
            self.env = environment
            let registry = LinkRegistry(myId: environment.myNodeId)
            self.registry = registry
            self.lock.lock(); self.rssiRegistry = registry; self.lock.unlock()
        }
    }

    /// Last scan RSSI (dBm) of the link [linkId], or nil when unknown (always nil for peripheral links). Any thread;
    /// feeds `rssi_dbm` of a LOC_HINT (SPEC 5.5).
    func rssiFor(linkId: String) -> Int? {
        lock.lock(); let r = rssiRegistry; lock.unlock()
        return r?.rssiFor(linkId)
    }

    /// Called (on the transport queue) with the radio state each time CoreBluetooth reports it, plus once after
    /// registration with the last known state. `AppModel` forwards it to `MeshEngine.setBluetoothOn`.
    func setStateObserver(_ observer: @escaping @Sendable (BluetoothRadioState) -> Void) {
        lock.lock(); stateObserver = observer; let last = lastRadioState; lock.unlock()
        if last != .unknown { observer(last) }
    }

    /// Creating the managers is what triggers the system Bluetooth prompt, so this is the moment the permission is
    /// asked for. Idempotent: waits for `poweredOn` and starts by itself.
    func start() {
        queue.async {
            if self.shutDown { return }
            self.wantRunning = true
            self.ensureManagers()
            self.reconcile()
        }
    }

    /// Bluetooth went off (the engine says so): tear everything down. `start()` brings it back.
    func stop() {
        queue.async {
            self.wantRunning = false
            self.stopInternal("stop requested")
        }
    }

    func shutdown() {
        queue.async {
            self.wantRunning = false
            self.shutDown = true
            self.stopInternal("shutdown")
            self.centralManager?.delegate = nil
            self.peripheralManager?.delegate = nil
            self.centralManager = nil
            self.peripheralManager = nil
        }
    }

    func info() -> MeshTransportInfo {
        lock.lock(); let d = diag; let duty = infoDuty; lock.unlock()
        return MeshTransportInfo(running: d.running, scanning: d.scanning, advertising: d.advertising,
                                 centralLinks: d.central, peripheralLinks: d.peripheral, connecting: d.connecting,
                                 dutyMode: duty?() ?? .normal)
    }

    func updateDiag() {
        lock.lock()
        diag = Diag(running: running, scanning: scanning, advertising: advertising, central: central.count,
                    peripheral: peripheral.count, connecting: connectingId)
        lock.unlock()
    }

    // ================================================================== lifecycle (queue)

    func ensureManagers() {
        if centralManager == nil {
            centralManager = CBCentralManager(
                delegate: self, queue: queue,
                options: [CBCentralManagerOptionRestoreIdentifierKey: CoreBluetoothTransport.centralRestoreId,
                          CBCentralManagerOptionShowPowerAlertKey: true])
        }
        if peripheralManager == nil {
            peripheralManager = CBPeripheralManager(
                delegate: self, queue: queue,
                options: [CBPeripheralManagerOptionRestoreIdentifierKey: CoreBluetoothTransport.peripheralRestoreId])
        }
    }

    /// Brings the transport in line with `wantRunning` and the radio state. Called after every relevant event.
    func reconcile() {
        guard wantRunning, !shutDown else { return }
        guard let cm = centralManager, cm.state == .poweredOn else {
            if running { stopInternal("Bluetooth is not powered on") }
            return
        }
        if !running { startRunning() }
        bringUpPeripheralRole()
    }

    private func startRunning() {
        running = true
        log("BLE: starting transport")
        scanNotBefore = 0
        preferUntil = now() + RecentPeers.preferWindowMs
        adoptRestoredPeripherals()
        startScan()
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + .milliseconds(CoreBluetoothTransport.tickMs),
                   repeating: .milliseconds(CoreBluetoothTransport.tickMs))
        t.setEventHandler { [weak self] in self?.tickSafely() }
        t.resume()
        tickTimer = t
        updateDiag()
    }

    func stopInternal(_ reason: String) {
        if !running && central.isEmpty && peripheral.isEmpty && serverService == nil && tickTimer == nil { return }
        log("BLE: stopping transport (\(reason))")
        running = false
        tickTimer?.cancel()
        tickTimer = nil
        stopScan()
        stopAdvertising()
        for c in Array(central.values) { teardownCentral(c, "transport stopped", backoffFailure: false) }
        for p in Array(peripheral.values) { teardownPeripheral(p, "transport stopped") }
        if let pm = peripheralManager, pm.state == .poweredOn { pm.removeAllServices() }
        serverService = nil
        serverChar = nil
        serviceReady = false
        registry.clear()
        ignored.removeAll()
        seenAt.removeAll()
        discoveryRetries.removeAll()
        backoff.clear() // a restarted transport starts clean: stale per-peer delays would block the re-link
        scanNotBefore = 0
        connectingId = nil
        restoredPeripherals.removeAll()
        updateDiag()
    }

    // ================================================================== LinkSender

    func send(linkId: String, frame: [UInt8]) {
        queue.async { self.sendInternal(linkId, frame) }
    }

    private func sendInternal(_ linkId: String, _ frame: [UInt8]) {
        if !running { return }
        let id = LinkId.address(linkId)
        if LinkId.isCentral(linkId) {
            guard let c = central[id], c.phase == .up, let ch = c.characteristic else {
                log("send: no live link \(linkId); frame dropped"); return
            }
            if frame.count > c.mtu - BleConstants.attOverhead {
                log("send: frame \(frame.count)B exceeds mtu \(c.mtu)-3 on \(linkId); dropped"); return
            }
            c.queue.enqueue(writeOp(c, ch, frame))
        } else {
            guard let p = peripheral[id], p.up, serverChar != nil else {
                log("send: no live link \(linkId); frame dropped"); return
            }
            let mtu = p.central.maximumUpdateValueLength + BleConstants.attOverhead
            if frame.count > mtu - BleConstants.attOverhead {
                log("send: frame \(frame.count)B exceeds mtu \(mtu)-3 on \(linkId); dropped"); return
            }
            p.queue.enqueue(notifyOp(p, frame))
        }
    }

    // ================================================================== HELLO: allowlist and duplicate links

    func onPeerHello(linkId: String, nodeId: [UInt8]) {
        queue.async { self.handleHello(linkId, nodeId) }
    }

    private func handleHello(_ linkId: String, _ nodeId: [UInt8]) {
        guard running, let env, let link = registry.get(linkId) else { return }
        let hex = Hex.encode(nodeId)
        if !Allowlist.matches(env.allowlist(), nodeIdHex: hex) {
            log("allowlist: node \(hex) on \(linkId) not allowed; closing and ignoring \(link.mac) for \(BleConstants.allowlistIgnoreMs / 1000)s")
            ignored[link.mac] = now() + BleConstants.allowlistIgnoreMs
            ignored["n:" + hex] = now() + BleConstants.allowlistIgnoreMs
            closeLink(linkId, "allowlist")
            return
        }
        switch registry.onHello(linkId, peerNodeId: nodeId) {
        case .keep:
            env.recentPeers.record(mac: link.mac, nodeIdHex: hex)
        case .closeThis(let reason):
            log("duplicate-link: closing \(linkId) (\(reason))")
            backoff.blockUntil(link.mac, untilMs: now() + BleConstants.duplicateIgnoreMs)
            closeLink(linkId, "duplicate")
        case .closeOther(let otherLinkId):
            log("duplicate-link: closing \(otherLinkId) (keeping \(linkId))")
            if let other = registry.get(otherLinkId) {
                backoff.blockUntil(other.mac, untilMs: now() + BleConstants.duplicateIgnoreMs)
            }
            closeLink(otherLinkId, "duplicate")
        }
    }

    private func closeLink(_ linkId: String, _ reason: String) {
        let id = LinkId.address(linkId)
        if LinkId.isCentral(linkId) {
            if let c = central[id] { teardownCentral(c, reason, backoffFailure: false) }
        } else if let p = peripheral[id] {
            // We cannot disconnect a central on iOS; forget it and ignore it so a stray write does not revive the link.
            let until = now() + BleConstants.duplicateIgnoreMs
            ignored[id] = max(ignored[id] ?? 0, until)
            teardownPeripheral(p, reason)
        }
    }

    // ================================================================== housekeeping tick

    private func tickSafely() {
        if !running { return }
        tick()
    }

    private func tick() {
        let t = now()
        for c in Array(central.values) {
            c.queue.tick()
            let abort = LinkHealth.setupAbortReason(
                phase: c.phase, inPhaseMs: t - c.phaseSince, totalMs: t - c.createdAt,
                consecutiveOpTimeouts: c.queue.consecutiveTimeouts)
            if let abort { teardownCentral(c, abort, backoffFailure: true); continue }
            if c.queue.consecutiveTimeouts >= 3 { teardownCentral(c, "3 consecutive GATT timeouts", backoffFailure: true); continue }
            // Liveness watchdog: an established link that carried no frame for three resync periods is a zombie (a peer
            // whose process restarted without a disconnect reaching us). Close it; the backoff applies.
            if c.phase == .up, let dead = LinkHealth.livenessAbortReason(lastRxAt: c.lastRxAt, now: t) {
                teardownCentral(c, dead, backoffFailure: true)
            }
        }
        for p in Array(peripheral.values) {
            if !p.up && t - p.connectedAt > BleConstants.peripheralSubscribeTimeoutMs {
                teardownPeripheral(p, "central never subscribed"); continue
            }
            p.queue.tick()
            if p.queue.consecutiveTimeouts >= 3 { teardownPeripheral(p, "3 consecutive notify timeouts"); continue }
            if p.up, let dead = LinkHealth.livenessAbortReason(lastRxAt: p.lastRxAt, now: t) {
                // iOS cannot disconnect a central: forget the link and ignore it briefly (as for duplicates), so a
                // stray write does not revive it half-built. A live peer re-subscribes and gets a fresh link.
                ignored[p.id] = max(ignored[p.id] ?? 0, t + BleConstants.duplicateIgnoreMs)
                teardownPeripheral(p, dead)
            }
        }
        if t - lastHousekeeping >= CoreBluetoothTransport.housekeepingMs {
            lastHousekeeping = t
            backoff.prune(now: t)
            ignored = ignored.filter { $0.value > t }
            if seenAt.count > 64 { seenAt.removeAll() }
            if serviceReady && !advertising { startAdvertising() }
            else if !serviceReady && serverService == nil { bringUpPeripheralRole() } // retry a failed addService
        }
        driveScan(t)
        updateDiag()
    }

    // ================================================================== scanning (central role)

    /// iOS scan windows: `.lowBattery` cycles 3 s / 27 s like Android; `.normal` (background) and `.continuous`
    /// (foreground) scan non-stop. In the background iOS slows and coalesces the scan by itself, and a stopped scan is
    /// lost to State Preservation. Duplicates are only reported in the foreground (iOS ignores the flag in background).
    private func driveScan(_ t: Int64) {
        guard let env else { return }
        let mode = env.dutyMode()
        let window: Int64? = (mode == .lowBattery) ? mode.scanMs : nil
        let wantDuplicates = (mode == .continuous)
        if scanning {
            if wantDuplicates != scanDuplicates { stopScan(); startScan(); return }
            guard let window else { return }
            if t - scanStartedAt >= window {
                stopScan()
                scanNotBefore = t + mode.idleMs
            }
        } else if t >= scanNotBefore || window == nil {
            if window == nil { scanNotBefore = 0 }
            startScan()
        }
    }

    func startScan() {
        guard running, !scanning, let cm = centralManager, cm.state == .poweredOn else { return }
        let duplicates = (env?.dutyMode() ?? .normal) == .continuous
        cm.scanForPeripherals(withServices: [serviceUUID],
                              options: [CBCentralManagerScanOptionAllowDuplicatesKey: duplicates])
        scanning = true
        scanStartedAt = now()
        scanDuplicates = duplicates
        log("BLE: scan started (\((env?.dutyMode() ?? .normal).label))")
        updateDiag()
    }

    func stopScan() {
        guard scanning else { return }
        scanning = false
        if let cm = centralManager, cm.state == .poweredOn { cm.stopScan() }
        updateDiag()
    }

    /// The advertiser's node id from the scan-response manufacturer data (company id 0xFFFF little-endian followed
    /// by 8 bytes), when iOS delivers it. Android advertisers include it; iPhones cannot.
    static func nodeHint(_ adv: [String: Any]) -> [UInt8]? {
        guard let d = adv[CBAdvertisementDataManufacturerDataKey] as? Data, d.count == 2 + nodeIdBytes else { return nil }
        let b = [UInt8](d)
        guard b[0] == UInt8(BleConstants.manufacturerId & 0xFF), b[1] == UInt8((BleConstants.manufacturerId >> 8) & 0xFF) else { return nil }
        return Array(b[2...])
    }

    /// Pre-connection filter on the advertiser's node id (Android `acceptHint`). False = skip this advertiser.
    func acceptHint(_ hint: [UInt8]) -> Bool {
        guard let env else { return false }
        let hex = Hex.encode(hint)
        let t = now()
        if hint == env.myNodeId { return false }
        // A gap of more than 10 s means the peer went away and came back, so the tie-break starts over.
        var seen = seenAt[hex] ?? (first: t, last: t)
        if t - seen.last > CoreBluetoothTransport.hintGapMs { seen.first = t }
        seen.last = t
        seenAt[hex] = seen
        if registry.isNodeConnected(hex) { return false }
        if (ignored["n:" + hex] ?? 0) > t { return false }
        if !Allowlist.matches(env.allowlist(), nodeIdHex: hex) { return false }
        // The lower node id (the SPEC section 2 central) connects at once; the higher one waits a moment.
        if Hex.encode(env.myNodeId) > hex && t - seen.first < CoreBluetoothTransport.higherIdConnectDelayMs { return false }
        return true
    }

    func pendingCount() -> Int {
        central.values.filter { $0.phase != .up }.count + peripheral.values.filter { !$0.up }.count
    }

    // ================================================================== advertising (peripheral role)

    func startAdvertising() {
        guard running, serviceReady, let pm = peripheralManager, pm.state == .poweredOn, !pm.isAdvertising else { return }
        // Only the 128-bit service UUID: with it the 31-byte advertisement is full, and iOS ignores manufacturer data
        // and (in the background) the local name anyway.
        pm.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceUUID]])
    }

    func stopAdvertising() {
        let was = advertising
        advertising = false
        if let pm = peripheralManager, pm.isAdvertising || was { pm.stopAdvertising() }
    }
}

// ================================================================== CBCentralManagerDelegate: state and restoration

extension CoreBluetoothTransport: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let state = BluetoothRadioState(central.state)
        log("BLE: central manager state \(state)")
        lock.lock(); lastRadioState = state; let observer = stateObserver; lock.unlock()
        observer?(state)
        reconcile()
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let ps = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        log("BLE: restoring central state with \(ps.count) peripheral(s)")
        restoredPeripherals = ps
    }
}
