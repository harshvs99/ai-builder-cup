import Foundation

/// What the "My needs" list shows for one NEED. Order is roughly "how far it has got".
public enum NeedStatus: Int, CaseIterable, Sendable, Hashable {
    case queued, sent, waitingUpload, uploaded, received, dispatched, onTheWay, resolved
}

public enum NeedStatusLogic {
    /// - Parameters:
    ///   - isGateway: this phone currently has internet and is syncing to the backend
    ///   - storedLocally: the NEED frame is in this phone's packet store (it has been originated)
    ///   - uploaded: a backend sync has gone through for this NEED (it is in the gateway's uploaded set)
    ///
    /// A NEED sitting on a gateway that no sync has accepted yet must not look delivered, so it is
    /// `waitingUpload`, and once uploaded it is `uploaded` until the RECEIVED ack arrives.
    public static func derive(_ need: MyNeed, isGateway: Bool, storedLocally: Bool, uploaded: Bool) -> NeedStatus {
        switch need.stage {
        case .resolved: return .resolved
        case .onTheWay: return .onTheWay
        case .dispatched: return .dispatched
        case .received: return .received
        default: break
        }
        if uploaded { return .uploaded }
        if isGateway && storedLocally { return .waitingUpload }
        if need.sentToMesh { return .sent }
        return .queued
    }
}
