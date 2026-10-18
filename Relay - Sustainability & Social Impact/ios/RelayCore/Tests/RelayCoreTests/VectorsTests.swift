import XCTest
@testable import RelayCore

final class VectorsTests: XCTestCase {
    private typealias JObj = [String: Any]

    private var root: JObj!
    private var vectors: [JObj]!

    override func setUpWithError() throws {
        root = (try loadJSONObject("protocol/vectors.json")) as? JObj
        vectors = root["vectors"] as? [JObj]
    }

    private func s(_ o: JObj, _ k: String) -> String { o[k] as! String }
    private func i(_ o: JObj, _ k: String) -> Int { (o[k] as! NSNumber).intValue }
    private func l(_ o: JObj, _ k: String) -> Int64 { (o[k] as! NSNumber).int64Value }
    private func d(_ o: JObj, _ k: String) -> Double { (o[k] as! NSNumber).doubleValue }
    private func obj(_ o: JObj, _ k: String) -> JObj { o[k] as! JObj }

    private func frameOf(_ v: JObj, _ payload: [UInt8]) -> Frame {
        let f = obj(v, "frame")
        let rec: [UInt8]? = (f["recipient_id"] is NSNull) ? nil : h(s(f, "recipient_id"))
        let sig = (obj(v, "fields")["signature"] as? String).map { h($0) }
        return Frame(type: i(f, "type"), ttl: i(f, "ttl"), timestampMs: l(f, "timestamp_ms"), senderId: h(s(f, "sender_id")),
                     recipientId: rec, payload: payload, signature: sig)
    }

    private func needFrom(_ fl: JObj, decodedStrings: Bool) -> Need {
        func str(_ k: String) -> String {
            decodedStrings ? ((fl[k + "_decoded"] as? String) ?? s(fl, k)) : s(fl, k)
        }
        let answers = (fl["answers"] as? [JObj])?.map { NeedAnswer(questionId: i($0, "id"), value: i($0, "value")) } ?? []
        return Need(
            msgId: h(s(fl, "msg_id")), originNode: h(s(fl, "origin_node")), createdAt: l(fl, "created_at"),
            category: NeedCategory.fromCode(i(fl, "category"))!, urgency: i(fl, "urgency"), people: i(fl, "people"),
            vulnFlags: i(fl, "vuln_flags"), latE5: Geo.toE5(d(fl, "lat")), lonE5: Geo.toE5(d(fl, "lon")),
            locAcc: i(fl, "loc_acc"), lang: Lang.fromCode(i(fl, "lang"))!,
            item: str("item"), landmark: str("landmark"), summary: str("summary"), answers: answers,
            photoTags: (fl["photo_tags_mask"] as? NSNumber)?.intValue ?? 0)
    }

    func testVectorsFilePresent() { XCTAssertEqual(14, vectors.count) }

    func testEveryVectorEncodesAndDecodes() throws {
        for v in vectors {
            let name = s(v, "name")
            let fl = obj(v, "fields")
            let payloadHex = s(v, "payload_hex")
            let frameHex = s(v, "frame_hex")
            let payload: [UInt8]
            switch s(v, "kind") {
            case "NEED":
                let need = needFrom(fl, decodedStrings: false)
                XCTAssertEqual(i(fl, "lat_e5"), need.latE5, "\(name) lat_e5")
                XCTAssertEqual(i(fl, "lon_e5"), need.lonE5, "\(name) lon_e5")
                let enc = try NeedCodec.encode(need)
                XCTAssertEqual(payloadHex, Hex.encode(enc), "\(name) payload")
                // decode == fields (strings as truncated on the wire)
                XCTAssertEqual(needFrom(fl, decodedStrings: true), NeedCodec.decode(h(payloadHex)), "\(name) decode")
                payload = enc
            case "ACK":
                let ack = Ack(ackId: h(s(fl, "ack_id")), refMsgId: h(s(fl, "ref_msg_id")), targetNode: h(s(fl, "target_node")),
                              ticketNo: l(fl, "ticket_no"), status: AckStatus.fromCode(i(fl, "status"))!,
                              etaMin: i(fl, "eta_min"), text: s(fl, "text"))
                let enc = try AckCodec.encode(ack)
                XCTAssertEqual(payloadHex, Hex.encode(enc), "\(name) payload")
                var expected = ack
                expected.text = s(fl, "text_decoded")
                XCTAssertEqual(expected, AckCodec.decode(h(payloadHex)), "\(name) decode")
                payload = enc
            case "HELLO":
                let hello = Hello(nodeId: h(s(fl, "node_id")), caps: i(fl, "caps"), hopsToGateway: i(fl, "hops_to_gateway"),
                                  appVersion: i(fl, "app_version"))
                let enc = try HelloCodec.encode(hello)
                XCTAssertEqual(payloadHex, Hex.encode(enc), "\(name) payload")
                XCTAssertEqual(hello, HelloCodec.decode(h(payloadHex)), "\(name) decode")
                payload = enc
            case "INVENTORY":
                let inv = Inventory(chunkIndex: i(fl, "chunk_index"), chunkCount: i(fl, "chunk_count"),
                                    ids: (fl["ids"] as! [String]).map { h($0) })
                let enc = try InventoryCodec.encode(inv)
                XCTAssertEqual(payloadHex, Hex.encode(enc), "\(name) payload")
                XCTAssertEqual(inv, InventoryCodec.decode(h(payloadHex)), "\(name) decode")
                payload = enc
            case "LOC_HINT":
                let hint = LocHint(refMsgId: h(s(fl, "ref_msg_id")), relayNode: h(s(fl, "relay_node")),
                                   latE5: Geo.toE5(d(fl, "lat")), lonE5: Geo.toE5(d(fl, "lon")), acc: i(fl, "acc"),
                                   rssiDbm: i(fl, "rssi_dbm"), observedAt: l(fl, "observed_at"))
                XCTAssertEqual(i(fl, "lat_e5"), hint.latE5, "\(name) lat_e5")
                XCTAssertEqual(i(fl, "lon_e5"), hint.lonE5, "\(name) lon_e5")
                let enc = try LocHintCodec.encode(hint)
                XCTAssertEqual(payloadHex, Hex.encode(enc), "\(name) payload")
                XCTAssertEqual(hint, LocHintCodec.decode(h(payloadHex)), "\(name) decode")
                XCTAssertEqual(s(fl, "key"), hint.keyHex, "\(name) key")
                XCTAssertEqual(i(fl, "acc_max_m"), hint.accuracyMaxM, "\(name) acc bound")
                payload = enc
            case "OPAQUE":
                payload = h(payloadHex)
            default:
                XCTFail("unknown kind"); return
            }
            let frame = frameOf(v, payload)
            XCTAssertEqual(frameHex, Hex.encode(try FrameCodec.encode(frame)), "\(name) frame")
            XCTAssertEqual(frame, FrameCodec.decode(h(frameHex)), "\(name) frame decode")
            if s(v, "kind") == "OPAQUE" {
                XCTAssertEqual(s(fl, "opaque_key"), Hex.encode(OpaqueKey.of(frame)))
                XCTAssertEqual(s(fl, "signature"), Hex.encode(FrameCodec.decode(h(frameHex))!.signature!))
            }
        }
    }

    func testMustRejectFramesAreRejected() {
        let bad = root["must_reject_frames"] as! [String: String]
        XCTAssertEqual(5, bad.count)
        for (name, hexv) in bad { XCTAssertNil(FrameCodec.decode(h(hexv)), "must reject \(name)") }
    }

    func testSizesMatchReferenceEncoder() {
        let sizes = obj(root, "sizes")
        var byName: [String: JObj] = [:]
        for v in vectors { byName[s(v, "name")] = v }
        XCTAssertEqual(i(sizes, "need1_payload"), h(s(byName["need_insulin_odisha"]!, "payload_hex")).count)
        XCTAssertEqual(i(sizes, "need2_payload"), h(s(byName["need_truncation_negative_coords"]!, "payload_hex")).count)
        XCTAssertEqual(i(sizes, "ack1_payload"), h(s(byName["ack_received_directed"]!, "payload_hex")).count)
        XCTAssertEqual(i(sizes, "need_v2_max_payload"), h(s(byName["need_v2_max_size"]!, "payload_hex")).count)
        XCTAssertEqual(NeedCodec.maxPayloadV2, i(sizes, "need_v2_max_payload"))
        XCTAssertLessThanOrEqual(h(s(byName["need_v2_max_size"]!, "frame_hex")).count, 182)
        XCTAssertEqual(i(sizes, "need_v3_max_payload"), h(s(byName["need_v3_max_size"]!, "payload_hex")).count)
        XCTAssertEqual(NeedCodec.maxPayloadV3, i(sizes, "need_v3_max_payload"))
        XCTAssertLessThanOrEqual(h(s(byName["need_v3_max_size"]!, "frame_hex")).count, 182)
    }

    func testV1VectorsStayV1AnswerVectorsAreV2AndTagVectorsAreV3() {
        for v in vectors where s(v, "kind") == "NEED" {
            let fields = obj(v, "fields")
            let want = fields["photo_tags"] != nil ? 3 : fields["answers"] != nil ? 2 : 1
            XCTAssertEqual(want, Int(h(s(v, "payload_hex"))[0]), s(v, "name"))
        }
    }

    func testPhotoTagTableMatchesSharedTable() {
        let bank = root["photo_tag_bank"] as! [JObj]
        XCTAssertEqual(PhotoTags.keys.count, bank.count)
        for b in bank { XCTAssertEqual(s(b, "key"), PhotoTags.keys[i(b, "bit")]) }
        XCTAssertEqual(0x7FFF, PhotoTags.allMask)
        XCTAssertEqual(["floodwater", "people_stranded"], PhotoTags.toKeys(0x21))
        XCTAssertEqual(0x21, PhotoTags.fromKeys(["people_stranded", "floodwater"]))
        XCTAssertNil(PhotoTags.fromKeys(["selfie"]))
    }

    func testEncoderRejectsReservedPhotoTagBitAndFallsBackToOlderVersions() throws {
        let v = vectors.first { s($0, "name") == "need_v3_tags_only" }!
        let need = needFrom(obj(v, "fields"), decodedStrings: false)
        for bad in [0x8000, 0x10000, -1] {
            var n = need; n.photoTags = bad
            XCTAssertThrowsError(try NeedCodec.encode(n))
        }
        var none = need; none.photoTags = 0
        XCTAssertEqual(1, Int(try NeedCodec.encode(none)[0]))
        none.answers = [NeedAnswer(1, 2)]
        XCTAssertEqual(2, Int(try NeedCodec.encode(none)[0]))
    }

    func testQuestionBankMatchesSharedTable() {
        let bank = root["answer_bank"] as! [JObj]
        XCTAssertEqual(QuestionBank.all.count, bank.count)
        for b in bank {
            let q = QuestionBank.byId(i(b, "id"))!
            XCTAssertEqual(s(b, "key"), q.key)
            let opts = b["options"] as! [JObj]
            XCTAssertEqual(q.options.count, opts.count)
            for o in opts { XCTAssertEqual(s(o, "key"), q.option(i(o, "value"))!.key) }
        }
    }

    func testMustRejectNeedPayloadsAreRejected() {
        let bad = root["must_reject_need_payloads"] as! [String: String]
        XCTAssertGreaterThanOrEqual(bad.count, 9)
        for (name, hexv) in bad { XCTAssertNil(NeedCodec.decode(h(hexv)), "must reject \(name)") }
    }

    func testMustRejectLocHintPayloadsAreRejected() {
        let bad = root["must_reject_loc_hint_payloads"] as! [String: String]
        XCTAssertGreaterThanOrEqual(bad.count, 7)
        for (name, hexv) in bad { XCTAssertNil(LocHintCodec.decode(h(hexv)), "must reject \(name)") }
    }

    func testLocHintSizeAndAccuracyTableMatchReferenceEncoder() {
        XCTAssertEqual(LocHintCodec.size, i(obj(root, "sizes"), "loc_hint_payload"))
        let table = root["loc_hint_acc_max_m"] as! [String: NSNumber]
        XCTAssertEqual(4, table.count)
        for (k, v) in table { XCTAssertEqual(v.intValue, LocHintCodec.accMaxM[Int(k)!]) }
    }

    // ---- BROADCAST (SPEC 5.6): its own vector list, so nothing that walks `vectors` is affected
    func testBroadcastVectorsMatchByteForByte() throws {
        let list = try XCTUnwrap(root["broadcast_vectors"] as? [JObj])
        XCTAssertGreaterThanOrEqual(list.count, 3)
        for v in list {
            let name = s(v, "name")
            let fl = obj(v, "fields")
            let kind: BroadcastKind = s(fl, "kind") == "disaster" ? .disaster : .disasterCleared
            let b = Broadcast(id: h(s(fl, "bcast_id")), kind: kind, disaster: try XCTUnwrap(DisasterType.fromKey(s(fl, "disaster"))),
                              issuedAt: l(fl, "issued_at"), latE5: i(fl, "lat_e5"), lonE5: i(fl, "lon_e5"), radiusKm: i(fl, "radius_km"))
            let payload = try BroadcastCodec.encode(b)
            XCTAssertEqual(s(v, "payload_hex"), Hex.encode(payload), "\(name) payload")
            XCTAssertEqual(b, BroadcastCodec.decode(h(s(v, "payload_hex"))), "\(name) decode")
            XCTAssertEqual(s(v, "frame_hex"), Hex.encode(try FrameCodec.encode(frameOf(v, payload))), "\(name) frame")
            XCTAssertEqual(s(fl, "bcast_id"), b.keyHex, "\(name) key")
            XCTAssertEqual(l(fl, "expires_at"), b.issuedAt + BroadcastCodec.ttlSec, "\(name) expiry")
            XCTAssertEqual(FrameTypes.broadcast, i(obj(v, "frame"), "type"))
        }
        XCTAssertEqual(BroadcastCodec.size, i(obj(root, "sizes"), "broadcast_payload"))
        XCTAssertEqual(BroadcastCodec.ttlSec, l(root, "broadcast_ttl_s"))
    }

    func testBroadcastPayloadsEveryDecoderMustReject() {
        let bad = root["must_reject_broadcast_payloads"] as! [String: String]
        XCTAssertGreaterThanOrEqual(bad.count, 8)
        for (name, hexv) in bad { XCTAssertNil(BroadcastCodec.decode(h(hexv)), "must reject \(name)") }
    }

    // ---- ANNOUNCEMENT (SPEC 5.7): its own vector list too
    func testAnnouncementVectorsMatchByteForByte() throws {
        let list = try XCTUnwrap(root["announcement_vectors"] as? [JObj])
        XCTAssertGreaterThanOrEqual(list.count, 5)
        for v in list {
            let name = s(v, "name")
            let fl = obj(v, "fields")
            let a = Announcement(
                announcementId: h(s(fl, "announcement_id")), lang: try XCTUnwrap(Lang.fromTag(s(fl, "lang"))),
                district: try XCTUnwrap(AnnouncementDistricts.indexOf(s(fl, "district"))),
                status: s(fl, "status") == "active" ? .active : .withdrawn,
                approvedAt: l(fl, "approved_at"), expiresAt: l(fl, "expires_at"), text: s(fl, "text"))
            let payload = try AnnouncementCodec.encode(a)
            XCTAssertEqual(s(v, "payload_hex"), Hex.encode(payload), "\(name) payload")
            XCTAssertEqual(a, AnnouncementCodec.decode(h(s(v, "payload_hex"))), "\(name) decode")
            XCTAssertEqual(s(v, "frame_hex"), Hex.encode(try FrameCodec.encode(frameOf(v, payload))), "\(name) frame")
            XCTAssertEqual(s(fl, "key"), a.keyHex, "\(name) key")
            XCTAssertLessThanOrEqual(s(v, "frame_hex").count / 2, 182, "\(name) fits one write")
            XCTAssertEqual(FrameTypes.announcement, i(obj(v, "frame"), "type"))
        }
        XCTAssertEqual(AnnouncementCodec.maxPayload, i(obj(root, "sizes"), "announcement_max_payload"))
        XCTAssertEqual(AnnouncementCodec.ttlSec, l(root, "announcement_ttl_s"))
    }

    func testAnnouncementPayloadsEveryDecoderMustReject() {
        let bad = root["must_reject_announcement_payloads"] as! [String: String]
        XCTAssertGreaterThanOrEqual(bad.count, 8)
        for (name, hexv) in bad { XCTAssertNil(AnnouncementCodec.decode(h(hexv)), "must reject \(name)") }
    }

    func testAnnouncementIndexTablesMatchTheVectors() {
        let langs = root["announcement_lang_codes"] as! [String: NSNumber]
        XCTAssertEqual(Lang.allCases.count, langs.count)
        for l in Lang.allCases { XCTAssertEqual(l.code, langs[l.tag]?.intValue, l.tag) }
        let districts = root["announcement_district_codes"] as! [String: NSNumber]
        XCTAssertEqual(AnnouncementDistricts.ids.count, districts.count)
        for (idx, id) in AnnouncementDistricts.ids.enumerated() { XCTAssertEqual(idx, districts[id]?.intValue, id) }
    }

    func testBroadcastRecipientAllFfIsBroadcast() throws {
        let f = Frame(type: FrameTypes.ack, ttl: 3, timestampMs: 5, senderId: [UInt8](repeating: 1, count: 8),
                      recipientId: [UInt8](repeating: 0xFF, count: 8), payload: [1])
        let d = try XCTUnwrap(FrameCodec.decode(try FrameCodec.encode(f)))
        XCTAssertNil(d.recipientId)
    }

    func testRsrFlagIgnoredAndSignatureKept() throws {
        let f = Frame(type: 0x7E, ttl: 2, timestampMs: 1, senderId: [UInt8](repeating: 0, count: 8), payload: [9],
                      signature: (0..<64).map { UInt8($0) })
        var raw = try FrameCodec.encode(f)
        raw[11] = raw[11] | UInt8(FrameCodec.flagIsRsr)
        let d = try XCTUnwrap(FrameCodec.decode(raw))
        XCTAssertEqual(f.signature, d.signature)
        raw[11] = raw[11] | UInt8(FrameCodec.flagHasRoute)
        XCTAssertNil(FrameCodec.decode(raw))
    }

    func testGeoRoundingMatchesPythonSemantics() {
        // half away from zero, computed in double exactly like the reference encoder
        XCTAssertEqual(-1200001, Geo.toE5(-12.000005))
        XCTAssertEqual(2029612, Geo.toE5(20.296123))
        XCTAssertEqual(1, Geo.toE5(0.000005))
        XCTAssertEqual(-1, Geo.toE5(-0.000005))
        XCTAssertEqual(0, Geo.toE5(0.0))
        XCTAssertEqual(0, Geo.toE5(Double.nan))
        XCTAssertEqual(Int(Int32.max), Geo.toE5(1e12))
        XCTAssertEqual(Int(Int32.min), Geo.toE5(-1e12))
    }

    func testPayloadValidationRejectsBadFields() throws {
        let good = h(s(vectors.first!, "payload_hex"))
        XCTAssertNotNil(NeedCodec.decode(good))
        func mut(_ i: Int, _ v: Int) -> [UInt8] { var c = good; c[i] = UInt8(v); return c }
        XCTAssertNil(NeedCodec.decode(mut(0, 3)), "bad payload_version")
        XCTAssertNil(NeedCodec.decode(mut(0, 2)), "v2 body without answer bytes")
        XCTAssertNil(NeedCodec.decode(mut(1 + 8 + 8 + 4, 7)), "category 7")
        XCTAssertNil(NeedCodec.decode(mut(1 + 8 + 8 + 4 + 1, 4)), "urgency 4")
        XCTAssertNil(NeedCodec.decode(mut(1 + 8 + 8 + 4 + 4 + 8, 6)), "loc_acc 6")
        XCTAssertNil(NeedCodec.decode(mut(1 + 8 + 8 + 4 + 4 + 8 + 1, 6)), "lang 6")
        XCTAssertNil(NeedCodec.decode(good + [0]), "trailing")
        XCTAssertNil(NeedCodec.decode(mut(35, 17)), "item len too big")
        XCTAssertNil(NeedCodec.decode(mut(36, 0xC0)), "invalid utf8") // 'i' of insulin -> invalid lead byte
    }

    // ---- additions beyond the Android suite: the pure-Swift SHA-256 and the hex/utf8 helpers it relies on

    func testSha256KnownDigests() {
        XCTAssertEqual("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hex.encode(Sha256.digest([])))
        XCTAssertEqual("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hex.encode(Sha256.digest(Array("abc".utf8))))
        // two-block message (FIPS 180-2 example)
        XCTAssertEqual("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                       Hex.encode(Sha256.digest(Array("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8))))
        XCTAssertEqual("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
                       Hex.encode(Sha256.digest([UInt8](repeating: 0x61, count: 1_000_000))))
    }

    func testHexRoundTripAndRejects() throws {
        XCTAssertEqual("00ff10", Hex.encode([0, 255, 16]))
        XCTAssertEqual([0, 255, 16], try Hex.decode("00FF10"))
        XCTAssertNil(Hex.decodeOrNull("abc"))
        XCTAssertNil(Hex.decodeOrNull("zz"))
        XCTAssertThrowsError(try NodeId.fromHex("0011"))
        XCTAssertLessThan(NodeId.compare([0x7F], [0x80]), 0)
    }

    func testStrictUtf8() {
        XCTAssertNil(Utf8.decodeStrict([0xC0, 0x80]))          // overlong
        XCTAssertNil(Utf8.decodeStrict([0xED, 0xA0, 0x80]))    // surrogate
        XCTAssertNil(Utf8.decodeStrict([0xF4, 0x90, 0x80, 0x80])) // > U+10FFFF
        XCTAssertNil(Utf8.decodeStrict([0xE0, 0xA4]))          // truncated
        XCTAssertEqual("पानी", Utf8.decodeStrict(Array("पानी".utf8)))
        XCTAssertEqual("😀", Utf8.decodeStrict(Array("😀".utf8)))
    }
}
