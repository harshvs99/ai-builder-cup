import Foundation

/// Orders and caps the frames pushed to a peer after an INVENTORY diff, so one link-up carries what matters
/// most inside a bounded burst and the rest follows later. Local policy only: nothing here is on the wire.
///
/// Order: ACKs addressed to the peer itself, other ACKs (they close the loop for a villager), then NEEDs, then
/// LOC_HINTs (they refine a location, they never gate a rescue). Each group is newest first. The batch is cut at [budgetBytes] of raw frame bytes, but always
/// holds at least one frame so progress is guaranteed.
public enum PushPlanner {
    /// About two ATT MTUs of payload (2 x (517 - 3)): a handful of frames per burst.
    public static let defaultBudgetBytes = 2 * (517 - 3)

    public struct Plan: Sendable {
        public let batch: [StoredPacket]
        public let rest: [StoredPacket]
    }

    public static func order(_ candidates: [StoredPacket], peerNodeId: [UInt8]?) -> [StoredPacket] {
        func group(_ p: StoredPacket) -> Int {
            if p.type == FrameTypes.ack, let peer = peerNodeId, recipientIs(p, peer) { return 0 }
            if p.type == FrameTypes.ack { return 1 }
            if p.type == FrameTypes.need { return 2 }
            return 3
        }
        // Stable: ties keep candidate (insertion) order, like Kotlin's sortedWith.
        return candidates.enumerated().map { (i: $0.offset, p: $0.element, g: group($0.element)) }
            .sorted { a, b in
                if a.g != b.g { return a.g < b.g }
                if a.p.expiryBasisMs != b.p.expiryBasisMs { return a.p.expiryBasisMs > b.p.expiryBasisMs }
                return a.i < b.i
            }
            .map { $0.p }
    }

    public static func plan(_ candidates: [StoredPacket], peerNodeId: [UInt8]?, budgetBytes: Int = defaultBudgetBytes) -> Plan {
        let ordered = order(candidates, peerNodeId: peerNodeId)
        var used = 0
        var n = 0
        for p in ordered {
            if n > 0 && used + p.raw.count > budgetBytes { break }
            used += p.raw.count
            n += 1
        }
        return Plan(batch: Array(ordered[0..<n]), rest: Array(ordered[n...]))
    }

    private static func recipientIs(_ p: StoredPacket, _ node: [UInt8]) -> Bool {
        FrameCodec.decode(p.raw)?.recipientId == node
    }
}
