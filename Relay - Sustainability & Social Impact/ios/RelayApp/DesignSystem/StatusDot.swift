import SwiftUI

/// Villager-facing reachability state (mirrors Android `VillagerStatus`): colour plus a word, never colour alone.
enum StatusKind: Sendable, Equatable {
    case reachable, waiting, searching, off

    var color: Color {
        switch self {
        case .reachable: return Theme.Palette.statusGreen
        case .waiting: return Theme.Palette.waitingAmber
        case .searching, .off: return Theme.Palette.outline
        }
    }
}

/// A small filled circle in the status colour. Decorative: pair it with text.
struct StatusDot: View {
    var kind: StatusKind
    var size: CGFloat = 9

    var body: some View {
        Circle()
            .fill(kind.color)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// "● REACHABLE": dot plus small-caps word in the status colour, read as one element.
struct StatusLabel: View {
    var kind: StatusKind
    var text: String

    var body: some View {
        HStack(spacing: Theme.Space.xs + 2) {
            StatusDot(kind: kind)
            SmallCapsLabel(text, color: kind.color)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
    }
}

#Preview("Status") {
    VStack(alignment: .leading, spacing: 12) {
        StatusLabel(kind: .reachable, text: "Reachable")
        StatusLabel(kind: .waiting, text: "Waiting")
        StatusLabel(kind: .searching, text: "Searching")
        StatusLabel(kind: .off, text: "Off")
    }
    .padding()
}
