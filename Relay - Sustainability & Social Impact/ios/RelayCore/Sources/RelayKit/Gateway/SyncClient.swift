import Foundation
import RelayCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct SyncConfig: Equatable, Sendable {
    public var baseUrl: String
    public var deviceKey: String
    public init(baseUrl: String, deviceKey: String) { self.baseUrl = baseUrl; self.deviceKey = deviceKey }
}

/// The optional `hints` object of a sync response: counts for the LOC_HINT frames in the upload.
public struct HintCounts: Equatable, Sendable {
    public var accepted: Int
    public var duplicates: Int
    public var rejected: Int
    public init(accepted: Int, duplicates: Int, rejected: Int) {
        self.accepted = accepted; self.duplicates = duplicates; self.rejected = rejected
    }
}

public struct SyncResponse: Equatable, Sendable {
    public var accepted: Int
    public var duplicates: Int
    public var rejected: Int
    /// ACK payloads (not frames), oldest first.
    public var acks: [[UInt8]]
    public var ackCursor: Int64
    /// Present only when the backend reports hints (it adds `hints` when the upload held any); nil otherwise.
    public var hints: HintCounts?

    public init(accepted: Int, duplicates: Int, rejected: Int, acks: [[UInt8]], ackCursor: Int64, hints: HintCounts? = nil) {
        self.accepted = accepted; self.duplicates = duplicates; self.rejected = rejected
        self.acks = acks; self.ackCursor = ackCursor; self.hints = hints
    }
}

/// Result of a `GET /v1/health` reachability probe; `detail` is short, human-readable text.
public struct ProbeResult: Equatable, Sendable {
    public var ok: Bool
    public var detail: String
    public var latencyMs: Int64
    public init(ok: Bool, detail: String, latencyMs: Int64) { self.ok = ok; self.detail = detail; self.latencyMs = latencyMs }
}

public struct SyncException: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var description: String { message }
}

/// `POST {base}/v1/sync` (SPEC section 7) over an injectable `HTTPTransport` (real `URLSession` by default).
/// Blocking; call from a worker queue.
///
/// Request: JSON `{"gateway_node": hex, "frames": [standard padded base64], "ack_cursor": n}` with headers
/// `Content-Type: application/json`, `Accept: application/json`, `X-Relay-Key`.
/// Response: JSON `{"accepted","duplicates","rejected","acks":[base64 ACK payloads],"ack_cursor"}` plus an optional
/// `"hints":{"accepted","duplicates","rejected"}` when the upload held LOC_HINT frames (SPEC section 7).
public final class SyncClient: Sendable {
    private let transport: any HTTPTransport
    private let connectTimeoutMs: Int
    private let readTimeoutMs: Int

    /// The two timeouts are summed into one request timeout, because `URLSession` has a single one.
    public init(transport: any HTTPTransport = URLSessionTransport(), connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 10_000) {
        self.transport = transport
        self.connectTimeoutMs = connectTimeoutMs
        self.readTimeoutMs = readTimeoutMs
    }

    /// `base` trimmed, minus trailing slashes, plus [path]; nil unless it is an http(s) URL with a host.
    static func endpoint(_ base: String, _ path: String) -> URL? {
        let b = base.trimmed.trimmingTrailing("/")
        let lower = b.lowercased()
        guard lower.hasPrefix("http://") || lower.hasPrefix("https://"),
              let url = URL(string: b + path), let host = url.host, !host.isEmpty else { return nil }
        return url
    }

    private struct RequestBody: Encodable {
        let gateway_node: String
        let frames: [String]
        let ack_cursor: Int64
    }

    private struct HintsBody: Decodable {
        let accepted: Int?
        let duplicates: Int?
        let rejected: Int?
    }

    private struct ResponseBody: Decodable {
        let accepted: Int?
        let duplicates: Int?
        let rejected: Int?
        let acks: [String]?
        let ack_cursor: Int64
        /// A malformed `hints` value (string, array, null) is ignored, not a failure.
        let hints: HintsBody?

        enum CodingKeys: String, CodingKey { case accepted, duplicates, rejected, acks, ack_cursor, hints }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            accepted = try c.decodeIfPresent(Int.self, forKey: .accepted)
            duplicates = try c.decodeIfPresent(Int.self, forKey: .duplicates)
            rejected = try c.decodeIfPresent(Int.self, forKey: .rejected)
            acks = try c.decodeIfPresent([String].self, forKey: .acks)
            ack_cursor = try c.decode(Int64.self, forKey: .ack_cursor)
            hints = try? c.decodeIfPresent(HintsBody.self, forKey: .hints)
        }
    }

    public func sync(_ cfg: SyncConfig, gatewayNode: [UInt8], frames: [[UInt8]], ackCursor: Int64) throws -> SyncResponse {
        let body: [UInt8]
        do {
            let enc = JSONEncoder()
            enc.outputFormatting = [.sortedKeys]
            body = [UInt8](try enc.encode(RequestBody(
                gateway_node: Hex.encode(gatewayNode),
                frames: frames.map { Data($0).base64EncodedString() }, // standard alphabet, padded, no line wrapping
                ack_cursor: ackCursor)))
        } catch {
            throw SyncException("cannot build request: \(error)")
        }
        guard let url = SyncClient.endpoint(cfg.baseUrl, "/v1/sync") else { throw SyncException("bad backend URL '\(cfg.baseUrl)'") }
        let request = HTTPRequest(
            url: url, method: "POST",
            headers: ["Content-Type": "application/json", "Accept": "application/json", "X-Relay-Key": cfg.deviceKey],
            body: body, timeoutMs: connectTimeoutMs + readTimeoutMs)
        let response: HTTPResponse
        do { response = try transport.send(request) } catch { throw SyncException(HTTPErrorText.describe(error)) }
        guard (200...299).contains(response.status) else {
            let err = String(decoding: response.body, as: UTF8.self).take(200)
            throw SyncException("HTTP \(response.status)\(err.trimmed.isEmpty ? "" : ": \(err)")")
        }
        return try parse(response.body)
    }

    /// `GET {base}/v1/health` with a [timeoutMs] timeout. Blocking; never throws. Not `/healthz`: Cloud Run reserves
    /// that path at its front end.
    public func probe(_ baseUrl: String, timeoutMs: Int = 5_000) -> ProbeResult {
        let started = currentTimeMs()
        func elapsed() -> Int64 { currentTimeMs() - started }
        guard let url = SyncClient.endpoint(baseUrl, "/v1/health") else { return ProbeResult(ok: false, detail: "bad backend URL", latencyMs: 0) }
        do {
            let r = try transport.send(HTTPRequest(url: url, method: "GET", timeoutMs: timeoutMs))
            if (200...299).contains(r.status) { return ProbeResult(ok: true, detail: "reachable (HTTP \(r.status))", latencyMs: elapsed()) }
            return ProbeResult(ok: false, detail: "HTTP \(r.status)", latencyMs: elapsed())
        } catch {
            return ProbeResult(ok: false, detail: SyncHealth.shortError(HTTPErrorText.describe(error)), latencyMs: elapsed())
        }
    }

    /// Parses a `/v1/sync` response body. Undecodable `acks` entries are skipped; a missing `ack_cursor` or
    /// non-JSON body throws `SyncException`.
    public func parse(_ body: [UInt8]) throws -> SyncResponse {
        let r: ResponseBody
        do { r = try JSONDecoder().decode(ResponseBody.self, from: Data(body)) } catch {
            throw SyncException("bad response body: \(error)")
        }
        let acks = (r.acks ?? []).compactMap { Data(base64Encoded: $0).map { [UInt8]($0) } } // skip bad entries
        return SyncResponse(accepted: r.accepted ?? 0, duplicates: r.duplicates ?? 0, rejected: r.rejected ?? 0,
                            acks: acks, ackCursor: r.ack_cursor,
                            hints: r.hints.map { HintCounts(accepted: $0.accepted ?? 0, duplicates: $0.duplicates ?? 0, rejected: $0.rejected ?? 0) })
    }

    /// Convenience for tests and callers holding the body as text.
    public func parse(_ text: String) throws -> SyncResponse { try parse([UInt8](text.utf8)) }
}
