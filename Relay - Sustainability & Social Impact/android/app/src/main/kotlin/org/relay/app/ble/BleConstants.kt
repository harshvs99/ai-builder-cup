package org.relay.app.ble

import java.util.UUID

/** SPEC section 2 transport constants. */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("5b9b5c02-f1b0-4ea1-9e8a-e08da2708c44")
    val CHAR_UUID: UUID = UUID.fromString("77175a01-40b4-447c-b66e-0cae864e64d5")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Scan-response manufacturer data id (0xFFFF = reserved for testing); the payload is our 8-byte node id. */
    const val MANUFACTURER_ID = 0xFFFF

    const val REQUESTED_MTU = 517
    const val MIN_MTU = 185
    const val ATT_OVERHEAD = 3
    const val DEFAULT_MTU = 23

    const val MAX_CENTRAL_LINKS = 4
    const val MAX_TOTAL_LINKS = 7

    const val OP_TIMEOUT_MS = 5_000L
    const val SETUP_TIMEOUT_MS = 25_000L
    const val PERIPHERAL_SUBSCRIBE_TIMEOUT_MS = 12_000L

    const val BACKOFF_MIN_MS = 2_000L
    const val BACKOFF_MAX_MS = 60_000L
    const val ALLOWLIST_IGNORE_MS = 60_000L
    const val DUPLICATE_IGNORE_MS = 10_000L

    /** Android allows 5 scan starts per 30 s window per app. */
    const val SCAN_STARTS_PER_WINDOW = 5
    const val SCAN_WINDOW_MS = 30_000L
}
