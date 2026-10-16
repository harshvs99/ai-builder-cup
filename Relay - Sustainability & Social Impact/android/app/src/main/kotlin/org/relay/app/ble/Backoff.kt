package org.relay.app.ble

/**
 * Per-key (MAC) reconnect backoff: 2 s, 4 s, 8 s ... capped at 60 s. Time is passed in so it is testable.
 * [blockUntil] also lets callers ignore a key for a fixed period (allowlist rejects).
 */
class Backoff(
    private val minMs: Long = BleConstants.BACKOFF_MIN_MS,
    private val maxMs: Long = BleConstants.BACKOFF_MAX_MS,
) {
    private class Entry(var failures: Int = 0, var nextAllowedAt: Long = 0)

    private val entries = HashMap<String, Entry>()

    @Synchronized fun canAttempt(key: String, now: Long): Boolean = (entries[key]?.nextAllowedAt ?: 0L) <= now

    @Synchronized fun remainingMs(key: String, now: Long): Long = maxOf(0L, (entries[key]?.nextAllowedAt ?: 0L) - now)

    /** Records a failure and returns the delay now imposed on [key]. */
    @Synchronized fun onFailure(key: String, now: Long): Long {
        val e = entries.getOrPut(key) { Entry() }
        e.failures = minOf(e.failures + 1, 20)
        val delay = minOf(maxMs, minMs shl minOf(e.failures - 1, 20))
        e.nextAllowedAt = maxOf(e.nextAllowedAt, now + delay)
        return delay
    }

    @Synchronized fun onSuccess(key: String) { entries.remove(key) }

    /** Do not attempt [key] before [untilMs]; does not count as a failure. */
    @Synchronized fun blockUntil(key: String, untilMs: Long) {
        val e = entries.getOrPut(key) { Entry() }
        e.nextAllowedAt = maxOf(e.nextAllowedAt, untilMs)
    }

    @Synchronized fun failures(key: String): Int = entries[key]?.failures ?: 0

    @Synchronized fun prune(now: Long) {
        entries.entries.removeAll { it.value.nextAllowedAt <= now && it.value.failures == 0 }
    }

    @Synchronized fun clear() = entries.clear()
}
