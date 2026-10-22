import Foundation
import RelayCore

/// Outcome of one sync attempt, for the UI.
public struct SyncOutcome: Equatable, Sendable {
    public var ok: Bool
    public var uploaded: Int
    public var accepted: Int
    public var duplicates: Int
    public var rejected: Int
    public var acksOriginated: Int
    /// Control-room broadcasts this round put into the mesh for the first time (already-held ones are not counted).
    public var broadcastsOriginated: Int
    /// Control-room announcements this round put into the mesh for the first time.
    public var announcementsOriginated: Int
    public var error: String?
    /// How many of the `uploaded` frames were LOC_HINTs (the rest are NEEDs).
    public var hintsUploaded: Int
    /// Backend's count of accepted hints, when its response had a `hints` object; nil otherwise.
    public var hintsAccepted: Int?

    public init(ok: Bool, uploaded: Int = 0, accepted: Int = 0, duplicates: Int = 0, rejected: Int = 0,
                acksOriginated: Int = 0, broadcastsOriginated: Int = 0, error: String? = nil, hintsUploaded: Int = 0,
                hintsAccepted: Int? = nil, announcementsOriginated: Int = 0) {
        self.ok = ok; self.uploaded = uploaded; self.accepted = accepted; self.duplicates = duplicates
        self.rejected = rejected; self.acksOriginated = acksOriginated; self.broadcastsOriginated = broadcastsOriginated
        self.announcementsOriginated = announcementsOriginated
        self.error = error
        self.hintsUploaded = hintsUploaded; self.hintsAccepted = hintsAccepted
    }
}

/// One gateway sync round: upload stored NEED frames and then stored LOC_HINT frames (SPEC 5.5) not yet uploaded
/// (NEEDs first, together capped by `maxFramesPerSync`), then wrap any returned ACK payloads and originate them into
/// the mesh. Runs against a fake `HTTPTransport` in tests.
///
/// Failure semantics: any error leaves `state` untouched, so the same frames go out again next round.
public final class GatewaySyncer: @unchecked Sendable {
    private let node: GossipNode
    private let store: any PacketStore
    private let state: GatewayState
    private let client: SyncClient
    private let config: @Sendable () -> SyncConfig
    private let log: @Sendable (String) -> Void
    private let maxFramesPerSync: Int
    /// The district id this gateway is in (last Ready pack or the one the person chose), or nil when unknown.
    private let district: @Sendable () -> String?
    private let syncLock = NSRecursiveLock()
    private let flagLock = NSLock()
    private var moreWaitingValue = false

    public init(
        node: GossipNode, store: any PacketStore, state: GatewayState, client: SyncClient,
        config: @escaping @Sendable () -> SyncConfig, log: @escaping @Sendable (String) -> Void = { _ in },
        maxFramesPerSync: Int = 100, district: @escaping @Sendable () -> String? = { nil }
    ) {
        self.node = node; self.store = store; self.state = state; self.client = client
        self.config = config; self.log = log; self.maxFramesPerSync = maxFramesPerSync; self.district = district
    }

    /// True if more NEED or LOC_HINT frames are waiting after the last round (caller may re-run immediately).
    public var moreWaiting: Bool {
        get { flagLock.lock(); defer { flagLock.unlock() }; return moreWaitingValue }
        set { flagLock.lock(); moreWaitingValue = newValue; flagLock.unlock() }
    }

    /// Keys of stored NEED frames not yet uploaded (LOC_HINTs are deliberately not counted here).
    public func pendingNeedKeys() -> [String] {
        store.all().filter { $0.type == FrameTypes.need && !state.isUploaded($0.key) }.map { $0.key }
    }

    public func syncOnce() -> SyncOutcome {
        syncLock.lock(); defer { syncLock.unlock() }
        let stored = store.all()
        let pendingNeeds = stored.filter { $0.type == FrameTypes.need && !state.isUploaded($0.key) }
        let pendingHints = stored.filter { $0.type == FrameTypes.locHint && !state.isUploaded($0.key) }
        // NEEDs go first so a flood of hints can never delay a request for help.
        let pending = pendingNeeds + pendingHints
        let batch = Array(pending.prefix(maxFramesPerSync))
        let hintsInBatch = batch.filter { $0.type == FrameTypes.locHint }.count
        moreWaiting = pending.count > batch.count
        do {
            let resp = try client.sync(config(), gatewayNode: node.nodeId, frames: batch.map { $0.raw }, ackCursor: state.ackCursor(),
                                       district: district(), announcementCursor: state.announcementCursor())
            var originated = 0
            for payload in resp.acks {
                guard let ack = AckCodec.decode(payload) else {
                    log("sync: backend returned an undecodable ACK payload; skipped")
                    continue
                }
                _ = try node.originateAck(ack)
                log("originating ACK ref=\(Hex.encode(ack.refMsgId)) ticket=#\(ack.ticketNo) status=\(ack.status.name) -> node \(Hex.encode(ack.targetNode))")
                originated += 1
            }
            // Every sync hands back every live broadcast; originating is idempotent, so each gateway just puts them all in.
            var bcasts = 0
            for payload in resp.broadcasts {
                guard let b = BroadcastCodec.decode(payload) else {
                    log("sync: backend returned an undecodable BROADCAST payload; skipped")
                    continue
                }
                if try node.originateBroadcast(b) != nil {
                    log("originating BROADCAST \(b.idHex) \(b.kind) \(b.disaster.key)")
                    bcasts += 1
                }
            }
            // Announcements (SPEC 5.7): originated like ACKs; originating one already held is a no-op. The cursor advances afterwards.
            var anns = 0
            for payload in resp.announcements {
                guard let a = AnnouncementCodec.decode(payload) else {
                    log("sync: backend returned an undecodable ANNOUNCEMENT payload; skipped")
                    continue
                }
                if try node.originateAnnouncement(a) != nil {
                    log("originating ANNOUNCEMENT \(a.idHex) \(a.lang.tag) \(a.status)")
                    anns += 1
                }
            }
            if let c = resp.announcementCursor { state.setAnnouncementCursor(c) }
            // Only after ACKs are safely in the store do we advance the cursor / mark frames uploaded.
            state.markUploaded(batch.map { $0.key })
            state.setAckCursor(resp.ackCursor)
            let hintNote = (hintsInBatch > 0 || resp.hints != nil)
                ? " hints=\(hintsInBatch) hints_accepted=\(resp.hints.map { String($0.accepted) } ?? "?")" : ""
            log("sync ok frames=\(batch.count) accepted=\(resp.accepted) duplicates=\(resp.duplicates) "
                + "rejected=\(resp.rejected) acks=\(originated) ack_cursor=\(resp.ackCursor)\(hintNote)")
            return SyncOutcome(ok: true, uploaded: batch.count, accepted: resp.accepted, duplicates: resp.duplicates,
                               rejected: resp.rejected, acksOriginated: originated, broadcastsOriginated: bcasts,
                               hintsUploaded: hintsInBatch, hintsAccepted: resp.hints?.accepted, announcementsOriginated: anns)
        } catch {
            moreWaiting = false
            let msg = (error as? SyncException)?.message ?? "\(type(of: error)): \(error)"
            log("sync failed: \(msg) (will retry)")
            return SyncOutcome(ok: false, error: msg)
        }
    }
}
