import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Where the pack is asked for: a position (the backend picks the nearest district) or a district id.
public enum PackQuery: Equatable, Sendable {
    case at(lat: Double, lon: Double)
    case district(String)
}

/// A transport or HTTP problem. `status` is the HTTP status, or 0 for a transport failure.
public struct ReadyApiError: Error, Equatable, Sendable, CustomStringConvertible {
    public let message: String
    public let status: Int
    public init(_ message: String, status: Int = 0) { self.message = message; self.status = status }
    public var description: String { message }
}

/// The three phone calls of the Ready contract. Bodies are returned as the backend sent them so the store keeps unknown fields.
public protocol ReadyApi: Sendable {
    /// `GET /v1/ready/pack` (device key).
    func pack(_ query: PackQuery, lang: String) throws -> String
    /// `POST /v1/ready/plan` (device key).
    func plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household) throws -> String
    /// `GET {audio_url}` (device key) → the WAV, or nil when the backend has none (404) or failed to make it (503): use on-device speech.
    func audio(_ audioUrl: String) throws -> [UInt8]?
    /// `GET /v1/public/shelters?district=` (no key) → the body, or nil when nothing is published yet (404).
    func publicShelters(_ district: String) throws -> String?
}

extension ReadyApi {
    public func publicShelters(_ district: String) throws -> String? { nil }
}

/// `ReadyApi` over the injectable `HTTPTransport` (real `URLSession` by default). Blocking; call from a worker queue.
public final class HttpReadyApi: ReadyApi {
    private let baseUrl: String
    private let deviceKey: String
    private let transport: any HTTPTransport
    private let connectTimeoutMs: Int
    private let readTimeoutMs: Int

    public init(baseUrl: String, deviceKey: String, transport: any HTTPTransport = URLSessionTransport(),
                connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 15_000) {
        self.baseUrl = baseUrl; self.deviceKey = deviceKey; self.transport = transport
        self.connectTimeoutMs = connectTimeoutMs; self.readTimeoutMs = readTimeoutMs
    }

    private static func encode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")) ?? s
    }

    public func pack(_ query: PackQuery, lang: String) throws -> String {
        let whereClause: String
        switch query {
        case .at(let lat, let lon): whereClause = "lat=\(lat)&lon=\(lon)"
        case .district(let id): whereClause = "district=" + Self.encode(id)
        }
        return String(decoding: try request("GET", "/v1/ready/pack?\(whereClause)&lang=" + Self.encode(lang), nil).body, as: UTF8.self)
    }

    public func plan(lang: String, lat: Double?, lon: Double?, district: String?, household: Household) throws -> String {
        var body: [String: Any] = ["lang": lang, "household": household.jsonObject()]
        if let lat, let lon { body["lat"] = lat; body["lon"] = lon } else if let district { body["district"] = district }
        let data = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        return String(decoding: try request("POST", "/v1/ready/plan", [UInt8](data)).body, as: UTF8.self)
    }

    public func audio(_ audioUrl: String) throws -> [UInt8]? {
        let r = try request("GET", audioUrl.hasPrefix("/") ? audioUrl : "/" + audioUrl, nil, okStatuses: [404, 503])
        return (r.status == 404 || r.status == 503) ? nil : r.body
    }

    public func publicShelters(_ district: String) throws -> String? {
        let r = try request("GET", "/v1/public/shelters?district=" + Self.encode(district), nil, okStatuses: [404], sendKey: false)
        return r.status == 404 ? nil : String(decoding: r.body, as: UTF8.self)
    }

    private func request(_ method: String, _ path: String, _ body: [UInt8]?, okStatuses: Set<Int> = [], sendKey: Bool = true) throws -> HTTPResponse {
        guard let url = SyncClient.endpoint(baseUrl, path) else { throw ReadyApiError("bad backend URL '\(baseUrl)'") }
        var headers = ["Accept": path.contains("/audio") ? "audio/wav" : "application/json"]
        if sendKey { headers["X-Relay-Key"] = deviceKey }
        if body != nil { headers["Content-Type"] = "application/json" }
        let response: HTTPResponse
        do {
            response = try transport.send(HTTPRequest(url: url, method: method, headers: headers, body: body, timeoutMs: connectTimeoutMs + readTimeoutMs))
        } catch {
            throw ReadyApiError(HTTPErrorText.describe(error))
        }
        if !(200...299).contains(response.status) && !okStatuses.contains(response.status) {
            let text = String(decoding: response.body.prefix(120), as: UTF8.self)
            throw ReadyApiError("HTTP \(response.status)\(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : ": \(text)")", status: response.status)
        }
        return response
    }
}
