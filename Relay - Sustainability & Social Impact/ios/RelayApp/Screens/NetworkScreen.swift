import SwiftUI
import RelayCore
import RelayKit

/// Network tab: status card with the chain diagram, the 2x2 tiles and the gateway / background switches.
struct NetworkScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings

    var body: some View {
        let network = NetworkModel.make(
            villager: model.villagerStatus, stats: stats, settings: model.settings, strings: strings)
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.Space.l) {
                subtitle(network)
                statusCard(network)
                StatTileGrid {
                    ForEach(Array(network.tiles.enumerated()), id: \.offset) { _, tile in
                        StatTile(title: tile.label, value: tile.value, valueColor: tile.tone == .good ? tile.tone.color : Theme.Palette.textPrimary)
                    }
                }
                toggles()
            }
            .padding(.horizontal, Theme.Space.gutter)
            .padding(.bottom, Theme.Space.xl)
        }
        .background(Theme.Palette.surface.ignoresSafeArea())
        .navigationTitle(network.title)
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { nav.push(.console) } label: { Image(systemName: "chevron.left.forwardslash.chevron.right") }
                    .accessibilityLabel(strings.network.consoleDescription)
            }
        }
    }

    /// Own non-cancelled requests stand in for "still in the packet store" (the store itself is not exposed to screens).
    private var stats: NetworkStats {
        let own = model.myNeeds.filter { !$0.cancelled }
        return NetworkStats.from(
            model.status, ownStored: own.count, ownDelivered: own.filter { $0.uploadedAtMs > 0 || $0.receivedAtMs > 0 }.count)
    }

    private func subtitle(_ network: NetworkModel) -> some View {
        HStack(spacing: 0) {
            Text(network.subtitle).font(.subheadline).foregroundStyle(.secondary)
            StatusLabel(kind: StatusKind(tone: network.statusTone), text: network.statusWord)
            Spacer(minLength: 0)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(network.subtitle + network.statusWord)
    }

    private func statusCard(_ network: NetworkModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text(network.statusTitle).font(.headline)
            Text(network.statusSentence).font(.subheadline).foregroundStyle(.secondary)
            ChainDiagram(nodes: designChainNodes(network.chain, strings.network))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(network.chainDescription)
                .padding(.top, Theme.Space.s)
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relayGlassCard()
    }

    private func toggles() -> some View {
        let s = strings.network
        return VStack(alignment: .leading, spacing: 0) {
            toggleRow(
                s.gatewayToggle, help: s.gatewayToggleHelp,
                isOn: Binding(
                    get: { GatewayChoice.isOn(model.settings.gatewayMode) },
                    set: { on in model.updateSettings { var x = $0; x.gatewayMode = GatewayChoice.fromToggle(on); return x } }))
            Divider()
            toggleRow(
                s.carryToggle, help: s.carryToggleHelpIOS,
                isOn: Binding(
                    get: { model.settings.carryInBackground },
                    set: { on in model.updateSettings { var x = $0; x.carryInBackground = on; return x } }))
            Text(s.iPhoneBestOpenNote).font(.footnote).foregroundStyle(.secondary)
                .padding(.top, Theme.Space.s)
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relaySurfaceCard()
    }

    private func toggleRow(_ title: String, help: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.body)
                Text(help).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .tint(Theme.Palette.statusGreen)
        .padding(.vertical, Theme.Space.s)
    }
}
