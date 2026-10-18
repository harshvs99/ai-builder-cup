import Foundation
import RelayCore

/// What the engine hands the transport when it is attached: identity, the live settings it needs, and the shared
/// recent-peers list. Closures are read on every use, so settings changes take effect without re-attaching.
public struct MeshTransportEnvironment: Sendable {
    /// This node's 8-byte id (advertised, and used for the duplicate-link rule).
    public let myNodeId: [UInt8]
    /// Current allowlist CSV (`Allowlist.matches`); empty accepts every peer.
    public let allowlist: @Sendable () -> String
    /// Current scan duty mode (battery / foreground aware); read on every tick.
    public let dutyMode: @Sendable () -> DutyCycle.Mode
    /// Peers to prefer when scanning; the transport records into it after each accepted HELLO.
    public let recentPeers: RecentPeers

    public init(myNodeId: [UInt8], allowlist: @escaping @Sendable () -> String, dutyMode: @escaping @Sendable () -> DutyCycle.Mode, recentPeers: RecentPeers) {
        self.myNodeId = myNodeId; self.allowlist = allowlist; self.dutyMode = dutyMode; self.recentPeers = recentPeers
    }
}

/// Callbacks from the transport into the engine (Android `BleTransport.Events`). Call them from any thread; a link
/// must be reported up before frames from it are delivered.
public protocol MeshTransportEvents: AnyObject, Sendable {
    /// A link finished setup (HELLO can now be exchanged). `linkId` is `c:<id>` or `p:<id>` (see `LinkId`).
    func onLinkUp(_ linkId: String)
    func onLinkDown(_ linkId: String)
    /// One whole frame arrived on [linkId].
    func onFrame(_ linkId: String, _ frame: [UInt8])
    func onLog(_ msg: String)
}

/// The radio under the mesh. The iOS app implements this with CoreBluetooth (a central that scans and connects, a
/// peripheral that advertises the Relay service and notifies); tests use a fake. It must not do gossip: it only moves
/// whole frames between links and reports link state.
///
/// Sending: `send(linkId:frame:)` (from `LinkSender`) is called by the engine, from any thread, outside engine locks;
/// enqueue and return, never block on BLE. Frames larger than the link's MTU minus `BleConstants.attOverhead` are
/// dropped (and logged) by the transport.
///
/// HELLO handling: the engine calls `onPeerHello` when a peer's HELLO arrives so the transport can apply the
/// allowlist and the duplicate-link rule (`LinkRegistry`, `Allowlist`), closing links and backing off as Android does.
///
/// Threading: all methods are safe to call from any thread; the implementation hops to its own queue (the Android
/// transport owns a single handler thread, CoreBluetooth wants a dedicated `DispatchQueue`).
public protocol MeshTransport: LinkSender, AnyObject, Sendable {
    /// Called once by `MeshEngine` before `start()`. Keep the references; report everything through [events].
    func attach(events: any MeshTransportEvents, environment: MeshTransportEnvironment)

    /// Start scanning and advertising (idempotent). If Bluetooth is not ready yet, wait and start when it is.
    func start()

    /// Stop because Bluetooth went off: tear down links (reporting `onLinkDown` for each) and reset scan accounting.
    func stop()

    /// Stop and release everything. The transport is not restarted afterwards.
    func shutdown()

    /// A peer's HELLO arrived on [linkId]; [nodeId] is its 8-byte id.
    func onPeerHello(linkId: String, nodeId: [UInt8])

    /// Diagnostics for the Network screen.
    func info() -> MeshTransportInfo

    /// Last scan RSSI (dBm) of the link [linkId], or nil when unknown (always nil for peripheral links). Any thread.
    /// Feeds `rssi_dbm` of a LOC_HINT (SPEC 5.5).
    func rssiFor(linkId: String) -> Int?
}

extension MeshTransport {
    public func rssiFor(linkId: String) -> Int? { nil }
}
