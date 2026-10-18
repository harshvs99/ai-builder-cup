import XCTest
@testable import RelayCore

final class SizeAndFuzzTests: XCTestCase {
    private let mtuPayload = 182 // 185 - 3

    private func worstNeed(_ s: String) -> Need {
        Need(msgId: NodeId.random(), originNode: NodeId.random(), createdAt: 0xFFFF_FFFF, category: .other, urgency: 3,
             people: 255, vulnFlags: 0xFF, latE5: Int(Int32.min), lonE5: Int(Int32.max), locAcc: 5, lang: .ceb,
             item: s, landmark: s, summary: s)
    }

    func testNeedPayloadNeverExceeds128BytesForAdversarialMultibyteStrings() throws {
        let adversarial = [
            String(repeating: "😀", count: 100),            // 4-byte code points
            String(repeating: "ଅ", count: 100),             // 3-byte Odia
            String(repeating: "पानी", count: 50),           // Devanagari with combining marks
            String(repeating: "é", count: 200),             // 2-byte
            String(repeating: "a", count: 300),             // ascii
            "\u{FFFD}" + String(repeating: "x", count: 100) + "\u{FFFD}", // stands in for the Kotlin lone surrogates
            String(repeating: "\u{0}", count: 100),
            String(repeating: "👨‍👩‍👧‍👦", count: 20),        // ZWJ sequences
        ]
        for s in adversarial {
            let payload = try NeedCodec.encode(worstNeed(s))
            XCTAssertLessThanOrEqual(payload.count, 128, "payload \(payload.count)")
            XCTAssertLessThanOrEqual(payload.count, NeedCodec.maxPayload)
            let frame = try FrameCodec.encode(Frame(type: FrameTypes.need, ttl: 7, timestampMs: T0,
                                                    senderId: [UInt8](repeating: 0, count: 8), payload: payload))
            XCTAssertTrue(frame.count <= 140 && frame.count <= mtuPayload, "frame \(frame.count)")
            let back = try XCTUnwrap(NeedCodec.decode(payload))
            XCTAssertLessThanOrEqual(Utf8.truncateToBytes(back.item, 100).count, 16)
            XCTAssertLessThanOrEqual(back.landmark.utf8.count, 24)
            XCTAssertLessThanOrEqual(back.summary.utf8.count, 40)
        }
    }

    func testRandomMultibyteStringsStayWithinLimitsAndRoundTrip() throws {
        var rnd = SeededRandom(seed: 99)
        let pool = ["a", "é", "ଅ", "पा", "😀", "ö", "ꙮ", "\u{301}"]
        for _ in 0..<2000 {
            var s = ""
            for _ in 0..<Int.random(in: 0..<80, using: &rnd) { s += pool[Int.random(in: 0..<pool.count, using: &rnd)] }
            let payload = try NeedCodec.encode(worstNeed(s))
            XCTAssertLessThanOrEqual(payload.count, 128)
            let n = try XCTUnwrap(NeedCodec.decode(payload), "round trip for \(s.unicodeScalars.count) scalars")
            XCTAssertEqual(Utf8.truncate(s, 16), n.item)
        }
    }

    func testTruncateIsOnCodePointBoundary() {
        XCTAssertEqual("पानी औ", Utf8.truncate("पानी और दवा चाहिए", 16))
        XCTAssertEqual("", Utf8.truncate("😀", 3))
        XCTAssertEqual("😀", Utf8.truncate("😀😀", 4))
        XCTAssertEqual("ab", Utf8.truncate("abc", 2))
    }

    func testAckFrameAtMostRoom() throws {
        let text = String(repeating: "a", count: 200)
        let ack = Ack(ackId: NodeId.random(), refMsgId: NodeId.random(), targetNode: NodeId.random(), ticketNo: 0xFFFF_FFFF,
                      status: .resolved, etaMin: 0xFFFF, text: text)
        let payload = try AckCodec.encode(ack)
        XCTAssertLessThanOrEqual(payload.count, 145, "ack payload \(payload.count)")
        let frame = try FrameCodec.encode(Frame(type: FrameTypes.ack, ttl: 7, timestampMs: T0,
                                                senderId: [UInt8](repeating: 0, count: 8),
                                                recipientId: [UInt8](repeating: 0, count: 8), payload: payload))
        XCTAssertLessThanOrEqual(frame.count, 182, "ack frame \(frame.count)")
        XCTAssertEqual(175, frame.count) // 22 + 8 + 33 + 112
        XCTAssertEqual(112, AckCodec.decode(payload)!.text.utf8.count)
        // multibyte text: 112 bytes holds 37 Odia characters (111 bytes)
        var odiaAck = ack; odiaAck.text = String(repeating: "ଅ", count: 200)
        let odia = try AckCodec.encode(odiaAck)
        XCTAssertEqual(37, AckCodec.decode(odia)!.text.unicodeScalars.count)
    }

    func testInventoryChunkFrameAtMost182() throws {
        let ids = (0..<19).map { _ in NodeId.random() }
        let frame = try FrameCodec.encode(Frame(type: FrameTypes.inventory, ttl: 1, timestampMs: T0,
                                                senderId: [UInt8](repeating: 0, count: 8),
                                                payload: try InventoryCodec.encode(Inventory(0, 1, ids))))
        XCTAssertEqual(178, frame.count)
        XCTAssertLessThanOrEqual(frame.count, 182)
        // chunking
        let chunks = InventoryCodec.chunk((0..<40).map { _ in NodeId.random() })
        XCTAssertEqual([19, 19, 2], chunks.map { $0.ids.count })
        XCTAssertTrue(chunks.allSatisfy { $0.chunkCount == 3 })
        XCTAssertEqual(1, InventoryCodec.chunk([]).count)
        XCTAssertEqual(0, InventoryCodec.chunk([])[0].ids.count)
    }

    func testHelloIs13Bytes() throws {
        XCTAssertEqual(13, try HelloCodec.encode(Hello(nodeId: NodeId.random(), caps: 1, hopsToGateway: 0, appVersion: 1)).count)
    }

    func testFuzzRandomBytesNeverTrap() {
        var rnd = SeededRandom(seed: 12345)
        for _ in 0..<10_000 {
            var b = (0..<Int.random(in: 0..<300, using: &rnd)).map { _ in UInt8.random(in: 0...255, using: &rnd) }
            // bias the first bytes towards plausible headers so deeper paths are reached
            if b.count > 14 && Bool.random(using: &rnd) { b[0] = 1; b[1] = UInt8(0x70 + Int.random(in: 0..<5, using: &rnd)) }
            _ = FrameCodec.decode(b)
            _ = NeedCodec.decode(b); _ = AckCodec.decode(b); _ = HelloCodec.decode(b); _ = InventoryCodec.decode(b)
        }
    }

    func testFuzzMutationsOfValidFramesNeverTrap() throws {
        var rnd = SeededRandom(seed: 777)
        let origin = NodeId.random()
        let need = sampleNeed(originNode: origin)
        let valid: [[UInt8]] = [
            needFrameBytes(need),
            encodeFrame(Frame(type: FrameTypes.ack, ttl: 7, timestampMs: T0, senderId: origin, recipientId: origin,
                              payload: encodeAck(sampleAck(need)))),
            encodeFrame(Frame(type: FrameTypes.hello, ttl: 1, timestampMs: T0, senderId: origin,
                              payload: encodeHello(Hello(nodeId: origin, caps: 1, hopsToGateway: 0, appVersion: 1)))),
            encodeFrame(Frame(type: FrameTypes.inventory, ttl: 1, timestampMs: T0, senderId: origin,
                              payload: encodeInventory(Inventory(0, 1, [NodeId.random()])))),
            encodeFrame(Frame(type: 0x7E, ttl: 5, timestampMs: T0, senderId: origin, payload: [1, 2],
                              signature: [UInt8](repeating: 0, count: 64))),
        ]
        let h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        for _ in 0..<10_000 {
            let src = valid[Int.random(in: 0..<valid.count, using: &rnd)]
            var m: [UInt8]
            switch Int.random(in: 0..<4, using: &rnd) {
            case 0:
                m = src
                for _ in 0...Int.random(in: 0..<4, using: &rnd) { m[Int.random(in: 0..<m.count, using: &rnd)] = UInt8.random(in: 0...255, using: &rnd) }
            case 1: m = Array(src.prefix(Int.random(in: 0...src.count, using: &rnd)))
            case 2: m = src + (0..<Int.random(in: 0..<5, using: &rnd)).map { _ in UInt8.random(in: 0...255, using: &rnd) }
            default:
                m = src
                m[Int.random(in: 0..<min(14, m.count), using: &rnd)] = UInt8.random(in: 0...255, using: &rnd)
            }
            _ = FrameCodec.decode(m)
            h.node.onFrame(Bool.random(using: &rnd) ? "L1" : "L2", m)   // whole engine must not trap either
            h.node.onFrame("unknown-link", m)
            if h.tasks.count > 500 { h.runTasks() }
        }
        h.runTasks()
    }
}
