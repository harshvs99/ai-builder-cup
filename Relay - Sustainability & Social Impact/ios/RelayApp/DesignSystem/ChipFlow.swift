import SwiftUI

/// Wraps its children onto as many lines as needed (leading aligned).
struct FlowLayout: Layout {
    var spacing: CGFloat = Theme.Space.s
    var lineSpacing: CGFloat = Theme.Space.s

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var lineHeight: CGFloat = 0
        var usedWidth: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > maxWidth {
                y += lineHeight + lineSpacing
                x = 0
                lineHeight = 0
            }
            x += size.width
            usedWidth = max(usedWidth, x)
            x += spacing
            lineHeight = max(lineHeight, size.height)
        }
        return CGSize(width: usedWidth, height: y + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var lineHeight: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                y += lineHeight + lineSpacing
                x = bounds.minX
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), anchor: .topLeading, proposal: ProposedViewSize(size))
            x += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

/// A wrapping group of chips: `ChipFlow { TintedChip("Water") ... }`.
struct ChipFlow<Content: View>: View {
    var spacing: CGFloat = Theme.Space.s
    @ViewBuilder var content: () -> Content

    var body: some View {
        FlowLayout(spacing: spacing, lineSpacing: spacing) { content() }
    }
}

/// A tinted capsule chip (not outlined). Tappable when `action` is given; `isSelected` fills it solid.
/// Use `systemImage: "checkmark"` with the green tint for "understood so far" chips.
struct TintedChip: View {
    var title: String
    var systemImage: String?
    var tint: Color
    var isSelected: Bool
    var action: (() -> Void)?

    init(
        _ title: String,
        systemImage: String? = nil,
        tint: Color = Theme.Palette.accent,
        isSelected: Bool = false,
        action: (() -> Void)? = nil
    ) {
        self.title = title
        self.systemImage = systemImage
        self.tint = tint
        self.isSelected = isSelected
        self.action = action
    }

    var body: some View {
        if let action {
            Button(action: action) { label }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
        } else {
            label
        }
    }

    private var label: some View {
        HStack(spacing: Theme.Space.xs + 2) {
            if let systemImage {
                Image(systemName: systemImage).font(.subheadline.weight(.semibold)).accessibilityHidden(true)
            }
            Text(title).font(.subheadline.weight(.medium))
        }
        .foregroundStyle(isSelected ? Color.white : tint)
        .padding(.horizontal, Theme.Space.m + 2)
        .padding(.vertical, Theme.Space.s)
        .frame(minHeight: 36)
        .background(isSelected ? tint : tint.opacity(0.15), in: Capsule())
        .contentShape(Capsule())
    }
}

#Preview("Chips") {
    VStack(alignment: .leading, spacing: 16) {
        ChipFlow {
            TintedChip("Water", isSelected: true) {}
            TintedChip("Food") {}
            TintedChip("Medicine") {}
            TintedChip("Rescue") {}
            TintedChip("Shelter") {}
            TintedChip("Something else") {}
        }
        ChipFlow {
            TintedChip("Drinking water", systemImage: "checkmark", tint: Theme.Palette.statusGreen)
            TintedChip("6 people", systemImage: "checkmark", tint: Theme.Palette.statusGreen)
            TintedChip("Near the primary school", systemImage: "checkmark", tint: Theme.Palette.statusGreen)
            TintedChip("Pump broken since yesterday", systemImage: "checkmark", tint: Theme.Palette.statusGreen)
        }
    }
    .padding()
}
