import Foundation
import RelayCore

/// Disasters the control room has declared, kept on the phone. Fed by the broadcast channel (a declaration arrives over the
/// mesh and is carried here) and by the developer setter; read by triage, which asks disaster-specific questions only
/// while a declaration is inside the `DisasterGate` window. Entries older than the window are dropped on every write.
/// Port of Android `DisasterStore`.
public final class DisasterStore: @unchecked Sendable {
    private static let key = "relay.disaster.declared"
    private let store: any KeyValueStore
    private let lock = NSLock()

    public init(store: any KeyValueStore) { self.store = store }

    public func all() -> [DisasterContext] {
        lock.lock(); defer { lock.unlock() }
        return Self.decode(store.stringValue(forKey: Self.key) ?? "")
    }

    /// Adds or refreshes a declaration of the same type: the newest one for a type wins.
    public func put(_ ctx: DisasterContext, nowSec: Int64 = Int64(Date().timeIntervalSince1970)) {
        lock.lock(); defer { lock.unlock() }
        let cur = Self.decode(store.stringValue(forKey: Self.key) ?? "")
        store.setString(Self.encode(Self.withDeclaration(cur, ctx, nowSec: nowSec)), forKey: Self.key)
    }

    /// A control-room broadcast arrived (or was replayed from the mesh store at start-up): a declaration is added with its
    /// area; a "cleared" ends every declaration of that disaster issued at or before it. Idempotent.
    public func apply(_ b: Broadcast, nowSec: Int64 = Int64(Date().timeIntervalSince1970)) {
        if !b.isLive(nowSec: nowSec) { return }
        switch b.kind {
        case .disaster:
            put(DisasterContext(type: b.disaster, issuedAtSec: b.issuedAt,
                                area: b.radiusKm > 0 ? .init(latE5: b.latE5, lonE5: b.lonE5, radiusKm: b.radiusKm) : nil), nowSec: nowSec)
        case .disasterCleared:
            lock.lock(); defer { lock.unlock() }
            let cur = Self.decode(store.stringValue(forKey: Self.key) ?? "")
            store.setString(Self.encode(Self.afterCleared(cur, b.disaster, clearedAtSec: b.issuedAt)), forKey: Self.key)
        }
    }

    public func clear() { lock.lock(); store.removeValue(forKey: Self.key); lock.unlock() }

    /// `here` is the phone's last known position (degrees x 1e5) so an area-limited declaration can be checked; nil = unknown.
    public func active(nowSec: Int64 = Int64(Date().timeIntervalSince1970), here: (latE5: Int, lonE5: Int)? = nil) -> DisasterGate.Active {
        DisasterGate.active(all(), nowSec: nowSec, here: here)
    }

    /// Hours since each live declaration was issued, by type key (for the model's prompt).
    public func agesHours(nowSec: Int64 = Int64(Date().timeIntervalSince1970)) -> [String: Int64] {
        let live = active(nowSec: nowSec).types
        var out: [String: Int64] = [:]
        for c in all() where live.contains(c.type) { out[c.type.key] = max(0, (nowSec - c.issuedAtSec) / 3600) }
        return out
    }

    /// `list` plus `ctx`: the newest declaration per type wins, and anything past the 24 h window is dropped.
    static func withDeclaration(_ list: [DisasterContext], _ ctx: DisasterContext, nowSec: Int64) -> [DisasterContext] {
        let kept = list.filter { $0.type != ctx.type || $0.issuedAtSec > ctx.issuedAtSec }
        let all = kept + (kept.contains { $0.type == ctx.type } ? [] : [ctx])
        return all.filter { nowSec - $0.issuedAtSec <= DisasterGate.maxAgeSec }
    }

    /// `list` without the declarations of `type` issued at or before `clearedAtSec`; a later declaration survives.
    static func afterCleared(_ list: [DisasterContext], _ type: DisasterType, clearedAtSec: Int64) -> [DisasterContext] {
        list.filter { !($0.type == type && $0.issuedAtSec <= clearedAtSec) }
    }

    // type:issued or type:issued:latE5:lonE5:radiusKm
    static func encode(_ l: [DisasterContext]) -> String {
        l.map { c in
            "\(c.type.key):\(c.issuedAtSec)" + (c.area.map { ":\($0.latE5):\($0.lonE5):\($0.radiusKm)" } ?? "")
        }.joined(separator: ",")
    }

    static func decode(_ s: String) -> [DisasterContext] {
        s.split(separator: ",", omittingEmptySubsequences: false).compactMap { part in
            let f = part.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            guard f.count == 2 || f.count == 5, let type = DisasterType.fromKey(f[0]), let at = Int64(f[1]) else { return nil }
            if f.count == 5 {
                guard let la = Int(f[2]), let lo = Int(f[3]), let r = Int(f[4]) else { return nil }
                return DisasterContext(type: type, issuedAtSec: at, area: .init(latE5: la, lonE5: lo, radiusKm: r))
            }
            return DisasterContext(type: type, issuedAtSec: at)
        }
    }
}
