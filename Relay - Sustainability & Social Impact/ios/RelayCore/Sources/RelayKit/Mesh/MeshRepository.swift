import Foundation
import RelayCore

/// Hub between the running `MeshEngine` (writer) and the UI (reader). Owns the persistent stores so they exist
/// whether or not the engine is currently running. Android's `MeshRepository` is a process-wide singleton; this one is
/// a plain object the app creates once (and keeps) with its Application Support directory.
///
/// Observing: `snapshot` / `snapshots()` carry the status and "my needs"; `logLines` / `logUpdates()` the diagnostics
/// log. All are `Broadcaster`-backed (current value first, then changes), so a SwiftUI `@Observable` wrapper just loops
/// `for await` over them.
public final class MeshRepository: @unchecked Sendable {
    public static let maxLog = 100

    /// Files created in the data directory (same names as Android's `filesDir`).
    public enum FileName {
        public static let packets = "packets.bin"
        public static let myNeeds = "my_needs.json"
        public static let gatewayState = "gateway_state.json"
        /// This node's identity: 16 hex characters. Android keeps it in SharedPreferences; here it is a file.
        public static let nodeId = "node_id.txt"
    }

    public let settings: AppSettings
    public let packetStore: FilePacketStore
    public let myNeedsStore: MyNeedsStore
    public let gatewayState: GatewayState
    public let nodeId: [UInt8]
    public var nodeIdHex: String { Hex.encode(nodeId) }

    private let flow: Broadcaster<MeshSnapshot>
    private let logFlow = Broadcaster<[String]>([])
    private let lock = NSLock()
    private var logBuffer: [String] = []
    private var outbox: [Need] = []
    private weak var engineRef: MeshEngine?
    private let logSink: (@Sendable (String) -> Void)?
    private let timeFormatter: DateFormatter

    /// - Parameters:
    ///   - dataDirectory: where the stores live (the iOS app passes Application Support); created if missing.
    ///   - settings: the persisted settings.
    ///   - logSink: also receives each log line (e.g. forward to `os.Logger`).
    ///   - packetFlushDelayMs: coalescing delay for packet-store writes (0 = write on every change).
    public init(dataDirectory: URL, settings: AppSettings, logSink: (@Sendable (String) -> Void)? = nil, packetFlushDelayMs: Int64 = 300) {
        try? FileManager.default.createDirectory(at: dataDirectory, withIntermediateDirectories: true)
        self.settings = settings
        self.logSink = logSink
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        timeFormatter = f
        let id = MeshRepository.loadOrCreateNodeId(dataDirectory.appendingPathComponent(FileName.nodeId))
        nodeId = id
        let sink = logSink
        packetStore = FilePacketStore(file: dataDirectory.appendingPathComponent(FileName.packets), flushDelayMs: packetFlushDelayMs,
                                      log: { sink?("store: " + $0) })
        myNeedsStore = MyNeedsStore(file: dataDirectory.appendingPathComponent(FileName.myNeeds))
        gatewayState = GatewayState(file: dataDirectory.appendingPathComponent(FileName.gatewayState))
        flow = Broadcaster(MeshSnapshot(myNeeds: myNeedsStore.list()))
        log("init: node id \(Hex.encode(id)), \(packetStore.size()) stored packet(s), \(myNeedsStore.list().count) own need(s)")
    }

    private static func loadOrCreateNodeId(_ file: URL) -> [UInt8] {
        if let bytes = AtomicFiles.readOrNull(file),
           let existing = Hex.decodeOrNull(String(decoding: bytes, as: UTF8.self).trimmed), existing.count == NodeId.size {
            return existing
        }
        let fresh = NodeId.random()
        // Written (and fsynced) before the id is ever put on the air.
        try? AtomicFiles.write(file, [UInt8](Hex.encode(fresh).utf8))
        return fresh
    }

    // ---------------------------------------------------------------- observation

    public var snapshot: MeshSnapshot { flow.value }
    public var status: MeshStatus { flow.value.status }
    public var myNeeds: [MyNeed] { flow.value.myNeeds }
    public var logLines: [String] { logFlow.value }

    /// Current snapshot now, then every change.
    public func snapshots() -> AsyncStream<MeshSnapshot> { flow.updates() }
    public func logUpdates() -> AsyncStream<[String]> { logFlow.updates() }

    /// The running engine (held weakly: the app owns it). Set by `MeshEngine.start()`, cleared by `stop()`.
    public var engine: MeshEngine? {
        get { lock.lock(); defer { lock.unlock() }; return engineRef }
        set { lock.lock(); engineRef = newValue; lock.unlock() }
    }

    public func log(_ msg: String) {
        logSink?(msg)
        lock.lock()
        logBuffer.append(timeFormatter.string(from: Date()) + " " + msg)
        if logBuffer.count > MeshRepository.maxLog { logBuffer.removeFirst(logBuffer.count - MeshRepository.maxLog) }
        let lines = logBuffer
        lock.unlock()
        logFlow.send(lines)
    }

    public func publishStatus(_ s: MeshStatus) { flow.modify { $0.status = s } }

    public func refreshMyNeeds() {
        let list = myNeedsStore.list()
        flow.modify { $0.myNeeds = list }
    }

    // ---------------------------------------------------------------- need submission

    /// Record [need] under "my needs" and push it into the mesh (now, or when the engine starts).
    public func submitNeed(_ need: Need) throws {
        let mine = MyNeed(
            msgIdHex: need.msgIdHex,
            createdAtMs: need.createdAt * 1000,
            category: need.category,
            urgency: need.urgency,
            people: need.people,
            item: need.item,
            landmark: need.landmark,
            summary: need.summary,
            lang: need.lang,
            needPayloadB64: Data(try NeedCodec.encode(need)).base64EncodedString(),
            vulnFlags: need.vulnFlags,
            answers: Dictionary(need.answers.map { ($0.questionId, $0.value) }, uniquingKeysWith: { _, b in b }),
            sentToMeshAtMs: 0
        )
        myNeedsStore.add(mine)
        refreshMyNeeds()
        if let e = engine {
            e.originate(need)
        } else {
            lock.lock(); outbox.append(need); lock.unlock()
            log("engine not running; NEED \(need.msgIdHex) queued until it starts")
        }
    }

    /// The user stopped sharing request [msgIdHex]: mark it cancelled and stop relaying its NEED from this phone
    /// (drop it from the packet store and the not-yet-started outbox). There is no cancel frame on the wire, so
    /// neighbours and the control room may still have it. Returns false for an unknown id.
    @discardableResult
    public func cancel(_ msgIdHex: String) -> Bool {
        guard let updated = myNeedsStore.update(msgIdHex, { $0.cancel() }) else { return false }
        lock.lock(); outbox.removeAll { $0.msgIdHex == msgIdHex }; lock.unlock()
        packetStore.remove(msgIdHex)
        packetStore.flush()
        refreshMyNeeds()
        log("cancelled own NEED \(msgIdHex) (stage=\(updated.stage)); no longer relayed from this phone")
        return true
    }

    /// Removes a request from this phone's list. An open request is cancelled first so it stops being relayed.
    public func deleteMyNeed(_ msgIdHex: String) {
        guard let need = myNeedsStore.list().first(where: { $0.msgIdHex == msgIdHex }) else { return }
        if !need.isClosed { cancel(msgIdHex) }
        myNeedsStore.remove(msgIdHex)
        refreshMyNeeds()
        log("deleted own NEED \(msgIdHex) from the list")
    }

    /// Stamp "uploaded" on own NEEDs the gateway state now lists as uploaded (this phone was the gateway).
    public func noteUploads() {
        var changed = false
        for m in myNeedsStore.list() where m.uploadedAtMs == 0 && gatewayState.isUploaded(m.msgIdHex) {
            myNeedsStore.update(m.msgIdHex) { $0.markUploaded() }
            changed = true
        }
        if changed { refreshMyNeeds() }
    }

    /// NEEDs submitted while no engine ran; the engine drains them on start.
    public func drainOutbox() -> [Need] {
        lock.lock(); defer { lock.unlock() }
        let l = outbox
        outbox.removeAll()
        return l
    }

    public func clearStore() {
        packetStore.clear()
        packetStore.flush()
        gatewayState.clearUploaded()
        log("store cleared")
    }

    public func clearMyNeeds() {
        myNeedsStore.clear()
        refreshMyNeeds()
    }
}
