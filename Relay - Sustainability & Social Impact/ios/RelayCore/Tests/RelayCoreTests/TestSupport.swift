import Foundation
import XCTest
@testable import RelayCore

let T0: Int64 = 1_790_000_000_000

/// Repo root = the nearest ancestor of this file that contains `protocol/` (fixtures are shared with Android, never copied).
let repoRoot: URL = {
    var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    while dir.path != "/" {
        if FileManager.default.fileExists(atPath: dir.appendingPathComponent("protocol/vectors.json").path) { return dir }
        dir = dir.deletingLastPathComponent()
    }
    fatalError("could not find the project root (directory containing protocol/) above \(#filePath)")
}()

func loadJSONObject(_ relativePath: String) throws -> Any {
    let data = try Data(contentsOf: repoRoot.appendingPathComponent(relativePath))
    return try JSONSerialization.jsonObject(with: data)
}

func hex(_ b: [UInt8]) -> String { Hex.encode(b) }
func h(_ s: String) -> [UInt8] { try! Hex.decode(s) }

func sampleNeed(
    originNode: [UInt8], msgId: [UInt8] = NodeId.random(), createdAtSec: Int64 = T0 / 1000,
    summary: String = "needs insulin"
) -> Need {
    Need(msgId: msgId, originNode: originNode, createdAt: createdAtSec, category: .medical, urgency: 3, people: 1,
         vulnFlags: 0, latE5: 2029612, lonE5: 8582451, locAcc: 1, lang: .en, item: "insulin", landmark: "school",
         summary: summary)
}

func sampleAck(_ need: Need, ticket: Int64 = 1, status: AckStatus = .received) -> Ack {
    Ack(ackId: NodeId.random(), refMsgId: need.msgId, targetNode: need.originNode, ticketNo: ticket, status: status,
        etaMin: Ack.etaUnknown, text: "received #\(ticket)")
}

func needFrameBytes(_ need: Need, ttl: Int = 7, sender: [UInt8]? = nil, ts: Int64 = T0) -> [UInt8] {
    try! FrameCodec.encode(Frame(type: FrameTypes.need, ttl: ttl, timestampMs: ts, senderId: sender ?? need.originNode,
                                 payload: try! NeedCodec.encode(need)))
}

func encodeFrame(_ f: Frame) -> [UInt8] { try! FrameCodec.encode(f) }
func encodeNeed(_ n: Need) -> [UInt8] { try! NeedCodec.encode(n) }
func encodeAck(_ a: Ack) -> [UInt8] { try! AckCodec.encode(a) }
func encodeHello(_ x: Hello) -> [UInt8] { try! HelloCodec.encode(x) }
func encodeInventory(_ x: Inventory) -> [UInt8] { try! InventoryCodec.encode(x) }

/// A binary min-heap of virtual-time events.
private struct EventHeap {
    struct Ev { let t: Int64; let seq: Int64; let task: () -> Void }
    private var a: [Ev] = []
    var isEmpty: Bool { a.isEmpty }
    var peekTime: Int64? { a.first?.t }
    private func less(_ x: Ev, _ y: Ev) -> Bool { x.t != y.t ? x.t < y.t : x.seq < y.seq }
    mutating func push(_ e: Ev) {
        a.append(e)
        var i = a.count - 1
        while i > 0 {
            let p = (i - 1) / 2
            if less(a[i], a[p]) { a.swapAt(i, p); i = p } else { break }
        }
    }
    mutating func pop() -> Ev {
        let top = a[0]
        let last = a.removeLast()
        if !a.isEmpty {
            a[0] = last
            var i = 0
            while true {
                let l = 2 * i + 1, r = l + 1
                var m = i
                if l < a.count && less(a[l], a[m]) { m = l }
                if r < a.count && less(a[r], a[m]) { m = r }
                if m == i { break }
                a.swapAt(i, m); i = m
            }
        }
        return top
    }
}

/// Discrete-event virtual-time network of GossipNodes (latency per link, optional loss).
final class TestNet {
    private var queue = EventHeap()
    private var seq: Int64 = 0
    private(set) var now: Int64 = T0
    private var rnd: SeededRandom
    var nodes: [String: N] = [:]
    private var nodeOrder: [String] = []
    private var links: [String: Int64] = [:] // "from|to" -> latency
    var lossProbability = 0.0

    init(seed: UInt64 = 1) { rnd = SeededRandom(seed: seed) }

    private struct NetClock: Clock {
        unowned let net: TestNet
        func nowMs() -> Int64 { net.now }
    }
    private struct NetScheduler: Scheduler {
        unowned let net: TestNet
        func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void) { net.at(net.now + delayMs, task) }
    }

    func at(_ t: Int64, _ task: @escaping () -> Void) {
        queue.push(.init(t: t, seq: seq, task: task)); seq += 1
    }

    final class N: LinkSender, GossipListener {
        let name: String
        unowned let net: TestNet
        let id: [UInt8]
        var needsDelivered: [Need] = []
        var acksForMe: [Ack] = []
        var acksSeen: [Ack] = []
        var locHints: [LocHint] = []
        var broadcasts: [Broadcast] = []
        var announcements: [Announcement] = []
        var sentFrames: [(String, [UInt8])] = []
        var onNeedHook: (Need) -> Void = { _ in }
        var node: GossipNode!

        init(_ name: String, net: TestNet) {
            self.name = name; self.net = net
            id = NodeId.random(using: &net.rnd)
            let nodeRng = SeededRandom(seed: net.rnd.next())
            node = GossipNode(nodeId: id, sender: self, clock: NetClock(net: net), scheduler: NetScheduler(net: net),
                              random: nodeRng, listener: self)
        }

        func send(linkId: String, frame: [UInt8]) {
            sentFrames.append((linkId, frame))
            guard let peer = net.nodes[linkId] else { return }
            guard let lat = net.links["\(name)|\(linkId)"] else { return }
            if net.lossProbability > 0 && Double.random(in: 0..<1, using: &net.rnd) < net.lossProbability { return }
            let from = name
            net.at(net.now + lat) {
                if self.net.links["\(linkId)|\(from)"] != nil { peer.node.onFrame(from, frame) }
            }
        }

        func onNeed(_ need: Need, frame: Frame, fromLink: String?) {
            needsDelivered.append(need)
            onNeedHook(need)
        }
        func onAckForMe(_ ack: Ack, frame: Frame) { acksForMe.append(ack) }
        func onAckSeen(_ ack: Ack, frame: Frame) { acksSeen.append(ack) }
        func onLocHint(_ hint: LocHint, frame: Frame, fromLink: String?) { locHints.append(hint) }
        func onBroadcast(_ broadcast: Broadcast, frame: Frame, fromLink: String?) { broadcasts.append(broadcast) }
        func onAnnouncement(_ announcement: Announcement, frame: Frame, fromLink: String?) { announcements.append(announcement) }
    }

    func node(_ name: String) -> N {
        if let n = nodes[name] { return n }
        let n = N(name, net: self)
        nodes[name] = n
        nodeOrder.append(name)
        return n
    }

    func connect(_ a: String, _ b: String, latencyMs: Int64 = 30) {
        let na = node(a), nb = node(b)
        links["\(a)|\(b)"] = latencyMs; links["\(b)|\(a)"] = latencyMs
        na.node.linkUp(b); nb.node.linkUp(a)
    }

    func disconnect(_ a: String, _ b: String) {
        links.removeValue(forKey: "\(a)|\(b)"); links.removeValue(forKey: "\(b)|\(a)")
        nodes[a]?.node.linkDown(b); nodes[b]?.node.linkDown(a)
    }

    func chain(_ names: [String]) { for i in 0..<(names.count - 1) { connect(names[i], names[i + 1]) } }

    func runUntilIdle(maxEvents: Int = 2_000_000) {
        var n = 0
        while !queue.isEmpty {
            let e = queue.pop()
            now = max(now, e.t)
            e.task()
            n += 1
            if n > maxEvents { fatalError("event storm") }
        }
    }

    func runFor(_ ms: Int64) {
        let end = now + ms
        while let t = queue.peekTime, t <= end {
            let e = queue.pop(); now = max(now, e.t); e.task()
        }
        now = end
    }

    func resyncRound() {
        for name in nodeOrder { nodes[name]!.node.resync() }
        runUntilIdle()
    }
}

/// Single-node harness with a manual scheduler, for protocol-rule unit tests.
final class SoloHarness {
    let id: [UInt8]
    var now: Int64
    var sent: [(String, [UInt8])] = []
    var tasks: [(Int64, @Sendable () -> Void)] = []
    var needs: [(Need, Frame)] = []
    var acksForMe: [Ack] = []
    var acksSeen: [Ack] = []
    var hints: [LocHint] = []
    var logs: [String] = []
    var node: GossipNode!

    private final class Impl: LinkSender, Clock, Scheduler, GossipListener {
        unowned let h: SoloHarness
        init(_ h: SoloHarness) { self.h = h }
        func send(linkId: String, frame: [UInt8]) { h.sent.append((linkId, frame)) }
        func nowMs() -> Int64 { h.now }
        func schedule(delayMs: Int64, task: @escaping @Sendable () -> Void) { h.tasks.append((delayMs, task)) }
        func onNeed(_ need: Need, frame: Frame, fromLink: String?) { h.needs.append((need, frame)) }
        func onAckForMe(_ ack: Ack, frame: Frame) { h.acksForMe.append(ack) }
        func onAckSeen(_ ack: Ack, frame: Frame) { h.acksSeen.append(ack) }
        func onLocHint(_ hint: LocHint, frame: Frame, fromLink: String?) { h.hints.append(hint) }
        func onLog(_ msg: String) { h.logs.append(msg) }
    }
    private var impl: Impl!

    init(nowMs: Int64 = T0) {
        now = nowMs
        id = NodeId.random()
        impl = Impl(self)
        node = GossipNode(nodeId: id, sender: impl, clock: impl, scheduler: impl, random: SeededRandom(seed: 7), listener: impl)
    }

    func runTasks() {
        while !tasks.isEmpty {
            let t = tasks
            tasks.removeAll()
            t.forEach { $0.1() }
        }
    }

    func sentTo(_ link: String, _ type: Int? = nil) -> [Frame] {
        sent.filter { $0.0 == link }.compactMap { FrameCodec.decode($0.1) }.filter { type == nil || $0.type == type }
    }

    func clearSent() { sent.removeAll() }
}
