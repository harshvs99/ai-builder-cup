import Foundation

/// Packet type bytes (SPEC section 4).
public enum FrameTypes {
    public static let need = 0x70
    public static let ack = 0x71
    public static let hello = 0x72
    public static let inventory = 0x73
}

/// Outer frame (SPEC section 3). Byte arrays compare by content.
public struct Frame: Equatable, Hashable, Sendable, CustomStringConvertible {
    public var version: Int
    public var type: Int
    public var ttl: Int
    public var timestampMs: Int64
    public var senderId: [UInt8]
    /// nil = broadcast (also used when the wire recipient was eight 0xFF bytes).
    public var recipientId: [UInt8]?
    public var payload: [UInt8]
    public var signature: [UInt8]?

    public init(
        version: Int = 1, type: Int, ttl: Int, timestampMs: Int64, senderId: [UInt8],
        recipientId: [UInt8]? = nil, payload: [UInt8], signature: [UInt8]? = nil
    ) {
        self.version = version; self.type = type; self.ttl = ttl; self.timestampMs = timestampMs
        self.senderId = senderId; self.recipientId = recipientId; self.payload = payload; self.signature = signature
    }

    public var description: String {
        "Frame(type=0x\(String(type, radix: 16)), ttl=\(ttl), ts=\(timestampMs), payload=\(payload.count)B)"
    }
}
