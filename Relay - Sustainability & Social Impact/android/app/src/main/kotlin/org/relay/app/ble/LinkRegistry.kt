package org.relay.app.ble

import org.relay.core.gossip.DuplicateLinkPolicy
import org.relay.core.ids.Hex

enum class LinkRole { CENTRAL, PERIPHERAL }

/**
 * Bookkeeping for established links (those reported to the gossip engine): caps, MAC to node-id mapping,
 * and the duplicate-link rule from SPEC section 2. No Android types.
 */
class LinkRegistry(private val myId: ByteArray) {
    data class Link(val linkId: String, val mac: String, val role: LinkRole, val nodeIdHex: String? = null)

    sealed interface Decision {
        /** Nothing to close. */
        data object Keep : Decision
        /** Close the link that just said HELLO. */
        data class CloseThis(val reason: String) : Decision
        /** Close the other (older) link to the same node. */
        data class CloseOther(val otherLinkId: String) : Decision
    }

    private val links = LinkedHashMap<String, Link>()
    /** MAC -> node id, access-ordered and bounded: peers rotate their MAC, so entries would otherwise pile up. */
    private val macToNode = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > MAX_MAC_ENTRIES
    }
    /** MAC -> last scan RSSI in dBm, access-ordered and bounded like [macToNode]. Written from the BLE scan callback. */
    private val macRssi = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > MAX_MAC_ENTRIES
    }
    private val myIdHex = Hex.encode(myId)

    companion object { const val MAX_MAC_ENTRIES = 64 }

    /** Remember the RSSI of the latest scan result from [mac]; values outside -127..0 dBm ("unavailable") are ignored. */
    @Synchronized fun noteScanRssi(mac: String, rssi: Int) {
        if (rssi in -127..0) macRssi[mac] = rssi
    }

    /**
     * Last scan RSSI (dBm) of the advertiser behind [linkId], or null when unknown. Only central links ("c:<mac>")
     * are known: we connected to the MAC we scanned. A peripheral link's MAC is the remote's connection address,
     * which was never scanned, so it has no entry.
     */
    @Synchronized fun rssiFor(linkId: String): Int? =
        if (linkId.startsWith("c:")) macRssi[linkId.substring(2)] else null

    @Synchronized fun add(linkId: String, mac: String, role: LinkRole) {
        links[linkId] = Link(linkId, mac, role)
    }

    @Synchronized fun remove(linkId: String) { links.remove(linkId) }

    @Synchronized fun clear() { links.clear(); macToNode.clear(); macRssi.clear() }

    @Synchronized fun get(linkId: String): Link? = links[linkId]
    @Synchronized fun knownMacCount(): Int = macToNode.size
    @Synchronized fun all(): List<Link> = links.values.toList()
    @Synchronized fun total(): Int = links.size
    @Synchronized fun centralCount(): Int = links.values.count { it.role == LinkRole.CENTRAL }

    @Synchronized fun hasLinkToMac(mac: String): Boolean = links.values.any { it.mac == mac }

    @Synchronized fun isNodeConnected(nodeIdHex: String): Boolean = links.values.any { it.nodeIdHex == nodeIdHex }

    /**
     * Should we initiate a central connection to the advertiser at [mac]? No if any live link already uses that
     * MAC, or if that MAC is known to be a node we already have a link to (avoids reconnect loops after a
     * duplicate-link close).
     */
    @Synchronized fun shouldConnect(mac: String): Boolean {
        if (hasLinkToMac(mac)) return false
        val node = macToNode[mac]
        if (node != null && isNodeConnected(node)) return false
        return true
    }

    /** Record the HELLO and decide whether this link or a duplicate must be closed. */
    @Synchronized fun onHello(linkId: String, peerNodeId: ByteArray): Decision {
        val link = links[linkId] ?: return Decision.Keep
        val hex = Hex.encode(peerNodeId)
        if (hex == myIdHex) return Decision.CloseThis("peer node id equals ours")
        links[linkId] = link.copy(nodeIdHex = hex)
        macToNode[link.mac] = hex
        val other = links.values.firstOrNull { it.linkId != linkId && it.nodeIdHex == hex } ?: return Decision.Keep
        if (other.role == link.role) {
            // Same role twice (e.g. the peer's MAC rotated): keep the older link.
            return Decision.CloseThis("duplicate ${link.role.name.lowercase()} link to ${hex.take(8)}")
        }
        val keepRole = if (DuplicateLinkPolicy.keepAsCentral(myId, peerNodeId)) LinkRole.CENTRAL else LinkRole.PERIPHERAL
        return if (link.role == keepRole) Decision.CloseOther(other.linkId)
        else Decision.CloseThis("duplicate link to ${hex.take(8)}; keeping ${keepRole.name.lowercase()} side")
    }
}
