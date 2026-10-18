import Foundation

/// 8-byte identifiers (node ids, msg ids, ack ids).
public enum NodeId {
    public static let size = 8

    /// Random id from the system CSPRNG.
    public static func random() -> [UInt8] {
        var g = SystemRandomNumberGenerator()
        return random(using: &g)
    }

    /// Random id from an injected generator (deterministic tests, simulators).
    public static func random<G: RandomNumberGenerator>(using g: inout G) -> [UInt8] {
        (0..<size).map { _ in UInt8.random(in: 0...255, using: &g) }
    }

    public static func toHex(_ id: [UInt8]) -> String { Hex.encode(id) }

    public static func fromHex(_ hex: String) throws -> [UInt8] {
        let b = try Hex.decode(hex)
        guard b.count == size else { throw IllegalArgumentError("node id must be 8 bytes, got \(b.count)") }
        return b
    }

    /// Unsigned big-endian compare of two 8-byte ids (negative, zero, positive like Kotlin's `compare`).
    public static func compare(_ a: [UInt8], _ b: [UInt8]) -> Int {
        let n = min(a.count, b.count)
        for i in 0..<n {
            let d = Int(a[i]) - Int(b[i])
            if d != 0 { return d }
        }
        return a.count - b.count
    }

    /// Short display form, e.g. "0011…".
    public static func short(_ id: [UInt8]) -> String { Hex.encode(Array(id.prefix(2))) + "…" }
}

/// A small deterministic generator (SplitMix64) for tests and simulators that need reproducible "randomness",
/// the role `java.util.Random(seed)` plays in the Android tests.
public struct SeededRandom: RandomNumberGenerator, Sendable {
    private var state: UInt64
    public init(seed: UInt64) { state = seed }
    public mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
