import Foundation
import RelayCore

/// One NEED this phone originated, with how far it has got.
///
/// Persisted as JSON with the same field names as Android's `my_needs.json` (`id`, `at`, `cat`, `urg`, ...), so a
/// file written by either app loads in the other. Files written before the redesign lack the newer fields and load
/// with defaults.
public struct MyNeed: Equatable, Hashable, Sendable, Codable {
    public var msgIdHex: String
    public var createdAtMs: Int64
    public var category: Category
    public var urgency: Int
    public var people: Int
    public var item: String
    public var landmark: String
    public var summary: String
    public var lang: Lang
    /// NeedCodec payload, base64, so a never-originated NEED can be re-sent after a restart.
    public var needPayloadB64: String
    /// At least one frame of this NEED has been handed to a link.
    public var sentToMesh: Bool
    /// Highest `AckStatus` code received so far, -1 if none.
    public var ackStatus: Int
    public var ticketNo: Int64
    public var ackText: String
    public var etaMin: Int
    /// Text of the RECEIVED ack, kept even after a later DISPATCHED ack replaces `ackText`.
    public var receivedText: String
    /// `VulnFlag` bitmask the NEED was sent with.
    public var vulnFlags: Int
    /// Intake answers, QuestionBank question id -> answer value (the NEED's answer bytes).
    public var answers: [Int: Int]
    // Per-step timestamps (epoch ms), 0 = not reached yet. Each is set the first time the step is reached.
    public var savedAtMs: Int64
    public var sentToMeshAtMs: Int64
    public var uploadedAtMs: Int64
    public var receivedAtMs: Int64
    public var dispatchedAtMs: Int64
    public var onTheWayAtMs: Int64
    public var resolvedAtMs: Int64
    /// Absolute expected arrival (epoch ms) from the latest ACK that carried an ETA; 0 = unknown.
    public var etaAtMs: Int64
    /// The user stopped sharing this request from this phone (no cancel frame exists on the wire).
    public var cancelled: Bool
    public var cancelledAtMs: Int64

    public init(
        msgIdHex: String, createdAtMs: Int64, category: Category, urgency: Int, people: Int, item: String,
        landmark: String, summary: String, lang: Lang, needPayloadB64: String,
        sentToMesh: Bool = false, ackStatus: Int = -1, ticketNo: Int64 = -1, ackText: String = "",
        etaMin: Int = 0xFFFF, receivedText: String = "", vulnFlags: Int = 0, answers: [Int: Int] = [:],
        savedAtMs: Int64? = nil, sentToMeshAtMs: Int64 = 0, uploadedAtMs: Int64 = 0, receivedAtMs: Int64 = 0,
        dispatchedAtMs: Int64 = 0, onTheWayAtMs: Int64 = 0, resolvedAtMs: Int64 = 0, etaAtMs: Int64 = 0,
        cancelled: Bool = false, cancelledAtMs: Int64 = 0
    ) {
        self.msgIdHex = msgIdHex; self.createdAtMs = createdAtMs; self.category = category; self.urgency = urgency
        self.people = people; self.item = item; self.landmark = landmark; self.summary = summary; self.lang = lang
        self.needPayloadB64 = needPayloadB64; self.sentToMesh = sentToMesh; self.ackStatus = ackStatus
        self.ticketNo = ticketNo; self.ackText = ackText; self.etaMin = etaMin; self.receivedText = receivedText
        self.vulnFlags = vulnFlags; self.answers = answers; self.savedAtMs = savedAtMs ?? createdAtMs
        self.sentToMeshAtMs = sentToMeshAtMs; self.uploadedAtMs = uploadedAtMs; self.receivedAtMs = receivedAtMs
        self.dispatchedAtMs = dispatchedAtMs; self.onTheWayAtMs = onTheWayAtMs; self.resolvedAtMs = resolvedAtMs
        self.etaAtMs = etaAtMs; self.cancelled = cancelled; self.cancelledAtMs = cancelledAtMs
    }

    public enum Stage: Int, CaseIterable, Sendable, Hashable { case queued, sent, received, dispatched, onTheWay, resolved }

    /// Closed = resolved or cancelled.
    public var isClosed: Bool { cancelled || ackStatus >= AckStatus.resolved.code }

    public var stage: Stage {
        if ackStatus >= AckStatus.resolved.code { return .resolved }
        if ackStatus == AckStatus.onTheWay.code { return .onTheWay }
        if ackStatus >= AckStatus.dispatched.code { return .dispatched }
        if ackStatus == AckStatus.received.code { return .received }
        if sentToMesh { return .sent }
        return .queued
    }

    /// Applies an ACK. A late lower-status ACK never moves the need back; each step timestamp is set only the first time.
    public func withAck(_ status: AckStatus, ticket: Int64, text: String, eta: Int, nowMs: Int64 = currentTimeMs()) -> MyNeed {
        let higher = status.code >= ackStatus
        func first(_ cur: Int64, _ reached: Bool) -> Int64 { reached && cur == 0 ? nowMs : cur }
        var n = self
        n.receivedAtMs = first(receivedAtMs, status == .received)
        n.dispatchedAtMs = first(dispatchedAtMs, status == .dispatched)
        n.onTheWayAtMs = first(onTheWayAtMs, status == .onTheWay)
        n.resolvedAtMs = first(resolvedAtMs, status == .resolved)
        n.etaAtMs = higher && eta != 0xFFFF ? nowMs + Int64(eta) * 60_000 : etaAtMs
        n.ackStatus = max(ackStatus, status.code)
        n.ticketNo = ticket
        n.ackText = higher ? text : ackText
        n.etaMin = higher ? eta : etaMin
        n.receivedText = status == .received ? text : receivedText
        return n
    }

    /// First NEED frame handed to a link; keeps an earlier timestamp.
    public func markSentToMesh(nowMs: Int64 = currentTimeMs()) -> MyNeed {
        if sentToMesh && sentToMeshAtMs != 0 { return self }
        var n = self
        n.sentToMesh = true
        n.sentToMeshAtMs = sentToMeshAtMs == 0 ? nowMs : sentToMeshAtMs
        return n
    }

    /// A backend sync accepted this NEED (this phone was the gateway); keeps an earlier timestamp.
    public func markUploaded(nowMs: Int64 = currentTimeMs()) -> MyNeed {
        if uploadedAtMs != 0 { return self }
        var n = self
        n.uploadedAtMs = nowMs
        return n
    }

    public func cancel(nowMs: Int64 = currentTimeMs()) -> MyNeed {
        if cancelled { return self }
        var n = self
        n.cancelled = true
        n.cancelledAtMs = nowMs
        return n
    }

    // ------------------------------------------------------------------ JSON (Android-compatible field names)

    private enum CodingKeys: String, CodingKey {
        case msgIdHex = "id", createdAtMs = "at", category = "cat", urgency = "urg", people = "ppl", item,
             landmark = "lm", summary = "sum", lang, needPayloadB64 = "need", sentToMesh = "sent", ackStatus = "ack",
             ticketNo = "ticket", ackText = "atext", etaMin = "eta", receivedText = "rtext", vulnFlags = "vuln", answers,
             savedAtMs = "t_saved", sentToMeshAtMs = "t_sent", uploadedAtMs = "t_up", receivedAtMs = "t_recv",
             dispatchedAtMs = "t_disp", onTheWayAtMs = "t_otw", resolvedAtMs = "t_done", etaAtMs = "eta_at",
             cancelled, cancelledAtMs = "t_cancel"
    }

    /// Required: `id`, `at`, `cat`, `urg`, `ppl`. Everything else defaults when absent, like Android's `opt*` reads.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let created = try c.decode(Int64.self, forKey: .createdAtMs)
        msgIdHex = try c.decode(String.self, forKey: .msgIdHex)
        createdAtMs = created
        category = Category.fromCode(try c.decode(Int.self, forKey: .category)) ?? .other
        urgency = try c.decode(Int.self, forKey: .urgency)
        people = try c.decode(Int.self, forKey: .people)
        item = try c.decodeIfPresent(String.self, forKey: .item) ?? ""
        landmark = try c.decodeIfPresent(String.self, forKey: .landmark) ?? ""
        summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
        lang = Lang.fromCode(try c.decodeIfPresent(Int.self, forKey: .lang) ?? 0) ?? .en
        needPayloadB64 = try c.decodeIfPresent(String.self, forKey: .needPayloadB64) ?? ""
        sentToMesh = try c.decodeIfPresent(Bool.self, forKey: .sentToMesh) ?? false
        ackStatus = try c.decodeIfPresent(Int.self, forKey: .ackStatus) ?? -1
        ticketNo = try c.decodeIfPresent(Int64.self, forKey: .ticketNo) ?? -1
        ackText = try c.decodeIfPresent(String.self, forKey: .ackText) ?? ""
        etaMin = try c.decodeIfPresent(Int.self, forKey: .etaMin) ?? 0xFFFF
        receivedText = try c.decodeIfPresent(String.self, forKey: .receivedText) ?? ""
        // Files written before the redesign lack everything below: defaults apply.
        vulnFlags = try c.decodeIfPresent(Int.self, forKey: .vulnFlags) ?? 0
        let raw = try c.decodeIfPresent([String: Int].self, forKey: .answers) ?? [:]
        var parsed: [Int: Int] = [:]
        for (k, v) in raw { if let q = Int(k) { parsed[q] = v } }
        answers = parsed
        savedAtMs = try c.decodeIfPresent(Int64.self, forKey: .savedAtMs) ?? created
        sentToMeshAtMs = try c.decodeIfPresent(Int64.self, forKey: .sentToMeshAtMs) ?? 0
        uploadedAtMs = try c.decodeIfPresent(Int64.self, forKey: .uploadedAtMs) ?? 0
        receivedAtMs = try c.decodeIfPresent(Int64.self, forKey: .receivedAtMs) ?? 0
        dispatchedAtMs = try c.decodeIfPresent(Int64.self, forKey: .dispatchedAtMs) ?? 0
        onTheWayAtMs = try c.decodeIfPresent(Int64.self, forKey: .onTheWayAtMs) ?? 0
        resolvedAtMs = try c.decodeIfPresent(Int64.self, forKey: .resolvedAtMs) ?? 0
        etaAtMs = try c.decodeIfPresent(Int64.self, forKey: .etaAtMs) ?? 0
        cancelled = try c.decodeIfPresent(Bool.self, forKey: .cancelled) ?? false
        cancelledAtMs = try c.decodeIfPresent(Int64.self, forKey: .cancelledAtMs) ?? 0
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(msgIdHex, forKey: .msgIdHex)
        try c.encode(createdAtMs, forKey: .createdAtMs)
        try c.encode(category.code, forKey: .category)
        try c.encode(urgency, forKey: .urgency)
        try c.encode(people, forKey: .people)
        try c.encode(item, forKey: .item)
        try c.encode(landmark, forKey: .landmark)
        try c.encode(summary, forKey: .summary)
        try c.encode(lang.code, forKey: .lang)
        try c.encode(needPayloadB64, forKey: .needPayloadB64)
        try c.encode(sentToMesh, forKey: .sentToMesh)
        try c.encode(ackStatus, forKey: .ackStatus)
        try c.encode(ticketNo, forKey: .ticketNo)
        try c.encode(ackText, forKey: .ackText)
        try c.encode(etaMin, forKey: .etaMin)
        try c.encode(receivedText, forKey: .receivedText)
        try c.encode(vulnFlags, forKey: .vulnFlags)
        var a: [String: Int] = [:]
        for (q, v) in answers { a[String(q)] = v }
        try c.encode(a, forKey: .answers)
        try c.encode(savedAtMs, forKey: .savedAtMs)
        try c.encode(sentToMeshAtMs, forKey: .sentToMeshAtMs)
        try c.encode(uploadedAtMs, forKey: .uploadedAtMs)
        try c.encode(receivedAtMs, forKey: .receivedAtMs)
        try c.encode(dispatchedAtMs, forKey: .dispatchedAtMs)
        try c.encode(onTheWayAtMs, forKey: .onTheWayAtMs)
        try c.encode(resolvedAtMs, forKey: .resolvedAtMs)
        try c.encode(etaAtMs, forKey: .etaAtMs)
        try c.encode(cancelled, forKey: .cancelled)
        try c.encode(cancelledAtMs, forKey: .cancelledAtMs)
    }

    /// One entry as Android's `toJson()` writes it.
    public func toJson() throws -> Data {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return try e.encode(self)
    }

    /// Parses one entry; nil when a required field is missing or malformed (Android's `fromJson`).
    public static func fromJson(_ data: Data) -> MyNeed? { try? JSONDecoder().decode(MyNeed.self, from: data) }
}

/// Persisted "my needs" list (newest first). Whole-file JSON array, atomic rewrite; a corrupt file loads as empty
/// and an entry that fails to parse is skipped.
public final class MyNeedsStore: @unchecked Sendable {
    private let file: URL
    private let lock = NSLock()
    private var items: [MyNeed] = []

    public init(file: URL) {
        self.file = file
        items = MyNeedsStore.load(file)
    }

    public func list() -> [MyNeed] { lock.lock(); defer { lock.unlock() }; return items }

    /// Adds [need] at the front; a need with the same id is ignored.
    public func add(_ need: MyNeed) {
        lock.lock(); defer { lock.unlock() }
        if items.contains(where: { $0.msgIdHex == need.msgIdHex }) { return }
        items.insert(need, at: 0)
        save()
    }

    /// Applies [f] to the need with this id; returns the updated need, or nil if unknown.
    @discardableResult
    public func update(_ msgIdHex: String, _ f: (MyNeed) -> MyNeed) -> MyNeed? {
        lock.lock(); defer { lock.unlock() }
        guard let i = items.firstIndex(where: { $0.msgIdHex == msgIdHex }) else { return nil }
        let cur = items[i]
        let next = f(cur)
        if next == cur { return cur }
        items[i] = next
        save()
        return next
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        items = []
        save()
    }

    private func save() {
        do {
            let e = JSONEncoder()
            e.outputFormatting = [.sortedKeys]
            try AtomicFiles.write(file, [UInt8](try e.encode(items)))
        } catch {
            // keep the in-memory list; next mutation retries
        }
    }

    private static func load(_ file: URL) -> [MyNeed] {
        guard let bytes = AtomicFiles.readOrNull(file) else { return [] }
        guard let boxes = try? JSONDecoder().decode([Lossy<MyNeed>].self, from: Data(bytes)) else { return [] }
        return boxes.compactMap { $0.value }
    }

    private struct Lossy<T: Decodable>: Decodable {
        let value: T?
        init(from decoder: Decoder) throws { value = try? T(from: decoder) }
    }
}
