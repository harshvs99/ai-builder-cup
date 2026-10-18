import Foundation

/// A position fix. `atMs` is the epoch ms the fix was taken (0 if unknown).
public struct Fix: Equatable, Sendable {
    public let lat: Double
    public let lon: Double
    /// Horizontal accuracy in metres (`CLLocation.horizontalAccuracy`); negative or NaN = unknown.
    public let accuracyM: Double
    public let atMs: Int64

    public init(lat: Double, lon: Double, accuracyM: Double, atMs: Int64 = 0) {
        self.lat = lat; self.lon = lon; self.accuracyM = accuracyM; self.atMs = atMs
    }

    public var locAcc: Int { LocAccuracy.bucket(accuracyM) }
}

/// Pure rules for which fix to keep. No platform types.
public enum FixPolicy {
    /// A fix older than this is not offered as the current position (also the cap for last-known seeds).
    public static let maxAgeMs: Int64 = 120_000

    /// A kept fix older than this is replaced by any fresh one, even a less accurate one.
    public static let staleAfterMs: Int64 = 45_000

    /// Accuracy at which listening stops early: it already maps to the best loc_acc bucket (< 10 m).
    public static let goodEnoughM = 10.0

    private static func acc(_ f: Fix) -> Double { f.accuracyM.isNaN || f.accuracyM < 0 ? Double.greatestFiniteMagnitude : f.accuracyM }

    public static func isUsable(_ f: Fix, nowMs: Int64) -> Bool { f.atMs <= 0 || nowMs - f.atMs <= maxAgeMs }

    public static func isGoodEnough(_ f: Fix) -> Bool { acc(f) < goodEnoughM }

    /// True when [candidate] should replace [current].
    public static func isBetter(current: Fix?, candidate: Fix, nowMs: Int64) -> Bool {
        guard let current else { return true }
        if current.atMs > 0 && nowMs - current.atMs > staleAfterMs { return true }
        return acc(candidate) <= acc(current)
    }
}

/// What the confirm card shows about location.
public enum LocUi: Sendable, Hashable { case noPermission, off, acquiring, ready }

public enum LocationState {
    public static func ui(hasPermission: Bool, systemEnabled: Bool, fix: Fix?, nowMs: Int64) -> LocUi {
        if let fix, FixPolicy.isUsable(fix, nowMs: nowMs) { return .ready }
        if !hasPermission { return .noPermission }
        if !systemEnabled { return .off }
        return .acquiring
    }

    public static func accuracyText(_ accuracyM: Double) -> String {
        if accuracyM.isNaN || accuracyM < 0 { return "accuracy unknown" }
        if accuracyM >= 1000 { return "±" + String(format: "%.1f", accuracyM / 1000) + " km" }
        return "±\(Int(accuracyM.rounded(.toNearestOrAwayFromZero))) m"
    }

    public static func label(_ ui: LocUi, fix: Fix?) -> String {
        switch ui {
        case .ready: return "📍 " + accuracyText(fix?.accuracyM ?? Double.nan)
        case .acquiring: return "📍 Getting location…"
        case .off: return "📍 Location is off"
        case .noPermission: return "📍 No permission"
        }
    }

    /// Shown under the location line when the NEED would go out without coordinates.
    public static func fallbackNote(_ ui: LocUi) -> String? { ui == .ready ? nil : "Rescuers will rely on the landmark" }

    /// Only an in-progress acquisition is worth waiting for; off / no permission cannot produce a fix.
    public static func shouldWaitForFix(_ ui: LocUi) -> Bool { ui == .acquiring }

    /// True when a stopped tracker should be restarted for a visible confirm card.
    public static func shouldRestart(_ ui: LocUi, listening: Bool, cardVisible: Bool) -> Bool {
        cardVisible && ui == .acquiring && !listening
    }
}

public enum FixWait {
    public static let defaultTimeoutMs: Int64 = 10_000

    private enum Outcome: Sendable { case fix(Fix), skipped, timedOut, streamEnded }

    /// Returns the first non-nil fix emitted by [fixes] (the current value counts, so pass a `Broadcaster` stream),
    /// or nil when [timeoutMs] passes or [skip] yields true ("Send now"). Never waits longer than [timeoutMs].
    /// (Kotlin `FixWait.await`; renamed because `await` is a Swift keyword.)
    public static func wait(fixes: AsyncStream<Fix?>, skip: AsyncStream<Bool>, timeoutMs: Int64 = defaultTimeoutMs) async -> Fix? {
        await withTaskGroup(of: Outcome.self) { group in
            group.addTask {
                for await f in fixes { if let f { return .fix(f) } }
                return .streamEnded
            }
            group.addTask {
                for await s in skip where s { return .skipped }
                return .streamEnded
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(max(0, timeoutMs)) * 1_000_000)
                return Task.isCancelled ? .streamEnded : .timedOut
            }
            var result: Fix?
            while let o = await group.next() {
                switch o {
                case .fix(let f): result = f; group.cancelAll(); return result
                case .skipped, .timedOut: group.cancelAll(); return nil
                case .streamEnded: continue
                }
            }
            return nil
        }
    }
}
