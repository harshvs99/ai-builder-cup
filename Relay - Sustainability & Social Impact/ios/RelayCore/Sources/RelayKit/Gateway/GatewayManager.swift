import Foundation
import RelayCore

/// While this phone has internet (and "Pretend offline" is off), marks the gossip node as a gateway and runs the
/// sync loop (every 10 s, plus immediately when a NEED arrives).
///
/// Android watches `ConnectivityManager` inside this class; here the platform layer (an `NWPathMonitor` in the iOS
/// app) reports reachability through `setInternetAvailable(_:)`. The class is otherwise a direct port: all timing
/// goes through the injected `Scheduler`, so give it a serial queue's scheduler (keep it separate from the gossip
/// scheduler, because a sync can block on the network for seconds) or a manual one in tests.
public final class GatewayManager: @unchecked Sendable {
    /// Milliseconds between periodic sync rounds.
    public static let tickIntervalMs: Int64 = 10_000

    private let node: GossipNode
    private let syncer: GatewaySyncer
    private let scheduler: any Scheduler
    private let pretendOffline: @Sendable () -> Bool
    private let log: @Sendable (String) -> Void
    private let onChange: @Sendable (_ internet: Bool, _ outcome: SyncOutcome?) -> Void

    private let lock = NSLock()
    private var started = false
    private var generation = 0
    private var internet = false
    private var kickQueued = false

    public init(
        node: GossipNode, syncer: GatewaySyncer, scheduler: any Scheduler,
        pretendOffline: @escaping @Sendable () -> Bool,
        log: @escaping @Sendable (String) -> Void = { _ in },
        onChange: @escaping @Sendable (_ internet: Bool, _ outcome: SyncOutcome?) -> Void
    ) {
        self.node = node; self.syncer = syncer; self.scheduler = scheduler
        self.pretendOffline = pretendOffline; self.log = log; self.onChange = onChange
    }

    public var internetAvailable: Bool { lock.lock(); defer { lock.unlock() }; return internet }

    /// The platform reports whether a validated internet path exists.
    public func setInternetAvailable(_ available: Bool) {
        lock.lock()
        let changed = internet != available
        internet = available
        lock.unlock()
        if changed { refresh() }
    }

    public func start() {
        lock.lock()
        if started { lock.unlock(); return }
        started = true
        generation += 1
        let gen = generation
        lock.unlock()
        scheduleTick(gen)
        refresh()
    }

    public func stop() {
        lock.lock()
        started = false
        generation += 1
        lock.unlock()
        node.isGateway = false
    }

    /// Re-evaluate gateway status (connectivity or the "Pretend offline" setting changed).
    public func refresh() {
        lock.lock()
        let net = internet
        let isStarted = started
        lock.unlock()
        let offline = pretendOffline()
        let want = isStarted && net && !offline
        if node.isGateway != want {
            log("gateway: \(want ? "ONLINE" : "offline") (internet=\(net) pretendOffline=\(offline))")
            node.isGateway = want
            if want { kick() }
        }
        onChange(net, nil)
    }

    /// Run a sync round soon (a new NEED was stored). Coalesces bursts.
    public func kick() {
        if !node.isGateway { return }
        lock.lock()
        if kickQueued { lock.unlock(); return }
        kickQueued = true
        lock.unlock()
        scheduler.schedule(delayMs: 0) { [self] in
            lock.lock(); kickQueued = false; lock.unlock()
            tick()
        }
    }

    private func scheduleTick(_ gen: Int) {
        scheduler.schedule(delayMs: GatewayManager.tickIntervalMs) { [self] in
            lock.lock()
            let live = started && generation == gen
            lock.unlock()
            if !live { return }
            tick()
            scheduleTick(gen) // fixed *delay*: the next round is counted from the end of this one
        }
    }

    private func tick() {
        if !node.isGateway { return }
        let outcome = syncer.syncOnce()
        onChange(internetAvailable, outcome)
        if outcome.ok && syncer.moreWaiting { kick() }
    }
}
