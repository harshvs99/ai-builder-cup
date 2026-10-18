import SwiftUI
import RelayCore
import RelayKit

/// Request detail: title and urgency, the timeline, the control room's message, "+ Add" and "× Cancel".
struct RequestDetailScreen: View {
    var msgId: String

    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings
    @State private var confirmingCancel = false
    @State private var voiceMissing = false

    private var need: MyNeed? { model.myNeeds.first { $0.msgIdHex == msgId } }

    var body: some View {
        Group {
            if let need {
                NowClock { now in detail(need, now: now) }
            } else {
                // The request is gone (e.g. its data was cleared).
                Text(strings.request.gone).font(.body).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top).padding(Theme.Space.gutter)
            }
        }
        .background(Theme.Palette.surface.ignoresSafeArea())
        .navigationTitle(strings.request.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let need {
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: RequestLogic.shareText(need, strings, area: model.settings.areaLabel, now: currentTimeMs())) {
                        Image(systemName: "square.and.arrow.up")
                    }
                    .accessibilityLabel(strings.request.share)
                }
            }
        }
    }

    private func detail(_ need: MyNeed, now: Int64) -> some View {
        let d = RequestDetailModel.make(need, now: now, area: model.settings.areaLabel, strings: strings)
        return ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.xl) {
                header(d)
                RequestTimelineView(steps: d.timeline.enumerated().map { TimelineStep(row: $1, index: $0) })
                if let message = d.message { controlRoom(message, need: need) }
                if d.showsActions { actions() }
            }
            .padding(.horizontal, Theme.Space.gutter)
            .padding(.bottom, Theme.Space.xl)
        }
    }

    private func header(_ d: RequestDetailModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack(alignment: .firstTextBaseline, spacing: Theme.Space.m) {
                Text(d.title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                if d.isClosed {
                    Text(d.badge).font(.subheadline).foregroundStyle(.secondary)
                } else {
                    UrgencyTag(text: d.badge, level: UrgencyLevel(rawValue: d.urgency) ?? .soon)
                }
            }
            Text(d.referenceLine).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private func controlRoom(_ message: RequestDetailModel.Message, need: MyNeed) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            SmallCapsLabel(message.heading)
            Text(message.text).font(.body)
            Button {
                voiceMissing = !model.speak(message.text, lang: need.lang)
            } label: {
                Label(message.hearLabel, systemImage: "speaker.wave.2.fill")
            }
            .buttonStyle(.relayTinted(Theme.Palette.accent))
            if voiceMissing {
                Text(message.noVoice).font(.footnote).foregroundStyle(Theme.Palette.cancelRed)
            }
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relayGlassCard()
    }

    private func actions() -> some View {
        HStack(spacing: Theme.Space.m) {
            Button(strings.request.addShort) { nav.startAsk(mode: .voice, prefillFrom: msgId) }
                .buttonStyle(.relayTinted(Theme.Palette.accent, fullWidth: true))
            Button(strings.request.cancelShort) { confirmingCancel = true }
                .buttonStyle(.relayTinted(Theme.Palette.cancelRed, fullWidth: true))
        }
        .confirmationDialog(strings.request.cancelDialogTitle, isPresented: $confirmingCancel, titleVisibility: .visible) {
            Button(strings.request.cancelConfirm, role: .destructive) { model.cancel(msgId) }
            Button(strings.request.cancelKeep, role: .cancel) {}
        } message: {
            Text(strings.request.cancelDialogBody)
        }
    }
}
