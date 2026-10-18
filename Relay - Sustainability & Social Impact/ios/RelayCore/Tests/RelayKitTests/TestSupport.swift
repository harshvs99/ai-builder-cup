import Foundation
import XCTest
import RelayCore
@testable import RelayKit

enum TestSupport {
    static func id(_ seed: Int) -> [UInt8] { (0..<8).map { UInt8(truncatingIfNeeded: seed + $0) } }

    static func need(_ msgSeed: Int, origin: [UInt8] = id(0x70), item: String = "insulin") -> Need {
        Need(msgId: id(msgSeed), originNode: origin, createdAt: currentTimeMs() / 1000, category: .medical, urgency: 3,
             people: 1, vulnFlags: 1, latE5: 2029612, lonE5: 8582451, locAcc: 2, lang: .en, item: item,
             landmark: "school", summary: "needs \(item)")
    }

    static func ack(_ ackSeed: Int, ref: [UInt8], target: [UInt8], ticket: Int64 = 7, status: AckStatus = .received, text: String = "received") -> Ack {
        Ack(ackId: id(ackSeed), refMsgId: ref, targetNode: target, ticketNo: ticket, status: status, etaMin: 0xFFFF, text: text)
    }

    /// A LOC_HINT for the NEED [refSeed] from relay phone [relay]; originate it on a node whose id is [relay].
    static func hint(_ refSeed: Int, _ relay: [UInt8], acc: Int = 3, rssi: Int = -70) -> LocHint {
        LocHint(refMsgId: id(refSeed), relayNode: relay, latE5: 2029612, lonE5: 8582451, acc: acc, rssiDbm: rssi,
                observedAt: currentTimeMs() / 1000)
    }

    final class Recorder: GossipListener, @unchecked Sendable {
        private let lock = NSLock()
        private var _acksSeen: [Ack] = [], _acksForMe: [Ack] = [], _needs: [Need] = []
        var acksSeen: [Ack] { lock.lock(); defer { lock.unlock() }; return _acksSeen }
        var acksForMe: [Ack] { lock.lock(); defer { lock.unlock() }; return _acksForMe }
        var needs: [Need] { lock.lock(); defer { lock.unlock() }; return _needs }
        func onNeed(_ need: Need, frame: Frame, fromLink: String?) { lock.lock(); _needs.append(need); lock.unlock() }
        func onAckSeen(_ ack: Ack, frame: Frame) { lock.lock(); _acksSeen.append(ack); lock.unlock() }
        func onAckForMe(_ ack: Ack, frame: Frame) { lock.lock(); _acksForMe.append(ack); lock.unlock() }
    }

    struct NoSender: LinkSender { func send(linkId: String, frame: [UInt8]) {} }
    struct NoScheduler: Scheduler { func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void) {} }

    static func node(nodeId: [UInt8] = id(0x10), store: any PacketStore = InMemoryPacketStore(), listener: any GossipListener = Recorder()) -> GossipNode {
        GossipNode(nodeId: nodeId, sender: NoSender(), clock: WallClock(), scheduler: NoScheduler(), store: store,
                   random: SeededRandom(seed: 1), listener: listener)
    }
}

/// A clock a test moves by hand, usable from `@Sendable` closures.
final class ManualClock: @unchecked Sendable {
    private let lock = NSLock()
    private var t: Int64
    init(_ t: Int64 = 0) { self.t = t }
    var now: Int64 { get { lock.lock(); defer { lock.unlock() }; return t } set { lock.lock(); t = newValue; lock.unlock() } }
}

/// A mutable value shared with `@Sendable` closures.
final class Box<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: T
    init(_ v: T) { self.v = v }
    var value: T { get { lock.lock(); defer { lock.unlock() }; return v } set { lock.lock(); v = newValue; lock.unlock() } }
    func mutate(_ f: (inout T) -> Void) { lock.lock(); f(&v); lock.unlock() }
}

class TempDirTestCase: XCTestCase {
    private(set) var tmp: URL!

    override func setUpWithError() throws {
        tmp = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("relaykit-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tmp)
    }

    func file(_ name: String) -> URL { tmp.appendingPathComponent(name) }
}

/// Fake backend: records requests and answers from a closure.
final class FakeHTTP: HTTPTransport, @unchecked Sendable {
    struct Req { let url: URL; let method: String; let headers: [String: String]; let body: [String: Any]? }

    private let lock = NSLock()
    private var _requests: [Req] = []
    private var _status = 200
    private var _responder: ([String: Any]) -> [String: Any] = { _ in
        ["accepted": 0, "duplicates": 0, "rejected": 0, "acks": [String](), "ack_cursor": 0]
    }
    private var _failure: Error?
    private var _rawBody: [UInt8]?

    var requests: [Req] { lock.lock(); defer { lock.unlock() }; return _requests }
    var status: Int { get { lock.lock(); defer { lock.unlock() }; return _status } set { lock.lock(); _status = newValue; lock.unlock() } }
    var failure: Error? { get { lock.lock(); defer { lock.unlock() }; return _failure } set { lock.lock(); _failure = newValue; lock.unlock() } }
    var rawBody: [UInt8]? { get { lock.lock(); defer { lock.unlock() }; return _rawBody } set { lock.lock(); _rawBody = newValue; lock.unlock() } }
    func respond(_ f: @escaping ([String: Any]) -> [String: Any]) { lock.lock(); _responder = f; lock.unlock() }

    func send(_ request: HTTPRequest) throws -> HTTPResponse {
        let parsed = request.body.flatMap { try? JSONSerialization.jsonObject(with: Data($0)) as? [String: Any] }
        lock.lock()
        _requests.append(Req(url: request.url, method: request.method, headers: request.headers, body: parsed))
        let st = _status, responder = _responder, fail = _failure, raw = _rawBody
        lock.unlock()
        if let fail { throw fail }
        if st < 200 || st > 299 { return HTTPResponse(status: st, body: [UInt8]("boom".utf8)) }
        if let raw { return HTTPResponse(status: st, body: raw) }
        let out = responder(parsed ?? [:])
        return HTTPResponse(status: st, body: [UInt8](try JSONSerialization.data(withJSONObject: out)))
    }
}

func waitUntil(_ timeout: TimeInterval = 5, _ cond: () -> Bool) -> Bool {
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
        if cond() { return true }
        Thread.sleep(forTimeInterval: 0.01)
    }
    return cond()
}
