import Foundation

/// LOC_HINT payload (SPEC 5.5): a relay with a position fix vouches that it heard a no-fix NEED directly from its
/// requester. `acc` is the relay's own accuracy bucket (not the NEED loc_acc buckets): 1 <=10 m, 2 <=25 m, 3 <=50 m,
/// 4 <=100 m.
public struct LocHint: Equatable, Hashable, Sendable {
    public var refMsgId: [UInt8]
    /// Also the frame sender_id.
    public var relayNode: [UInt8]
    public var latE5: Int
    public var lonE5: Int
    public var acc: Int
    /// dBm of the relay's link to the requester, or `LocHintCodec.rssiUnknown`.
    public var rssiDbm: Int
    /// unix seconds, u32: when the relay received the NEED from the requester
    public var observedAt: Int64

    public init(refMsgId: [UInt8], relayNode: [UInt8], latE5: Int, lonE5: Int, acc: Int, rssiDbm: Int, observedAt: Int64) {
        self.refMsgId = refMsgId; self.relayNode = relayNode; self.latE5 = latE5; self.lonE5 = lonE5
        self.acc = acc; self.rssiDbm = rssiDbm; self.observedAt = observedAt
    }

    public var lat: Double { Geo.fromE5(latE5) }
    public var lon: Double { Geo.fromE5(lonE5) }
    /// Upper bound of `acc` in metres.
    public var accuracyMaxM: Int { LocHintCodec.accMaxM[acc] }
    /// Dedupe key in the gossip store (SPEC 5.5).
    public var keyHex: String { Hex.encode(LocHintCodec.key(refMsgId, relayNode)) }
    public var refMsgIdHex: String { Hex.encode(refMsgId) }
}

public enum LocHintCodec {
    public static let version = 1
    public static let size = 31
    public static let rssiUnknown = -128
    /// Upper bound in metres per accuracy bucket; index 0 is unused (0 is not a valid bucket).
    public static let accMaxM = [0, 10, 25, 50, 100]
    /// A relay with a worse fix than this never emits a hint.
    public static let maxAccuracyM: Float = 100

    /// Accuracy bucket for a fix of [accuracyM] metres, or nil when it is too coarse (or unknown) to hint with.
    public static func bucketFor(_ accuracyM: Float) -> Int? {
        if accuracyM.isNaN || accuracyM < 0 { return nil }
        for b in 1...4 where accuracyM <= Float(accMaxM[b]) { return b }
        return nil
    }

    /// First 8 bytes of SHA-256(0x74 || ref_msg_id || relay_node): one hint per relay per NEED.
    public static func key(_ refMsgId: [UInt8], _ relayNode: [UInt8]) -> [UInt8] {
        Array(Sha256.digest([0x74] + refMsgId + relayNode).prefix(8))
    }

    public static func encode(_ h: LocHint) throws -> [UInt8] {
        guard h.refMsgId.count == 8 else { throw IllegalArgumentError("ref_msg_id must be 8 bytes") }
        guard h.relayNode.count == 8 else { throw IllegalArgumentError("relay_node must be 8 bytes") }
        guard (1...4).contains(h.acc) else { throw IllegalArgumentError("acc out of range") }
        guard (-128...127).contains(h.rssiDbm) else { throw IllegalArgumentError("rssi_dbm out of i8 range") }
        guard (0...0xFFFF_FFFF).contains(h.observedAt) else { throw IllegalArgumentError("observed_at out of u32 range") }
        guard coordsInRange(h.latE5, h.lonE5) else { throw IllegalArgumentError("coordinates out of range") }
        var w = ByteWriter(capacity: size)
        w.u8(version)
        w.put(h.refMsgId)
        w.put(h.relayNode)
        w.i32(h.latE5)
        w.i32(h.lonE5)
        w.u8(h.acc)
        w.u8(h.rssiDbm)
        w.u32(h.observedAt)
        return w.bytes
    }

    /// Nil if invalid (SPEC 5.5 validation). Never traps.
    public static func decode(_ p: [UInt8]) -> LocHint? {
        guard p.count == size, Int(p[0]) == version else { return nil }
        var r = ByteReader(p, at: 1)
        let ref = r.take(8)
        let relay = r.take(8)
        let lat = r.i32()
        let lon = r.i32()
        let acc = r.u8()
        guard (1...4).contains(acc), coordsInRange(lat, lon) else { return nil }
        let rssi = Int(Int8(truncatingIfNeeded: r.u8())) // signed
        let observed = r.u32()
        guard r.ok, !r.hasRemaining else { return nil }
        return LocHint(refMsgId: ref, relayNode: relay, latE5: lat, lonE5: lon, acc: acc, rssiDbm: rssi, observedAt: observed)
    }

    private static func coordsInRange(_ latE5: Int, _ lonE5: Int) -> Bool {
        (-9_000_000...9_000_000).contains(latE5) && (-18_000_000...18_000_000).contains(lonE5)
    }
}
