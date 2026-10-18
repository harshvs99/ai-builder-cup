import XCTest
import RelayCore
@testable import RelayKit

final class FilePacketStoreTests: TempDirTestCase {
    private func pkt(_ key: String, type: Int = FrameTypes.need, basis: Int64 = 1_000, size: Int = 60) -> StoredPacket {
        let h = key.utf8.reduce(0) { ($0 &* 31 &+ Int($1)) & 0xFF }
        return StoredPacket(key: key, type: type, raw: (0..<size).map { UInt8(truncatingIfNeeded: $0 + h) }, expiryBasisMs: basis)
    }

    func testRoundTripPreservesEverythingInOrder() {
        let f = file("packets.bin")
        let a = FilePacketStore(file: f)
        let packets = [pkt("aa11"), pkt("bb22", type: FrameTypes.ack, basis: 5_000, size: 100), pkt("cc33", basis: 9_999_999_999, size: 140)]
        packets.forEach { a.put($0) }

        let b = FilePacketStore(file: f)
        XCTAssertFalse(b.loadedCorrupt)
        XCTAssertEqual(["aa11", "bb22", "cc33"], b.allKeys())
        for p in packets {
            let got = b.get(p.key)!
            XCTAssertEqual(p.type, got.type)
            XCTAssertEqual(p.expiryBasisMs, got.expiryBasisMs)
            XCTAssertEqual(p.raw, got.raw)
        }
        XCTAssertTrue(b.contains("bb22"))
        XCTAssertNil(b.get("nope"))
    }

    func testRemoveAndExpireArePersisted() {
        let f = file("packets.bin")
        let a = FilePacketStore(file: f)
        a.put(pkt("old1", basis: 10)); a.put(pkt("old2", basis: 20)); a.put(pkt("new1", basis: 1_000)); a.put(pkt("gone", basis: 2_000))
        XCTAssertEqual(["old1", "old2"], Set(a.expire(500)))
        a.remove("gone")
        XCTAssertEqual(["new1"], FilePacketStore(file: f).allKeys())
    }

    func testRewriteIsAtomicNoTempFileLeftAndStaleTempIgnored() throws {
        let f = file("packets.bin")
        let tmpFile = AtomicFiles.tmpFor(f)
        let a = FilePacketStore(file: f)
        a.put(pkt("k1"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: f.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tmpFile.path), "temp file must be renamed away")

        // Simulate a crash mid-rewrite: a half-written temp file next to a good main file.
        try Data([1, 2, 3, 4, 5]).write(to: tmpFile)
        let b = FilePacketStore(file: f)
        XCTAssertEqual(["k1"], b.allKeys())
        b.put(pkt("k2")) // next rewrite replaces the stale temp
        XCTAssertFalse(FileManager.default.fileExists(atPath: tmpFile.path))
        XCTAssertEqual(["k1", "k2"], FilePacketStore(file: f).allKeys())
    }

    func testGarbageFileLoadsEmptyAndDoesNotThrow() throws {
        let f = file("packets.bin")
        try Data("this is not a packet store".utf8).write(to: f)
        let s = FilePacketStore(file: f)
        XCTAssertTrue(s.loadedCorrupt)
        XCTAssertEqual(0, s.size())
        XCTAssertTrue(FileManager.default.fileExists(atPath: file("packets.bin.corrupt").path))
        s.put(pkt("fresh"))
        XCTAssertEqual(["fresh"], FilePacketStore(file: f).allKeys())
    }

    func testTruncatedAndBitFlippedFilesLoadEmpty() throws {
        let f = file("packets.bin")
        do { let s = FilePacketStore(file: f); s.put(pkt("a1")); s.put(pkt("a2")) }
        let good = [UInt8](try Data(contentsOf: f))

        try Data(good.prefix(good.count - 20)).write(to: f)
        do { let s = FilePacketStore(file: f); XCTAssertTrue(s.loadedCorrupt && s.size() == 0) }

        var flipped = good
        flipped[flipped.count / 2] ^= 0x55
        try Data(flipped).write(to: f)
        do { let s = FilePacketStore(file: f); XCTAssertTrue(s.loadedCorrupt && s.size() == 0) }

        try Data().write(to: f)
        XCTAssertEqual(0, FilePacketStore(file: f).size())
    }

    func testMissingFileIsAnEmptyStore() {
        let s = FilePacketStore(file: file("none/packets.bin"))
        XCTAssertEqual(0, s.size()); XCTAssertFalse(s.loadedCorrupt)
    }

    func testDebouncedFlushWritesEventuallyAndOnFlush() {
        let f = file("packets.bin")
        let s = FilePacketStore(file: f, flushDelayMs: 50)
        s.put(pkt("d1"))
        s.flush() // explicit flush is immediate
        XCTAssertEqual(["d1"], FilePacketStore(file: f).allKeys())
        s.put(pkt("d2"))
        XCTAssertTrue(waitUntil(3) { FilePacketStore(file: f).allKeys().count >= 2 })
        XCTAssertEqual(["d1", "d2"], FilePacketStore(file: f).allKeys())
        s.close()
    }

    func testClearEmptiesStoreAndFile() {
        let f = file("packets.bin")
        let s = FilePacketStore(file: f)
        s.put(pkt("x")); s.clear()
        XCTAssertEqual(0, FilePacketStore(file: f).size())
    }

    // ---- cross-platform: the exact bytes Android's `FilePacketStore.encode` produces

    /// magic "RLY1", version 1, count 2, then {UTF "aa11", type 0x70, basis 1000, len 3, raw 01 02 03},
    /// {UTF "é\u{0}", type 0x71, basis -1, len 0}, then the CRC-32 of everything before it as a long.
    /// The body is hand-assembled, not produced by the Swift encoder (Java modified UTF-8: U+0000 is C0 80).
    func testDecodesAndEncodesTheAndroidFileFormatByteForByte() throws {
        let hex = "524c5931" + "0001" + "00000002"
            + "0004" + "61613131" + "70" + "00000000000003e8" + "0003" + "010203"
            + "0004" + "c3a9c080" + "71" + "ffffffffffffffff" + "0000"
        let body = try Hex.decode(hex)
        let crc = CRC32.checksum(body)
        let file = body + (0..<8).map { UInt8((UInt64(crc) >> UInt64(56 - 8 * $0)) & 0xFF) }
        let packets = FilePacketStore.decode(file)
        XCTAssertEqual([
            StoredPacket(key: "aa11", type: 0x70, raw: [1, 2, 3], expiryBasisMs: 1000),
            StoredPacket(key: "é\u{0}", type: 0x71, raw: [], expiryBasisMs: -1),
        ], packets)
        XCTAssertEqual(file, try FilePacketStore.encode(packets!))
        // The standard CRC-32 check value ("123456789" -> cbf43926, same as zlib / java.util.zip): guards the table.
        XCTAssertEqual(0xCBF43926, CRC32.checksum([UInt8]("123456789".utf8)))
    }
}
