import Foundation

/// Per-key (peer address) reconnect backoff: 2 s, 4 s, 8 s ... capped at 60 s. Time is passed in so it is testable.
/// `blockUntil` also lets callers ignore a key for a fixed period (allowlist rejects).
public final class Backoff: @unchecked Sendable {
    private final class Entry {
        var failures = 0
        var nextAllowedAt: Int64 = 0
    }

    private let minMs: Int64
    private let maxMs: Int64
    private let lock = NSLock()
    private var entries: [String: Entry] = [:]

    public init(minMs: Int64 = BleConstants.backoffMinMs, maxMs: Int64 = BleConstants.backoffMaxMs) {
        self.minMs = minMs; self.maxMs = maxMs
    }

    public func canAttempt(_ key: String, now: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return (entries[key]?.nextAllowedAt ?? 0) <= now
    }

    public func remainingMs(_ key: String, now: Int64) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        return max(0, (entries[key]?.nextAllowedAt ?? 0) - now)
    }

    /// Records a failure and returns the delay now imposed on [key].
    @discardableResult
    public func onFailure(_ key: String, now: Int64) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        let e = entries[key] ?? Entry()
        entries[key] = e
        e.failures = min(e.failures + 1, 20)
        let delay = min(maxMs, minMs << Int64(min(e.failures - 1, 20)))
        e.nextAllowedAt = max(e.nextAllowedAt, now + delay)
        return delay
    }

    public func onSuccess(_ key: String) {
        lock.lock(); defer { lock.unlock() }
        entries.removeValue(forKey: key)
    }

    /// Do not attempt [key] before [untilMs]; does not count as a failure.
    public func blockUntil(_ key: String, untilMs: Int64) {
        lock.lock(); defer { lock.unlock() }
        let e = entries[key] ?? Entry()
        entries[key] = e
        e.nextAllowedAt = max(e.nextAllowedAt, untilMs)
    }

    public func failures(_ key: String) -> Int {
        lock.lock(); defer { lock.unlock() }
        return entries[key]?.failures ?? 0
    }

    public func prune(now: Int64) {
        lock.lock(); defer { lock.unlock() }
        entries = entries.filter { !($0.value.nextAllowedAt <= now && $0.value.failures == 0) }
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        entries.removeAll()
    }
}
