import SwiftUI

/// Urgency levels 0...3 (Can wait, Soon, Urgent, Life at risk).
enum UrgencyLevel: Int, CaseIterable, Sendable {
    case canWait = 0, soon, urgent, lifeAtRisk

    var color: Color {
        switch self {
        case .canWait: return Theme.Palette.textSecondary
        case .soon, .urgent: return Theme.Palette.waitingAmber
        case .lifeAtRisk: return Theme.Palette.cancelRed
        }
    }

    var tintBackground: Color {
        switch self {
        case .canWait: return Theme.Palette.surfaceCardHigh
        case .soon, .urgent: return Theme.Palette.urgentOrange.opacity(0.22)
        case .lifeAtRisk: return Theme.Palette.cancelRedContainer
        }
    }
}

/// The orange text tag next to a request title ("Urgent"). `text` is the localized word.
struct UrgencyTag: View {
    var text: String
    var level: UrgencyLevel

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(level.color)
            .padding(.horizontal, Theme.Space.m)
            .padding(.vertical, Theme.Space.xs + 1)
            .background(level.tintBackground, in: Capsule())
    }
}

#Preview("Urgency tags") {
    HStack {
        UrgencyTag(text: "Can wait", level: .canWait)
        UrgencyTag(text: "Soon", level: .soon)
        UrgencyTag(text: "Urgent", level: .urgent)
        UrgencyTag(text: "Life at risk", level: .lifeAtRisk)
    }
    .padding()
}
