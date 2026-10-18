import Foundation

/// ACK payload (SPEC 5.2).
public struct Ack: Equatable, Hashable, Sendable {
    public var ackId: [UInt8]
    public var refMsgId: [UInt8]
    /// = origin_node of the NEED; also the frame recipient.
    public var targetNode: [UInt8]
    /// u32
    public var ticketNo: Int64
    public var status: AckStatus
    /// u16, 0xFFFF = unknown
    public var etaMin: Int
    /// <= 112 bytes of UTF-8, already in the requester's language.
    public var text: String

    public init(ackId: [UInt8], refMsgId: [UInt8], targetNode: [UInt8], ticketNo: Int64, status: AckStatus,
                etaMin: Int, text: String) {
        self.ackId = ackId; self.refMsgId = refMsgId; self.targetNode = targetNode
        self.ticketNo = ticketNo; self.status = status; self.etaMin = etaMin; self.text = text
    }

    public var ackIdHex: String { Hex.encode(ackId) }

    public static let etaUnknown = 0xFFFF
}

public enum AckCodec {
    public static let version = 1
    public static let maxText = 112
    public static let fixedSize = 32
    public static let maxPayload = fixedSize + 1 + maxText

    public static func encode(_ ack: Ack) throws -> [UInt8] {
        guard ack.ackId.count == 8 else { throw IllegalArgumentError("ack_id must be 8 bytes") }
        guard ack.refMsgId.count == 8 else { throw IllegalArgumentError("ref_msg_id must be 8 bytes") }
        guard ack.targetNode.count == 8 else { throw IllegalArgumentError("target_node must be 8 bytes") }
        guard (0...0xFFFF_FFFF).contains(ack.ticketNo) else { throw IllegalArgumentError("ticket_no out of u32 range") }
        guard (0...0xFFFF).contains(ack.etaMin) else { throw IllegalArgumentError("eta_min out of u16 range") }
        let text = Utf8.truncateToBytes(ack.text, maxText)
        var w = ByteWriter(capacity: fixedSize + 1 + text.count)
        w.u8(version)
        w.put(ack.ackId)
        w.put(ack.refMsgId)
        w.put(ack.targetNode)
        w.u32(ack.ticketNo)
        w.u8(ack.status.code)
        w.u16(ack.etaMin)
        w.u8(text.count)
        w.put(text)
        return w.bytes
    }

    public static func decode(_ p: [UInt8]) -> Ack? {
        if p.count < fixedSize + 1 || p.count > maxPayload { return nil }
        if Int(p[0]) != version { return nil }
        var r = ByteReader(p, at: 1)
        let ackId = r.take(8)
        let ref = r.take(8)
        let target = r.take(8)
        let ticket = r.u32()
        guard let status = AckStatus.fromCode(r.u8()) else { return nil }
        let eta = r.u16()
        guard let text = NeedCodec.readString(&r, max: maxText) else { return nil }
        if r.hasRemaining || !r.ok { return nil }
        return Ack(ackId: ackId, refMsgId: ref, targetNode: target, ticketNo: ticket, status: status, etaMin: eta, text: text)
    }
}
