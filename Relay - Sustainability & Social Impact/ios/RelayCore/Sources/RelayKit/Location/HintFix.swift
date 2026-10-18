import Foundation
import RelayCore

/// Bridge from the app's `Fix` to the core protocol's `RelayFix` for LOC_HINT (SPEC 5.5, mesh-assisted location).
/// Pure Swift, no platform types.
///
/// What a background relay can know about its position: the platform location provider only has a fix while something
/// is listening (a 30 s window opened by `requestFix`), and returns false without the location permission or with
/// Location Services off. On iOS the app has When-In-Use authorization only, so a relay phone usually has a fix only
/// while the app is on screen; hinting from the background would need Always authorization and the `location`
/// background mode, which are deliberately not requested. `LocHintResponder` handles the "no fix right now" case by
/// calling `requestFix` and retrying briefly.
public enum HintFix {
    /// [f] as a `RelayFix`. A fix without a timestamp (atMs <= 0) counts as taken at [nowMs].
    public static func toRelayFix(_ f: Fix, nowMs: Int64) -> RelayFix {
        RelayFix(lat: f.lat, lon: f.lon, accuracyM: Float(f.accuracyM), atMs: f.atMs > 0 ? f.atMs : nowMs)
    }

    /// The current fix as a `RelayFix`, or nil when there is none or it is too old (`FixPolicy.isUsable`).
    public static func current(_ fix: Fix?, nowMs: Int64) -> RelayFix? {
        guard let fix, FixPolicy.isUsable(fix, nowMs: nowMs) else { return nil }
        return toRelayFix(fix, nowMs: nowMs)
    }
}

/// Thread-safe holder of the platform's latest fix. The location provider is main-actor bound while the engine's
/// `LocHintResponder` asks for the fix from its own queue, so the provider publishes into this box.
public final class LatestFix: @unchecked Sendable {
    private let lock = NSLock()
    private var value: Fix?

    public init() {}

    public func set(_ fix: Fix?) { lock.lock(); value = fix; lock.unlock() }
    public func get() -> Fix? { lock.lock(); defer { lock.unlock() }; return value }

    /// `HintFix.current` over the held fix.
    public func relayFix(nowMs: Int64 = currentTimeMs()) -> RelayFix? { HintFix.current(get(), nowMs: nowMs) }
}
