import Foundation
import RelayCore

public enum LinkRole: Sendable, Hashable {
    case central, peripheral
    /// Lower-case name, used in decision reasons.
    var word: String { self == .central ? "central" : "peripheral" }
}

/// Bookkeeping for established links (those reported to the gossip engine): caps, address to node-id mapping,
/// and the duplicate-link rule from SPEC section 2. No CoreBluetooth types. `mac` is whatever stable per-peer
/// address the transport has (a MAC on Android, an identifier UUID string on iOS).
public final class LinkRegistry: @unchecked Sendable {
    public struct Link: Equatable, Sendable {
        public let linkId: String
        public let mac: String
        public let role: LinkRole
        public let nodeIdHex: String?
        public init(linkId: String, mac: String, role: LinkRole, nodeIdHex: String? = nil) {
            self.linkId = linkId; self.mac = mac; self.role = role; self.nodeIdHex = nodeIdHex
        }
    }

    public enum Decision: Equatable, Sendable {
        /// Nothing to close.
        case keep
        /// Close the link that just said HELLO.
        case closeThis(reason: String)
        /// Close the other (older) link to the same node.
        case closeOther(otherLinkId: String)
    }

    /// Peers rotate their address, so the address-to-node map is bounded (least recently used goes first).
    public static let maxMacEntries = 64

    private let myId: [UInt8]
    private let myIdHex: String
    private let lock = NSLock()
    private var links: [String: Link] = [:]
    private var linkOrder: [String] = []
    private var macToNode: [String: String] = [:]
    private var macOrder: [String] = [] // least recently used first

    public init(myId: [UInt8]) {
        self.myId = myId
        self.myIdHex = Hex.encode(myId)
    }

    public func add(_ linkId: String, mac: String, role: LinkRole) {
        lock.lock(); defer { lock.unlock() }
        if links[linkId] == nil { linkOrder.append(linkId) }
        links[linkId] = Link(linkId: linkId, mac: mac, role: role)
    }

    public func remove(_ linkId: String) {
        lock.lock(); defer { lock.unlock() }
        if links.removeValue(forKey: linkId) != nil { linkOrder.removeAll { $0 == linkId } }
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        links.removeAll(); linkOrder.removeAll(); macToNode.removeAll(); macOrder.removeAll()
    }

    public func get(_ linkId: String) -> Link? { lock.lock(); defer { lock.unlock() }; return links[linkId] }
    public func knownMacCount() -> Int { lock.lock(); defer { lock.unlock() }; return macToNode.count }
    public func all() -> [Link] { lock.lock(); defer { lock.unlock() }; return linkOrder.compactMap { links[$0] } }
    public func total() -> Int { lock.lock(); defer { lock.unlock() }; return links.count }
    public func centralCount() -> Int { lock.lock(); defer { lock.unlock() }; return links.values.filter { $0.role == .central }.count }

    public func hasLinkToMac(_ mac: String) -> Bool { lock.lock(); defer { lock.unlock() }; return hasLinkToMacLocked(mac) }
    public func isNodeConnected(_ nodeIdHex: String) -> Bool { lock.lock(); defer { lock.unlock() }; return isNodeConnectedLocked(nodeIdHex) }

    private func hasLinkToMacLocked(_ mac: String) -> Bool { links.values.contains { $0.mac == mac } }
    private func isNodeConnectedLocked(_ hex: String) -> Bool { links.values.contains { $0.nodeIdHex == hex } }

    private func touch(_ mac: String) {
        macOrder.removeAll { $0 == mac }
        macOrder.append(mac)
    }

    /// Should we initiate a central connection to the advertiser at [mac]? No if any live link already uses that
    /// address, or if that address is known to be a node we already have a link to (avoids reconnect loops after a
    /// duplicate-link close).
    public func shouldConnect(_ mac: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if hasLinkToMacLocked(mac) { return false }
        if let node = macToNode[mac] {
            touch(mac)
            if isNodeConnectedLocked(node) { return false }
        }
        return true
    }

    /// Record the HELLO and decide whether this link or a duplicate must be closed.
    @discardableResult
    public func onHello(_ linkId: String, peerNodeId: [UInt8]) -> Decision {
        lock.lock(); defer { lock.unlock() }
        guard let link = links[linkId] else { return .keep }
        let hex = Hex.encode(peerNodeId)
        if hex == myIdHex { return .closeThis(reason: "peer node id equals ours") }
        links[linkId] = Link(linkId: link.linkId, mac: link.mac, role: link.role, nodeIdHex: hex)
        macToNode[link.mac] = hex
        touch(link.mac)
        while macOrder.count > LinkRegistry.maxMacEntries {
            macToNode.removeValue(forKey: macOrder.removeFirst())
        }
        guard let other = linkOrder.compactMap({ links[$0] }).first(where: { $0.linkId != linkId && $0.nodeIdHex == hex }) else { return .keep }
        if other.role == link.role {
            // Same role twice (e.g. the peer's address rotated): keep the older link.
            return .closeThis(reason: "duplicate \(link.role.word) link to \(hex.take(8))")
        }
        let keepRole: LinkRole = DuplicateLinkPolicy.keepAsCentral(myId: myId, peerId: peerNodeId) ? .central : .peripheral
        return link.role == keepRole
            ? .closeOther(otherLinkId: other.linkId)
            : .closeThis(reason: "duplicate link to \(hex.take(8)); keeping \(keepRole.word) side")
    }
}
