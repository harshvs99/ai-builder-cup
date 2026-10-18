import Foundation
@preconcurrency import CoreBluetooth
import RelayCore
import RelayKit

// Server role: publish the Relay service, accept subscribers, receive their writes, notify them.
// Everything here runs on the transport queue (see CoreBluetoothTransport.swift).

extension CoreBluetoothTransport {
    // ================================================================== GATT server

    /// Adds the Relay service (once) and starts advertising when it is in. Called from `reconcile()`.
    func bringUpPeripheralRole() {
        guard running, let pm = peripheralManager, pm.state == .poweredOn else { return }
        if serverService == nil {
            if let restored = restoredService, let ch = restored.characteristics?.first(where: { $0.uuid == charUUID }) as? CBMutableCharacteristic {
                // State restoration handed our service back: reuse it instead of adding a second copy.
                serverService = restored
                serverChar = ch
                serviceReady = true
                restoredService = nil
                log("BLE: GATT service restored")
                if pm.isAdvertising { pm.stopAdvertising() }
                startAdvertising()
                return
            }
            restoredService = nil
            // CoreBluetooth adds the Client Characteristic Configuration descriptor itself for a notify characteristic.
            let ch = CBMutableCharacteristic(
                type: charUUID, properties: [.write, .writeWithoutResponse, .notify], value: nil, permissions: [.writeable])
            let service = CBMutableService(type: serviceUUID, primary: true)
            service.characteristics = [ch]
            serverService = service
            serverChar = ch
            serviceReady = false
            pm.add(service)
        } else if serviceReady {
            startAdvertising()
        }
    }

    /// Remove a peripheral link. iOS offers no way to disconnect a central, so the link is only forgotten (and, for
    /// duplicates and allowlist rejects, ignored for a while by the caller).
    func teardownPeripheral(_ p: PeripheralLink, _ reason: String) {
        if peripheral[p.id] !== p { return }
        peripheral.removeValue(forKey: p.id)
        p.queue.clear()
        if p.up {
            let node = registry.get(p.linkId)?.nodeIdHex
            registry.remove(p.linkId)
            events?.onLinkDown(p.linkId)
            if running, let node, env?.recentPeers.all().contains(where: { $0.nodeIdHex == node }) == true {
                preferUntil = now() + RecentPeers.preferWindowMs
            }
        }
        log("BLE: closing \(p.linkId) (\(reason))")
        updateDiag()
    }

    /// One whole frame as a notification. `updateValue` returning false means CoreBluetooth's transmit queue is full:
    /// the op is retried when `peripheralManagerIsReady(toUpdateSubscribers:)` fires (and on the 500 ms tick). A
    /// notification has no completion callback, so a successful hand-off completes the op at once.
    func notifyOp(_ p: PeripheralLink, _ frame: [UInt8]) -> GattOp {
        let data = Data(frame)
        let q = p.queue
        return GattOp("notify(\(frame.count)B)") { [weak self, weak q, weak p] in
            guard let self, let p, let pm = self.peripheralManager, let ch = self.serverChar, pm.state == .poweredOn else { return .failed }
            if pm.updateValue(data, for: ch, onSubscribedCentrals: [p.central]) {
                q?.onComplete()
                return .started
            }
            return .retry
        }
    }

    private func peripheralLink(for remote: CBCentral) -> PeripheralLink? {
        peripheral[remote.identifier.uuidString]
    }

    /// A frame written by [remote]. Frames from a link that is not up yet (a write that beat the subscribe) are kept
    /// and replayed after `onLinkUp`, like Android.
    private func receive(from remote: CBCentral, _ bytes: [UInt8]) {
        let id = remote.identifier.uuidString
        if (ignored[id] ?? 0) > now() { return }
        let link: PeripheralLink
        if let existing = peripheral[id] {
            link = existing
        } else {
            if registry.total() + pendingCount() >= BleConstants.maxTotalLinks { return }
            link = PeripheralLink(central: remote, log: { [weak self] in self?.log($0) })
            peripheral[id] = link
            updateDiag()
        }
        if link.up { events?.onFrame(link.linkId, bytes) }
        else if link.earlyFrames.count < 64 { link.earlyFrames.append(bytes) }
    }
}

// ================================================================== CBPeripheralManagerDelegate

extension CoreBluetoothTransport: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        log("BLE: peripheral manager state \(BluetoothRadioState(peripheral.state))")
        if peripheral.state == .poweredOn {
            reconcile()
        } else {
            // The system dropped our services and subscribers with the radio.
            for p in Array(self.peripheral.values) { teardownPeripheral(p, "peripheral manager not powered on") }
            serverService = nil
            serverChar = nil
            serviceReady = false
            advertising = false
            updateDiag()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] ?? []
        restoredService = services.first { $0.uuid == serviceUUID }
        log("BLE: restoring peripheral state (\(services.count) service(s), ours \(restoredService != nil ? "present" : "absent"))")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            log("BLE: addService failed: \(error.localizedDescription)")
            serverService = nil
            serverChar = nil
            return
        }
        guard service.uuid == serviceUUID else { return }
        log("BLE: GATT service added")
        serviceReady = true
        startAdvertising()
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            advertising = false
            log("BLE: advertising failed: \(error.localizedDescription); retrying in 5s")
            queue.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard let self, self.running, self.serviceReady, !self.advertising else { return }
                self.startAdvertising()
            }
        } else {
            advertising = true
            log("BLE: advertising started")
        }
        updateDiag()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central remote: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard running, characteristic.uuid == charUUID else { return }
        let id = remote.identifier.uuidString
        if (ignored[id] ?? 0) > now() {
            log("BLE: ignoring subscribe from \(id) (ignored)")
            return
        }
        let link: PeripheralLink
        if let existing = peripheralLink(for: remote) {
            link = existing
            link.central = remote
        } else {
            if registry.total() + pendingCount() >= BleConstants.maxTotalLinks {
                log("BLE: refusing subscriber \(id) (link cap)")
                return
            }
            link = PeripheralLink(central: remote, log: { [weak self] in self?.log($0) })
            self.peripheral[id] = link
        }
        if link.up { return }
        link.mtu = remote.maximumUpdateValueLength + BleConstants.attOverhead
        log("BLE: incoming subscriber \(link.linkId) mtu=\(link.mtu)")
        if link.mtu < BleConstants.minMtu {
            teardownPeripheral(link, "mtu too small (\(link.mtu) < \(BleConstants.minMtu))")
            return
        }
        link.up = true
        registry.add(link.linkId, mac: id, role: .peripheral)
        updateDiag()
        events?.onLinkUp(link.linkId)
        for f in link.earlyFrames { events?.onFrame(link.linkId, f) }
        link.earlyFrames.removeAll()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central remote: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == charUUID, let link = peripheralLink(for: remote) else { return }
        teardownPeripheral(link, "central unsubscribed")
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        peripheral.respond(to: request, withResult: .readNotPermitted)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let first = requests.first else { return }
        var result = CBATTError.Code.success
        for r in requests {
            if r.characteristic.uuid != charUUID || r.value == nil {
                result = .writeNotPermitted
            } else if r.offset != 0 {
                result = .invalidOffset // prepared / long writes are not part of the protocol
            } else if let value = r.value {
                receive(from: r.central, [UInt8](value))
            }
        }
        // Answered once, for the first request, as CoreBluetooth requires (a no-op for write-without-response).
        peripheral.respond(to: first, withResult: result)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        for p in Array(self.peripheral.values) { p.queue.tick() }
    }
}
