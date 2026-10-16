package org.relay.app.ble

enum class StartResult {
    /** The operation was issued; wait for [GattOpQueue.onComplete]. */
    STARTED,
    /** The stack was busy; try again on a later [GattOpQueue.tick]. */
    RETRY,
    /** The operation cannot be issued; drop it and move on. */
    FAILED,
}

interface GattOp {
    val label: String
    fun start(): StartResult
}

/**
 * Serialises GATT operations on one connection: Android allows exactly one outstanding operation per
 * BluetoothGatt / per notifying device. The next operation starts only when [onComplete] is called (from the
 * matching stack callback) or the in-flight one times out. No Android types: unit-testable on the JVM.
 *
 * Intended to be driven from a single thread; methods are synchronized anyway.
 */
class GattOpQueue(
    private val timeoutMs: Long = BleConstants.OP_TIMEOUT_MS,
    private val maxQueued: Int = 512,
    private val maxRetries: Int = 8,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private class Entry(val op: GattOp) { var retries = 0 }

    private val pending = ArrayDeque<Entry>()
    private var current: Entry? = null
    private var startedAt = 0L
    private var pumping = false
    /** Set when the stack said BUSY: retries happen on [tick], not on every enqueue/complete, so bursts don't burn the retry budget. */
    private var waitingForTick = false

    /** Operations that timed out since the last successful completion. */
    @get:Synchronized var consecutiveTimeouts: Int = 0
        private set

    @get:Synchronized var totalTimeouts: Int = 0
        private set

    @get:Synchronized var dropped: Int = 0
        private set

    @Synchronized fun size(): Int = pending.size + if (current != null) 1 else 0
    @Synchronized fun inFlight(): Boolean = current != null

    /** Returns false (and counts a drop) when the queue is full. */
    @Synchronized fun enqueue(op: GattOp): Boolean {
        if (pending.size >= maxQueued) { dropped++; log("gatt queue full, dropping ${op.label}"); return false }
        pending.addLast(Entry(op))
        pump()
        return true
    }

    /** The stack reported that the in-flight operation finished (success or error). */
    @Synchronized fun onComplete(): Boolean {
        if (current == null) return false
        current = null
        consecutiveTimeouts = 0
        pump()
        return true
    }

    /** Call periodically (a few times a second): times out a stuck operation and retries busy ones. */
    @Synchronized fun tick() {
        waitingForTick = false
        val c = current
        if (c != null && now() - startedAt >= timeoutMs) {
            log("gatt op '${c.op.label}' timed out after ${timeoutMs}ms; advancing")
            current = null
            consecutiveTimeouts++
            totalTimeouts++
        }
        pump()
    }

    @Synchronized fun clear() {
        pending.clear()
        current = null
        waitingForTick = false
    }

    private fun pump() {
        if (pumping || waitingForTick) return
        pumping = true
        try {
            while (current == null) {
                val next = pending.removeFirstOrNull() ?: return
                current = next
                startedAt = now()
                val result = try { next.op.start() } catch (e: Exception) {
                    log("gatt op '${next.op.label}' threw $e"); StartResult.FAILED
                }
                when (result) {
                    StartResult.STARTED -> { /* may already have completed synchronously: then current != next */ }
                    StartResult.FAILED -> { if (current === next) current = null; dropped++ }
                    StartResult.RETRY -> {
                        if (current === next) current = null
                        next.retries++
                        if (next.retries > maxRetries) {
                            dropped++
                            log("gatt op '${next.op.label}' still busy after $maxRetries retries; dropped")
                            continue
                        }
                        pending.addFirst(next)
                        waitingForTick = true
                        return // wait for the next tick()
                    }
                }
            }
        } finally {
            pumping = false
        }
    }
}
