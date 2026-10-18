import SwiftUI

/// A glass segmented capsule where the selected segment is filled with `tint` (orange for urgency).
/// Segments are plain buttons so each is a 48 pt+ touch target and reads as "selected" to VoiceOver.
struct GlassSegmentedCapsule: View {
    var options: [String]
    @Binding var selection: Int
    var tint: Color = Theme.Palette.urgentOrange
    var selectedForeground: Color = Theme.Palette.onUrgentOrange

    @Namespace private var ns

    var body: some View {
        HStack(spacing: 2) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, title in
                Button {
                    withAnimation(.snappy(duration: 0.2)) { selection = index }
                } label: {
                    Text(title)
                        .font(.subheadline.weight(index == selection ? .semibold : .regular))
                        .foregroundStyle(index == selection ? selectedForeground : Color.primary)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, minHeight: Theme.Size.minTouch)
                        .padding(.horizontal, Theme.Space.xs)
                        .background {
                            if index == selection {
                                Capsule().fill(tint).matchedGeometryEffect(id: "segment", in: ns)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(index == selection ? .isSelected : [])
            }
        }
        .padding(3)
        .background { SegmentTrack() }
    }
}

private struct SegmentTrack: View {
    var body: some View {
        if #available(iOS 26.0, *) {
            Capsule().fill(Color.clear).glassEffect(.regular, in: Capsule())
        } else {
            Capsule().fill(.ultraThinMaterial).overlay(Capsule().fill(Theme.Palette.surfaceCardHigh.opacity(0.6)))
        }
    }
}

#Preview("Segmented") {
    struct Demo: View {
        @State var sel = 2
        var body: some View {
            GlassSegmentedCapsule(options: ["Can wait", "Soon", "Urgent", "Life at risk"], selection: $sel)
                .padding()
        }
    }
    return Demo()
}
