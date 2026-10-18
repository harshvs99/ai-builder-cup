import SwiftUI
import RelayCore
import RelayKit

/// Developer console: node id, mesh status, peers, the log and a resync button. Technical English on purpose: this
/// screen is for the people building Relay and is not part of the villager flow, so it is not localised.
struct DeveloperScreen: View {
    /// Opened from the "< >" button on Network (true) or from the last row of Settings (false).
    var isConsole: Bool

    @Environment(AppModel.self) private var model
    @Environment(\.strings) private var strings
    @State private var log: [String] = []

    var body: some View {
        let status = model.status
        List {
            Section {
                NavigationLink { GemmaLabScreen() } label: { Text(verbatim: "Gemma lab (on-device model)") }
            }
            DisasterLab()
            Section {
                row("Node id", model.nodeIdHex)
                row("Backend", model.settings.backendUrl)
            } header: {
                Text(verbatim: "This phone")
            }
            Section {
                row("Service running", yesNo(status.serviceRunning))
                row("Bluetooth on", yesNo(status.bluetoothOn))
                row("Gateway", yesNo(status.isGateway))
                row("Internet", yesNo(status.internetAvailable))
                row("Hops to gateway", status.hopsToGateway >= VillagerStatus.noGatewayHops ? "none" : "\(status.hopsToGateway)")
                row("Stored needs / acks", "\(status.storedNeeds) / \(status.storedAcks)")
                row("Pending uploads", "\(status.pendingUploads)")
                row("Scan mode", status.scanMode.label)
                if let error = status.lastSyncError { row("Last sync error", error) }
                Button { model.resyncNow() } label: { Text(verbatim: "Resync now") }
            } header: {
                Text(verbatim: "Mesh")
            }
            Section {
                ForEach(status.peers, id: \.linkId) { peer in
                    row(peer.nodeIdHex.map { String($0.prefix(8)) } ?? "(no HELLO yet)",
                        "\(peer.isGateway ? "gateway, " : "")\(peer.hops) hop\(peer.hops == 1 ? "" : "s")")
                }
            } header: {
                Text(verbatim: "Peers (\(status.peers.count))")
            }
            Section {
                ForEach(Array(log.enumerated()), id: \.offset) { _, line in
                    Text(line).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                }
            } header: {
                Text(verbatim: "Log")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(isConsole ? strings.network.consoleDescription : strings.network.developer)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            log = model.logLines
            for await lines in model.logUpdates() { log = lines }
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(verbatim: title)
            Spacer(minLength: Theme.Space.m)
            Text(verbatim: value).foregroundStyle(.secondary).multilineTextAlignment(.trailing).textSelection(.enabled)
        }
        .font(.subheadline)
    }

    private func yesNo(_ value: Bool) -> String { value ? "yes" : "no" }
}
