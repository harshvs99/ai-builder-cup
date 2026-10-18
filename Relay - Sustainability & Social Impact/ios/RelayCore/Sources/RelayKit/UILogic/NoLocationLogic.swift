import Foundation

/// Why there is no GPS fix right now.
public enum NoLocationReason: Sendable, Hashable { case permissionOff, servicesOff, finding }

/// Primary action of the no-location dialog: which flow "Turn on location" starts.
public enum TurnOnAction: Sendable, Hashable { case requestPermission, openLocationSettings, retryFix }

/// What the dialog shows. `confirmSendAnyway` = a second tap is needed on "Send without location".
public struct NoLocationPrompt: Equatable, Sendable {
    public let reason: NoLocationReason
    public let bodyWithLandmark: Bool
    public let turnOn: TurnOnAction
    /// True: the secondary button says "Add a landmark"; false: "Change landmark".
    public let addLandmark: Bool
    public let confirmSendAnyway: Bool

    public init(reason: NoLocationReason, bodyWithLandmark: Bool, turnOn: TurnOnAction, addLandmark: Bool, confirmSendAnyway: Bool) {
        self.reason = reason; self.bodyWithLandmark = bodyWithLandmark; self.turnOn = turnOn
        self.addLandmark = addLandmark; self.confirmSendAnyway = confirmSendAnyway
    }
}

/// Pure decisions for "never let a request leave without the person knowing it has no location".
public enum NoLocationLogic {
    /// Reason for the Where row's warning line; nil when there is a fix.
    public static func reason(permissionGranted: Bool, servicesEnabled: Bool, fixPresent: Bool) -> NoLocationReason? {
        if fixPresent { return nil }
        if !permissionGranted { return .permissionOff }
        if !servicesEnabled { return .servicesOff }
        return .finding
    }

    /// `reason` for what the confirm card shows (`LocUi`); nil for `.ready`.
    public static func reason(for ui: LocUi) -> NoLocationReason? {
        reason(permissionGranted: ui != .noPermission, servicesEnabled: ui != .off && ui != .noPermission, fixPresent: ui == .ready)
    }

    /// The dialog to show when Send is tapped, or nil when the request may go out: there is a fix, or we are still
    /// inside the short wait for one.
    public static func promptOnSend(
        permissionGranted: Bool, servicesEnabled: Bool, fixPresent: Bool, waiting: Bool, landmarkEmpty: Bool
    ) -> NoLocationPrompt? {
        if fixPresent || waiting { return nil }
        guard let reason = reason(permissionGranted: permissionGranted, servicesEnabled: servicesEnabled, fixPresent: false) else { return nil }
        let turnOn: TurnOnAction
        switch reason {
        case .permissionOff: turnOn = .requestPermission
        case .servicesOff: turnOn = .openLocationSettings
        case .finding: turnOn = .retryFix
        }
        return NoLocationPrompt(reason: reason, bodyWithLandmark: !landmarkEmpty, turnOn: turnOn,
                                addLandmark: landmarkEmpty, confirmSendAnyway: landmarkEmpty)
    }

    // ---- strings

    public static func reasonText(_ ask: AskStrings, _ reason: NoLocationReason) -> String {
        switch reason {
        case .permissionOff: return ask.whereReasonPermissionOff
        case .servicesOff: return ask.whereReasonServicesOff
        case .finding: return ask.whereReasonFinding
        }
    }

    public static func body(_ ask: AskStrings, _ p: NoLocationPrompt, landmark: String) -> String {
        p.bodyWithLandmark ? ask.noLocationBodyWithLandmark(landmark.trimmed) : ask.noLocationBodyNoLandmark
    }

    /// Title and body, spoken when "Read messages aloud" is on.
    public static func speech(_ ask: AskStrings, _ p: NoLocationPrompt, landmark: String) -> String {
        ask.noLocationTitle + ". " + body(ask, p, landmark: landmark)
    }
}
