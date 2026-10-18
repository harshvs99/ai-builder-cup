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
}
