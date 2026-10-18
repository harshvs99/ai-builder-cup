package org.relay.app

/**
 * The plain-language connectivity status shown on Home and Network (design spec section 2), derived from
 * [MeshStatus]. Pure: no strings, ids or error text; the UI turns [kind] into words per language.
 */
data class VillagerStatus(
    val kind: Kind,
    val dot: Dot,
    /** Phones in range right now. */
    val peers: Int,
    /** Hops to the nearest gateway; null when this phone is the gateway or none is known. */
    val hopsToGateway: Int?,
    /** Milliseconds since the last successful sync of this phone as gateway; null when unknown. */
    val syncedAgoMs: Long?,
) {
    enum class Kind {
        /** A gateway phone is known and reachable over the mesh. */
        REACHABLE,
        /** This phone is the gateway and syncing OK. */
        THIS_PHONE_GATEWAY,
        /** Peers around, but no gateway, or the gateway cannot reach the backend. */
        WAITING_FOR_GATEWAY,
        /** No peers. */
        SEARCHING,
        BLUETOOTH_OFF,
    }

    enum class Dot { GREEN, AMBER, GREY }

    val isReachable: Boolean get() = dot == Dot.GREEN

    companion object {
        const val NO_GATEWAY_HOPS = 0xFF

        fun from(s: MeshStatus, now: Long): VillagerStatus {
            val peers = s.peers.size
            fun st(kind: Kind, dot: Dot, hops: Int? = null, ago: Long? = null) = VillagerStatus(kind, dot, peers, hops, ago)
            return when {
                !s.bluetoothOn -> st(Kind.BLUETOOTH_OFF, Dot.GREY)
                // This phone is the gateway: it is only "sending" while its backend sync is not failing.
                s.isGateway ->
                    if (s.syncHealth.lastOk == false) st(Kind.WAITING_FOR_GATEWAY, Dot.AMBER)
                    else st(
                        Kind.THIS_PHONE_GATEWAY, Dot.GREEN,
                        ago = s.syncHealth.lastOkAtMs.takeIf { it > 0 }?.let { (now - it).coerceAtLeast(0) },
                    )
                s.hopsToGateway < NO_GATEWAY_HOPS -> st(Kind.REACHABLE, Dot.GREEN, hops = s.hopsToGateway.coerceAtLeast(1))
                peers > 0 -> st(Kind.WAITING_FOR_GATEWAY, Dot.AMBER)
                else -> st(Kind.SEARCHING, Dot.GREY)
            }
        }
    }
}
