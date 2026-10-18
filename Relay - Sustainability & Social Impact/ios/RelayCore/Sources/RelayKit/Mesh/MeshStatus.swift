import Foundation
import RelayCore

public struct PeerInfo: Equatable, Hashable, Sendable {
    public let linkId: String
    /// nil until the peer's HELLO arrives
    public let nodeIdHex: String?
    public let isGateway: Bool
    public let hops: Int

    public init(linkId: String, nodeIdHex: String?, isGateway: Bool, hops: Int) {
        self.linkId = linkId; self.nodeIdHex = nodeIdHex; self.isGateway = isGateway; self.hops = hops
    }
}

/// Point-in-time view of the transport for the diagnostics screen (Android `BleTransport.Info`).
public struct MeshTransportInfo: Equatable, Sendable {
    public var running: Bool
    public var scanning: Bool
    public var advertising: Bool
    public var centralLinks: Int
    public var peripheralLinks: Int
    public var connecting: String?
    public var dutyMode: DutyCycle.Mode

    public init(running: Bool = false, scanning: Bool = false, advertising: Bool = false, centralLinks: Int = 0,
                peripheralLinks: Int = 0, connecting: String? = nil, dutyMode: DutyCycle.Mode = .normal) {
        self.running = running; self.scanning = scanning; self.advertising = advertising
        self.centralLinks = centralLinks; self.peripheralLinks = peripheralLinks
        self.connecting = connecting; self.dutyMode = dutyMode
    }
}

/// Everything the Home / Network / Settings screens show about the mesh. Plain value; republished by the engine
/// about once a second (only when it changed).
public struct MeshStatus: Equatable, Sendable {
    public var serviceRunning: Bool
    public var bluetoothOn: Bool
    public var peers: [PeerInfo]
    public var isGateway: Bool
    public var hopsToGateway: Int
    public var storedNeeds: Int
    public var storedAcks: Int
    /// Stored LOC_HINT frames (mesh-assisted location, SPEC 5.5).
    public var storedHints: Int
    public var stats: GossipStats
    public var transport: MeshTransportInfo?
    public var internetAvailable: Bool
    public var lastSyncOkAtMs: Int64
    public var lastSyncError: String?
    public var pendingUploads: Int
    /// Outcome of the gateway's most recent backend sync (meaningful while `isGateway`).
    public var syncHealth: SyncHealth
    /// Battery level 0..100, or -1 when unknown. UI: show next to the mesh-hours estimate.
    public var batteryPercent: Int
    public var charging: Bool
    /// Current scan duty mode; `scanMode.label` is a ready-to-show description.
    public var scanMode: DutyCycle.Mode
    /// Estimated mesh hours left at the current duty mode; nil while charging or battery unknown.
    public var meshHoursRemaining: Double?
    /// Last peers with a successful exchange (node id hex), newest first.
    public var recentPeerIds: [String]

    public init(
        serviceRunning: Bool = false, bluetoothOn: Bool = false, peers: [PeerInfo] = [], isGateway: Bool = false,
        hopsToGateway: Int = 0xFF, storedNeeds: Int = 0, storedAcks: Int = 0, storedHints: Int = 0, stats: GossipStats = GossipStats(),
        transport: MeshTransportInfo? = nil, internetAvailable: Bool = false, lastSyncOkAtMs: Int64 = 0,
        lastSyncError: String? = nil, pendingUploads: Int = 0, syncHealth: SyncHealth = SyncHealth(),
        batteryPercent: Int = -1, charging: Bool = false, scanMode: DutyCycle.Mode = .normal,
        meshHoursRemaining: Double? = nil, recentPeerIds: [String] = []
    ) {
        self.serviceRunning = serviceRunning; self.bluetoothOn = bluetoothOn; self.peers = peers
        self.isGateway = isGateway; self.hopsToGateway = hopsToGateway; self.storedNeeds = storedNeeds
        self.storedAcks = storedAcks; self.stats = stats; self.transport = transport
        self.internetAvailable = internetAvailable; self.lastSyncOkAtMs = lastSyncOkAtMs
        self.lastSyncError = lastSyncError; self.pendingUploads = pendingUploads; self.syncHealth = syncHealth
        self.batteryPercent = batteryPercent; self.charging = charging; self.scanMode = scanMode
        self.meshHoursRemaining = meshHoursRemaining; self.recentPeerIds = recentPeerIds
        self.storedHints = storedHints
    }
}

/// What a SwiftUI store observes: the latest status plus the "my needs" list (newest first).
public struct MeshSnapshot: Equatable, Sendable {
    public var status: MeshStatus
    public var myNeeds: [MyNeed]
    public init(status: MeshStatus = MeshStatus(), myNeeds: [MyNeed] = []) { self.status = status; self.myNeeds = myNeeds }
}
