import Foundation

/// `GET {base}/v1/languages` (no key): which languages the backend has switched on. Port of Android `LanguageSync`.
/// Returns the body only when it has the expected shape, so a caller caches a good copy and a failure keeps the old one.
/// Blocking; call from a worker queue.
public final class LanguageClient: Sendable {
    private let transport: any HTTPTransport

    public init(transport: any HTTPTransport = URLSessionTransport()) { self.transport = transport }

    public func fetch(_ baseUrl: String, timeoutMs: Int = 5_000) -> String? {
        guard let url = SyncClient.endpoint(baseUrl, "/v1/languages") else { return nil }
        do {
            let r = try transport.send(HTTPRequest(url: url, method: "GET", headers: ["Accept": "application/json"], timeoutMs: timeoutMs))
            guard (200...299).contains(r.status) else { return nil }
            let body = String(decoding: r.body, as: UTF8.self)
            return LanguageCatalog.parseServer(body) == nil ? nil : body
        } catch {
            return nil
        }
    }
}
