package org.relay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relay.core.frame.Frame
import org.relay.core.frame.FrameCodec
import org.relay.core.frame.FrameTypes
import org.relay.core.ids.NodeId
import org.relay.core.payload.Ack
import org.relay.core.payload.AckCodec
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Hello
import org.relay.core.payload.HelloCodec
import org.relay.core.payload.Inventory
import org.relay.core.payload.InventoryCodec
import org.relay.core.payload.Lang
import org.relay.core.payload.Need
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.Utf8
import java.util.Random

class SizeAndFuzzTest {
    private val mtuPayload = 182 // 185 - 3

    private fun worstNeed(s: String) = Need(
        NodeId.random(), NodeId.random(), 0xFFFFFFFFL, Category.OTHER, 3, 255, 0xFF, Int.MIN_VALUE, Int.MAX_VALUE, 5,
        Lang.CEB, s, s, s)

    @Test fun needPayloadNeverExceeds128BytesForAdversarialMultibyteStrings() {
        val adversarial = listOf(
            "😀".repeat(100),            // 4-byte code points
            "ଅ".repeat(100),                        // 3-byte Odia
            "पानी".repeat(50),                      // Devanagari with combining marks
            "é".repeat(200),                        // 2-byte
            "a".repeat(300),                        // ascii
            "\uD800" + "x".repeat(100) + "\uDC00",  // lone surrogates
            "\u0000".repeat(100),
            "👨‍👩‍👧‍👦".repeat(20),   // ZWJ sequences
        )
        for (s in adversarial) {
            val payload = NeedCodec.encode(worstNeed(s))
            assertTrue("payload ${payload.size}", payload.size <= 128)
            assertTrue(payload.size <= NeedCodec.MAX_PAYLOAD)
            val frame = FrameCodec.encode(Frame(type = FrameTypes.NEED, ttl = 7, timestampMs = T0, senderId = ByteArray(8), payload = payload))
            assertTrue("frame ${frame.size}", frame.size <= 140 && frame.size <= mtuPayload)
            val back = NeedCodec.decode(payload)!!
            assertTrue(Utf8.truncateToBytes(back.item, 100).size <= 16)
            assertTrue(back.landmark.toByteArray().size <= 24)
            assertTrue(back.summary.toByteArray().size <= 40)
        }
    }

    @Test fun randomMultibyteStringsStayWithinLimitsAndRoundTrip() {
        val rnd = Random(99)
        val pool = listOf("a", "é", "ଅ", "पा", "😀", "ö", "ꙮ", "́")
        repeat(2000) {
            val s = buildString { repeat(rnd.nextInt(80)) { append(pool[rnd.nextInt(pool.size)]) } }
            val payload = NeedCodec.encode(worstNeed(s))
            assertTrue(payload.size <= 128)
            val n = NeedCodec.decode(payload)
            assertTrue("round trip for ${s.length} chars", n != null)
            assertEquals(Utf8.truncate(s, 16), n!!.item)
        }
    }

    @Test fun truncateIsOnCodePointBoundary() {
        assertEquals("पानी औ", Utf8.truncate("पानी और दवा चाहिए", 16))
        assertEquals("", Utf8.truncate("😀", 3))
        assertEquals("😀", Utf8.truncate("😀😀", 4))
        assertEquals("ab", Utf8.truncate("abc", 2))
    }

    @Test fun ackFrameAtMostRoom() {
        val text = "a".repeat(200)
        val ack = Ack(NodeId.random(), NodeId.random(), NodeId.random(), 0xFFFFFFFFL, AckStatus.RESOLVED, 0xFFFF, text)
        val payload = AckCodec.encode(ack)
        assertTrue("ack payload ${payload.size}", payload.size <= 145)
        val frame = FrameCodec.encode(Frame(type = FrameTypes.ACK, ttl = 7, timestampMs = T0, senderId = ByteArray(8),
            recipientId = ByteArray(8), payload = payload))
        assertTrue("ack frame ${frame.size}", frame.size <= 182)
        assertEquals(175, frame.size) // 22 + 8 + 33 + 112
        assertEquals(112, AckCodec.decode(payload)!!.text.toByteArray().size)
        // multibyte text: 112 bytes holds 37 Odia characters (111 bytes)
        val odia = AckCodec.encode(ack.copy(text = "ଅ".repeat(200)))
        assertEquals(37, AckCodec.decode(odia)!!.text.length)
    }

    @Test fun inventoryChunkFrameAtMost182() {
        val ids = List(19) { NodeId.random() }
        val frame = FrameCodec.encode(Frame(type = FrameTypes.INVENTORY, ttl = 1, timestampMs = T0, senderId = ByteArray(8),
            payload = InventoryCodec.encode(Inventory(0, 1, ids))))
        assertEquals(178, frame.size)
        assertTrue(frame.size <= 182)
        // chunking
        val chunks = InventoryCodec.chunk(List(40) { NodeId.random() })
        assertEquals(listOf(19, 19, 2), chunks.map { it.ids.size })
        assertTrue(chunks.all { it.chunkCount == 3 })
        assertEquals(1, InventoryCodec.chunk(emptyList()).size)
        assertEquals(0, InventoryCodec.chunk(emptyList())[0].ids.size)
    }

    @Test fun helloIs13Bytes() {
        assertEquals(13, HelloCodec.encode(Hello(NodeId.random(), 1, 0, 1)).size)
    }

    @Test fun fuzzRandomBytesNeverThrow() {
        val rnd = Random(12345)
        repeat(10_000) {
            val b = ByteArray(rnd.nextInt(300)).also { rnd.nextBytes(it) }
            // bias the first bytes towards plausible headers so deeper paths are reached
            if (b.size > 14 && rnd.nextBoolean()) { b[0] = 1; b[1] = (0x70 + rnd.nextInt(5)).toByte() }
            FrameCodec.decode(b)
            NeedCodec.decode(b); AckCodec.decode(b); HelloCodec.decode(b); InventoryCodec.decode(b)
        }
    }

    @Test fun fuzzMutationsOfValidFramesNeverThrow() {
        val rnd = Random(777)
        val origin = NodeId.random()
        val need = sampleNeed(origin)
        val valid = listOf(
            needFrameBytes(need),
            FrameCodec.encode(Frame(type = FrameTypes.ACK, ttl = 7, timestampMs = T0, senderId = origin, recipientId = origin,
                payload = AckCodec.encode(sampleAck(need)))),
            FrameCodec.encode(Frame(type = FrameTypes.HELLO, ttl = 1, timestampMs = T0, senderId = origin,
                payload = HelloCodec.encode(Hello(origin, 1, 0, 1)))),
            FrameCodec.encode(Frame(type = FrameTypes.INVENTORY, ttl = 1, timestampMs = T0, senderId = origin,
                payload = InventoryCodec.encode(Inventory(0, 1, listOf(NodeId.random()))))),
            FrameCodec.encode(Frame(type = 0x7E, ttl = 5, timestampMs = T0, senderId = origin, payload = byteArrayOf(1, 2),
                signature = ByteArray(64))),
        )
        val h = SoloHarness()
        h.node.linkUp("L1"); h.node.linkUp("L2")
        repeat(10_000) {
            val src = valid[rnd.nextInt(valid.size)]
            val m = when (rnd.nextInt(4)) {
                0 -> src.copyOf().also { for (k in 0..rnd.nextInt(4)) it[rnd.nextInt(it.size)] = rnd.nextInt(256).toByte() }
                1 -> src.copyOf(rnd.nextInt(src.size + 1))
                2 -> src + ByteArray(rnd.nextInt(5)).also { rnd.nextBytes(it) }
                else -> src.copyOf().also { it[rnd.nextInt(minOf(14, it.size))] = rnd.nextInt(256).toByte() }
            }
            FrameCodec.decode(m)
            h.node.onFrame(if (rnd.nextBoolean()) "L1" else "L2", m)   // whole engine must not throw either
            h.node.onFrame("unknown-link", m)
            if (h.tasks.size > 500) h.runTasks()
        }
        h.runTasks()
    }
}
