package org.relay.app.ui.network

import org.relay.app.GatewayMode
import org.relay.app.MeshStatus
import org.relay.app.Settings
import org.relay.app.VillagerStatus

/**
 * One node of the Network chain diagram. [hops] is the hop count to show under the node (null for You / unknown).
 */
data class ChainNode(val kind: Kind, val hops: Int? = null) {
    enum class Kind {
        /** This phone, as an ordinary phone. */
        YOU,
        /** This phone, and it is the gateway (green circle with a sync icon). */
        YOU_GATEWAY,
        /** A phone in between. */
        HOP,
        /** A known gateway phone. */
        GATEWAY,
        /** "?": no gateway known. */
        UNKNOWN,
    }
}

/** The chain diagram's nodes for a villager status (spec section 7). Pure. */
object ChainModel {
    fun from(s: VillagerStatus): List<ChainNode> = when (s.kind) {
        // Only You, as the gateway.
        VillagerStatus.Kind.THIS_PHONE_GATEWAY -> listOf(ChainNode(ChainNode.Kind.YOU_GATEWAY))
        VillagerStatus.Kind.REACHABLE -> {
            val h = (s.hopsToGateway ?: 1).coerceAtLeast(1)
            if (h == 1) listOf(ChainNode(ChainNode.Kind.YOU), ChainNode(ChainNode.Kind.GATEWAY, 1))
            // One intermediate circle stands for every phone between; it is labelled with the hops to its far end.
            else listOf(ChainNode(ChainNode.Kind.YOU), ChainNode(ChainNode.Kind.HOP, h - 1), ChainNode(ChainNode.Kind.GATEWAY, h))
        }
        VillagerStatus.Kind.WAITING_FOR_GATEWAY, VillagerStatus.Kind.SEARCHING, VillagerStatus.Kind.BLUETOOTH_OFF ->
            listOf(ChainNode(ChainNode.Kind.YOU), ChainNode(ChainNode.Kind.UNKNOWN))
    }
}

/** The 2x2 tile numbers. Pure; see [from] for each definition. */
data class NetworkStats(
    val phonesNearby: Int,
    val carriedForNeighbours: Int,
    val waitingToPassOn: Int,
    /** Rounded mesh hours left, or null while charging / battery unknown. */
    val meshHoursLeft: Int?,
    val charging: Boolean,
) {
    companion object {
        /**
         * - Phones nearby: peers in range.
         * - Carried for neighbours: stored NEED + ACK frames that this phone did not originate
         *   (`stored - ownStored`, never below 0). [ownStored] = own non-cancelled NEEDs still in the packet store.
         * - Waiting to pass on: as the gateway, the frames not yet uploaded ([MeshStatus.pendingUploads]); otherwise
         *   every stored NEED not known to be delivered, because a non-gateway phone cannot see what others
         *   uploaded: `storedNeeds - ownDelivered`. [ownDelivered] = own stored NEEDs already uploaded or
         *   acknowledged by the control room.
         * - Mesh hours left: [MeshStatus.meshHoursRemaining] rounded down.
         */
        fun from(s: MeshStatus, ownStored: Int, ownDelivered: Int): NetworkStats {
            val carried = (s.storedNeeds + s.storedAcks - ownStored).coerceAtLeast(0)
            val waiting = if (s.isGateway) s.pendingUploads else (s.storedNeeds - ownDelivered).coerceAtLeast(0)
            return NetworkStats(
                phonesNearby = s.peers.size,
                carriedForNeighbours = carried,
                waitingToPassOn = waiting,
                meshHoursLeft = s.meshHoursRemaining?.let { Math.floor(it).toInt().coerceAtLeast(0) },
                charging = s.charging,
            )
        }
    }
}

/** Real permission state as shown in Settings: green Allowed / amber Ask each time / Not allowed. */
enum class PermissionWord {
    ALLOWED, ASK_EACH_TIME, NOT_ALLOWED;

    companion object {
        /**
         * Granted is Allowed. Not granted: a permission the app asks for at the moment of use (location at Send,
         * microphone at the mic tap) is "Ask each time"; one the mesh cannot run without (Bluetooth) is "Not allowed".
         * Android does not tell an app "denied for good" from "never asked", so this is the honest middle word.
         */
        fun of(granted: Boolean, askedWhenNeeded: Boolean): PermissionWord = when {
            granted -> ALLOWED
            askedWhenNeeded -> ASK_EACH_TIME
            else -> NOT_ALLOWED
        }
    }
}

/** Gateway toggle and picker semantics. ALWAYS has no separate behaviour (see [GatewayManager]), so it reads as Automatic. */
object GatewayChoice {
    /** The modes offered in the picker. */
    val options = listOf(GatewayMode.AUTOMATIC, GatewayMode.NEVER)

    fun effective(mode: GatewayMode): GatewayMode = if (mode == GatewayMode.NEVER) GatewayMode.NEVER else GatewayMode.AUTOMATIC

    fun isOn(mode: GatewayMode): Boolean = effective(mode) == GatewayMode.AUTOMATIC
    fun fromToggle(on: Boolean): GatewayMode = if (on) GatewayMode.AUTOMATIC else GatewayMode.NEVER
}

/** When the foreground service runs relative to the app being visible ("Carry messages in the background"). */
object CarryPolicy {
    /**
     * Stop the service when the activity stops, unless the user wants background carrying, or the stop is only a
     * configuration change (rotation recreates the activity and would otherwise bounce the service).
     */
    fun stopWhenBackgrounded(settings: Settings, changingConfigurations: Boolean): Boolean =
        !settings.carryInBackground && !changingConfigurations
}

/** Tile text for "Mesh hours left". */
object MeshHoursText {
    sealed interface Value {
        data object Unknown : Value
        data object Charging : Value
        data object UnderOne : Value
        data class Hours(val n: Int) : Value
    }

    fun of(stats: NetworkStats): Value = when {
        stats.charging -> Value.Charging
        stats.meshHoursLeft == null -> Value.Unknown
        stats.meshHoursLeft < 1 -> Value.UnderOne
        else -> Value.Hours(stats.meshHoursLeft)
    }
}
