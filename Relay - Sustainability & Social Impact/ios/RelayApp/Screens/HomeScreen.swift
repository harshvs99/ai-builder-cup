import SwiftUI
import RelayCore
import RelayKit

/// Home tab: area and status line, status card, the yellow mic, "Type instead" and the two latest requests.
struct HomeScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(AppRouter.self) private var router
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings
    @State private var showHelp = false
    @State private var askedBluetooth = false

    var body: some View {
        NowClock { now in
            let home = HomeModel.make(
                villager: model.villagerStatus, area: model.settings.areaLabel, needs: model.myNeeds, now: now, strings: strings)
            content(home)
                .sheet(isPresented: $showHelp) { HelpSheet(help: home.help) }
        }
        .navigationTitle(strings.home.title)
        .navigationBarTitleDisplayMode(.large)
        .background(Theme.Palette.surface.ignoresSafeArea())
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showHelp = true } label: { Image(systemName: "questionmark.circle") }
                    .accessibilityLabel(strings.home.helpLabel)
            }
        }
        .task {
            // The Bluetooth prompt appears on the first Home, not at launch.
            if askedBluetooth { return }
            askedBluetooth = true
            await model.ensureBluetooth()
        }
    }

    private func content(_ home: HomeModel) -> some View {
        ScrollView {
            VStack(spacing: Theme.Space.xl) {
                headerLine(home)
                readyBanner()
                statusCard(home)
                micSection(home)
                requests(home)
            }
            .padding(.horizontal, Theme.Space.gutter)
            .padding(.bottom, Theme.Space.xl)
        }
    }

    private func headerLine(_ home: HomeModel) -> some View {
        HStack(spacing: Theme.Space.xs + 2) {
            if !home.area.isEmpty {
                Text("\(home.area) ·").font(.subheadline).foregroundStyle(.secondary)
            }
            StatusLabel(kind: StatusKind(tone: home.statusTone), text: home.statusWord)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(home.headerLine)
    }

    /// The cyclone warning banner (only while the stored pack has an active warning): severity colour, headline,
    /// "Landfall in about N h" and a "Get ready" button.
    @ViewBuilder
    private func readyBanner() -> some View {
        let nowSec = Int64(Date().timeIntervalSince1970)
        if let w = ReadyView.activeWarning(model.ready.snapshot.pack, nowSec: nowSec) {
            let r = strings.ready
            let level = ReadyView.level(w)
            let colors = levelColors(level)
            let hours = ReadyView.landfallHours(w, nowSec: nowSec)
            VStack(alignment: .leading, spacing: 6) {
                Text(r.levelWord(level)).font(.subheadline.weight(.semibold))
                Text(w.headline).font(.headline)
                if let h = hours { Text(r.landfallIn(h)).font(.subheadline) }
                Button { nav.push(.ready) } label: {
                    Text(r.getReady).font(.headline).frame(minHeight: Theme.Size.minTouch, alignment: .leading)
                }
            }
            .foregroundStyle(colors.foreground)
            .tint(colors.foreground)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Theme.Space.l)
            .background(colors.background, in: RoundedRectangle(cornerRadius: 16))
            .accessibilityElement(children: .combine)
            .accessibilityLabel(r.bannerDescription(level, headline: w.headline, hours: hours))
            .accessibilityAddTraits(.isButton)
        }
    }

    private func statusCard(_ home: HomeModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Button { router.select(.network) } label: {
                HStack(spacing: Theme.Space.m) {
                    VStack(alignment: .leading, spacing: Theme.Space.xs) {
                        Text(home.statusTitle).font(.headline).foregroundStyle(Color.primary)
                        Text(home.statusDetail).font(.subheadline).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold)).foregroundStyle(.tertiary).accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(home.statusAccessibility)
            .accessibilityAddTraits(.isButton)

            if home.showsTurnOnBluetooth {
                Button(home.turnOnLabel) { model.openAppSettings() }
                    .buttonStyle(.relayTinted(Theme.Palette.accent))
            }
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relayGlassCard()
    }

    private func micSection(_ home: HomeModel) -> some View {
        VStack(spacing: Theme.Space.m) {
            MicButton(accessibilityLabel: home.micAccessibility) { nav.startAsk(mode: .voice) }
            Text(home.micTitle).font(.title2.weight(.semibold)).multilineTextAlignment(.center)
            Text(home.micSubtitle).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button { nav.startAsk(mode: .text) } label: {
                Label(home.typeInstead, systemImage: "keyboard")
            }
            .buttonStyle(.relayTinted(Theme.Palette.accent))
        }
        .frame(maxWidth: .infinity)
    }

    private func requests(_ home: HomeModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack {
                Text(home.yourRequests).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button(home.seeAll) { router.select(.requests) }
            }
            if home.recent.isEmpty {
                Text(home.noRequests).font(.subheadline).foregroundStyle(.secondary)
                    .padding(.vertical, Theme.Space.s)
            } else {
                RecentRequestsList(rows: home.recent)
                    .relaySurfaceCard()
            }
        }
    }
}

/// Home's latest requests. `swipeActions` only works on `List` rows, so this is a non-scrolling `List` sized to its
/// rows (each row reports its height) inside Home's `ScrollView`, in place of the plain `VStack` used before.
private struct RecentRequestsList: View {
    var rows: [RequestRowModel]
    @State private var heights: [String: CGFloat] = [:]

    var body: some View {
        List {
            ForEach(rows) { row in
                RequestRowButton(row: row)
                    .background(GeometryReader { Color.clear.preference(key: RowHeightKey.self, value: [row.id: $0.size.height]) })
                    .listRowInsets(EdgeInsets(top: 0, leading: Theme.Space.l, bottom: 0, trailing: Theme.Space.l))
                    .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollDisabled(true)
        .onPreferenceChange(RowHeightKey.self) { heights = $0 }
        // One point per row for the separator; a first-layout guess keeps the list from collapsing to zero.
        .frame(height: rows.reduce(CGFloat(0)) { $0 + (heights[$1.id] ?? 72) + 1 })
    }
}

private struct RowHeightKey: PreferenceKey {
    static let defaultValue: [String: CGFloat] = [:]
    static func reduce(value: inout [String: CGFloat], nextValue: () -> [String: CGFloat]) {
        value.merge(nextValue()) { _, new in new }
    }
}

/// A tappable request row that opens Request detail on the current tab's stack. Swiping it left reveals a Delete
/// button (no full-swipe delete: only tapping the button deletes); it must sit in a `List` for the swipe to work.
struct RequestRowButton: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings
    var row: RequestRowModel

    var body: some View {
        let urgency = model.myNeeds.first { $0.msgIdHex == row.msgIdHex }?.urgency
        Button { nav.push(.request(msgId: row.msgIdHex)) } label: {
            RequestRowView(row: row, urgency: urgency)
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) { model.deleteMyNeed(row.msgIdHex) } label: {
                Label(strings.common.delete, systemImage: "trash")
            }
        }
    }
}

private struct HelpSheet: View {
    var help: HomeModel.Help
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(help.title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            ForEach(Array(help.lines.enumerated()), id: \.offset) { _, line in
                Text(line).font(.body)
            }
            Spacer(minLength: 0)
            Button(help.close) { dismiss() }.buttonStyle(.relayTinted(Theme.Palette.accent, fullWidth: true))
        }
        .padding(Theme.Space.gutter)
        .presentationDetents([.medium, .large])
    }
}
