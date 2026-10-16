package org.relay.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.relay.app.store.AtomicFiles
import org.relay.app.store.FilePacketStore
import org.relay.core.frame.FrameTypes
import org.relay.core.gossip.StoredPacket
import java.io.File

class FilePacketStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pkt(key: String, type: Int = FrameTypes.NEED, basis: Long = 1_000, size: Int = 60) =
        StoredPacket(key, type, ByteArray(size) { (it + key.hashCode()).toByte() }, basis)

    @Test fun roundTripPreservesEverythingInOrder() {
        val f = File(tmp.root, "packets.bin")
        val a = FilePacketStore(f)
        val packets = listOf(pkt("aa11"), pkt("bb22", FrameTypes.ACK, 5_000, 100), pkt("cc33", basis = 9_999_999_999L, size = 140))
        packets.forEach { a.put(it) }

        val b = FilePacketStore(f)
        assertFalse(b.loadedCorrupt)
        assertEquals(listOf("aa11", "bb22", "cc33"), b.allKeys())
        for (p in packets) {
            val got = b.get(p.key)!!
            assertEquals(p.type, got.type)
            assertEquals(p.expiryBasisMs, got.expiryBasisMs)
            assertArrayEquals(p.raw, got.raw)
        }
        assertTrue(b.contains("bb22"))
        assertNull(b.get("nope"))
    }

    @Test fun removeAndExpireArePersisted() {
        val f = File(tmp.root, "packets.bin")
        val a = FilePacketStore(f)
        a.put(pkt("old1", basis = 10)); a.put(pkt("old2", basis = 20)); a.put(pkt("new1", basis = 1_000)); a.put(pkt("gone", basis = 2_000))
        assertEquals(setOf("old1", "old2"), a.expire(cutoffMs = 500).toSet())
        a.remove("gone")
        assertEquals(listOf("new1"), FilePacketStore(f).allKeys())
    }

    @Test fun rewriteIsAtomicNoTempFileLeftAndStaleTempIgnored() {
        val f = File(tmp.root, "packets.bin")
        val tmpFile = AtomicFiles.tmpFor(f)
        val a = FilePacketStore(f)
        a.put(pkt("k1"))
        assertTrue(f.exists())
        assertFalse("temp file must be renamed away", tmpFile.exists())

        // Simulate a crash mid-rewrite: a half-written temp file next to a good main file.
        tmpFile.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val b = FilePacketStore(f)
        assertEquals(listOf("k1"), b.allKeys())
        b.put(pkt("k2")) // next rewrite replaces the stale temp
        assertFalse(tmpFile.exists())
        assertEquals(listOf("k1", "k2"), FilePacketStore(f).allKeys())
    }

    @Test fun garbageFileLoadsEmptyAndDoesNotThrow() {
        val f = File(tmp.root, "packets.bin")
        f.writeBytes("this is not a packet store".toByteArray())
        val s = FilePacketStore(f)
        assertTrue(s.loadedCorrupt)
        assertEquals(0, s.size())
        assertTrue(File(tmp.root, "packets.bin.corrupt").exists())
        s.put(pkt("fresh"))
        assertEquals(listOf("fresh"), FilePacketStore(f).allKeys())
    }

    @Test fun truncatedAndBitFlippedFilesLoadEmpty() {
        val f = File(tmp.root, "packets.bin")
        FilePacketStore(f).also { it.put(pkt("a1")); it.put(pkt("a2")) }
        val good = f.readBytes()

        f.writeBytes(good.copyOf(good.size - 20))
        assertTrue(FilePacketStore(f).let { it.loadedCorrupt && it.size() == 0 })

        val flipped = good.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x55).toByte() }
        File(tmp.root, "packets.bin").writeBytes(flipped)
        assertTrue(FilePacketStore(f).let { it.loadedCorrupt && it.size() == 0 })

        f.writeBytes(ByteArray(0))
        assertEquals(0, FilePacketStore(f).size())
    }

    @Test fun missingFileIsAnEmptyStore() {
        val s = FilePacketStore(File(tmp.root, "none/packets.bin"))
        assertEquals(0, s.size()); assertFalse(s.loadedCorrupt)
    }

    @Test fun debouncedFlushWritesEventuallyAndOnFlush() {
        val f = File(tmp.root, "packets.bin")
        val s = FilePacketStore(f, flushDelayMs = 50)
        s.put(pkt("d1"))
        s.flush() // explicit flush is immediate
        assertEquals(listOf("d1"), FilePacketStore(f).allKeys())
        s.put(pkt("d2"))
        val deadline = System.currentTimeMillis() + 3_000
        while (FilePacketStore(f).allKeys().size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("d1", "d2"), FilePacketStore(f).allKeys())
        s.close()
    }

    @Test fun clearEmptiesStoreAndFile() {
        val f = File(tmp.root, "packets.bin")
        val s = FilePacketStore(f)
        s.put(pkt("x")); s.clear()
        assertEquals(0, FilePacketStore(f).size())
    }
}
