package org.relay.core.payload

import java.nio.ByteBuffer

/** INVENTORY chunk payload (SPEC 5.4). */
data class Inventory(
    val chunkIndex: Int,
    val chunkCount: Int,
    val ids: List<ByteArray>,
) {
    override fun equals(other: Any?): Boolean =
        other is Inventory && chunkIndex == other.chunkIndex && chunkCount == other.chunkCount &&
            ids.size == other.ids.size && ids.indices.all { ids[it].contentEquals(other.ids[it]) }

    override fun hashCode(): Int {
        var h = chunkIndex * 31 + chunkCount
        for (id in ids) h = 31 * h + id.contentHashCode()
        return h
    }
}

object InventoryCodec {
    const val VERSION = 1
    const val MAX_IDS_PER_CHUNK = 19
    const val MAX_CHUNKS = 255

    fun encode(inv: Inventory): ByteArray {
        require(inv.ids.size <= MAX_IDS_PER_CHUNK) { "too many ids in chunk" }
        require(inv.chunkCount in 1..MAX_CHUNKS && inv.chunkIndex in 0 until inv.chunkCount)
        require(inv.ids.all { it.size == 8 })
        val buf = ByteBuffer.allocate(4 + 8 * inv.ids.size)
        buf.put(VERSION.toByte())
        buf.put(inv.chunkIndex.toByte())
        buf.put(inv.chunkCount.toByte())
        buf.put(inv.ids.size.toByte())
        for (id in inv.ids) buf.put(id)
        return buf.array()
    }

    fun decode(p: ByteArray): Inventory? {
        if (p.size < 4 || (p[0].toInt() and 0xFF) != VERSION) return null
        val index = p[1].toInt() and 0xFF
        val count = p[2].toInt() and 0xFF
        val n = p[3].toInt() and 0xFF
        if (count < 1 || index >= count || n > MAX_IDS_PER_CHUNK) return null
        if (p.size != 4 + 8 * n) return null
        val ids = List(n) { i -> p.copyOfRange(4 + 8 * i, 12 + 8 * i) }
        return Inventory(index, count, ids)
    }

    /**
     * Split [ids] into chunks of at most 19. An empty list yields one empty chunk. Lists longer than
     * 255 * 19 ids are cut to that size (the caller should pass the most important ids first).
     */
    fun chunk(ids: List<ByteArray>): List<Inventory> {
        val capped = if (ids.size > MAX_CHUNKS * MAX_IDS_PER_CHUNK) ids.subList(0, MAX_CHUNKS * MAX_IDS_PER_CHUNK) else ids
        if (capped.isEmpty()) return listOf(Inventory(0, 1, emptyList()))
        val parts = capped.chunked(MAX_IDS_PER_CHUNK)
        return parts.mapIndexed { i, part -> Inventory(i, parts.size, part) }
    }
}
