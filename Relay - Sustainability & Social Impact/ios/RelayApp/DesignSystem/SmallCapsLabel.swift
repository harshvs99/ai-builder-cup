import SwiftUI

/// Small tracked uppercase label: "REACHABLE", "LISTENING", "LIVE", "FROM THE CONTROL ROOM".
/// Uses a Dynamic Type text style.
struct SmallCapsLabel: View {
    var text: String
    var color: Color

    init(_ text: String, color: Color = .secondary) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .textCase(.uppercase)
            .tracking(0.8)
            .foregroundStyle(color)
    }
}

#Preview("Small caps") {
    VStack(alignment: .leading, spacing: 8) {
        SmallCapsLabel("From the control room")
        SmallCapsLabel("Listening", color: Theme.Palette.accent)
        SmallCapsLabel("Live", color: Theme.Palette.accent)
    }
    .padding()
}
