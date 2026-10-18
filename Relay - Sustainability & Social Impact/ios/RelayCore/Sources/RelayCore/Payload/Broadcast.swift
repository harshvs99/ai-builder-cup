import Foundation

/// What a `Broadcast` says.
public enum BroadcastKind: Int, Sendable, Hashable {
    /// The control room declares a disaster in the area.
    case disaster = 1
    /// The control room says it is over; ends a declaration of the same disaster issued earlier.
    case disasterCleared = 2

    public var code: Int { rawValue }
    public static func fromCode(_ c: Int) -> BroadcastKind? { BroadcastKind(rawValue: c) }
}

/// BROADCAST payload (SPEC 5.6): the control room speaking to every phone. `radiusKm` 0 = the whole control-room area;
/// otherwise the declaration applies within that distance of (`latE5`, `lonE5`).
public struct Broadcast: Equatable, Hashable, Sendable {
    public var id: [UInt8]
    public var kind: BroadcastKind
    public var disaster: DisasterType
    /// unix seconds, u32: when the control room issued it. Its 24 h life counts from here, not from when a phone got it.
    public var issuedAt: Int64
    public var latE5: Int
    public var lonE5: Int
    public var radiusKm: Int

    public init(id: [UInt8], kind: BroadcastKind, disaster: DisasterType, issuedAt: Int64, latE5: Int = 0, lonE5: Int = 0, radiusKm: Int = 0) {
        self.id = id; self.kind = kind; self.disaster = disaster; self.issuedAt = issuedAt
        self.latE5 = latE5; self.lonE5 = lonE5; self.radiusKm = radiusKm
    }

    public var idHex: String { Hex.encode(id) }
    /// Dedupe key in the gossip store: the id itself.
    public var keyHex: String { idHex }

    /// True while the broadcast is inside its life: not yet expired, and not stamped from the future beyond clock drift.
    public func isLive(nowSec: Int64) -> Bool {
        let age = nowSec - issuedAt
        return age >= -BroadcastCodec.clockSkewSec && age <= BroadcastCodec.ttlSec
    }
}

public enum BroadcastCodec {
    public static let version = 1
    public static let size = 25
    /// A broadcast lives 24 h from issue; phones drop it then and never relay it after.
    public static let ttlSec: Int64 = 24 * 3600
    public static let clockSkewSec: Int64 = 10 * 60

    public static func encode(_ b: Broadcast) throws -> [UInt8] {
        guard b.id.count == 8 else { throw IllegalArgumentError("bcast_id must be 8 bytes") }
        guard (0...0xFFFF_FFFF).contains(b.issuedAt) else { throw IllegalArgumentError("issued_at out of u32 range") }
        guard (0...0xFFFF).contains(b.radiusKm) else { throw IllegalArgumentError("radius_km out of u16 range") }
        guard coordsInRange(b.latE5, b.lonE5) else { throw IllegalArgumentError("coordinates out of range") }
        var w = ByteWriter(capacity: size)
        w.u8(version)
        w.put(b.id)
        w.u8(b.kind.code)
        w.u8(b.disaster.code)
        w.u32(b.issuedAt)
        w.i32(b.latE5)
        w.i32(b.lonE5)
        w.u16(b.radiusKm)
        return w.bytes
    }

    /// Nil if invalid (SPEC 5.6 validation). Never traps.
    public static func decode(_ p: [UInt8]) -> Broadcast? {
        guard p.count == size, Int(p[0]) == version else { return nil }
        var r = ByteReader(p, at: 1)
        let id = r.take(8)
        guard let kind = BroadcastKind.fromCode(r.u8()), let disaster = DisasterType.fromCode(r.u8()) else { return nil }
        let issued = r.u32()
        let lat = r.i32()
        let lon = r.i32()
        let radius = r.u16()
        guard r.ok, coordsInRange(lat, lon) else { return nil }
        return Broadcast(id: id, kind: kind, disaster: disaster, issuedAt: issued, latE5: lat, lonE5: lon, radiusKm: radius)
    }

    private static func coordsInRange(_ latE5: Int, _ lonE5: Int) -> Bool {
        (-9_000_000...9_000_000).contains(latE5) && (-18_000_000...18_000_000).contains(lonE5)
    }
}
