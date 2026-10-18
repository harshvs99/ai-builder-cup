import Foundation

/// This phone's own position fix. [atMs] is when it was taken (epoch ms).
public struct RelayFix: Equatable, Sendable {
    public var lat: Double
    public var lon: Double
    public var accuracyM: Float
    public var atMs: Int64

    public init(lat: Double, lon: Double, accuracyM: Float, atMs: Int64) {
        self.lat = lat; self.lon = lon; self.accuracyM = accuracyM; self.atMs = atMs
    }
}

/// Decides when this phone puts a LOC_HINT (SPEC 5.5) into the mesh. Feed it every NEED the gossip node delivers
/// (`onNeed(_:fromLink:)`, from `GossipListener.onNeed`); it emits at most one hint per NEED, and only when ALL of
/// these hold:
///
///  - `enabled` ("Help locate neighbours") is on, checked again at the moment of emission;
///  - the NEED has `loc_acc == 0` (its requester has no fix);
///  - it arrived directly from its origin: the HELLO of the link it came in on carries `origin_node`;
///  - `fix` returns a fix taken within `maxFixAgeMs` with accuracy <= 100 m.
///
/// With no good fix yet it calls `requestFix` once (the platform may start a short location listen) and checks again
/// after each of `retryDelaysMs`, so a phone whose GPS was idle can still help; then it gives up for that NEED.
/// RSSI and observed_at are taken when the NEED arrives, not when the hint is finally sent.
public final class LocHintResponder: @unchecked Sendable {
    /// A fix older than this is not "where the phone is now". Same as the app's FixPolicy.MAX_AGE_MS.
    public static let maxFixAgeMs: Int64 = 120_000
    /// 3 s + 7 s + 20 s: the hint goes out within 30 s of the NEED or not at all.
    public static let defaultRetryDelaysMs: [Int64] = [3_000, 7_000, 20_000]

    private let node: GossipNode
    private let clock: any Clock
    private let scheduler: any Scheduler
    private let enabled: @Sendable () -> Bool
    private let fix: @Sendable () -> RelayFix?
    private let rssi: @Sendable (_ linkId: String) -> Int?
    private let requestFix: @Sendable () -> Void
    private let log: @Sendable (String) -> Void
    private let retryDelaysMs: [Int64]

    /// `rssi` is the signal strength of the link in dBm, or nil when unknown.
    public init(
        node: GossipNode, clock: any Clock, scheduler: any Scheduler,
        enabled: @escaping @Sendable () -> Bool,
        fix: @escaping @Sendable () -> RelayFix?,
        rssi: @escaping @Sendable (_ linkId: String) -> Int? = { _ in nil },
        requestFix: @escaping @Sendable () -> Void = {},
        log: @escaping @Sendable (String) -> Void = { _ in },
        retryDelaysMs: [Int64] = LocHintResponder.defaultRetryDelaysMs
    ) {
        self.node = node; self.clock = clock; self.scheduler = scheduler; self.enabled = enabled; self.fix = fix
        self.rssi = rssi; self.requestFix = requestFix; self.log = log; self.retryDelaysMs = retryDelaysMs
    }

    /// Call for each NEED newly delivered by the node. [fromLink] is nil for a NEED originated on this phone.
    public func onNeed(_ need: Need, fromLink: String?) {
        guard let fromLink, need.locAcc == 0, enabled() else { return }
        guard let peer = node.peers()[fromLink] else { return }
        if peer.nodeId != need.originNode || need.originNode == node.nodeId { return }
        let observedAt = clock.nowMs() / 1000
        let rssiDbm = rssi(fromLink).map { min(127, max(-127, $0)) } ?? LocHintCodec.rssiUnknown
        attempt(need, observedAt, rssiDbm, 0)
    }

    private func attempt(_ need: Need, _ observedAt: Int64, _ rssiDbm: Int, _ tries: Int) {
        if !enabled() { return }
        let now = clock.nowMs()
        let f = fix()
        let bucket: Int? = f.flatMap { f in
            (0...Self.maxFixAgeMs).contains(now - f.atMs) && (-90.0...90.0).contains(f.lat) && (-180.0...180.0).contains(f.lon)
                ? LocHintCodec.bucketFor(f.accuracyM) : nil
        }
        if let f, let bucket {
            let hint = LocHint(
                refMsgId: need.msgId, relayNode: node.nodeId, latE5: Geo.toE5(f.lat), lonE5: Geo.toE5(f.lon),
                acc: bucket, rssiDbm: rssiDbm, observedAt: observedAt)
            if (try? node.originateLocHint(hint)) != nil {
                log("LOC_HINT sent for \(need.msgIdHex): acc<=\(hint.accuracyMaxM) m rssi="
                    + (rssiDbm == LocHintCodec.rssiUnknown ? "?" : String(rssiDbm)))
            }
            return
        }
        if tries == 0 { requestFix() }
        guard tries < retryDelaysMs.count else {
            log("no usable fix to hint \(Hex.encode(need.msgId)); skipped")
            return
        }
        scheduler.schedule(delayMs: retryDelaysMs[tries]) { [self] in attempt(need, observedAt, rssiDbm, tries + 1) }
    }
}
