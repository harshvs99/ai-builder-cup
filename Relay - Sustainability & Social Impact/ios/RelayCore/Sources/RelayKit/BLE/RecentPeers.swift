import Foundation

/// The last few peers with a successful exchange (HELLO accepted), newest first, so a restarted phone re-links to
/// them first. Advertising addresses rotate, so the node id is the identity; the address is kept as a fallback
/// for scan results that carry no node-id hint. `onChange` persists the list (`AppSettings.recentPeers`).
public final class RecentPeers: @unchecked Sendable {
    public struct Peer: Equatable, Hashable, Sendable {
        public let mac: String
        public let nodeIdHex: String
        public init(mac: String, nodeIdHex: String) { self.mac = mac; self.nodeIdHex = nodeIdHex }
    }

    public static let maxPeers = 3
    /// After start-up or after losing a recent peer, non-recent advertisers wait this long.
    public static let preferWindowMs: Int64 = 4_000

    private let max: Int
    private let onChange: @Sendable ([Peer]) -> Void
    private let lock = NSLock()
    private var peers: [Peer]

    public init(initial: [Peer] = [], max: Int = RecentPeers.maxPeers, onChange: @escaping @Sendable ([Peer]) -> Void = { _ in }) {
        self.max = max
        self.onChange = onChange
        var seen = Set<String>()
        peers = Array(initial.filter { seen.insert($0.nodeIdHex).inserted }.prefix(max))
    }

    public func all() -> [Peer] { lock.lock(); defer { lock.unlock() }; return peers }

    public func record(mac: String, nodeIdHex: String) {
        lock.lock()
        if let f = peers.first, f.nodeIdHex == nodeIdHex, f.mac == mac { lock.unlock(); return }
        peers = Array(([Peer(mac: mac, nodeIdHex: nodeIdHex)] + peers.filter { $0.nodeIdHex != nodeIdHex }).prefix(max))
        let snapshot = peers
        lock.unlock()
        onChange(snapshot)
    }

    public func isPreferred(mac: String, nodeIdHex: String?) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return isPreferredLocked(mac: mac, nodeIdHex: nodeIdHex)
    }

    private func isPreferredLocked(mac: String, nodeIdHex: String?) -> Bool {
        peers.contains { (nodeIdHex != nil && $0.nodeIdHex == nodeIdHex) || (nodeIdHex == nil && $0.mac == mac) }
    }

    /// True when the scan result at [mac] / [nodeIdHex] should wait: inside the preference window
    /// (`now < preferUntil`), it is not a recent peer, and a recent peer is not linked yet.
    public func shouldDefer(now: Int64, preferUntil: Int64, mac: String, nodeIdHex: String?, isLinked: (String) -> Bool) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if now >= preferUntil { return false }
        if isPreferredLocked(mac: mac, nodeIdHex: nodeIdHex) { return false }
        return peers.contains { !isLinked($0.nodeIdHex) }
    }

    /// `mac,nodeIdHex;mac,nodeIdHex`
    public static func serialize(_ peers: [Peer]) -> String { peers.map { "\($0.mac),\($0.nodeIdHex)" }.joined(separator: ";") }

    public static func parse(_ s: String?) -> [Peer] {
        (s ?? "").split(separator: ";", omittingEmptySubsequences: false).compactMap { e in
            let parts = e.split(separator: ",", omittingEmptySubsequences: false)
            if parts.count == 2, !parts[0].trimmed.isEmpty, !parts[1].trimmed.isEmpty { return Peer(mac: String(parts[0]), nodeIdHex: String(parts[1])) }
            return nil
        }
    }
}

private extension Substring {
    var trimmed: String { String(self).trimmed }
}
