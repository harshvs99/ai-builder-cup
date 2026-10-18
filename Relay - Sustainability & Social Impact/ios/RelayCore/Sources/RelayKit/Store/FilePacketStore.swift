import Foundation
import RelayCore

/// [PacketStore] held in memory and mirrored to one file.
///
/// File layout (identical to Android, big-endian):
/// magic(4) version(2) count(4) { key(UTF) type(1) expiryBasisMs(8) rawLen(2) raw } crc32(8, of everything before).
/// `UTF` is Java's `writeUTF`: a 2-byte length then modified UTF-8.
/// The file is rewritten as a whole via temp file + rename ([AtomicFiles]), so a crash leaves either the old or
/// the new complete file. A corrupt or truncated file loads as an empty store (the bad file is renamed to
/// `<name>.corrupt` for post-mortem) and never throws.
///
/// `flushDelayMs` = 0 writes synchronously on every mutation (tests); > 0 coalesces writes on a background queue.
public final class FilePacketStore: PacketStore, @unchecked Sendable {
    private let file: URL
    private let flushDelayMs: Int64
    private let log: @Sendable (String) -> Void
    private let lock = NSRecursiveLock()
    private var order: [String] = []
    private var map: [String: StoredPacket] = [:]
    private var dirty = false
    private var flushScheduled = false
    private var closed = false
    private var corrupt = false
    private let flusher = DispatchQueue(label: "relay-store")

    /// True when the last load found an unreadable file and started empty.
    public var loadedCorrupt: Bool { lock.lock(); defer { lock.unlock() }; return corrupt }

    public init(file: URL, flushDelayMs: Int64 = 0, log: @escaping @Sendable (String) -> Void = { _ in }) {
        self.file = file
        self.flushDelayMs = flushDelayMs
        self.log = log
        load()
    }

    // ------------------------------------------------------------------ PacketStore

    public func put(_ packet: StoredPacket) {
        locked {
            if map[packet.key] == nil { order.append(packet.key) }
            map[packet.key] = packet
            markDirty()
        }
    }
    public func get(_ key: String) -> StoredPacket? { locked { map[key] } }
    public func contains(_ key: String) -> Bool { locked { map[key] != nil } }
    public func allKeys() -> [String] { locked { order } }
    public func all() -> [StoredPacket] { locked { order.compactMap { map[$0] } } }
    public func remove(_ key: String) {
        locked {
            if map.removeValue(forKey: key) != nil {
                order.removeAll { $0 == key }
                markDirty()
            }
        }
    }

    public func expire(_ cutoffMs: Int64) -> [String] {
        locked {
            let dead = order.filter { (map[$0]?.expiryBasisMs ?? Int64.max) < cutoffMs }
            if !dead.isEmpty {
                let gone = Set(dead)
                for k in dead { map.removeValue(forKey: k) }
                order.removeAll { gone.contains($0) }
                markDirty()
            }
            return dead
        }
    }

    public func size() -> Int { locked { map.count } }

    public func clear() {
        locked {
            if !map.isEmpty { map.removeAll(); order.removeAll(); markDirty() }
        }
    }

    // ------------------------------------------------------------------ persistence

    /// Write now if there are unsaved changes.
    public func flush() {
        locked {
            if !dirty { return }
            dirty = false
            do {
                try AtomicFiles.write(file, FilePacketStore.encode(order.compactMap { map[$0] }))
            } catch {
                dirty = true
                log("packet store write failed: \(error)")
            }
        }
    }

    /// Flushes and stops the background writer (later mutations write synchronously).
    public func close() {
        flush()
        locked { closed = true }
    }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        return body()
    }

    private func markDirty() {
        dirty = true
        if flushDelayMs <= 0 || closed { flush(); return }
        if flushScheduled { return }
        flushScheduled = true
        flusher.asyncAfter(deadline: .now() + .milliseconds(Int(flushDelayMs))) { [self] in
            locked { flushScheduled = false; flush() }
        }
    }

    private func load() {
        guard let bytes = AtomicFiles.readOrNull(file) else { return }
        guard let parsed = FilePacketStore.decode(bytes) else {
            corrupt = true
            log("packet store file unreadable; starting empty")
            let bad = file.deletingLastPathComponent().appendingPathComponent(file.lastPathComponent + ".corrupt")
            try? FileManager.default.removeItem(at: bad)
            try? FileManager.default.moveItem(at: file, to: bad)
            return
        }
        for p in parsed {
            if map[p.key] == nil { order.append(p.key) }
            map[p.key] = p
        }
    }

    private static let magic: UInt32 = 0x524C_5931 // "RLY1"
    private static let version: UInt16 = 1

    static func encode(_ packets: [StoredPacket]) throws -> [UInt8] {
        var w = ByteWriter()
        w.u32(magic)
        w.u16(version)
        w.u32(UInt32(packets.count))
        for p in packets {
            try w.utf(p.key)
            w.u8(UInt8(truncatingIfNeeded: p.type))
            w.u64(UInt64(bitPattern: p.expiryBasisMs))
            guard p.raw.count <= 0xFFFF else { throw IllegalArgumentError("raw frame too large") }
            w.u16(UInt16(p.raw.count))
            w.bytes.append(contentsOf: p.raw)
        }
        let crc = CRC32.checksum(w.bytes)
        w.u64(UInt64(crc))
        return w.bytes
    }

    /// Nil when the bytes are not a valid store file.
    static func decode(_ bytes: [UInt8]) -> [StoredPacket]? {
        if bytes.count < 4 + 2 + 4 + 8 { return nil }
        let bodyLen = bytes.count - 8
        var tail = ByteReader(Array(bytes[bodyLen...]))
        guard let crcStored = tail.u64() else { return nil }
        if UInt64(CRC32.checksum(Array(bytes[..<bodyLen]))) != crcStored { return nil }
        var r = ByteReader(Array(bytes[..<bodyLen]))
        guard r.u32() == magic, r.u16() == version, let count = r.u32(), count <= 1_000_000 else { return nil }
        var out: [StoredPacket] = []
        out.reserveCapacity(Int(count))
        for _ in 0..<count {
            guard let key = r.utf(), let type = r.u8(), let basis = r.u64(), let len = r.u16(),
                  let raw = r.take(Int(len)) else { return nil }
            out.append(StoredPacket(key: key, type: Int(type), raw: raw, expiryBasisMs: Int64(bitPattern: basis)))
        }
        return r.remaining == 0 ? out : nil
    }
}

// ---------------------------------------------------------------------- big-endian stream helpers

struct ByteWriter {
    var bytes: [UInt8] = []
    mutating func u8(_ v: UInt8) { bytes.append(v) }
    mutating func u16(_ v: UInt16) { bytes.append(UInt8(v >> 8)); bytes.append(UInt8(v & 0xFF)) }
    mutating func u32(_ v: UInt32) { for s in stride(from: 24, through: 0, by: -8) { bytes.append(UInt8((v >> UInt32(s)) & 0xFF)) } }
    mutating func u64(_ v: UInt64) { for s in stride(from: 56, through: 0, by: -8) { bytes.append(UInt8((v >> UInt64(s)) & 0xFF)) } }

    /// Java `DataOutputStream.writeUTF`: 2-byte length + modified UTF-8 (NUL as C0 80, surrogates as 3-byte units).
    mutating func utf(_ s: String) throws {
        var enc: [UInt8] = []
        for u in s.utf16 {
            switch u {
            case 1...0x7F: enc.append(UInt8(u))
            case 0, 0x80...0x7FF: enc.append(UInt8(0xC0 | (u >> 6))); enc.append(UInt8(0x80 | (u & 0x3F)))
            default:
                enc.append(UInt8(0xE0 | (u >> 12))); enc.append(UInt8(0x80 | ((u >> 6) & 0x3F))); enc.append(UInt8(0x80 | (u & 0x3F)))
            }
        }
        guard enc.count <= 0xFFFF else { throw IllegalArgumentError("key too long") }
        u16(UInt16(enc.count))
        bytes.append(contentsOf: enc)
    }
}

struct ByteReader {
    private let bytes: [UInt8]
    private var pos = 0
    init(_ bytes: [UInt8]) { self.bytes = bytes }
    var remaining: Int { bytes.count - pos }

    mutating func take(_ n: Int) -> [UInt8]? {
        guard n >= 0, remaining >= n else { return nil }
        defer { pos += n }
        return Array(bytes[pos..<(pos + n)])
    }
    mutating func u8() -> UInt8? { take(1)?[0] }
    mutating func u16() -> UInt16? { take(2).map { UInt16($0[0]) << 8 | UInt16($0[1]) } }
    mutating func u32() -> UInt32? { take(4).map { $0.reduce(UInt32(0)) { $0 << 8 | UInt32($1) } } }
    mutating func u64() -> UInt64? { take(8).map { $0.reduce(UInt64(0)) { $0 << 8 | UInt64($1) } } }

    /// Java `DataInputStream.readUTF`.
    mutating func utf() -> String? {
        guard let len = u16(), let b = take(Int(len)) else { return nil }
        var units: [UInt16] = []
        var i = 0
        while i < b.count {
            let c = b[i]
            switch c >> 4 {
            case 0...7: units.append(UInt16(c)); i += 1
            case 12, 13:
                guard i + 1 < b.count, b[i + 1] & 0xC0 == 0x80 else { return nil }
                units.append(UInt16(c & 0x1F) << 6 | UInt16(b[i + 1] & 0x3F)); i += 2
            case 14:
                guard i + 2 < b.count, b[i + 1] & 0xC0 == 0x80, b[i + 2] & 0xC0 == 0x80 else { return nil }
                units.append(UInt16(c & 0x0F) << 12 | UInt16(b[i + 1] & 0x3F) << 6 | UInt16(b[i + 2] & 0x3F)); i += 3
            default: return nil
            }
        }
        return String(decoding: units, as: UTF16.self)
    }
}

/// CRC-32 (IEEE 802.3, the `java.util.zip.CRC32` polynomial).
enum CRC32 {
    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }
    static func checksum(_ bytes: [UInt8]) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in bytes { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return ~c
    }
}
