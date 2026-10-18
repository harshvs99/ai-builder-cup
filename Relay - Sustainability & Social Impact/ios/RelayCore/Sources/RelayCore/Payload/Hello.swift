import Foundation

/// HELLO payload (SPEC 5.3).
public struct Hello: Equatable, Hashable, Sendable {
    public var nodeId: [UInt8]
    public var caps: Int
    /// 0 if gateway, 0xFF unknown, else min(neighbour)+1 capped at 0xFE.
    public var hopsToGateway: Int
    public var appVersion: Int

    public init(nodeId: [UInt8], caps: Int, hopsToGateway: Int, appVersion: Int) {
        self.nodeId = nodeId; self.caps = caps; self.hopsToGateway = hopsToGateway; self.appVersion = appVersion
    }

    public var isGateway: Bool { caps & HelloCodec.capGateway != 0 }
}

public enum HelloCodec {
    public static let version = 1
    public static let size = 13
    public static let capGateway = 1
    public static let hopsUnknown = 0xFF
    public static let hopsMax = 0xFE

    public static func encode(_ h: Hello) throws -> [UInt8] {
        guard h.nodeId.count == 8 else { throw IllegalArgumentError("node_id must be 8 bytes") }
        guard (0...255).contains(h.caps), (0...255).contains(h.hopsToGateway), (0...0xFFFF).contains(h.appVersion) else {
            throw IllegalArgumentError("hello field out of range")
        }
        var w = ByteWriter(capacity: size)
        w.u8(version)
        w.put(h.nodeId)
        w.u8(h.caps)
        w.u8(h.hopsToGateway)
        w.u16(h.appVersion)
        return w.bytes
    }

    public static func decode(_ p: [UInt8]) -> Hello? {
        guard p.count == size, Int(p[0]) == version else { return nil }
        var r = ByteReader(p, at: 1)
        let id = r.take(8)
        let caps = r.u8()
        let hops = r.u8()
        let app = r.u16()
        return Hello(nodeId: id, caps: caps, hopsToGateway: hops, appVersion: app)
    }
}
