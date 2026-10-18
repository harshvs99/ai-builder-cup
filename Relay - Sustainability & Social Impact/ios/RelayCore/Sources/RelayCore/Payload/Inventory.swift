import Foundation

/// INVENTORY chunk payload (SPEC 5.4).
public struct Inventory: Equatable, Hashable, Sendable {
    public var chunkIndex: Int
    public var chunkCount: Int
    public var ids: [[UInt8]]

    public init(chunkIndex: Int, chunkCount: Int, ids: [[UInt8]]) {
        self.chunkIndex = chunkIndex; self.chunkCount = chunkCount; self.ids = ids
    }
    public init(_ chunkIndex: Int, _ chunkCount: Int, _ ids: [[UInt8]]) {
        self.init(chunkIndex: chunkIndex, chunkCount: chunkCount, ids: ids)
    }
}

public enum InventoryCodec {
    public static let version = 1
    public static let maxIdsPerChunk = 19
    public static let maxChunks = 255

    public static func encode(_ inv: Inventory) throws -> [UInt8] {
        guard inv.ids.count <= maxIdsPerChunk else { throw IllegalArgumentError("too many ids in chunk") }
        guard (1...maxChunks).contains(inv.chunkCount), (0..<inv.chunkCount).contains(inv.chunkIndex) else {
            throw IllegalArgumentError("chunk index/count out of range")
        }
        guard inv.ids.allSatisfy({ $0.count == 8 }) else { throw IllegalArgumentError("ids must be 8 bytes") }
        var w = ByteWriter(capacity: 4 + 8 * inv.ids.count)
        w.u8(version)
        w.u8(inv.chunkIndex)
        w.u8(inv.chunkCount)
        w.u8(inv.ids.count)
        for id in inv.ids { w.put(id) }
        return w.bytes
    }

    public static func decode(_ p: [UInt8]) -> Inventory? {
        guard p.count >= 4, Int(p[0]) == version else { return nil }
        let index = Int(p[1]), count = Int(p[2]), n = Int(p[3])
        if count < 1 || index >= count || n > maxIdsPerChunk { return nil }
        if p.count != 4 + 8 * n { return nil }
        let ids = (0..<n).map { i in Array(p[(4 + 8 * i)..<(12 + 8 * i)]) }
        return Inventory(chunkIndex: index, chunkCount: count, ids: ids)
    }

    /// Split [ids] into chunks of at most 19. An empty list yields one empty chunk. Lists longer than
    /// 255 * 19 ids are cut to that size (the caller should pass the most important ids first).
    public static func chunk(_ ids: [[UInt8]]) -> [Inventory] {
        let cap = maxChunks * maxIdsPerChunk
        let capped = ids.count > cap ? Array(ids[0..<cap]) : ids
        if capped.isEmpty { return [Inventory(chunkIndex: 0, chunkCount: 1, ids: [])] }
        var parts: [[[UInt8]]] = []
        var i = 0
        while i < capped.count {
            parts.append(Array(capped[i..<min(i + maxIdsPerChunk, capped.count)]))
            i += maxIdsPerChunk
        }
        return parts.enumerated().map { Inventory(chunkIndex: $0.offset, chunkCount: parts.count, ids: $0.element) }
    }
}
