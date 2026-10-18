import Foundation
@preconcurrency import CoreBluetooth
import RelayKit

// Per-link state for CoreBluetoothTransport. Instances are created, read and mutated only on the transport's
// serial queue (see the threading note in CoreBluetoothTransport.swift); they are plain classes with no locking.

/// A link where we are the central: we connected to a peer's GATT server and write frames to its characteristic.
/// Link id `c:<peripheral identifier>`.
final class CentralLink {
    let id: String
    let peripheral: CBPeripheral
    let linkId: String
    let createdAt: Int64
    var phase: SetupPhase = .connecting { didSet { phaseSince = monotonicMs() } }
    private(set) var phaseSince: Int64
    /// ATT MTU = `maximumWriteValueLength(for: .withoutResponse) + 3`.
    var mtu = BleConstants.defaultMtu
    var service: CBService?
    var characteristic: CBCharacteristic?
    let queue: GattOpQueue
    /// Notifications that raced ahead of the subscribe completion; replayed right after `onLinkUp`.
    var earlyFrames: [[UInt8]] = []

    init(peripheral: CBPeripheral, log: @escaping @Sendable (String) -> Void) {
        let now = monotonicMs()
        self.id = peripheral.identifier.uuidString
        self.peripheral = peripheral
        self.linkId = LinkId.central(peripheral.identifier.uuidString)
        self.createdAt = now
        self.phaseSince = now
        let linkId = LinkId.central(peripheral.identifier.uuidString)
        self.queue = GattOpQueue(log: { log("[\(linkId)] \($0)") })
    }
}

/// A link where we are the peripheral: a remote central subscribed to our characteristic and writes frames to it.
/// Link id `p:<central identifier>`. CoreBluetooth gives a peripheral no connect callback, so a link exists from the
/// first subscribe (or write) and is "up" once the subscribe was accepted.
final class PeripheralLink {
    let id: String
    var central: CBCentral
    let linkId: String
    let connectedAt: Int64
    var mtu = BleConstants.defaultMtu
    var up = false
    let queue: GattOpQueue
    var earlyFrames: [[UInt8]] = []

    init(central: CBCentral, log: @escaping @Sendable (String) -> Void) {
        self.id = central.identifier.uuidString
        self.central = central
        self.linkId = LinkId.peripheral(central.identifier.uuidString)
        self.connectedAt = monotonicMs()
        let linkId = LinkId.peripheral(central.identifier.uuidString)
        self.queue = GattOpQueue(log: { log("[\(linkId)] \($0)") })
    }
}

/// Coarse Bluetooth radio state for the UI and the engine (`MeshEngine.setBluetoothOn`).
enum BluetoothRadioState: Sendable, Equatable {
    case unknown, resetting, unsupported, unauthorized, poweredOff, poweredOn

    init(_ s: CBManagerState) {
        switch s {
        case .poweredOn: self = .poweredOn
        case .poweredOff: self = .poweredOff
        case .unauthorized: self = .unauthorized
        case .unsupported: self = .unsupported
        case .resetting: self = .resetting
        case .unknown: self = .unknown
        @unknown default: self = .unknown
        }
    }

    var isOn: Bool { self == .poweredOn }
}
