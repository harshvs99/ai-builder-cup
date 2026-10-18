package org.relay.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.ids.Hex
import org.relay.core.payload.Ack
import org.relay.core.payload.AckCodec
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Geo
import org.relay.core.payload.Hello
import org.relay.core.payload.HelloCodec
import org.relay.core.payload.Inventory
import org.relay.core.payload.InventoryCodec
import org.relay.core.payload.Lang
import org.relay.core.payload.LocHint
import org.relay.core.payload.LocHintCodec
import org.relay.core.payload.Need
import org.relay.core.payload.NeedAnswer
import org.relay.core.payload.NeedCodec
import org.relay.core.intake.QuestionBank
import org.relay.core.payload.OpaqueKey
import org.relay.core.payload.PhotoTags
import java.io.File

class VectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(
        File(System.getProperty("relay.vectors") ?: error("relay.vectors system property not set")).readText()
    ).jsonObject
    private val vectors = root["vectors"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int
    private fun JsonObject.l(k: String) = this[k]!!.jsonPrimitive.long
    private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.content.toDouble()
    private fun h(s: String) = Hex.decode(s)

    private fun frameOf(v: JsonObject, payload: ByteArray): Frame {
        val f = v["frame"]!!.jsonObject
        val rec = f["recipient_id"]!!.let { if (it is kotlinx.serialization.json.JsonNull) null else h(it.jsonPrimitive.content) }
        val sig = v["fields"]!!.jsonObject["signature"]?.jsonPrimitive?.content?.let { h(it) }
        return Frame(type = f.i("type"), ttl = f.i("ttl"), timestampMs = f.l("timestamp_ms"),
            senderId = h(f.s("sender_id")), recipientId = rec, payload = payload, signature = sig)
    }

    private fun needFrom(fl: JsonObject, decodedStrings: Boolean) = Need(
        msgId = h(fl.s("msg_id")), originNode = h(fl.s("origin_node")), createdAt = fl.l("created_at"),
        category = Category.fromCode(fl.i("category"))!!, urgency = fl.i("urgency"), people = fl.i("people"),
        vulnFlags = fl.i("vuln_flags"), latE5 = Geo.toE5(fl.d("lat")), lonE5 = Geo.toE5(fl.d("lon")),
        locAcc = fl.i("loc_acc"), lang = Lang.fromCode(fl.i("lang"))!!,
        item = if (decodedStrings) fl["item_decoded"]?.jsonPrimitive?.content ?: fl.s("item") else fl.s("item"),
        landmark = if (decodedStrings) fl["landmark_decoded"]?.jsonPrimitive?.content ?: fl.s("landmark") else fl.s("landmark"),
        summary = if (decodedStrings) fl["summary_decoded"]?.jsonPrimitive?.content ?: fl.s("summary") else fl.s("summary"),
        answers = fl["answers"]?.jsonArray?.map { NeedAnswer(it.jsonObject.i("id"), it.jsonObject.i("value")) } ?: emptyList(),
        photoTags = fl["photo_tags_mask"]?.jsonPrimitive?.int ?: 0,
    )

    @Test fun vectorsFilePresent() { assertEquals(14, vectors.size) }

    @Test fun everyVectorEncodesAndDecodes() {
        for (v in vectors) {
            val name = v.s("name")
            val fl = v["fields"]!!.jsonObject
            val payloadHex = v.s("payload_hex")
            val frameHex = v.s("frame_hex")
            val payload: ByteArray = when (v.s("kind")) {
                "NEED" -> {
                    val need = needFrom(fl, decodedStrings = false)
                    assertEquals("$name lat_e5", fl.i("lat_e5"), need.latE5)
                    assertEquals("$name lon_e5", fl.i("lon_e5"), need.lonE5)
                    val enc = NeedCodec.encode(need)
                    assertEquals("$name payload", payloadHex, Hex.encode(enc))
                    // decode == fields (strings as truncated on the wire)
                    assertEquals("$name decode", needFrom(fl, decodedStrings = true), NeedCodec.decode(h(payloadHex)))
                    enc
                }
                "ACK" -> {
                    val ack = Ack(h(fl.s("ack_id")), h(fl.s("ref_msg_id")), h(fl.s("target_node")), fl.l("ticket_no"),
                        AckStatus.fromCode(fl.i("status"))!!, fl.i("eta_min"), fl.s("text"))
                    val enc = AckCodec.encode(ack)
                    assertEquals("$name payload", payloadHex, Hex.encode(enc))
                    assertEquals("$name decode", ack.copy(text = fl.s("text_decoded")), AckCodec.decode(h(payloadHex)))
                    enc
                }
                "HELLO" -> {
                    val hello = Hello(h(fl.s("node_id")), fl.i("caps"), fl.i("hops_to_gateway"), fl.i("app_version"))
                    val enc = HelloCodec.encode(hello)
                    assertEquals("$name payload", payloadHex, Hex.encode(enc))
                    assertEquals("$name decode", hello, HelloCodec.decode(h(payloadHex)))
                    enc
                }
                "INVENTORY" -> {
                    val inv = Inventory(fl.i("chunk_index"), fl.i("chunk_count"), fl["ids"]!!.jsonArray.map { h(it.jsonPrimitive.content) })
                    val enc = InventoryCodec.encode(inv)
                    assertEquals("$name payload", payloadHex, Hex.encode(enc))
                    assertEquals("$name decode", inv, InventoryCodec.decode(h(payloadHex)))
                    enc
                }
                "LOC_HINT" -> {
                    val hint = LocHint(h(fl.s("ref_msg_id")), h(fl.s("relay_node")), Geo.toE5(fl.d("lat")), Geo.toE5(fl.d("lon")),
                        fl.i("acc"), fl.i("rssi_dbm"), fl.l("observed_at"))
                    assertEquals("$name lat_e5", fl.i("lat_e5"), hint.latE5)
                    assertEquals("$name lon_e5", fl.i("lon_e5"), hint.lonE5)
                    val enc = LocHintCodec.encode(hint)
                    assertEquals("$name payload", payloadHex, Hex.encode(enc))
                    assertEquals("$name decode", hint, LocHintCodec.decode(h(payloadHex)))
                    assertEquals("$name key", fl.s("key"), hint.keyHex)
                    assertEquals("$name acc bound", fl.i("acc_max_m"), hint.accuracyMaxM)
                    enc
                }
                "OPAQUE" -> h(payloadHex)
                else -> error("unknown kind")
            }
            val frame = frameOf(v, payload)
            assertEquals("$name frame", frameHex, Hex.encode(FrameCodec.encode(frame)))
            assertEquals("$name frame decode", frame, FrameCodec.decode(h(frameHex)))
            if (v.s("kind") == "OPAQUE") {
                assertEquals(fl.s("opaque_key"), Hex.encode(OpaqueKey.of(frame)))
                assertEquals(fl.s("signature"), Hex.encode(FrameCodec.decode(h(frameHex))!!.signature!!))
            }
        }
    }

    @Test fun mustRejectFramesAreRejected() {
        val bad = root["must_reject_frames"]!!.jsonObject
        assertEquals(5, bad.size)
        for ((name, hexv) in bad) assertNull("must reject $name", FrameCodec.decode(h(hexv.jsonPrimitive.content)))
    }

    @Test fun sizesMatchReferenceEncoder() {
        val sizes = root["sizes"]!!.jsonObject
        val byName = vectors.associateBy { it.s("name") }
        assertEquals(sizes.i("need1_payload"), h(byName["need_insulin_odisha"]!!.s("payload_hex")).size)
        assertEquals(sizes.i("need2_payload"), h(byName["need_truncation_negative_coords"]!!.s("payload_hex")).size)
        assertEquals(sizes.i("ack1_payload"), h(byName["ack_received_directed"]!!.s("payload_hex")).size)
        assertEquals(sizes.i("need_v2_max_payload"), h(byName["need_v2_max_size"]!!.s("payload_hex")).size)
        assertEquals(NeedCodec.MAX_PAYLOAD_V2, sizes.i("need_v2_max_payload"))
        assertTrue(h(byName["need_v2_max_size"]!!.s("frame_hex")).size <= 182)
        assertEquals(sizes.i("need_v3_max_payload"), h(byName["need_v3_max_size"]!!.s("payload_hex")).size)
        assertEquals(NeedCodec.MAX_PAYLOAD_V3, sizes.i("need_v3_max_payload"))
        assertTrue(h(byName["need_v3_max_size"]!!.s("frame_hex")).size <= 182)
    }

    @Test fun v1VectorsStayV1AnswerVectorsAreV2AndTagVectorsAreV3() {
        for (v in vectors.filter { it.s("kind") == "NEED" }) {
            val fields = v["fields"]!!.jsonObject
            val want = if (fields["photo_tags"] != null) 3 else if (fields["answers"] != null) 2 else 1
            assertEquals(v.s("name"), want, h(v.s("payload_hex"))[0].toInt())
        }
    }

    @Test fun photoTagTableMatchesSharedTable() {
        val bank = root["photo_tag_bank"]!!.jsonArray.map { it.jsonObject }
        assertEquals(PhotoTags.KEYS.size, bank.size)
        for (b in bank) assertEquals(b.s("key"), PhotoTags.KEYS[b.i("bit")])
        assertEquals(0x7FFF, PhotoTags.ALL_MASK)
        assertEquals(listOf("floodwater", "people_stranded"), PhotoTags.toKeys(0x21))
        assertEquals(0x21, PhotoTags.fromKeys(listOf("people_stranded", "floodwater")))
        assertNull(PhotoTags.fromKeys(listOf("selfie")))
    }

    @Test fun encoderRejectsReservedPhotoTagBit() {
        val need = needFrom(byName("need_v3_tags_only")["fields"]!!.jsonObject, decodedStrings = false)
        for (bad in listOf(0x8000, 0x10000, -1)) {
            try { NeedCodec.encode(need.copy(photoTags = bad)); error("expected IllegalArgumentException for $bad") }
            catch (e: IllegalArgumentException) { /* expected */ }
        }
        // no tags and no answers falls back to v1; answers only to v2
        assertEquals(1, NeedCodec.encode(need.copy(photoTags = 0))[0].toInt())
        assertEquals(2, NeedCodec.encode(need.copy(photoTags = 0, answers = listOf(NeedAnswer(1, 2))))[0].toInt())
    }

    private fun byName(name: String) = vectors.first { it.s("name") == name }

    @Test fun questionBankMatchesSharedTable() {
        val bank = root["answer_bank"]!!.jsonArray.map { it.jsonObject }
        assertEquals(QuestionBank.all.size, bank.size)
        for (b in bank) {
            val q = QuestionBank.byId(b.i("id"))!!
            assertEquals(b.s("key"), q.key)
            val opts = b["options"]!!.jsonArray.map { it.jsonObject }
            assertEquals(q.options.size, opts.size)
            for (o in opts) assertEquals(o.s("key"), q.option(o.i("value"))!!.key)
        }
    }

    @Test fun mustRejectNeedPayloadsAreRejected() {
        val bad = root["must_reject_need_payloads"]!!.jsonObject
        assertTrue(bad.size >= 9)
        for ((name, hexv) in bad) assertNull("must reject $name", NeedCodec.decode(h(hexv.jsonPrimitive.content)))
    }

    @Test fun mustRejectLocHintPayloadsAreRejected() {
        val bad = root["must_reject_loc_hint_payloads"]!!.jsonObject
        assertTrue(bad.size >= 7)
        for ((name, hexv) in bad) assertNull("must reject $name", LocHintCodec.decode(h(hexv.jsonPrimitive.content)))
    }

    @Test fun locHintSizeAndAccuracyTableMatchReferenceEncoder() {
        assertEquals(LocHintCodec.SIZE, root["sizes"]!!.jsonObject.i("loc_hint_payload"))
        val table = root["loc_hint_acc_max_m"]!!.jsonObject
        assertEquals(4, table.size)
        for ((k, v) in table) assertEquals(v.jsonPrimitive.int, LocHintCodec.ACC_MAX_M[k.toInt()])
    }

    @Test fun broadcastRecipientAllFfIsBroadcast() {
        val f = Frame(type = FrameTypes.ACK, ttl = 3, timestampMs = 5, senderId = ByteArray(8) { 1 },
            recipientId = ByteArray(8) { 0xFF.toByte() }, payload = byteArrayOf(1))
        val d = FrameCodec.decode(FrameCodec.encode(f))
        assertNotNull(d)
        assertNull(d!!.recipientId)
    }

    @Test fun rsrFlagIgnoredAndSignatureKept() {
        val f = Frame(type = 0x7E, ttl = 2, timestampMs = 1, senderId = ByteArray(8), payload = byteArrayOf(9),
            signature = ByteArray(64) { it.toByte() })
        val raw = FrameCodec.encode(f)
        raw[11] = (raw[11].toInt() or FrameCodec.FLAG_IS_RSR).toByte()
        val d = FrameCodec.decode(raw)!!
        assertArrayEquals(f.signature, d.signature)
        raw[11] = (raw[11].toInt() or FrameCodec.FLAG_HAS_ROUTE).toByte()
        assertNull(FrameCodec.decode(raw))
    }

    @Test fun geoRoundingMatchesPythonSemantics() {
        // half away from zero, computed in double exactly like the reference encoder
        assertEquals(-1200001, Geo.toE5(-12.000005))
        assertEquals(2029612, Geo.toE5(20.296123))
        assertEquals(1, Geo.toE5(0.000005))
        assertEquals(-1, Geo.toE5(-0.000005))
        assertEquals(0, Geo.toE5(0.0))
        assertEquals(0, Geo.toE5(Double.NaN))
        assertTrue(Geo.toE5(1e12) == Int.MAX_VALUE)
    }

    @Test fun payloadValidationRejectsBadFields() {
        val good = h(vectors.first().s("payload_hex"))
        assertNotNull(NeedCodec.decode(good))
        fun mut(i: Int, v: Int) = good.copyOf().also { it[i] = v.toByte() }
        assertNull("bad payload_version", NeedCodec.decode(mut(0, 3)))
        assertNull("v2 body without answer bytes", NeedCodec.decode(mut(0, 2)))
        assertNull("category 7", NeedCodec.decode(mut(1 + 8 + 8 + 4, 7)))
        assertNull("urgency 4", NeedCodec.decode(mut(1 + 8 + 8 + 4 + 1, 4)))
        assertNull("loc_acc 6", NeedCodec.decode(mut(1 + 8 + 8 + 4 + 4 + 8, 6)))
        assertNull("lang 6", NeedCodec.decode(mut(1 + 8 + 8 + 4 + 4 + 8 + 1, 6)))
        assertNull("trailing", NeedCodec.decode(good + byteArrayOf(0)))
        assertNull("item len too big", NeedCodec.decode(mut(35, 17)))
        val badUtf8 = good.copyOf().also { it[36] = 0xC0.toByte() } // 'i' of insulin -> invalid lead byte
        assertNull("invalid utf8", NeedCodec.decode(badUtf8))
    }

    // ---- BROADCAST (SPEC 5.6): its own vector list, so nothing that walks `vectors` is affected
    @Test fun broadcastVectorsMatchByteForByte() {
        val list = root["broadcast_vectors"]!!.jsonArray.map { it.jsonObject }
        assertTrue(list.size >= 3)
        for (v in list) {
            val name = v.s("name")
            val fl = v["fields"]!!.jsonObject
            val kind = if (fl.s("kind") == "disaster") org.relay.core.payload.BroadcastKind.DISASTER else org.relay.core.payload.BroadcastKind.DISASTER_CLEARED
            val b = org.relay.core.payload.Broadcast(
                h(fl.s("bcast_id")), kind, org.relay.core.triage.DisasterType.fromKey(fl.s("disaster"))!!, fl.l("issued_at"),
                fl.i("lat_e5"), fl.i("lon_e5"), fl.i("radius_km"),
            )
            val payload = org.relay.core.payload.BroadcastCodec.encode(b)
            assertEquals("$name payload", v.s("payload_hex"), Hex.encode(payload))
            assertEquals("$name decode", b, org.relay.core.payload.BroadcastCodec.decode(h(v.s("payload_hex"))))
            assertEquals("$name frame", v.s("frame_hex"), Hex.encode(FrameCodec.encode(frameOf(v, payload))))
            assertEquals("$name key", fl.s("bcast_id"), b.keyHex)
            assertEquals("$name expiry", fl.l("expires_at"), b.issuedAt + org.relay.core.payload.BroadcastCodec.TTL_SEC)
        }
        assertEquals(org.relay.core.payload.BroadcastCodec.SIZE, root["sizes"]!!.jsonObject.i("broadcast_payload"))
        assertEquals(org.relay.core.payload.BroadcastCodec.TTL_SEC, root["broadcast_ttl_s"]!!.jsonPrimitive.long)
    }

    @Test fun broadcastPayloadsEveryDecoderMustReject() {
        val bad = root["must_reject_broadcast_payloads"]!!.jsonObject
        assertTrue(bad.size >= 8)
        for ((name, hex) in bad) assertNull(name, org.relay.core.payload.BroadcastCodec.decode(h(hex.jsonPrimitive.content)))
    }

    // ---- ANNOUNCEMENT (SPEC 5.7): its own vector list too
    @Test fun announcementVectorsMatchByteForByte() {
        val list = root["announcement_vectors"]!!.jsonArray.map { it.jsonObject }
        assertTrue(list.size >= 5)
        for (v in list) {
            val name = v.s("name")
            val fl = v["fields"]!!.jsonObject
            val a = org.relay.core.payload.Announcement(
                h(fl.s("announcement_id")), Lang.fromTag(fl.s("lang"))!!,
                org.relay.core.payload.AnnouncementDistricts.indexOf(fl.s("district"))!!,
                if (fl.s("status") == "active") org.relay.core.payload.AnnouncementStatus.ACTIVE else org.relay.core.payload.AnnouncementStatus.WITHDRAWN,
                fl.l("approved_at"), fl.l("expires_at"), fl.s("text"),
            )
            val payload = org.relay.core.payload.AnnouncementCodec.encode(a)
            assertEquals("$name payload", v.s("payload_hex"), Hex.encode(payload))
            assertEquals("$name decode", a, org.relay.core.payload.AnnouncementCodec.decode(h(v.s("payload_hex"))))
            assertEquals("$name frame", v.s("frame_hex"), Hex.encode(FrameCodec.encode(frameOf(v, payload))))
            assertEquals("$name key", fl.s("key"), a.keyHex)
            assertTrue("$name fits one write", v.s("frame_hex").length / 2 <= 182)
        }
        assertEquals(org.relay.core.payload.AnnouncementCodec.MAX_PAYLOAD, root["sizes"]!!.jsonObject.i("announcement_max_payload"))
        assertEquals(org.relay.core.payload.AnnouncementCodec.TTL_SEC, root["announcement_ttl_s"]!!.jsonPrimitive.long)
    }

    @Test fun announcementPayloadsEveryDecoderMustReject() {
        val bad = root["must_reject_announcement_payloads"]!!.jsonObject
        assertTrue(bad.size >= 8)
        for ((name, hex) in bad) assertNull(name, org.relay.core.payload.AnnouncementCodec.decode(h(hex.jsonPrimitive.content)))
    }

    @Test fun announcementIndexTablesMatchTheVectors() {
        val langs = root["announcement_lang_codes"]!!.jsonObject
        assertEquals(Lang.entries.size, langs.size)
        for (l in Lang.entries) assertEquals(l.tag, l.code, langs[l.tag]!!.jsonPrimitive.int)
        val districts = root["announcement_district_codes"]!!.jsonObject
        assertEquals(org.relay.core.payload.AnnouncementDistricts.IDS.size, districts.size)
        for ((i, id) in org.relay.core.payload.AnnouncementDistricts.IDS.withIndex()) assertEquals(id, i, districts[id]!!.jsonPrimitive.int)
    }
}
