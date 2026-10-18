import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One HTTP request, in plain values so tests can fake the network.
public struct HTTPRequest: Equatable, Sendable {
    public var url: URL
    public var method: String
    public var headers: [String: String]
    public var body: [UInt8]?
    /// Request timeout in milliseconds (connect and read together; `URLSession` has no separate connect timeout).
    public var timeoutMs: Int

    public init(url: URL, method: String, headers: [String: String] = [:], body: [UInt8]? = nil, timeoutMs: Int) {
        self.url = url; self.method = method; self.headers = headers; self.body = body; self.timeoutMs = timeoutMs
    }
}

public struct HTTPResponse: Equatable, Sendable {
    public var status: Int
    public var body: [UInt8]
    public init(status: Int, body: [UInt8] = []) { self.status = status; self.body = body }
}

/// The injectable HTTP seam under `SyncClient`. `send` blocks until the response arrives (the gateway runs it on a
/// worker queue) and throws on transport failure; any HTTP status, including 4xx/5xx, is a normal `HTTPResponse`.
public protocol HTTPTransport: Sendable {
    func send(_ request: HTTPRequest) throws -> HTTPResponse
}

/// `URLSession`-backed transport. Uses an ephemeral session (no cookies, no cache) and bridges the async callback
/// with a semaphore, so call it from a background queue, never the main thread.
public final class URLSessionTransport: HTTPTransport, @unchecked Sendable {
    private let session: URLSession

    public init(session: URLSession? = nil) {
        if let session { self.session = session } else {
            let cfg = URLSessionConfiguration.ephemeral
            cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
            self.session = URLSession(configuration: cfg)
        }
    }

    private final class Box: @unchecked Sendable {
        var data: Data?
        var response: URLResponse?
        var error: Error?
    }

    public func send(_ request: HTTPRequest) throws -> HTTPResponse {
        var r = URLRequest(url: request.url)
        r.httpMethod = request.method
        r.timeoutInterval = TimeInterval(request.timeoutMs) / 1000
        r.cachePolicy = .reloadIgnoringLocalCacheData
        for (k, v) in request.headers { r.setValue(v, forHTTPHeaderField: k) }
        r.httpBody = request.body.map { Data($0) }
        let box = Box()
        let done = DispatchSemaphore(value: 0)
        let task = session.dataTask(with: r) { data, response, error in
            box.data = data; box.response = response; box.error = error
            done.signal()
        }
        task.resume()
        done.wait()
        if let e = box.error { throw e }
        guard let http = box.response as? HTTPURLResponse else { throw SyncException("no HTTP response") }
        return HTTPResponse(status: http.statusCode, body: [UInt8](box.data ?? Data()))
    }
}

/// Turns a transport error into the short English phrases `SyncHealth.shortError` classifies ("timed out",
/// "unknown host", "connection refused", ...). Android got these for free from Java exception names.
public enum HTTPErrorText {
    public static func describe(_ error: Error) -> String {
        if let e = error as? SyncException { return e.message }
        if let u = error as? URLError {
            switch u.code {
            case .timedOut: return "URLError: timed out"
            case .cannotFindHost, .dnsLookupFailed: return "URLError: Unable to resolve host"
            case .notConnectedToInternet, .internationalRoamingOff, .dataNotAllowed: return "URLError: Network is unreachable"
            case .cannotConnectToHost, .networkConnectionLost: return "URLError: Connection refused"
            case .appTransportSecurityRequiresSecureConnection: return "URLError: Cleartext HTTP traffic not permitted"
            case .badURL, .unsupportedURL: return "URLError: bad backend URL"
            default: return "URLError(\(u.code.rawValue)): \(u.localizedDescription)"
            }
        }
        return "\(type(of: error)): \(error)"
    }
}
