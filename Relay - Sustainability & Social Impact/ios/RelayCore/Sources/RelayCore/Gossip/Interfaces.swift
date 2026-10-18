import Foundation

/// Sends one whole frame over one link. Called OUTSIDE the engine lock, possibly from any thread
/// that drives the engine; implementations should be non-blocking (enqueue, don't wait for BLE).
/// The byte array is owned by the callee (a fresh copy per call).
public protocol LinkSender {
    func send(linkId: String, frame: [UInt8])
}

public protocol Clock {
    func nowMs() -> Int64
}

/// Runs [task] once after [delayMs]. The simulator / tests implement this with virtual time; the app uses
/// `DispatchQueueScheduler`.
public protocol Scheduler {
    func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void)
}

/// Callbacks are invoked outside the engine lock, so a listener may call back into the node. All methods have
/// empty default implementations.
public protocol GossipListener {
    func onNeed(_ need: Need, frame: Frame, fromLink: String?)
    func onAckForMe(_ ack: Ack, frame: Frame)
    func onAckSeen(_ ack: Ack, frame: Frame)
    func onPeerHello(linkId: String, hello: Hello)
    func onLog(_ msg: String)
}

extension GossipListener {
    public func onNeed(_ need: Need, frame: Frame, fromLink: String?) {}
    public func onAckForMe(_ ack: Ack, frame: Frame) {}
    public func onAckSeen(_ ack: Ack, frame: Frame) {}
    public func onPeerHello(linkId: String, hello: Hello) {}
    public func onLog(_ msg: String) {}
}

/// Wall-clock `Clock`.
public struct WallClock: Clock, Sendable {
    public init() {}
    public func nowMs() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }
}

/// `Scheduler` backed by a real `DispatchQueue` (the app, a real-time simulator). The Android equivalent wraps a
/// `ScheduledExecutorService`.
public struct DispatchQueueScheduler: Scheduler, @unchecked Sendable {
    private let queue: DispatchQueue
    public init(queue: DispatchQueue = DispatchQueue(label: "relay.gossip.scheduler")) { self.queue = queue }

    public func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void) {
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(max(0, delayMs))), execute: task)
    }
}

/// Bookkeeping for the duplicate-link rule (SPEC section 2).
public enum DuplicateLinkPolicy {
    /// When two links reach the same node id, keep the link where the node with the numerically
    /// lower id (unsigned, big-endian) is the CENTRAL. Returns true iff THIS node ([myId]) should be
    /// the central on the kept link, i.e. `myId < peerId`. If you are central on a duplicate link
    /// and this returns false, close that link; if you are peripheral and it returns true, the peer
    /// is expected to close its side (close yours too if it lingers).
    public static func keepAsCentral(myId: [UInt8], peerId: [UInt8]) -> Bool {
        NodeId.compare(myId, peerId) < 0
    }
}

/// One stored NEED/ACK frame. [raw] is the encoded frame as received (or originated).
public struct StoredPacket: Equatable, Hashable, Sendable {
    public let key: String
    public let type: Int
    public let raw: [UInt8]
    /// NEED: created_at in ms. ACK: first-stored time in ms. Expiry is `basis + 72 h`.
    public let expiryBasisMs: Int64

    public init(key: String, type: Int, raw: [UInt8], expiryBasisMs: Int64) {
        self.key = key; self.type = type; self.raw = raw; self.expiryBasisMs = expiryBasisMs
    }
}

/// Persistence seam. The engine serialises all calls under its own lock.
public protocol PacketStore {
    func put(_ packet: StoredPacket)
    func get(_ key: String) -> StoredPacket?
    func contains(_ key: String) -> Bool
    /// Keys in insertion order (oldest first).
    func allKeys() -> [String]
    func remove(_ key: String)
    /// Removes every packet whose `expiryBasisMs < cutoffMs`; returns the removed keys.
    func expire(_ cutoffMs: Int64) -> [String]
    func all() -> [StoredPacket]
}

extension PacketStore {
    public func all() -> [StoredPacket] { allKeys().compactMap { get($0) } }
}

/// Thread-safe in-memory store that keeps insertion order (like Kotlin's `LinkedHashMap`: re-putting a key
/// replaces the packet but keeps its position).
public final class InMemoryPacketStore: PacketStore, @unchecked Sendable {
    private let lock = NSLock()
    private var order: [String] = []
    private var map: [String: StoredPacket] = [:]

    public init() {}

    public func put(_ packet: StoredPacket) {
        lock.lock(); defer { lock.unlock() }
        if map[packet.key] == nil { order.append(packet.key) }
        map[packet.key] = packet
    }
    public func get(_ key: String) -> StoredPacket? { lock.lock(); defer { lock.unlock() }; return map[key] }
    public func contains(_ key: String) -> Bool { lock.lock(); defer { lock.unlock() }; return map[key] != nil }
    public func allKeys() -> [String] { lock.lock(); defer { lock.unlock() }; return order }
    public func remove(_ key: String) {
        lock.lock(); defer { lock.unlock() }
        if map.removeValue(forKey: key) != nil { order.removeAll { $0 == key } }
    }
    public func all() -> [StoredPacket] { lock.lock(); defer { lock.unlock() }; return order.compactMap { map[$0] } }
    public func expire(_ cutoffMs: Int64) -> [String] {
        lock.lock(); defer { lock.unlock() }
        let dead = order.filter { (map[$0]?.expiryBasisMs ?? Int64.max) < cutoffMs }
        if !dead.isEmpty {
            let deadSet = Set(dead)
            for k in dead { map.removeValue(forKey: k) }
            order.removeAll { deadSet.contains($0) }
        }
        return dead
    }
}

/// Counters, for diagnostics and the simulator.
public struct GossipStats: Equatable, Sendable {
    public var framesReceived: Int64 = 0
    public var framesRejected: Int64 = 0
    /// NEED/ACK/opaque frames dropped because their key was already known.
    public var duplicates: Int64 = 0
    public var framesSent: Int64 = 0
    public var needsDelivered: Int64 = 0
    public var acksDelivered: Int64 = 0

    public init() {}
}
