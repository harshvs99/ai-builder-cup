package org.relay.app.ble

/** Phases of a central link's setup, in order. */
enum class SetupPhase { CONNECTING, MTU, DISCOVERING, SUBSCRIBING, UP }

/** When to give up on a half-built central link. No Android types: unit-testable. */
object LinkHealth {
    /** A connect that has not completed after this long is abandoned (the stack's own timeout is ~30 s). */
    const val CONNECT_TIMEOUT_MS = 10_000L

    /**
     * Reason to tear down a link that is still being set up, or null to keep waiting. A single timed-out setup
     * operation (e.g. requestMtu) is fatal at once: nothing else would ever advance the state machine, so waiting
     * for the overall deadline only stalls the hop for 25 s. The caller applies the reconnect backoff.
     */
    fun setupAbortReason(phase: SetupPhase, inPhaseMs: Long, totalMs: Long, consecutiveOpTimeouts: Int): String? = when {
        phase == SetupPhase.UP -> null
        consecutiveOpTimeouts >= 1 -> "setup operation timed out in $phase"
        phase == SetupPhase.CONNECTING && inPhaseMs > CONNECT_TIMEOUT_MS -> "connect timeout"
        totalMs > BleConstants.SETUP_TIMEOUT_MS -> "setup timeout in $phase"
        else -> null
    }

    /**
     * Every link carries a frame at least once per resync (the gossip engine sends an inventory on every link every
     * [BleConstants.RESYNC_INTERVAL_MS]); a link silent for [LIVENESS_TIMEOUT_MS] (3 resyncs: two can be lost or
     * delayed by a busy radio) is a zombie, e.g. a peer whose process restarted without a disconnect reaching us.
     */
    const val LIVENESS_TIMEOUT_MS = 3 * BleConstants.RESYNC_INTERVAL_MS

    /** Reason to close an established link that has received nothing since [lastRxAt], or null while it is live. */
    fun livenessAbortReason(lastRxAt: Long, now: Long): String? {
        val idle = now - lastRxAt
        return if (idle >= LIVENESS_TIMEOUT_MS) "no frame for ${idle / 1000}s (liveness watchdog)" else null
    }

    /** Delay before the single re-connect after a service-changed event (not the exponential backoff). */
    const val SERVICE_CHANGED_RECONNECT_MS = 1_000L

    /** Delay before the single retry after discovery found no Relay service. */
    const val DISCOVERY_RETRY_MS = 1_500L

    /**
     * Discovery found no Relay service on a device we expected to be a peer (it may not have added its GATT service
     * yet, or we hold a stale cache): retry once after [DISCOVERY_RETRY_MS], then give up and let the backoff apply.
     * [retriesSoFar] counts the retries already made for this MAC; returns the delay, or null to give up.
     */
    fun discoveryRetryDelayMs(retriesSoFar: Int): Long? = if (retriesSoFar < 1) DISCOVERY_RETRY_MS else null
}
