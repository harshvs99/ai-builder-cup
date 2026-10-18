import Foundation

/// Dedupe key for unknown types / payload versions (SPEC section 5).
public enum OpaqueKey {
    /// First 8 bytes of SHA-256 over type || sender_id || timestamp_ms (8 bytes BE) || payload.
    public static func of(_ frame: Frame) -> [UInt8] {
        var w = ByteWriter(capacity: 17 + frame.payload.count)
        w.u8(frame.type)
        w.put(frame.senderId)
        w.i64(frame.timestampMs)
        w.put(frame.payload)
        return Array(Sha256.digest(w.bytes).prefix(8))
    }
}
