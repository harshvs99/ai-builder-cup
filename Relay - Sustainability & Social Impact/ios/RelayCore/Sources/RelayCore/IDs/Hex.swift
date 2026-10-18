import Foundation

/// Thrown by encoders and ID helpers for programmer errors (a field out of its wire range, an id of the wrong
/// length). The Kotlin original throws `IllegalArgumentException` from `require`; decoders never throw, they return nil.
public struct IllegalArgumentError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// Lowercase hex helpers.
public enum Hex {
    private static let digits: [UInt8] = Array("0123456789abcdef".utf8)

    public static func encode(_ bytes: [UInt8]) -> String {
        var out = [UInt8]()
        out.reserveCapacity(bytes.count * 2)
        for b in bytes {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 0x0F)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Returns nil for odd length or non-hex characters (upper case is accepted).
    public static func decodeOrNull(_ hex: String) -> [UInt8]? {
        let chars = Array(hex.utf8)
        if chars.count % 2 != 0 { return nil }
        var out = [UInt8]()
        out.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return nil }
            out.append(UInt8(hi << 4 | lo))
            i += 2
        }
        return out
    }

    public static func decode(_ hex: String) throws -> [UInt8] {
        guard let b = decodeOrNull(hex) else { throw IllegalArgumentError("invalid hex: \(hex)") }
        return b
    }

    private static func nibble(_ c: UInt8) -> Int? {
        switch c {
        case 0x30...0x39: return Int(c) - 0x30
        case 0x61...0x66: return Int(c) - 0x61 + 10
        case 0x41...0x46: return Int(c) - 0x41 + 10
        default: return nil
        }
    }
}
