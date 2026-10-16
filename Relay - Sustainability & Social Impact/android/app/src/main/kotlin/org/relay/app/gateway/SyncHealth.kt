package org.relay.app.gateway

import org.relay.app.BackendDefaults

/**
 * What the gateway knows about its link to the backend: the outcome of the most recent sync attempt. Immutable;
 * [record] returns the next value. Pure, so the transitions are unit-tested without Android.
 */
data class SyncHealth(
    /** Epoch ms of the last attempt (ok or not); 0 = none yet. */
    val lastAttemptAtMs: Long = 0,
    /** Epoch ms of the last successful sync; 0 = never. */
    val lastOkAtMs: Long = 0,
    /** Outcome of the last attempt; null until one has run. */
    val lastOk: Boolean? = null,
    /** Short human text for the last failure (null after a success). */
    val error: String? = null,
    /** `host:port` of the backend the last attempt used. */
    val host: String = "",
    val consecutiveFailures: Int = 0,
    /** Epoch ms of the first failure in the current run of failures; 0 when not failing. */
    val failingSinceMs: Long = 0,
) {
    fun record(ok: Boolean, rawError: String?, nowMs: Long, backendUrl: String): SyncHealth =
        if (ok) {
            copy(
                lastAttemptAtMs = nowMs, lastOkAtMs = nowMs, lastOk = true, error = null,
                host = BackendDefaults.hostOf(backendUrl), consecutiveFailures = 0, failingSinceMs = 0,
            )
        } else {
            copy(
                lastAttemptAtMs = nowMs, lastOk = false, error = shortError(rawError),
                host = BackendDefaults.hostOf(backendUrl), consecutiveFailures = consecutiveFailures + 1,
                failingSinceMs = if (lastOk == false && failingSinceMs != 0L) failingSinceMs else nowMs,
            )
        }

    companion object {
        /** Turns "SocketTimeoutException: failed to connect to /10.0.2.2 (port 8080) ..." into "timed out" etc. */
        fun shortError(raw: String?): String {
            val r = raw.orEmpty()
            val l = r.lowercase()
            return when {
                r.isBlank() -> "unknown error"
                l.contains("sockettimeout") || l.contains("timed out") || l.contains("timeout") -> "timed out"
                l.contains("unknownhost") || l.contains("unable to resolve") || l.contains("no address associated") -> "unknown host"
                l.contains("enetunreach") || l.contains("network is unreachable") || l.contains("no route to host") -> "network unreachable"
                l.contains("connectexception") || l.contains("connection refused") || l.contains("econnrefused") -> "connection refused"
                l.contains("cleartext") -> "cleartext HTTP blocked"
                l.startsWith("http 401") || l.startsWith("http 403") -> "backend rejected the device key (${r.take(8).trim()})"
                l.startsWith("http ") -> r.take(40)
                l.contains("bad backend url") -> "bad backend URL"
                l.contains("bad response") -> "bad response from backend"
                else -> r.take(60)
            }
        }
    }
}

/** How the sync health is presented. */
enum class HealthKind { NOT_GATEWAY, PENDING, OK, FAILING }

data class HealthLine(val kind: HealthKind, val text: String)

object SyncHealthText {
    /** "12 s ago" / "3 min ago" / "2 h ago". */
    fun ago(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return when {
            s < 60 -> "$s s ago"
            s < 3600 -> "${s / 60} min ago"
            else -> "${s / 3600} h ago"
        }
    }

    /**
     * The one-line gateway status shown on Home and Settings. [backendHost] is the host of the *currently
     * configured* backend; health recorded against a different host is treated as "not yet tried".
     */
    fun line(health: SyncHealth, isGateway: Boolean, backendHost: String, nowMs: Long): HealthLine {
        if (!isGateway) return HealthLine(HealthKind.NOT_GATEWAY, "Not a gateway (no internet or Pretend offline)")
        val h = if (health.host == backendHost) health else SyncHealth()
        return when (h.lastOk) {
            null -> HealthLine(HealthKind.PENDING, "Gateway — contacting backend ($backendHost)…")
            true -> HealthLine(HealthKind.OK, "Gateway ✓ synced ${ago(nowMs - h.lastOkAtMs)}")
            false -> HealthLine(
                HealthKind.FAILING,
                "Gateway can't reach backend ($backendHost) — check Settings / Tailscale",
            )
        }
    }

    /** Second line for the failing case: reason plus how long it has been failing. */
    fun failureDetail(health: SyncHealth, nowMs: Long): String? {
        if (health.lastOk != false) return null
        val since = if (health.failingSinceMs > 0) " · failing for ${ago(nowMs - health.failingSinceMs).removeSuffix(" ago")}" else ""
        return "${health.error ?: "unknown error"}$since"
    }
}
