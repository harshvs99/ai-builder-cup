import Foundation
@preconcurrency import CoreBluetooth
import RelayCore
import RelayKit

// Client role: scan results -> connect -> discover service + characteristic -> subscribe -> write frames.
// Everything here runs on the transport queue (see CoreBluetoothTransport.swift).

extension CoreBluetoothTransport {
    // ================================================================== scan results -> connect

    func maybeConnect(_ peripheral: CBPeripheral, hint: [UInt8]?) {
        guard running, let env else { return }
        let id = peripheral.identifier.uuidString
        if let hint, !acceptHint(hint) { return }
        let hintHex = hint.map { Hex.encode($0) }
        let t = now()
        if env.recentPeers.shouldDefer(now: t, preferUntil: preferUntil, mac: id, nodeIdHex: hintHex,
                                       isLinked: { self.registry.isNodeConnected($0) }) { return }
        if connectingId != nil { return } // one connection attempt at a time
        if central[id] != nil { return }
        if central.count >= BleConstants.maxCentralLinks { return }
        if registry.total() + pendingCount() >= BleConstants.maxTotalLinks { return }
        if (ignored[id] ?? 0) > t { return }
        if !registry.shouldConnect(id) { return }
        if self.peripheral[id] != nil { return }
        if !backoff.canAttempt(id, now: t) { return }
        connectTo(peripheral)
    }

    private func connectTo(_ peripheral: CBPeripheral) {
        guard let cm = centralManager, cm.state == .poweredOn else { return }
        let id = peripheral.identifier.uuidString
        log("BLE: connecting to \(id) (central \(central.count + 1)/\(BleConstants.maxCentralLinks))")
        peripheral.delegate = self
        let link = CentralLink(peripheral: peripheral, log: { [weak self] in self?.log($0) })
        central[id] = link
        connectingId = id
        updateDiag()
        cm.connect(peripheral, options: nil)
    }

    /// State Preservation and Restoration: adopt peripherals iOS kept connected for us while the app was not running.
    /// Setup is simply run again on them (service discovery and subscribing are idempotent).
    func adoptRestoredPeripherals() {
        let restored = restoredPeripherals
        restoredPeripherals.removeAll()
        for p in restored {
            let id = p.identifier.uuidString
            if central[id] != nil || central.count >= BleConstants.maxCentralLinks { continue }
            switch p.state {
            case .connected:
                p.delegate = self
                let link = CentralLink(peripheral: p, log: { [weak self] in self?.log($0) })
                central[id] = link
                log("BLE: adopting restored connection \(link.linkId)")
                beginSetup(link)
            case .connecting:
                p.delegate = self
                let link = CentralLink(peripheral: p, log: { [weak self] in self?.log($0) })
                central[id] = link
                connectingId = id
                log("BLE: adopting restored pending connection \(link.linkId)")
            default:
                break
            }
        }
        updateDiag()
    }

    // ================================================================== setup state machine

    /// Connected: read the MTU iOS negotiated, then discover. The MTU is re-read and enforced when the link comes up
    /// (the exchange can still be settling right at connect).
    private func beginSetup(_ c: CentralLink) {
        c.mtu = measuredMtu(c.peripheral)
        log("BLE: \(c.linkId) mtu=\(c.mtu) (at connect)")
        c.phase = .discovering
        c.queue.enqueue(discoverServicesOp(c))
    }

    /// ATT MTU as CoreBluetooth reports it: the largest single write without response plus the 3-byte ATT header.
    /// (The with-response limit is 512 because long writes exist; Relay never uses them, so it is not the MTU.)
    private func measuredMtu(_ p: CBPeripheral) -> Int {
        p.maximumWriteValueLength(for: .withoutResponse) + BleConstants.attOverhead
    }

    fileprivate func link(for peripheral: CBPeripheral) -> CentralLink? {
        let id = peripheral.identifier.uuidString
        if let c = central[id], c.peripheral === peripheral { return c }
        return nil
    }

    /// Remove a central link and release the connection. [backoffFailure] applies the 2 s .. 60 s reconnect backoff
    /// (always for setup failures); a link that was up also backs off, like Android. [skipBackoff] suppresses even the
    /// backoff an established link normally earns; [reconnectAfterMs] schedules one fresh connect to the same
    /// peripheral (service changed / discovery retry). Android also drops the GATT cache first (hidden
    /// `BluetoothGatt.refresh()`); CoreBluetooth has no such call and re-reads the service table itself after a
    /// service-changed indication and on every new connection, so nothing is needed here.
    func teardownCentral(_ c: CentralLink, _ reason: String, backoffFailure: Bool,
                         reconnectAfterMs: Int64? = nil, skipBackoff: Bool = false) {
        if central[c.id] !== c { return }
        central.removeValue(forKey: c.id)
        let wasUp = c.phase == .up
        c.queue.clear()
        if connectingId == c.id { connectingId = nil }
        if wasUp {
            let node = registry.get(c.linkId)?.nodeIdHex
            registry.remove(c.linkId)
            events?.onLinkDown(c.linkId)
            if running, let node, env?.recentPeers.all().contains(where: { $0.nodeIdHex == node }) == true {
                preferUntil = now() + RecentPeers.preferWindowMs
            }
        }
        let delay: Int64 = (!skipBackoff && (backoffFailure || wasUp)) ? backoff.onFailure(c.id, now: now()) : 0
        log("BLE: closing \(c.linkId) (\(reason))" + (delay > 0 ? "; retry in \(delay)ms" : ""))
        if let cm = centralManager, cm.state == .poweredOn, c.peripheral.state != .disconnected {
            cm.cancelPeripheralConnection(c.peripheral)
        }
        if let reconnectAfterMs, running {
            let p = c.peripheral
            queue.asyncAfter(deadline: .now() + .milliseconds(Int(reconnectAfterMs))) { [weak self] in
                self?.reconnectCentral(p)
            }
        }
        updateDiag()
    }

    /// One scheduled re-connect to [p] (service changed / discovery retry); skipped if anything else got there first,
    /// in which case the scan finds it again.
    private func reconnectCentral(_ p: CBPeripheral) {
        let id = p.identifier.uuidString
        guard running else { return }
        let why: String?
        if central[id] != nil { why = "already connecting" }
        else if connectingId != nil { why = "another connect in progress" }
        else if central.count >= BleConstants.maxCentralLinks || registry.total() + pendingCount() >= BleConstants.maxTotalLinks { why = "link cap" }
        else if registry.hasLinkToMac(id) || self.peripheral[id] != nil { why = "already linked" }
        else if p.state != .disconnected { why = "peripheral state \(p.state.rawValue)" }
        else { why = nil }
        if let why { log("BLE: scheduled reconnect to \(id) skipped (\(why)); scan will find it"); return }
        log("BLE: scheduled reconnect to \(id)")
        connectTo(p)
    }

    /// Discovery found no Relay service (or characteristic) on a device we expected to be a peer: it may not have added
    /// its GATT service yet (its app just restarted) or we hold a stale table. Retry once after a short delay without
    /// backoff, then give up and let the backoff apply.
    private func serviceMissing(_ c: CentralLink, _ detail: String) {
        let retries = discoveryRetries[c.id] ?? 0
        if let retryIn = LinkHealth.discoveryRetryDelayMs(retriesSoFar: retries) {
            discoveryRetries[c.id] = retries + 1
            log("BLE: \(c.linkId) \(detail); retrying once in \(retryIn)ms")
            teardownCentral(c, detail, backoffFailure: false, reconnectAfterMs: retryIn, skipBackoff: true)
        } else {
            discoveryRetries.removeValue(forKey: c.id)
            log("BLE: \(c.linkId) \(detail) after retry; giving up")
            teardownCentral(c, detail, backoffFailure: true)
        }
    }

    // ---- central GATT operations (serialised through CentralLink.queue)

    private func discoverServicesOp(_ c: CentralLink) -> GattOp {
        let p = c.peripheral
        let svc = serviceUUID
        return GattOp("discoverServices") {
            guard p.state == .connected else { return .failed }
            p.discoverServices([svc])
            return .started
        }
    }

    private func discoverCharacteristicsOp(_ c: CentralLink, _ service: CBService) -> GattOp {
        let p = c.peripheral
        let ch = charUUID
        return GattOp("discoverCharacteristics") {
            guard p.state == .connected else { return .failed }
            p.discoverCharacteristics([ch], for: service)
            return .started
        }
    }

    private func subscribeOp(_ c: CentralLink, _ ch: CBCharacteristic) -> GattOp {
        let p = c.peripheral
        return GattOp("enableNotifications") {
            guard p.state == .connected, ch.properties.contains(.notify) || ch.properties.contains(.indicate) else { return .failed }
            p.setNotifyValue(true, for: ch)
            return .started
        }
    }

    /// Write without response, like Android, so each write is a single ATT command of at most MTU-3 bytes (Android's
    /// server rejects prepared/long writes). There is no completion callback for it, so the op completes at once;
    /// `canSendWriteWithoutResponse` is the flow control: while false the op is retried, immediately when
    /// `peripheralIsReady(toSendWriteWithoutResponse:)` fires and otherwise on the 500 ms tick.
    func writeOp(_ c: CentralLink, _ ch: CBCharacteristic, _ frame: [UInt8]) -> GattOp {
        let p = c.peripheral
        let data = Data(frame)
        let type: CBCharacteristicWriteType = ch.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
        let q = c.queue
        return GattOp("write(\(frame.count)B)") { [weak q] in
            guard p.state == .connected else { return .failed }
            if type == .withoutResponse {
                if !p.canSendWriteWithoutResponse { return .retry }
                p.writeValue(data, for: ch, type: .withoutResponse)
                q?.onComplete()
                return .started
            }
            p.writeValue(data, for: ch, type: .withResponse) // completion: didWriteValueFor
            return .started
        }
    }
}

// ================================================================== CBCentralManagerDelegate: discovery and connection

extension CoreBluetoothTransport {
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Remember the signal strength for the link we may open to this advertiser (LOC_HINT, SPEC 5.5).
        registry.noteScanRssi(peripheral.identifier.uuidString, RSSI.intValue)
        maybeConnect(peripheral, hint: CoreBluetoothTransport.nodeHint(advertisementData))
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard let c = link(for: peripheral) else {
            // Not one of ours any more (torn down while connecting): release it.
            central.cancelPeripheralConnection(peripheral)
            return
        }
        guard c.phase == .connecting else { return }
        beginSetup(c)
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        guard let c = link(for: peripheral) else { return }
        teardownCentral(c, "connect failed: \(error?.localizedDescription ?? "unknown")", backoffFailure: true)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard let c = link(for: peripheral) else { return }
        teardownCentral(c, "disconnected: \(error?.localizedDescription ?? "peer closed")", backoffFailure: c.phase != .up)
    }
}

// ================================================================== CBPeripheralDelegate: setup, frames, flow control

extension CoreBluetoothTransport: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let c = link(for: peripheral) else { return }
        c.queue.onComplete()
        guard c.phase == .discovering, c.service == nil else { return }
        if let error {
            serviceMissing(c, "service discovery failed: \(error.localizedDescription)")
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            serviceMissing(c, "relay service missing")
            return
        }
        c.service = service
        c.queue.enqueue(discoverCharacteristicsOp(c, service))
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let c = link(for: peripheral) else { return }
        c.queue.onComplete()
        guard c.phase == .discovering, c.characteristic == nil else { return }
        if let error {
            serviceMissing(c, "characteristic discovery failed: \(error.localizedDescription)")
            return
        }
        guard let ch = service.characteristics?.first(where: { $0.uuid == charUUID }) else {
            serviceMissing(c, "relay characteristic missing")
            return
        }
        c.characteristic = ch
        c.phase = .subscribing
        c.queue.enqueue(subscribeOp(c, ch))
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard let c = link(for: peripheral) else { return }
        c.queue.onComplete()
        guard c.phase == .subscribing else { return }
        if let error {
            teardownCentral(c, "subscribe failed: \(error.localizedDescription)", backoffFailure: true)
            return
        }
        guard characteristic.isNotifying else {
            teardownCentral(c, "notifications not enabled", backoffFailure: true)
            return
        }
        // The MTU exchange has certainly finished by now: enforce SPEC section 2's minimum.
        c.mtu = max(c.mtu, measuredMtu(peripheral))
        log("BLE: \(c.linkId) mtu=\(c.mtu)")
        if c.mtu < BleConstants.minMtu {
            teardownCentral(c, "mtu too small (\(c.mtu) < \(BleConstants.minMtu))", backoffFailure: true)
            return
        }
        c.phase = .up
        c.lastRxAt = now()
        discoveryRetries.removeValue(forKey: c.id)
        if connectingId == c.id { connectingId = nil }
        backoff.onSuccess(c.id)
        registry.add(c.linkId, mac: c.id, role: .central)
        updateDiag()
        events?.onLinkUp(c.linkId)
        for f in c.earlyFrames { events?.onFrame(c.linkId, f) }
        c.earlyFrames.removeAll()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == charUUID, let c = link(for: peripheral),
              let data = characteristic.value else { return }
        let bytes = [UInt8](data)
        c.lastRxAt = now()
        switch c.phase {
        case .up: events?.onFrame(c.linkId, bytes)
        case .subscribing: if c.earlyFrames.count < 64 { c.earlyFrames.append(bytes) }
        default: break
        }
    }

    /// Only used for the `.withResponse` fallback (a peer whose characteristic lacks write-without-response).
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let c = link(for: peripheral) else { return }
        if let error { log("[\(c.linkId)] write completed with error \(error.localizedDescription)") }
        c.queue.onComplete()
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        link(for: peripheral)?.queue.tick()
    }

    func peripheral(_ peripheral: CBPeripheral, didModifyServices invalidatedServices: [CBService]) {
        guard let c = link(for: peripheral), invalidatedServices.contains(where: { $0.uuid == serviceUUID }) else { return }
        // The peer's GATT database changed under an open connection (e.g. its app process restarted and re-added the
        // service): our characteristic handle and the HELLO/inventory state on this link are stale. Drop the link
        // (gossip sees a linkDown) and reconnect once shortly, without the exponential backoff.
        log("BLE: service changed on \(c.linkId) (peer restarted?); closing and reconnecting in \(LinkHealth.serviceChangedReconnectMs)ms")
        teardownCentral(c, "service changed", backoffFailure: false,
                        reconnectAfterMs: LinkHealth.serviceChangedReconnectMs, skipBackoff: true)
    }
}
