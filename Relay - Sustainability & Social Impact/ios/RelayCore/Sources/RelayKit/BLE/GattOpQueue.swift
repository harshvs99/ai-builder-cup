import Foundation

public enum StartResult: Sendable, Hashable {
    /// The operation was issued; wait for `GattOpQueue.onComplete`.
    case started
    /// The stack was busy; try again on a later `GattOpQueue.tick`.
    case retry
    /// The operation cannot be issued; drop it and move on.
    case failed
}

/// One GATT operation as a closure, so any transport can drive the queue (CoreBluetooth writes, notifies, ...).
/// `start` issues the operation and says how that went; completion is reported separately through
/// `GattOpQueue.onComplete`, from the stack's matching delegate callback.
public struct GattOp {
    public let label: String
    public let start: () -> StartResult

    public init(_ label: String, start: @escaping () -> StartResult) {
        self.label = label; self.start = start
    }
}

/// Serialises GATT operations on one connection: the stack allows one outstanding operation per peripheral.
/// The next operation starts only when `onComplete` is called (from the matching stack callback) or the in-flight
/// one times out. No CoreBluetooth types, so it is unit-testable.
///
/// Intended to be driven from a single queue; methods lock anyway (recursively: an op's `start` may complete
/// synchronously by calling `onComplete`).
public final class GattOpQueue: @unchecked Sendable {
    private final class Entry {
        let op: GattOp
        var retries = 0
        init(_ op: GattOp) { self.op = op }
    }

    private let timeoutMs: Int64
    private let maxQueued: Int
    private let maxRetries: Int
    private let now: @Sendable () -> Int64
    private let log: @Sendable (String) -> Void

    private let lock = NSRecursiveLock()
    private var pending: [Entry] = []
    private var current: Entry?
    private var startedAt: Int64 = 0
    private var pumping = false
    /// Set when the stack said busy: retries happen on `tick`, not on every enqueue/complete, so bursts don't burn the retry budget.
    private var waitingForTick = false
    private var consecutiveTimeoutsValue = 0
    private var totalTimeoutsValue = 0
    private var droppedValue = 0

    public init(
        timeoutMs: Int64 = BleConstants.opTimeoutMs, maxQueued: Int = 512, maxRetries: Int = 8,
        now: @escaping @Sendable () -> Int64 = { monotonicMs() }, log: @escaping @Sendable (String) -> Void = { _ in }
    ) {
        self.timeoutMs = timeoutMs; self.maxQueued = maxQueued; self.maxRetries = maxRetries
        self.now = now; self.log = log
    }

    /// Operations that timed out since the last successful completion.
    public var consecutiveTimeouts: Int { locked { consecutiveTimeoutsValue } }
    public var totalTimeouts: Int { locked { totalTimeoutsValue } }
    public var dropped: Int { locked { droppedValue } }

    public func size() -> Int { locked { pending.count + (current != nil ? 1 : 0) } }
    public func inFlight() -> Bool { locked { current != nil } }

    /// Returns false (and counts a drop) when the queue is full.
    @discardableResult
    public func enqueue(_ op: GattOp) -> Bool {
        locked {
            if pending.count >= maxQueued {
                droppedValue += 1
                log("gatt queue full, dropping \(op.label)")
                return false
            }
            pending.append(Entry(op))
            pump()
            return true
        }
    }

    /// The stack reported that the in-flight operation finished (success or error).
    @discardableResult
    public func onComplete() -> Bool {
        locked {
            if current == nil { return false }
            current = nil
            consecutiveTimeoutsValue = 0
            pump()
            return true
        }
    }

    /// Call periodically (a few times a second): times out a stuck operation and retries busy ones.
    public func tick() {
        locked {
            waitingForTick = false
            if let c = current, now() - startedAt >= timeoutMs {
                log("gatt op '\(c.op.label)' timed out after \(timeoutMs)ms; advancing")
                current = nil
                consecutiveTimeoutsValue += 1
                totalTimeoutsValue += 1
            }
            pump()
        }
    }

    public func clear() {
        locked {
            pending.removeAll()
            current = nil
            waitingForTick = false
        }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body()
    }

    private func pump() {
        if pumping || waitingForTick { return }
        pumping = true
        defer { pumping = false }
        while current == nil {
            if pending.isEmpty { return }
            let next = pending.removeFirst()
            current = next
            startedAt = now()
            let result = next.op.start()
            switch result {
            case .started:
                break // may already have completed synchronously: then current !== next
            case .failed:
                if current === next { current = nil }
                droppedValue += 1
            case .retry:
                if current === next { current = nil }
                next.retries += 1
                if next.retries > maxRetries {
                    droppedValue += 1
                    log("gatt op '\(next.op.label)' still busy after \(maxRetries) retries; dropped")
                    continue
                }
                pending.insert(next, at: 0)
                waitingForTick = true
                return // wait for the next tick()
            }
        }
    }
}
