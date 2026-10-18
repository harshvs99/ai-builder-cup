import SwiftUI
import RelayCore
import RelayKit

// Maps RelayKit / RelayCore values onto the plain parameters the DesignSystem components take, so views stay layout.
// The DesignSystem has its own `ChainNode`; inside this module the bare name means that one, RelayKit's is spelled out.

extension RequestCategory {
    init(_ category: NeedCategory) {
        switch category {
        case .medical: self = .medical
        case .rescue: self = .rescue
        case .water: self = .water
        case .food: self = .food
        case .shelter: self = .shelter
        case .missingPerson: self = .missingPerson
        case .other: self = .other
        }
    }
}

extension StatusKind {
    init(tone: StatusTone) {
        switch tone {
        case .good: self = .reachable
        case .warning: self = .waiting
        case .neutral, .bad: self = .searching
        }
    }
}

extension StatusTone {
    /// Colour of a permission value or status word (green / orange / red / grey).
    var color: Color {
        switch self {
        case .good: return Theme.Palette.statusGreen
        case .warning: return Theme.Palette.urgentOrange
        case .bad: return Theme.Palette.cancelRed
        case .neutral: return Color.secondary
        }
    }
}

extension TimelineStep {
    init(row: TimelineRow, index: Int) {
        let state: TimelineStep.State
        switch row.state {
        case .done: state = .done
        case .current: state = .current
        case .future: state = .future
        }
        self.init(id: "\(index)", title: row.title, subtitle: row.subtitle, state: state,
                  isLive: row.tag != nil, liveText: row.tag ?? "Live", stateDescription: nil)
    }
}

extension RequestRowView {
    /// A list row from RelayKit's display model. `urgency` comes from the request itself (the row model only has `isLoud`).
    init(row: RequestRowModel, urgency: Int?) {
        let status: RequestRowStatus = row.isCancelled ? .cancelled : (row.isClosed ? .closed : .open)
        self.init(
            category: RequestCategory(row.category), title: row.title, subtitle: row.statusLine, meta: row.metaLine,
            status: status, statusText: status == .open ? nil : row.trailing,
            urgency: urgency.flatMap { UrgencyLevel(rawValue: $0) })
    }
}

/// The chain diagram's nodes in the words of [s]. A gateway phone itself is drawn as the green gateway circle.
func designChainNodes(_ nodes: [RelayKit.ChainNode], _ s: NetworkSettingsStrings) -> [ChainNode] {
    nodes.map { node in
        switch node.kind {
        case .you: return ChainNode(kind: .you, label: s.chainYou, caption: node.caption(s))
        case .youGateway: return ChainNode(kind: .gateway, label: s.chainYou, caption: node.caption(s))
        case .hop: return ChainNode(kind: .hop, label: node.caption(s))
        case .gateway: return ChainNode(kind: .gateway, label: node.caption(s))
        case .unknown: return ChainNode(kind: .unknown, label: node.caption(s))
        }
    }
}

/// What the Settings permission rows read: the app's three-state permission mapped through RelayKit's words.
@MainActor
func permissionStates(_ p: Permissions) -> PermissionStates {
    func status(_ s: PermissionState, whileUsing: Bool = false) -> PermissionStatus {
        switch s {
        case .granted: return whileUsing ? .grantedWhileUsing : .granted
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        }
    }
    return PermissionStates(
        bluetooth: .of(status(p.bluetooth)), location: .of(status(p.location, whileUsing: true)),
        microphone: .of(status(p.microphone)))
}

extension ThemeMode {
    var colorScheme: ColorScheme? {
        switch self {
        case .light: return .light
        case .dark: return .dark
        case .auto: return nil
        }
    }
}
