package org.relay.app.ble

/** Android permits at most 5 BLE scan starts per 30 s window; never exceed it. */
class ScanThrottle(
    private val maxStarts: Int = BleConstants.SCAN_STARTS_PER_WINDOW,
    private val windowMs: Long = BleConstants.SCAN_WINDOW_MS,
) {
    private val starts = ArrayDeque<Long>()

    /** Records a start and returns true if one is allowed now. */
    @Synchronized fun tryAcquire(now: Long): Boolean {
        prune(now)
        if (starts.size >= maxStarts) return false
        starts.addLast(now)
        return true
    }

    /** Milliseconds until the next start would be allowed (0 if allowed now). */
    @Synchronized fun waitMs(now: Long): Long {
        prune(now)
        if (starts.size < maxStarts) return 0
        return maxOf(0L, starts.first() + windowMs - now)
    }

    private fun prune(now: Long) {
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
    }
}
