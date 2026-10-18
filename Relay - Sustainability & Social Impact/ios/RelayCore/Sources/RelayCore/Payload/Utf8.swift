import Foundation

/// UTF-8 helpers that never split a code point.
public enum Utf8 {
    private static func cpBytes(_ cp: UInt32) -> Int {
        if cp < 0x80 { return 1 }
        if cp < 0x800 { return 2 }
        if cp < 0x10000 { return 3 }
        return 4
    }

    /// UTF-8 bytes of [s], at most [maxBytes] long, cut on a code point (Unicode scalar) boundary. Swift strings
    /// cannot hold a lone surrogate, so unlike the Kotlin original there is no U+FFFD substitution to do here.
    public static func truncateToBytes(_ s: String, _ maxBytes: Int) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(max(0, min(s.utf8.count, maxBytes)))
        var written = 0
        for scalar in s.unicodeScalars {
            let n = cpBytes(scalar.value)
            if written + n > maxBytes { break }
            out.append(contentsOf: Array(String(scalar).utf8))
            written += n
        }
        return out
    }

    /// [s] truncated so its UTF-8 encoding is at most [maxBytes], on a code point boundary.
    public static func truncate(_ s: String, _ maxBytes: Int) -> String {
        String(decoding: truncateToBytes(s, maxBytes), as: UTF8.self)
    }

    /// Strict decode of `bytes[offset ..< offset + length]`: nil on any malformed sequence (bad lead or
    /// continuation byte, overlong form, surrogate, beyond U+10FFFF, truncated sequence).
    public static func decodeStrict(_ bytes: [UInt8], offset: Int = 0, length: Int? = nil) -> String? {
        let len = length ?? (bytes.count - offset)
        guard offset >= 0, len >= 0, offset + len <= bytes.count else { return nil }
        let end = offset + len
        var i = offset
        while i < end {
            let b0 = bytes[i]
            if b0 < 0x80 { i += 1; continue }
            var need = 0
            var minCp: UInt32 = 0
            var cp: UInt32 = 0
            switch b0 {
            case 0xC2...0xDF: need = 1; minCp = 0x80; cp = UInt32(b0 & 0x1F)
            case 0xE0...0xEF: need = 2; minCp = 0x800; cp = UInt32(b0 & 0x0F)
            case 0xF0...0xF4: need = 3; minCp = 0x10000; cp = UInt32(b0 & 0x07)
            default: return nil
            }
            for k in 1...need {
                guard i + k < end else { return nil }
                let c = bytes[i + k]
                if c & 0xC0 != 0x80 { return nil }
                cp = cp << 6 | UInt32(c & 0x3F)
            }
            if cp < minCp || cp > 0x10FFFF || (0xD800...0xDFFF).contains(cp) { return nil }
            i += need + 1
        }
        return String(decoding: bytes[offset..<end], as: UTF8.self)
    }
}
