import Foundation

/// Phases of a central link's setup, in order.
public enum SetupPhase: Int, CaseIterable, Sendable, Hashable { case connecting, mtu, discovering, subscribing, up

    /// Kotlin enum `name`, as used in the abort reasons ("setup timeout in DISCOVERING").
    public var name: String {
        switch self {
        case .connecting: return "CONNECTING"
        case .mtu: return "MTU"
        case .discovering: return "DISCOVERING"
        case .subscribing: return "SUBSCRIBING"
        case .up: return "UP"
        }
    }
}

/// When to give up on a half-built central link. No CoreBluetooth types: unit-testable.
public enum LinkHealth {
    /// A connect that has not completed after this long is abandoned (the stack's own timeout is ~30 s).
    public static let connectTimeoutMs: Int64 = 10_000

    /// Reason to tear down a link that is still being set up, or nil to keep waiting. A single timed-out setup
    /// operation (e.g. an MTU request) is fatal at once: nothing else would ever advance the state machine, so waiting
    /// for the overall deadline only stalls the hop for 25 s. The caller applies the reconnect backoff.
    public static func setupAbortReason(phase: SetupPhase, inPhaseMs: Int64, totalMs: Int64, consecutiveOpTimeouts: Int) -> String? {
        if phase == .up { return nil }
        if consecutiveOpTimeouts >= 1 { return "setup operation timed out in \(phase.name)" }
        if phase == .connecting && inPhaseMs > connectTimeoutMs { return "connect timeout" }
        if totalMs > BleConstants.setupTimeoutMs { return "setup timeout in \(phase.name)" }
        return nil
    }

    /// Every link carries a frame at least once per resync (the gossip engine sends an inventory on every link every
    /// `BleConstants.resyncIntervalMs`); a link silent for `livenessTimeoutMs` (3 resyncs: two can be lost or delayed by a
    /// busy radio) is a zombie, e.g. a peer whose process restarted without a disconnect reaching us.
    public static let livenessTimeoutMs: Int64 = 3 * BleConstants.resyncIntervalMs

    /// Reason to close an established link that has received nothing since [lastRxAt], or nil while it is live.
    public static func livenessAbortReason(lastRxAt: Int64, now: Int64) -> String? {
        let idle = now - lastRxAt
        return idle >= livenessTimeoutMs ? "no frame for \(idle / 1000)s (liveness watchdog)" : nil
    }

    /// Delay before the single re-connect after a service-changed event (not the exponential backoff).
    public static let serviceChangedReconnectMs: Int64 = 1_000

    /// Delay before the single retry after discovery found no Relay service.
    public static let discoveryRetryMs: Int64 = 1_500

    /// Discovery found no Relay service on a device we expected to be a peer (it may not have added its GATT service
    /// yet, or we hold a stale cache): retry once after `discoveryRetryMs`, then give up and let the backoff apply.
    /// [retriesSoFar] counts the retries already made for this peer; returns the delay, or nil to give up.
    public static func discoveryRetryDelayMs(retriesSoFar: Int) -> Int64? { retriesSoFar < 1 ? discoveryRetryMs : nil }
}
