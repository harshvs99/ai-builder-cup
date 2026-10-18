import SwiftUI

/// Navigation chrome for the flow screens: a glass capsule on the leading side ("Cancel" or "‹ Back"),
/// a centred title, and the step counter ("1 of 3") trailing.
///
///     .relayStepNavigation(title: "New request", step: "1 of 3", leadingTitle: "Cancel") { dismiss() }
///     .relayStepNavigation(title: "Check and send", step: "2 of 3", leadingTitle: "Back", leadingShowsChevron: true) { ... }
extension View {
    func relayStepNavigation(
        title: String,
        step: String,
        leadingTitle: String,
        leadingShowsChevron: Bool = false,
        onLeading: @escaping () -> Void
    ) -> some View {
        self
            .navigationBarTitleDisplayMode(.inline)
            .navigationBarBackButtonHidden(true)
            .toolbar {
                StepNavToolbar(
                    title: title, step: step, leadingTitle: leadingTitle,
                    leadingShowsChevron: leadingShowsChevron, onLeading: onLeading
                )
            }
    }
}

struct StepNavToolbar: ToolbarContent {
    var title: String
    var step: String
    var leadingTitle: String
    var leadingShowsChevron: Bool
    var onLeading: () -> Void

    var body: some ToolbarContent {
        ToolbarItem(placement: .topBarLeading) {
            StepNavLeadingButton(title: leadingTitle, showsChevron: leadingShowsChevron, action: onLeading)
        }
        ToolbarItem(placement: .principal) {
            Text(title).font(.headline)
        }
        ToolbarItem(placement: .topBarTrailing) {
            Text(step)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityLabel(step)
        }
    }
}

/// The leading capsule. iOS 26 draws toolbar buttons as glass on its own, so only older systems get the
/// manual capsule.
struct StepNavLeadingButton: View {
    var title: String
    var showsChevron: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 2) {
                if showsChevron { Image(systemName: "chevron.left").font(.body.weight(.semibold)) }
                Text(title)
            }
            .modifier(LegacyCapsule())
        }
    }
}

private struct LegacyCapsule: ViewModifier {
    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 26.0, *) {
            content
        } else {
            content
                .padding(.horizontal, Theme.Space.m)
                .padding(.vertical, Theme.Space.xs + 2)
                .background(.ultraThinMaterial, in: Capsule())
        }
    }
}

#Preview("Step nav") {
    NavigationStack {
        Text("Step content")
            .relayStepNavigation(title: "New request", step: "1 of 3", leadingTitle: "Cancel") {}
    }
}
