import Foundation

/// Scan duty cycle. Advertising is always on; only the scanner is cycled.
///
/// Choice (kept deliberately simple): below `lowBatteryPct` and not charging, scan 3 s every 30 s; else with the
/// app in the foreground (any screen: the user is watching and the screen is the dominant drain anyway), scan
/// continuously; else scan 3 s / idle 12 s. The cycle is checked on the transport's 500 ms tick, so windows are
/// accurate to about half a second. Every mode stays far below Android's 5 scan starts per 30 s (`ScanThrottle`).
/// (iOS throttles background scanning itself; the CoreBluetooth transport can treat `normal` as "background".)
public enum DutyCycle {
    public static let lowBatteryPct = 20

    public enum Mode: Sendable, Hashable, CaseIterable {
        /// Foreground: one long-lived scan.
        case continuous
        case normal
        case lowBattery

        public var label: String {
            switch self {
            case .continuous: return "foreground, continuous scan"
            case .normal: return "scan 3 s / idle 12 s"
            case .lowBattery: return "battery under 20%: scan 3 s / idle 27 s"
            }
        }
        /// Scan window; nil = scan continuously.
        public var scanMs: Int64? {
            switch self {
            case .continuous: return nil
            case .normal, .lowBattery: return 3_000
            }
        }
        public var idleMs: Int64 {
            switch self {
            case .continuous: return 0
            case .normal: return 12_000
            case .lowBattery: return 27_000
            }
        }
        /// Fraction of time the scanner is on.
        public var dutyFraction: Double {
            guard let s = scanMs else { return 1.0 }
            return Double(s) / Double(s + idleMs)
        }
        /// Scan starts per 30 s window (continuous counts as one).
        public var startsPer30s: Int {
            guard let s = scanMs else { return 1 }
            return Int((30_000.0 / Double(s + idleMs)).rounded(.up))
        }
        /// Kotlin enum `name`, for diagnostics.
        public var name: String {
            switch self {
            case .continuous: return "CONTINUOUS"
            case .normal: return "NORMAL"
            case .lowBattery: return "LOW_BATTERY"
            }
        }
    }

    /// [batteryPct] < 0 means unknown, treated as not low.
    public static func choose(batteryPct: Int, charging: Bool, foreground: Bool) -> Mode {
        if (0..<lowBatteryPct).contains(batteryPct) && !charging { return .lowBattery }
        if foreground { return .continuous }
        return .normal
    }
}
