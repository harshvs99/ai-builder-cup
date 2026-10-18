import Foundation

/// A value that many observers can watch: the Swift stand-in for Kotlin's `MutableStateFlow`.
///
/// `updates()` yields the current value first and then every distinct change (equal values are not re-sent). Each
/// subscriber keeps only the newest unread value, so a slow SwiftUI wrapper never builds a backlog. Thread-safe;
/// `send(_:)` may be called from any thread.
public final class Broadcaster<Value: Sendable & Equatable>: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Value
    private var nextId = 0
    private var continuations: [Int: AsyncStream<Value>.Continuation] = [:]

    public init(_ initial: Value) { current = initial }

    /// The latest value.
    public var value: Value { lock.lock(); defer { lock.unlock() }; return current }

    /// Replaces the value; observers are notified only when it differs from the current one.
    public func send(_ next: Value) {
        modify { $0 = next }
    }

    /// Mutates the value in place under the lock and notifies observers if it changed.
    public func modify(_ f: (inout Value) -> Void) {
        lock.lock()
        var next = current
        f(&next)
        if next == current { lock.unlock(); return }
        current = next
        let targets = Array(continuations.values)
        lock.unlock()
        for c in targets { c.yield(next) }
    }

    /// A stream that yields the current value immediately, then each change. It ends when the consumer stops.
    public func updates() -> AsyncStream<Value> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            lock.lock()
            let id = nextId
            nextId += 1
            continuations[id] = continuation
            let now = current
            lock.unlock()
            continuation.onTermination = { [weak self] _ in
                guard let self else { return }
                self.lock.lock()
                self.continuations.removeValue(forKey: id)
                self.lock.unlock()
            }
            continuation.yield(now)
        }
    }
}
