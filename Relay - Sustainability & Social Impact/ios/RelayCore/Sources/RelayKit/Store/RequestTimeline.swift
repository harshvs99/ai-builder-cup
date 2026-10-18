import Foundation
import RelayCore

/// The vertical timeline of one request (design spec section 5): every step reached so far, the latest one marked
/// current, future steps left out. Pure; the UI turns `Step.key` into words per language.
public enum RequestTimeline {
    public enum Key: Int, CaseIterable, Sendable, Hashable {
        case saved, passedToNeighbour, reachedGateway, seenByControlRoom, helpComing, teamOnTheWay, delivered
    }

    public enum State: Sendable, Hashable { case done, current }

    public struct Step: Equatable, Hashable, Sendable {
        public let key: Key
        public let state: State
        /// When the phone first saw this step reached (epoch ms); 0 = reached but the time was not recorded.
        public let atMs: Int64
        /// Expected arrival (epoch ms) for `helpComing` / `teamOnTheWay` when the ACK carried an ETA, else 0.
        public let etaAtMs: Int64
        /// Urgency 0..3 of the request, on `seenByControlRoom` ("marked urgent"); nil elsewhere.
        public let urgency: Int?
        /// LIVE tag: this is the in-progress "team on the way" step.
        public let live: Bool
        /// The ETA has passed and the team has not arrived.
        public let etaOverdue: Bool

        public init(key: Key, state: State, atMs: Int64, etaAtMs: Int64 = 0, urgency: Int? = nil, live: Bool = false, etaOverdue: Bool = false) {
            self.key = key; self.state = state; self.atMs = atMs; self.etaAtMs = etaAtMs
            self.urgency = urgency; self.live = live; self.etaOverdue = etaOverdue
        }
    }

    /// Steps in order. The latest reached step is `current` unless the request is delivered or cancelled (then all
    /// are `done`). Steps implied by a later ACK count as reached even if no timestamp was recorded for them.
    public static func steps(_ need: MyNeed, now: Int64) -> [Step] {
        let ack = need.ackStatus
        let all: [(Key, Bool)] = [
            (.saved, true),
            (.passedToNeighbour, need.sentToMesh || ack >= 0 || need.uploadedAtMs > 0),
            (.reachedGateway, need.uploadedAtMs > 0 || ack >= AckStatus.received.code),
            (.seenByControlRoom, ack >= AckStatus.received.code),
            (.helpComing, ack >= AckStatus.dispatched.code),
            (.teamOnTheWay, ack >= AckStatus.onTheWay.code),
            (.delivered, ack >= AckStatus.resolved.code),
        ]
        let reached = all.filter { $0.1 }.map { $0.0 }
        let delivered = reached.contains(.delivered)
        let lastIsCurrent = !delivered && !need.cancelled
        return reached.enumerated().map { i, key in
            let current = lastIsCurrent && i == reached.count - 1
            let at: Int64
            switch key {
            case .saved: at = need.savedAtMs
            case .passedToNeighbour: at = need.sentToMeshAtMs
            case .reachedGateway: at = need.uploadedAtMs > 0 ? need.uploadedAtMs : need.receivedAtMs
            case .seenByControlRoom: at = need.receivedAtMs
            case .helpComing: at = need.dispatchedAtMs
            case .teamOnTheWay: at = need.onTheWayAtMs
            case .delivered: at = need.resolvedAtMs
            }
            // "Expected by" only on the current step; an earlier done step does not repeat it.
            let hasEta = current && (key == .helpComing || key == .teamOnTheWay) && need.etaAtMs > 0
            return Step(
                key: key,
                state: current ? .current : .done,
                atMs: at,
                etaAtMs: hasEta ? need.etaAtMs : 0,
                urgency: key == .seenByControlRoom ? need.urgency : nil,
                live: key == .teamOnTheWay && current,
                etaOverdue: hasEta && now > need.etaAtMs
            )
        }
    }

    /// The latest step, to describe a request in a list row ("Reached a gateway phone · 2 min ago").
    public static func latest(_ need: MyNeed, now: Int64) -> Step { steps(need, now: now).last! }
}
