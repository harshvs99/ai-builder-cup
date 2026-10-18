import Foundation

/// Big-endian writer used by the codecs.
struct ByteWriter {
    private(set) var bytes: [UInt8] = []

    init(capacity: Int = 0) { bytes.reserveCapacity(capacity) }

    mutating func u8(_ v: Int) { bytes.append(UInt8(truncatingIfNeeded: v)) }
    mutating func u16(_ v: Int) { u8(v >> 8); u8(v) }
    mutating func u32(_ v: Int64) { for s in [24, 16, 8, 0] { bytes.append(UInt8(truncatingIfNeeded: v >> Int64(s))) } }
    mutating func i32(_ v: Int) { u32(Int64(v)) }
    mutating func i64(_ v: Int64) { for s in stride(from: 56, through: 0, by: -8) { bytes.append(UInt8(truncatingIfNeeded: v >> Int64(s))) } }
    mutating func put(_ b: [UInt8]) { bytes.append(contentsOf: b) }
}

/// Big-endian reader that never traps: reading past the end returns zeros and clears [ok], so decoders can
/// check once and return nil (the Kotlin decoders catch `BufferUnderflowException` for the same reason).
struct ByteReader {
    let bytes: [UInt8]
    var pos: Int
    private(set) var ok = true

    init(_ bytes: [UInt8], at pos: Int = 0) { self.bytes = bytes; self.pos = pos }

    var remaining: Int { bytes.count - pos }
    var hasRemaining: Bool { remaining > 0 }

    mutating func u8() -> Int {
        guard pos < bytes.count else { ok = false; return 0 }
        defer { pos += 1 }
        return Int(bytes[pos])
    }
    mutating func u16() -> Int { let a = u8(); let b = u8(); return a << 8 | b }
    mutating func u32() -> Int64 {
        var v: Int64 = 0
        for _ in 0..<4 { v = v << 8 | Int64(u8()) }
        return v
    }
    /// Signed 32-bit value as an Int.
    mutating func i32() -> Int { Int(Int32(truncatingIfNeeded: u32())) }
    mutating func i64() -> Int64 {
        var v: UInt64 = 0
        for _ in 0..<8 { v = v << 8 | UInt64(u8()) }
        return Int64(bitPattern: v)
    }
    mutating func take(_ n: Int) -> [UInt8] {
        guard n >= 0, remaining >= n else { ok = false; pos = bytes.count; return [] }
        defer { pos += n }
        return Array(bytes[pos..<pos + n])
    }
}
