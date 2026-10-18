import XCTest
@testable import RelayKit
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

final class SyncHealthTests: XCTestCase {
    private let url = "http://100.116.13.37:8080"
    private let host = "100.116.13.37:8080"

    func testStartsWithNoAttempt() {
        let h = SyncHealth()
        XCTAssertNil(h.lastOk)
        XCTAssertEqual(.pending, SyncHealthText.line(h, isGateway: true, backendHost: host, nowMs: 1_000).kind)
    }

    func testSuccessThenFailureThenRecovery() {
        var h = SyncHealth().record(true, nil, 10_000, url)
        XCTAssertEqual(true, h.lastOk); XCTAssertEqual(10_000, h.lastOkAtMs); XCTAssertEqual(host, h.host); XCTAssertEqual(0, h.consecutiveFailures)

        h = h.record(false, "SocketTimeoutException: failed to connect to /100.116.13.37 (port 8080)", 20_000, url)
        XCTAssertEqual(false, h.lastOk)
        XCTAssertEqual("timed out", h.error)
        XCTAssertEqual(10_000, h.lastOkAtMs) // last good time is kept
        XCTAssertEqual(1, h.consecutiveFailures)
        XCTAssertEqual(20_000, h.failingSinceMs)

        h = h.record(false, "SocketTimeoutException: x", 30_000, url)
        XCTAssertEqual(2, h.consecutiveFailures)
        XCTAssertEqual(20_000, h.failingSinceMs) // the failure run started at the first failure

        h = h.record(true, nil, 40_000, url)
        XCTAssertEqual(true, h.lastOk); XCTAssertNil(h.error); XCTAssertEqual(0, h.consecutiveFailures); XCTAssertEqual(0, h.failingSinceMs)
        XCTAssertEqual(40_000, h.lastOkAtMs)
    }

    func testFirstEverFailureStartsFailureRun() {
        let h = SyncHealth().record(false, "boom", 5_000, url)
        XCTAssertEqual(5_000, h.failingSinceMs)
        XCTAssertEqual(1, h.consecutiveFailures)
    }

    func testShortErrors() {
        XCTAssertEqual("timed out", SyncHealth.shortError("SocketTimeoutException: failed to connect to /10.0.2.2 (port 8080) from /10.1.1.1 (port 4) after 5000ms"))
        XCTAssertEqual("unknown host", SyncHealth.shortError("UnknownHostException: Unable to resolve host \"x\": No address associated with hostname"))
        XCTAssertEqual("connection refused", SyncHealth.shortError("ConnectException: failed to connect to /1.2.3.4 (port 8080): connect failed: ECONNREFUSED (Connection refused)"))
        XCTAssertEqual("network unreachable", SyncHealth.shortError("ConnectException: connect failed: ENETUNREACH (Network is unreachable)"))
        XCTAssertEqual("cleartext HTTP blocked", SyncHealth.shortError("IOException: Cleartext HTTP traffic to x not permitted"))
        XCTAssertTrue(SyncHealth.shortError("HTTP 401: {\"detail\":\"bad key\"}").contains("401"))
        XCTAssertEqual("HTTP 500: boom", SyncHealth.shortError("HTTP 500: boom"))
        XCTAssertEqual("unknown error", SyncHealth.shortError(nil))
        XCTAssertEqual("unknown error", SyncHealth.shortError(" "))
        XCTAssertEqual(60, SyncHealth.shortError(String(repeating: "x", count: 200)).count)
    }

    func testIosTransportErrorsMapToTheSameShortPhrases() {
        XCTAssertEqual("timed out", SyncHealth.shortError(HTTPErrorText.describe(URLError(.timedOut))))
        XCTAssertEqual("unknown host", SyncHealth.shortError(HTTPErrorText.describe(URLError(.cannotFindHost))))
        XCTAssertEqual("unknown host", SyncHealth.shortError(HTTPErrorText.describe(URLError(.dnsLookupFailed))))
        XCTAssertEqual("connection refused", SyncHealth.shortError(HTTPErrorText.describe(URLError(.cannotConnectToHost))))
        XCTAssertEqual("network unreachable", SyncHealth.shortError(HTTPErrorText.describe(URLError(.notConnectedToInternet))))
        XCTAssertEqual("cleartext HTTP blocked", SyncHealth.shortError(HTTPErrorText.describe(URLError(.appTransportSecurityRequiresSecureConnection))))
        XCTAssertEqual("bad backend URL", SyncHealth.shortError(HTTPErrorText.describe(URLError(.badURL))))
    }

    func testLinesForEachState() {
        let ok = SyncHealth().record(true, nil, 100_000, url)
        let l = SyncHealthText.line(ok, isGateway: true, backendHost: host, nowMs: 112_000)
        XCTAssertEqual(.ok, l.kind)
        XCTAssertEqual("Gateway ✓ synced 12 s ago", l.text)

        let bad = ok.record(false, "SocketTimeoutException", 120_000, url)
        let b = SyncHealthText.line(bad, isGateway: true, backendHost: host, nowMs: 125_000)
        XCTAssertEqual(.failing, b.kind)
        XCTAssertEqual("Gateway can't reach backend (\(host)) — check Settings / Tailscale", b.text)
        XCTAssertEqual("timed out · failing for 5 s", SyncHealthText.failureDetail(bad, nowMs: 125_000))
        XCTAssertNil(SyncHealthText.failureDetail(ok, nowMs: 125_000))

        XCTAssertEqual(.notGateway, SyncHealthText.line(ok, isGateway: false, backendHost: host, nowMs: 112_000).kind)
    }

    func testHealthForADifferentBackendIsIgnored() {
        let bad = SyncHealth().record(false, "SocketTimeoutException", 1_000, "http://10.0.2.2:8080")
        XCTAssertEqual(.pending, SyncHealthText.line(bad, isGateway: true, backendHost: host, nowMs: 2_000).kind)
    }

    func testAgoFormatting() {
        XCTAssertEqual("0 s ago", SyncHealthText.ago(-5))
        XCTAssertEqual("59 s ago", SyncHealthText.ago(59_999))
        XCTAssertEqual("1 min ago", SyncHealthText.ago(60_000))
        XCTAssertEqual("2 h ago", SyncHealthText.ago(2 * 3_600_000 + 5))
    }

    func testProbeAgainstFakeServer() {
        // /v1/health -> 200, /bad/v1/health -> 503, anything else -> connection refused
        let routed = RoutedHTTP { req in
            if req.url.port == 1 { throw URLError(.cannotConnectToHost) }
            switch req.url.path {
            case "/v1/health": return HTTPResponse(status: 200, body: [UInt8]("{\"ok\":true}".utf8))
            case "/bad/v1/health": return HTTPResponse(status: 503)
            default: throw URLError(.cannotConnectToHost)
            }
        }
        let c = SyncClient(transport: routed)
        let ok = c.probe("http://127.0.0.1:9999/", timeoutMs: 2_000)
        XCTAssertTrue(ok.ok, ok.detail)
        XCTAssertEqual("reachable (HTTP 200)", ok.detail)
        let bad = c.probe("http://127.0.0.1:9999/bad", timeoutMs: 2_000)
        XCTAssertFalse(bad.ok); XCTAssertEqual("HTTP 503", bad.detail)
        let closed = c.probe("http://127.0.0.1:1", timeoutMs: 1_000)
        XCTAssertFalse(closed.ok); XCTAssertEqual("connection refused", closed.detail)
        XCTAssertFalse(c.probe("not a url", timeoutMs: 500).ok)
        XCTAssertEqual("bad backend URL", c.probe("ftp://x", timeoutMs: 500).detail)
        // the probe sends a GET with no body
        XCTAssertEqual("GET", routed.last?.method)
    }
}

/// An `HTTPTransport` from a closure.
final class RoutedHTTP: HTTPTransport, @unchecked Sendable {
    private let handler: @Sendable (HTTPRequest) throws -> HTTPResponse
    private let lock = NSLock()
    private var _last: HTTPRequest?
    init(_ handler: @escaping @Sendable (HTTPRequest) throws -> HTTPResponse) { self.handler = handler }
    var last: HTTPRequest? { lock.lock(); defer { lock.unlock() }; return _last }
    func send(_ request: HTTPRequest) throws -> HTTPResponse {
        lock.lock(); _last = request; lock.unlock()
        return try handler(request)
    }
}
