import XCTest
import RelayCore
@testable import RelayKit
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// The real `URLSession` path against a throwaway loopback HTTP server (a raw socket on a background thread).
final class URLSessionTransportTests: XCTestCase {
    private final class MiniServer: @unchecked Sendable {
        let port: UInt16
        private let fd: Int32
        private let lock = NSLock()
        private var _requests: [String] = []
        private var running = true
        var requests: [String] { lock.lock(); defer { lock.unlock() }; return _requests }

        init(respond: @escaping @Sendable (String) -> (Int, String)) throws {
            let s = socket(AF_INET, Int32(SOCK_STREAM.rawValue), 0)
            guard s >= 0 else { throw NSError(domain: "socket", code: Int(errno)) }
            var yes: Int32 = 1
            setsockopt(s, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
            var addr = sockaddr_in()
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_addr = in_addr(s_addr: UInt32(0x7F000001).bigEndian)
            addr.sin_port = 0
            let bound = withUnsafePointer(to: &addr) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(s, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
            }
            guard bound == 0, listen(s, 8) == 0 else { throw NSError(domain: "bind", code: Int(errno)) }
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            withUnsafeMutablePointer(to: &addr) { p in
                p.withMemoryRebound(to: sockaddr.self, capacity: 1) { _ = getsockname(s, $0, &len) }
            }
            fd = s
            port = UInt16(bigEndian: addr.sin_port)
            let t = Thread { [self] in serve(respond) }
            t.start()
        }

        private func serve(_ respond: @Sendable (String) -> (Int, String)) {
            while true {
                lock.lock(); let go = running; lock.unlock()
                if !go { return }
                let c = accept(fd, nil, nil)
                if c < 0 { return }
                var data = [UInt8]()
                var buf = [UInt8](repeating: 0, count: 4096)
                while true {
                    let n = read(c, &buf, buf.count)
                    if n <= 0 { break }
                    data.append(contentsOf: buf[0..<n])
                    let text = String(decoding: data, as: UTF8.self)
                    if let r = text.range(of: "\r\n\r\n") {
                        let head = text[..<r.lowerBound].lowercased()
                        let want = head.components(separatedBy: "content-length:").dropFirst().first.flatMap { Int($0.prefix { $0 != "\r" }.trimmingCharacters(in: .whitespaces)) } ?? 0
                        if data.count - (text.utf8.distance(from: text.startIndex, to: r.upperBound)) >= want { break }
                    }
                }
                let req = String(decoding: data, as: UTF8.self)
                lock.lock(); _requests.append(req); lock.unlock()
                let (status, body) = respond(req)
                let resp = "HTTP/1.1 \(status) X\r\nContent-Type: application/json\r\nContent-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n\(body)"
                _ = resp.withCString { write(c, $0, strlen($0)) }
                close(c)
            }
        }

        func stop() {
            lock.lock(); running = false; lock.unlock()
            shutdown(fd, Int32(SHUT_RDWR)); close(fd)
        }
    }

    func testRealUrlSessionRoundTripAgainstALoopbackServer() throws {
        let server = try MiniServer { req in
            if req.hasPrefix("GET /v1/health") { return (200, #"{"ok":true}"#) }
            return (200, #"{"accepted":1,"duplicates":0,"rejected":0,"acks":["AQID"],"ack_cursor":5}"#)
        }
        defer { server.stop() }
        let client = SyncClient(transport: URLSessionTransport(), connectTimeoutMs: 2_000, readTimeoutMs: 3_000)
        let cfg = SyncConfig(baseUrl: "http://127.0.0.1:\(server.port)/", deviceKey: "dev-relay-key")
        let r = try client.sync(cfg, gatewayNode: TestSupport.id(1), frames: [[1, 2, 3]], ackCursor: 4)
        XCTAssertEqual(SyncResponse(accepted: 1, duplicates: 0, rejected: 0, acks: [[1, 2, 3]], ackCursor: 5), r)

        let raw = try XCTUnwrap(server.requests.first)
        XCTAssertTrue(raw.hasPrefix("POST /v1/sync HTTP/1.1"), raw)
        XCTAssertTrue(raw.lowercased().contains("x-relay-key: dev-relay-key"), raw)
        XCTAssertTrue(raw.lowercased().contains("content-type: application/json"), raw)
        XCTAssertTrue(raw.contains(#""ack_cursor":4"#) && raw.contains(#""frames":["AQID"]"#), raw)
        XCTAssertTrue(raw.contains("\"gateway_node\":\"\(Hex.encode(TestSupport.id(1)))\""), raw)

        let probe = client.probe("http://127.0.0.1:\(server.port)", timeoutMs: 2_000)
        XCTAssertTrue(probe.ok, probe.detail)
    }

    func testRealUrlSessionReportsRefusedConnectionsAndHttpErrors() throws {
        let server = try MiniServer { _ in (500, "boom") }
        let port = server.port
        let client = SyncClient(transport: URLSessionTransport(), connectTimeoutMs: 1_000, readTimeoutMs: 2_000)
        XCTAssertThrowsError(try client.sync(SyncConfig(baseUrl: "http://127.0.0.1:\(port)", deviceKey: "k"), gatewayNode: TestSupport.id(1), frames: [], ackCursor: 0)) {
            XCTAssertEqual("HTTP 500: boom", ($0 as? SyncException)?.message)
        }
        server.stop()
        let down = client.probe("http://127.0.0.1:\(port)", timeoutMs: 1_000)
        XCTAssertFalse(down.ok)
        XCTAssertEqual("connection refused", down.detail)
    }
}
