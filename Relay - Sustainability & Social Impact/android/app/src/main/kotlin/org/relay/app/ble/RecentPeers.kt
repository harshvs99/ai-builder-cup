package org.relay.app.ble

/**
 * The last few peers with a successful exchange (HELLO accepted), newest first, so a restarted phone re-links to
 * them first. Advertising addresses rotate, so the node id is the identity; the address is kept as a fallback
 * for scan results that carry no node-id hint. [onChange] persists the list.
 */
class RecentPeers(
    initial: List<Peer> = emptyList(),
    private val max: Int = MAX,
    private val onChange: (List<Peer>) -> Unit = {},
) {
    data class Peer(val mac: String, val nodeIdHex: String)

    private var peers: List<Peer> = initial.distinctBy { it.nodeIdHex }.take(max)

    @Synchronized fun all(): List<Peer> = peers

    @Synchronized fun record(mac: String, nodeIdHex: String) {
        if (peers.firstOrNull()?.let { it.nodeIdHex == nodeIdHex && it.mac == mac } == true) return
        peers = (listOf(Peer(mac, nodeIdHex)) + peers.filter { it.nodeIdHex != nodeIdHex }).take(max)
        onChange(peers)
    }

    @Synchronized fun isPreferred(mac: String, nodeIdHex: String?): Boolean =
        peers.any { (nodeIdHex != null && it.nodeIdHex == nodeIdHex) || (nodeIdHex == null && it.mac == mac) }

    /**
     * True when the scan result at [mac] / [nodeIdHex] should wait: inside the preference window
     * (`now < preferUntil`), it is not a recent peer, and a recent peer is not linked yet.
     */
    @Synchronized fun shouldDefer(now: Long, preferUntil: Long, mac: String, nodeIdHex: String?, isLinked: (String) -> Boolean): Boolean {
        if (now >= preferUntil) return false
        if (isPreferred(mac, nodeIdHex)) return false
        return peers.any { !isLinked(it.nodeIdHex) }
    }

    companion object {
        const val MAX = 3
        /** After start-up or after losing a recent peer, non-recent advertisers wait this long. */
        const val PREFER_WINDOW_MS = 4_000L

        fun serialize(peers: List<Peer>): String = peers.joinToString(";") { "${it.mac},${it.nodeIdHex}" }

        fun parse(s: String?): List<Peer> =
            s.orEmpty().split(';').mapNotNull { e ->
                val parts = e.split(',')
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) Peer(parts[0], parts[1]) else null
            }
    }
}
