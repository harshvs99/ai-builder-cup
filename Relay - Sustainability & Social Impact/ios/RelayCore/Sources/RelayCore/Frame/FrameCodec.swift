import Foundation

/// Encoder/decoder for the bitchat-v1-compatible outer frame (SPEC section 3).
public enum FrameCodec {
    public static let headerSize = 14
    public static let idSize = 8
    public static let signatureSize = 64
    /// Smallest valid frame: header + sender id, empty payload.
    public static let minSize = headerSize + idSize

    public static let flagHasRecipient = 0x01
    public static let flagHasSignature = 0x02
    public static let flagIsCompressed = 0x04
    public static let flagHasRoute = 0x08
    public static let flagIsRsr = 0x10

    private static let ttlOffset = 2
    private static let tsOffset = 3

    /// Encode per the Relay encoder rules: version 1, no padding, compressed/route/RSR flags 0.
    /// Throws `IllegalArgumentError` for frames that cannot be represented (programmer error).
    public static func encode(_ frame: Frame) throws -> [UInt8] {
        guard frame.version == 1 else { throw IllegalArgumentError("only version 1 is supported") }
        guard (0...255).contains(frame.type) else { throw IllegalArgumentError("type out of range") }
        guard (0...255).contains(frame.ttl) else { throw IllegalArgumentError("ttl out of range") }
        guard frame.senderId.count == idSize else { throw IllegalArgumentError("sender_id must be 8 bytes") }
        if let rec = frame.recipientId, rec.count != idSize { throw IllegalArgumentError("recipient_id must be 8 bytes") }
        if let sig = frame.signature, sig.count != signatureSize { throw IllegalArgumentError("signature must be 64 bytes") }
        guard frame.payload.count <= 0xFFFF else { throw IllegalArgumentError("payload too large") }

        let flags = (frame.recipientId != nil ? flagHasRecipient : 0) | (frame.signature != nil ? flagHasSignature : 0)
        var w = ByteWriter(capacity: minSize + (frame.recipientId != nil ? idSize : 0) + frame.payload.count
            + (frame.signature?.count ?? 0))
        w.u8(1)
        w.u8(frame.type)
        w.u8(frame.ttl)
        w.i64(frame.timestampMs)
        w.u8(flags)
        w.u16(frame.payload.count)
        w.put(frame.senderId)
        if let rec = frame.recipientId { w.put(rec) }
        w.put(frame.payload)
        if let sig = frame.signature { w.put(sig) }
        return w.bytes
    }

    /// Decode per the Relay decoder rules. Returns nil (never traps) when the frame must be
    /// rejected: wrong version, compressed or route flag, or a byte count that does not exactly
    /// match the header. IS_RSR and unknown flag bits are ignored. A recipient of eight 0xFF bytes
    /// is reported as broadcast (`recipientId == nil`).
    public static func decode(_ bytes: [UInt8]) -> Frame? {
        if bytes.count < minSize { return nil }
        if Int(bytes[0]) != 1 { return nil }
        let flags = Int(bytes[11])
        if flags & (flagIsCompressed | flagHasRoute) != 0 { return nil }
        let payloadLen = Int(bytes[12]) << 8 | Int(bytes[13])
        let hasRecipient = flags & flagHasRecipient != 0
        let hasSignature = flags & flagHasSignature != 0
        let expected = minSize + (hasRecipient ? idSize : 0) + payloadLen + (hasSignature ? signatureSize : 0)
        if bytes.count != expected { return nil }

        let type = Int(bytes[1])
        let ttl = Int(bytes[2])
        var r = ByteReader(bytes, at: tsOffset)
        let ts = r.i64()
        var pos = headerSize
        let sender = Array(bytes[pos..<pos + idSize])
        pos += idSize
        var recipient: [UInt8]?
        if hasRecipient {
            let rec = Array(bytes[pos..<pos + idSize])
            pos += idSize
            recipient = rec.allSatisfy { $0 == 0xFF } ? nil : rec
        }
        let payload = Array(bytes[pos..<pos + payloadLen])
        pos += payloadLen
        let signature = hasSignature ? Array(bytes[pos..<pos + signatureSize]) : nil
        return Frame(version: 1, type: type, ttl: ttl, timestampMs: ts, senderId: sender, recipientId: recipient,
                     payload: payload, signature: signature)
    }

    /// Copy of already-encoded [raw] with the ttl byte replaced (relay: everything else untouched).
    public static func withTtl(_ raw: [UInt8], _ ttl: Int) -> [UInt8] {
        var out = raw
        out[ttlOffset] = UInt8(truncatingIfNeeded: ttl)
        return out
    }

    /// Copy of already-encoded [raw] with the timestamp replaced (inventory push re-stamp).
    public static func withTimestamp(_ raw: [UInt8], _ timestampMs: Int64) -> [UInt8] {
        var out = raw
        for i in 0..<8 { out[tsOffset + i] = UInt8(truncatingIfNeeded: timestampMs >> Int64(56 - 8 * i)) }
        return out
    }
}
