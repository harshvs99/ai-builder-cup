package org.relay.app.store

import org.relay.core.gossip.PacketStore
import org.relay.core.gossip.StoredPacket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * [PacketStore] held in memory and mirrored to one file.
 *
 * File layout: magic(4) version(2) count(4) { key(UTF) type(1) expiryBasisMs(8) rawLen(2) raw } crc32(8, of everything before).
 * The file is rewritten as a whole via temp file + rename ([AtomicFiles]), so a crash leaves either the old or
 * the new complete file. A corrupt or truncated file loads as an empty store (the bad file is renamed to
 * `<name>.corrupt` for post-mortem) and never throws.
 *
 * [flushDelayMs] = 0 writes synchronously on every mutation (tests); > 0 coalesces writes on a daemon thread.
 */
class FilePacketStore(
    private val file: File,
    private val flushDelayMs: Long = 0,
    private val log: (String) -> Unit = {},
) : PacketStore {
    private val map = LinkedHashMap<String, StoredPacket>()
    private var dirty = false
    private var flushScheduled = false
    private val flusher: ScheduledExecutorService? =
        if (flushDelayMs > 0) Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "relay-store").also { it.isDaemon = true } } else null

    /** True when the last load found an unreadable file and started empty. */
    @Volatile var loadedCorrupt: Boolean = false
        private set

    init { load() }

    // ------------------------------------------------------------------ PacketStore

    @Synchronized override fun put(packet: StoredPacket) { map[packet.key] = packet; markDirty() }
    @Synchronized override fun get(key: String): StoredPacket? = map[key]
    @Synchronized override fun contains(key: String): Boolean = map.containsKey(key)
    @Synchronized override fun allKeys(): List<String> = map.keys.toList()
    @Synchronized override fun all(): List<StoredPacket> = map.values.toList()
    @Synchronized override fun remove(key: String) { if (map.remove(key) != null) markDirty() }

    @Synchronized override fun expire(cutoffMs: Long): List<String> {
        val dead = map.values.filter { it.expiryBasisMs < cutoffMs }.map { it.key }
        if (dead.isNotEmpty()) { dead.forEach { map.remove(it) }; markDirty() }
        return dead
    }

    @Synchronized fun size(): Int = map.size

    @Synchronized fun clear() { if (map.isNotEmpty()) { map.clear(); markDirty() } }

    // ------------------------------------------------------------------ persistence

    /** Write now if there are unsaved changes. */
    @Synchronized fun flush() {
        if (!dirty) return
        dirty = false
        try {
            AtomicFiles.write(file, encode(map.values))
        } catch (e: Exception) {
            dirty = true
            log("packet store write failed: $e")
        }
    }

    fun close() {
        flush()
        flusher?.shutdown()
    }

    private fun markDirty() {
        dirty = true
        if (flusher == null) { flush(); return }
        if (flushScheduled) return
        flushScheduled = true
        try {
            flusher.schedule({ synchronized(this) { flushScheduled = false; flush() } }, flushDelayMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            flushScheduled = false
            flush()
        }
    }

    private fun load() {
        val bytes = AtomicFiles.readOrNull(file) ?: return
        val parsed = try { decode(bytes) } catch (e: Exception) { null }
        if (parsed == null) {
            loadedCorrupt = true
            log("packet store file unreadable; starting empty")
            try { file.renameTo(File(file.parentFile, file.name + ".corrupt")) } catch (_: Exception) {}
            return
        }
        for (p in parsed) map[p.key] = p
    }

    companion object {
        private const val MAGIC = 0x524C5931 // "RLY1"
        private const val VERSION = 1

        internal fun encode(packets: Collection<StoredPacket>): ByteArray {
            val body = ByteArrayOutputStream()
            DataOutputStream(body).use { d ->
                d.writeInt(MAGIC)
                d.writeShort(VERSION)
                d.writeInt(packets.size)
                for (p in packets) {
                    d.writeUTF(p.key)
                    d.writeByte(p.type)
                    d.writeLong(p.expiryBasisMs)
                    require(p.raw.size <= 0xFFFF) { "raw frame too large" }
                    d.writeShort(p.raw.size)
                    d.write(p.raw)
                }
            }
            val bodyBytes = body.toByteArray()
            val crc = CRC32().also { it.update(bodyBytes) }.value
            val out = ByteArrayOutputStream(bodyBytes.size + 8)
            out.write(bodyBytes)
            DataOutputStream(out).writeLong(crc)
            return out.toByteArray()
        }

        /** Null when the bytes are not a valid store file. */
        internal fun decode(bytes: ByteArray): List<StoredPacket>? {
            if (bytes.size < 4 + 2 + 4 + 8) return null
            val bodyLen = bytes.size - 8
            val crcStored = DataInputStream(ByteArrayInputStream(bytes, bodyLen, 8)).readLong()
            val crc = CRC32().also { it.update(bytes, 0, bodyLen) }.value
            if (crc != crcStored) return null
            val d = DataInputStream(ByteArrayInputStream(bytes, 0, bodyLen))
            if (d.readInt() != MAGIC) return null
            if (d.readUnsignedShort() != VERSION) return null
            val count = d.readInt()
            if (count < 0 || count > 1_000_000) return null
            val out = ArrayList<StoredPacket>(count)
            repeat(count) {
                val key = d.readUTF()
                val type = d.readUnsignedByte()
                val basis = d.readLong()
                val len = d.readUnsignedShort()
                val raw = ByteArray(len)
                d.readFully(raw)
                out += StoredPacket(key, type, raw, basis)
            }
            if (d.available() != 0) return null
            return out
        }
    }
}
