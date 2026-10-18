import Foundation

/// The plain-language connectivity status shown on Home and Network (design spec section 2), derived from
/// `MeshStatus`. Pure: no strings, ids or error text; the UI turns `kind` into words per language.
public struct VillagerStatus: Equatable, Sendable {
    public let kind: Kind
    public let dot: Dot
    /// Phones in range right now.
    public let peers: Int
    /// Hops to the nearest gateway; nil when this phone is the gateway or none is known.
    public let hopsToGateway: Int?
    /// Milliseconds since the last successful sync of this phone as gateway; nil when unknown.
    public let syncedAgoMs: Int64?

    public init(kind: Kind, dot: Dot, peers: Int, hopsToGateway: Int?, syncedAgoMs: Int64?) {
        self.kind = kind; self.dot = dot; self.peers = peers; self.hopsToGateway = hopsToGateway; self.syncedAgoMs = syncedAgoMs
    }

    public enum Kind: Sendable, Hashable {
        /// A gateway phone is known and reachable over the mesh.
        case reachable
        /// This phone is the gateway and syncing OK.
        case thisPhoneGateway
        /// Peers around, but no gateway, or the gateway cannot reach the backend.
        case waitingForGateway
        /// No peers.
        case searching
        case bluetoothOff
    }

    public enum Dot: Sendable, Hashable { case green, amber, grey }

    public var isReachable: Bool { dot == .green }

    public static let noGatewayHops = 0xFF

    public static func from(_ s: MeshStatus, now: Int64) -> VillagerStatus {
        let peers = s.peers.count
        func st(_ kind: Kind, _ dot: Dot, hops: Int? = nil, ago: Int64? = nil) -> VillagerStatus {
            VillagerStatus(kind: kind, dot: dot, peers: peers, hopsToGateway: hops, syncedAgoMs: ago)
        }
        if !s.bluetoothOn { return st(.bluetoothOff, .grey) }
        // This phone is the gateway: it is only "sending" while its backend sync is not failing.
        if s.isGateway {
            if s.syncHealth.lastOk == false { return st(.waitingForGateway, .amber) }
            let ok = s.syncHealth.lastOkAtMs
            return st(.thisPhoneGateway, .green, ago: ok > 0 ? max(0, now - ok) : nil)
        }
        if s.hopsToGateway < noGatewayHops { return st(.reachable, .green, hops: max(1, s.hopsToGateway)) }
        if peers > 0 { return st(.waitingForGateway, .amber) }
        return st(.searching, .grey)
    }
}
