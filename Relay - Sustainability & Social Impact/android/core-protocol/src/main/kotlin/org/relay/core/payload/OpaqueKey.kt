package org.relay.core.payload

import org.relay.core.frame.Frame
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Dedupe key for unknown types / payload versions (SPEC section 5). */
object OpaqueKey {
    /** First 8 bytes of SHA-256 over type || sender_id || timestamp_ms (8 bytes BE) || payload. */
    fun of(frame: Frame): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(frame.type.toByte())
        md.update(frame.senderId)
        md.update(ByteBuffer.allocate(8).putLong(frame.timestampMs).array())
        md.update(frame.payload)
        return md.digest().copyOf(8)
    }
}
