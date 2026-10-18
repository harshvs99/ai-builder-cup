import Foundation

/// What the gateway knows about its link to the backend: the outcome of the most recent sync attempt. Immutable;
/// `record` returns the next value. Pure, so the transitions are unit-tested without a network.
public struct SyncHealth: Equatable, Sendable {
    /// Epoch ms of the last attempt (ok or not); 0 = none yet.
    public var lastAttemptAtMs: Int64
    /// Epoch ms of the last successful sync; 0 = never.
    public var lastOkAtMs: Int64
    /// Outcome of the last attempt; nil until one has run.
    public var lastOk: Bool?
    /// Short human text for the last failure (nil after a success).
    public var error: String?
    /// `host:port` of the backend the last attempt used.
    public var host: String
    public var consecutiveFailures: Int
    /// Epoch ms of the first failure in the current run of failures; 0 when not failing.
    public var failingSinceMs: Int64

    public init(lastAttemptAtMs: Int64 = 0, lastOkAtMs: Int64 = 0, lastOk: Bool? = nil, error: String? = nil,
                host: String = "", consecutiveFailures: Int = 0, failingSinceMs: Int64 = 0) {
        self.lastAttemptAtMs = lastAttemptAtMs; self.lastOkAtMs = lastOkAtMs; self.lastOk = lastOk; self.error = error
        self.host = host; self.consecutiveFailures = consecutiveFailures; self.failingSinceMs = failingSinceMs
    }

    public func record(_ ok: Bool, _ rawError: String?, _ nowMs: Int64, _ backendUrl: String) -> SyncHealth {
        var h = self
        h.lastAttemptAtMs = nowMs
        h.host = BackendDefaults.hostOf(backendUrl)
        if ok {
            h.lastOkAtMs = nowMs; h.lastOk = true; h.error = nil
            h.consecutiveFailures = 0; h.failingSinceMs = 0
        } else {
            h.lastOk = false; h.error = SyncHealth.shortError(rawError)
            h.consecutiveFailures = consecutiveFailures + 1
            h.failingSinceMs = (lastOk == false && failingSinceMs != 0) ? failingSinceMs : nowMs
        }
        return h
    }

    /// Turns "SocketTimeoutException: failed to connect to /10.0.2.2 (port 8080) ..." into "timed out" etc.
    public static func shortError(_ raw: String?) -> String {
        let r = raw ?? ""
        let l = r.lowercased()
        if r.trimmed.isEmpty { return "unknown error" }
        if l.contains("sockettimeout") || l.contains("timed out") || l.contains("timeout") { return "timed out" }
        if l.contains("unknownhost") || l.contains("unable to resolve") || l.contains("no address associated") { return "unknown host" }
        if l.contains("enetunreach") || l.contains("network is unreachable") || l.contains("no route to host") { return "network unreachable" }
        if l.contains("connectexception") || l.contains("connection refused") || l.contains("econnrefused") { return "connection refused" }
        if l.contains("cleartext") { return "cleartext HTTP blocked" }
        if l.hasPrefix("http 401") || l.hasPrefix("http 403") { return "backend rejected the device key (\(r.take(8).trimmed))" }
        if l.hasPrefix("http ") { return r.take(40) }
        if l.contains("bad backend url") { return "bad backend URL" }
        if l.contains("bad response") { return "bad response from backend" }
        return r.take(60)
    }
}

/// How the sync health is presented.
public enum HealthKind: Sendable, Hashable { case notGateway, pending, ok, failing }

public struct HealthLine: Equatable, Sendable {
    public let kind: HealthKind
    public let text: String
    public init(kind: HealthKind, text: String) { self.kind = kind; self.text = text }
}

public enum SyncHealthText {
    /// "12 s ago" / "3 min ago" / "2 h ago".
    public static func ago(_ ms: Int64) -> String {
        let s = max(0, ms) / 1000
        if s < 60 { return "\(s) s ago" }
        if s < 3600 { return "\(s / 60) min ago" }
        return "\(s / 3600) h ago"
    }

    /// The one-line gateway status shown on Home and Settings. [backendHost] is the host of the *currently
    /// configured* backend; health recorded against a different host is treated as "not yet tried".
    public static func line(_ health: SyncHealth, isGateway: Bool, backendHost: String, nowMs: Int64) -> HealthLine {
        if !isGateway { return HealthLine(kind: .notGateway, text: "Not a gateway (no internet or Pretend offline)") }
        let h = health.host == backendHost ? health : SyncHealth()
        switch h.lastOk {
        case nil: return HealthLine(kind: .pending, text: "Gateway — contacting backend (\(backendHost))…")
        case true?: return HealthLine(kind: .ok, text: "Gateway ✓ synced \(ago(nowMs - h.lastOkAtMs))")
        case false?: return HealthLine(kind: .failing, text: "Gateway can't reach backend (\(backendHost)) — check Settings / Tailscale")
        }
    }

    /// Second line for the failing case: reason plus how long it has been failing.
    public static func failureDetail(_ health: SyncHealth, nowMs: Int64) -> String? {
        if health.lastOk != false { return nil }
        var since = ""
        if health.failingSinceMs > 0 {
            let a = ago(nowMs - health.failingSinceMs)
            since = " · failing for " + (a.hasSuffix(" ago") ? String(a.dropLast(4)) : a)
        }
        return "\(health.error ?? "unknown error")\(since)"
    }
}
