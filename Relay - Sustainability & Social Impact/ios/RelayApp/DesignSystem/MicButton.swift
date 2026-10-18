import SwiftUI

/// The 160 pt yellow circular mic button. While `isListening` it shows a waveform and a gentle pulsing ring
/// (static when Reduce Motion is on). The glyph is the one fixed-size element in the design system.
struct MicButton: View {
    var isListening = false
    var accessibilityLabel: String
    var accessibilityHint: String?
    var action: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulse = false

    init(
        isListening: Bool = false,
        accessibilityLabel: String,
        accessibilityHint: String? = nil,
        action: @escaping () -> Void
    ) {
        self.isListening = isListening
        self.accessibilityLabel = accessibilityLabel
        self.accessibilityHint = accessibilityHint
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            ZStack {
                if isListening {
                    Circle()
                        .stroke(Theme.Palette.micYellow.opacity(0.45), lineWidth: 6)
                        .frame(width: Theme.Size.micButton, height: Theme.Size.micButton)
                        .scaleEffect(pulse && !reduceMotion ? 1.18 : 1.0)
                        .opacity(pulse && !reduceMotion ? 0.0 : 0.9)
                }
                Circle()
                    .fill(Theme.Palette.micYellow)
                    .frame(width: Theme.Size.micButton, height: Theme.Size.micButton)
                    .shadow(color: Theme.Palette.micYellow.opacity(0.35), radius: 18, y: 6)
                Image(systemName: isListening ? "waveform" : "mic.fill")
                    .font(.system(size: 64, weight: .semibold))
                    .foregroundStyle(Theme.Palette.onMicYellow)
            }
            .frame(width: Theme.Size.micButton + 36, height: Theme.Size.micButton + 36)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(accessibilityHint ?? "")
        .accessibilityAddTraits(isListening ? .isSelected : [])
        .onChange(of: isListening) { _, listening in
            updatePulse(listening)
        }
        .onAppear { updatePulse(isListening) }
    }

    private func updatePulse(_ listening: Bool) {
        if listening && !reduceMotion {
            pulse = false
            withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { pulse = true }
        } else {
            withAnimation(nil) { pulse = false }
        }
    }
}

#Preview("Mic") {
    VStack(spacing: 24) {
        MicButton(accessibilityLabel: "Press and say what you need") {}
        MicButton(isListening: true, accessibilityLabel: "Stop listening") {}
    }
    .padding()
    .background(Theme.Palette.surface)
}
