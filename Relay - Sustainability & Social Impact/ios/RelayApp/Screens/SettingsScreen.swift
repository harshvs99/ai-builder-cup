import SwiftUI
import RelayCore
import RelayKit

/// Settings tab: inset grouped list, one section per group of the spec.
struct SettingsScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings
    @State private var sheet: SettingsSheet?
    @State private var editingArea = false
    @State private var areaDraft = ""

    var body: some View {
        let s = strings.network
        let settings = model.settings
        let screen = SettingsModel.make(settings: settings, permissions: permissionStates(model.permissions), strings: strings)
        List {
            Section(s.groupLanguageVoice) {
                valueRow(s.language, value: screen.languageName) { sheet = .language }
                Toggle(s.readAloud, isOn: Binding(get: { settings.readAloud }, set: { v in update { $0.readAloud = v } }))
                choiceRow(
                    s.voiceSpeed, options: VoiceSpeed.allCases, selected: settings.voiceSpeed,
                    label: { SettingsModel.speedLabel($0, s) }, set: { v in update { $0.voiceSpeed = v } })
            }

            Section {
                choiceRow(
                    s.theme, options: ThemeMode.allCases, selected: settings.themeMode,
                    label: { SettingsModel.themeLabel($0, s) }, set: { v in update { $0.themeMode = v } })
                Toggle(s.largerText, isOn: Binding(get: { settings.largerText }, set: { v in update { $0.largerText = v } }))
                valueRow(s.yourArea, value: screen.areaValue) {
                    areaDraft = settings.areaLabel
                    editingArea = true
                }
            } header: {
                Text(s.groupScreenBattery)
            } footer: {
                Text(s.darkNote)
            }

            Section(s.groupNetworkPermissions) {
                valueRow(s.gatewayMode, value: screen.gatewayName) { sheet = .gateway }
                VStack(alignment: .leading, spacing: Theme.Space.xs) {
                    Toggle(s.helpLocate, isOn: Binding(
                        get: { settings.helpLocateNeighbours }, set: { v in update { $0.helpLocateNeighbours = v } }))
                    Text(s.helpLocateHelp).font(.subheadline).foregroundStyle(.secondary)
                }
                .padding(.vertical, Theme.Space.xs)
                ForEach(Array(screen.permissions.enumerated()), id: \.offset) { _, row in
                    valueRow(row.title, value: row.value, color: row.tone.color, hint: s.permOpensSettings) {
                        model.openAppSettings()
                    }
                }
            }

            Section(s.groupBeforeDisaster) {
                VStack(alignment: .leading, spacing: Theme.Space.m) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.householdPlan).font(.body)
                        Text(s.householdPlanHelp).font(.subheadline).foregroundStyle(.secondary)
                    }
                    Button(s.setUp) { sheet = .comingSoon }.buttonStyle(.relayTinted(Theme.Palette.accent))
                }
                .padding(.vertical, Theme.Space.xs)
            }

            Section {
                valueRow(s.developer, value: nil) { nav.push(.developer) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.Palette.surface.ignoresSafeArea())
        .navigationTitle(s.settingsTitle)
        .navigationBarTitleDisplayMode(.large)
        .sheet(item: $sheet) { sheet in
            switch sheet {
            case .language: LanguagePickerSheet()
            case .gateway: GatewayPickerSheet()
            case .comingSoon: ComingSoonSheet()
            }
        }
        .alert(s.yourArea, isPresented: $editingArea) {
            TextField(s.areaHelp, text: $areaDraft)
            Button(s.save) {
                let value = String(areaDraft.trimmingCharacters(in: .whitespaces).prefix(SettingsModel.areaMax))
                update { $0.areaLabel = value }
            }
            Button(strings.common.cancel, role: .cancel) {}
        } message: {
            Text(s.areaHelp)
        }
    }

    private func update(_ change: @escaping (inout RelaySettings) -> Void) {
        model.updateSettings { var x = $0; change(&x); return x }
    }

    /// "Label  value ›": a button row. Chevron marks that it opens something.
    private func valueRow(
        _ title: String, value: String?, color: Color = .secondary, hint: String? = nil, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: Theme.Space.s) {
                Text(title).foregroundStyle(Color.primary)
                Spacer(minLength: Theme.Space.m)
                if let value { Text(value).foregroundStyle(color).multilineTextAlignment(.trailing) }
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: Theme.Size.minTouch)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint(hint ?? "")
    }

    private func choiceRow<T: Hashable>(
        _ title: String, options: [T], selected: T, label: @escaping (T) -> String, set: @escaping (T) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text(title)
            GlassSegmentedCapsule(
                options: options.map(label),
                selection: Binding(
                    get: { options.firstIndex(of: selected) ?? 0 },
                    set: { set(options[$0]) }),
                tint: Theme.Palette.accent, selectedForeground: .white)
        }
        .padding(.vertical, Theme.Space.xs)
    }
}

enum SettingsSheet: Identifiable {
    case language, gateway, comingSoon
    var id: Self { self }
}

private struct LanguagePickerSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.strings) private var strings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let rows = LanguageScreenModel.make(selected: model.settings.language, strings: strings).rows
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(strings.network.chooseLanguage).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) {
                ForEach(rows) { row in
                    Button {
                        model.updateSettings { $0.withLanguage(row.lang) }
                        dismiss()
                    } label: {
                        HStack(spacing: Theme.Space.m) {
                            Image(systemName: row.isSelected ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(row.isSelected ? Theme.Palette.accent : Color.secondary)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.nativeName).font(.headline).foregroundStyle(Color.primary)
                                Text(row.englishName).font(.subheadline).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                        }
                        .frame(minHeight: Theme.Size.minTouch + 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(row.isSelected ? .isSelected : [])
                }
            }
            Spacer(minLength: 0)
        }
        .padding(Theme.Space.gutter)
        .presentationDetents([.medium, .large])
    }
}

/// Automatic or Never; Always is not offered (it behaves like Automatic).
private struct GatewayPickerSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.strings) private var strings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let s = strings.network
        let current = GatewayChoice.effective(model.settings.gatewayMode)
        VStack(alignment: .leading, spacing: Theme.Space.l) {
            Text(s.gatewayMode).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            ForEach(GatewayChoice.options, id: \.self) { mode in
                Button {
                    model.updateSettings { var x = $0; x.gatewayMode = mode; return x }
                } label: {
                    HStack(alignment: .top, spacing: Theme.Space.m) {
                        Image(systemName: mode == current ? "checkmark.circle.fill" : "circle")
                            .foregroundStyle(mode == current ? Theme.Palette.accent : Color.secondary)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(GatewayChoice.name(mode, s)).font(.headline).foregroundStyle(Color.primary)
                            Text(GatewayChoice.help(mode, s)).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .frame(minHeight: Theme.Size.minTouch)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(mode == current ? .isSelected : [])
            }
            Spacer(minLength: 0)
            Button(strings.common.close) { dismiss() }.buttonStyle(.relayTinted(Theme.Palette.accent, fullWidth: true))
        }
        .padding(Theme.Space.gutter)
        .presentationDetents([.medium])
    }
}

private struct ComingSoonSheet: View {
    @Environment(\.strings) private var strings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text(strings.network.comingSoonTitle).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Text(strings.network.comingSoonBody).font(.body).foregroundStyle(.secondary)
            Spacer(minLength: 0)
            Button(strings.common.close) { dismiss() }.buttonStyle(.relayTinted(Theme.Palette.accent, fullWidth: true))
        }
        .padding(Theme.Space.gutter)
        .presentationDetents([.fraction(0.4)])
    }
}
