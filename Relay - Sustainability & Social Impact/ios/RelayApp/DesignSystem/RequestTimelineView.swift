import SwiftUI

/// One step of the request timeline.
struct TimelineStep: Identifiable, Sendable {
    enum State: Sendable { case done, current, future }
    let id: String
    var title: String
    /// Time and/or detail, e.g. "2:02 PM · uploaded to the control room".
    var subtitle: String?
    var state: State
    /// Shows the small-caps "LIVE" tag (Team on the way).
    var isLive = false
    /// Localized word for the tag (defaults to "Live"; shown uppercase).
    var liveText = "Live"
    /// Localized spoken state for VoiceOver ("Done", "Current step"); nothing is spoken when nil.
    var stateDescription: String?
}

/// Vertical timeline: green check circles for done steps, a blue ring for the current step (with an optional
/// LIVE tag), grey rings for future steps (callers normally omit them). Named `RequestTimelineView` to avoid
/// clashing with SwiftUI's own `TimelineView`.
struct RequestTimelineView: View {
    var steps: [TimelineStep]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(steps.enumerated()), id: \.element.id) { index, step in
                HStack(alignment: .top, spacing: Theme.Space.m + 2) {
                    VStack(spacing: 0) {
                        marker(step.state)
                        if index < steps.count - 1 {
                            Rectangle()
                                .fill(step.state == .done ? Theme.Palette.statusGreen.opacity(0.5) : Theme.Palette.outline.opacity(0.35))
                                .frame(width: 2)
                                .frame(maxHeight: .infinity)
                        }
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: Theme.Space.s) {
                            Text(step.title)
                                .font(.headline)
                                .foregroundStyle(step.state == .future ? Color.secondary : Color.primary)
                            if step.isLive { SmallCapsLabel(step.liveText, color: Theme.Palette.accent) }
                        }
                        if let subtitle = step.subtitle {
                            Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.bottom, index < steps.count - 1 ? Theme.Space.xl : 0)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                    .accessibilityValue(step.stateDescription ?? "")
                }
            }
        }
    }

    @ViewBuilder
    private func marker(_ state: TimelineStep.State) -> some View {
        let size = Theme.Size.timelineMarker
        switch state {
        case .done:
            Circle().fill(Theme.Palette.statusGreen)
                .frame(width: size, height: size)
                .overlay(Image(systemName: "checkmark").font(.footnote.weight(.bold)).foregroundStyle(.white))
        case .current:
            Circle().strokeBorder(Theme.Palette.accent, lineWidth: 3)
                .frame(width: size, height: size)
        case .future:
            Circle().strokeBorder(Theme.Palette.outline.opacity(0.5), lineWidth: 2)
                .frame(width: size, height: size)
        }
    }
}

#Preview("Timeline") {
    RequestTimelineView(steps: [
        TimelineStep(id: "saved", title: "Saved on this phone", subtitle: "2:00 PM", state: .done),
        TimelineStep(id: "passed", title: "Passed to a neighbour's phone", subtitle: "2:02 PM", state: .done),
        TimelineStep(id: "gateway", title: "Reached a gateway phone", subtitle: "2:05 PM · uploaded to the control room", state: .done),
        TimelineStep(id: "seen", title: "Seen by the control room", subtitle: "2:09 PM · marked urgent", state: .done),
        TimelineStep(id: "onway", title: "Team on the way", subtitle: "Expected by 4:30 PM", state: .current, isLive: true),
    ])
    .padding()
}
