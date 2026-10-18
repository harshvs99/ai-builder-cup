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
}
