import Foundation

/// Android permits at most 5 BLE scan starts per 30 s window; never exceed it. iOS has no such limit; the CoreBluetooth
/// transport may simply not use this.
public final class ScanThrottle: @unchecked Sendable {
    private let maxStarts: Int
    private let windowMs: Int64
    private let lock = NSLock()
    private var starts: [Int64] = []

    public init(maxStarts: Int = BleConstants.scanStartsPerWindow, windowMs: Int64 = BleConstants.scanWindowMs) {
        self.maxStarts = maxStarts; self.windowMs = windowMs
    }

    /// Records a start and returns true if one is allowed now.
    public func tryAcquire(now: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        prune(now)
        if starts.count >= maxStarts { return false }
        starts.append(now)
        return true
    }

    /// Milliseconds until the next start would be allowed (0 if allowed now).
    public func waitMs(now: Int64) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        prune(now)
        if starts.count < maxStarts { return 0 }
        return Swift.max(0, starts[0] + windowMs - now)
    }

    /// Forget all recorded starts (the Bluetooth stack was restarted).
    public func reset() {
        lock.lock(); defer { lock.unlock() }
        starts.removeAll()
    }

    private func prune(_ now: Int64) {
        while let f = starts.first, now - f >= windowMs { starts.removeFirst() }
    }
}
