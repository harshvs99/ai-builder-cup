import SwiftUI
import RelayCore
import RelayKit

/// Check and send, step 2 of 3: the summary list with "Change" buttons, quick questions, urgency and Send.
struct CheckScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings
    @State private var editor: ActiveEditor?
    @State private var noVoiceInstalled = false

    private var ctl: AskFlowController { nav.ensureFlow() }
    private var lang: Lang { model.settings.language }

    var body: some View {
        let flow = ctl.flow
        let check = CheckModel.make(
            draft: flow.draft, locUi: model.locationUi, accuracyM: model.locationFix?.accuracyM, phase: flow.sendPhase,
            lang: lang, strings: strings)
        List {
            Section {
                Text(check.isThisRight).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
            }
            Section {
                ForEach(check.rows) { row in summaryRow(row, check) }
            }
            if !check.questions.isEmpty {
                Section {
                    ForEach(check.questions) { question in questionView(question) }
                } header: {
                    Text(check.quickQuestionsTitle)
                } footer: {
                    if noVoiceInstalled { Text(strings.ask.noVoiceInstalled) }
                }
            }
            Section {
                urgency(check)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: Theme.Space.s, leading: 0, bottom: Theme.Space.s, trailing: 0))
            } header: {
                Text(check.howUrgent)
            }
            Section {
                Button(check.changeSomething) {
                    back()
                }
                .buttonStyle(.relayGrey())
                .disabled(!check.sendEnabled)
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Theme.Palette.surface.ignoresSafeArea())
        .safeAreaInset(edge: .bottom) { sendBar(check) }
        .relayStepNavigation(
            title: check.title, step: check.stepText, leadingTitle: strings.common.back, leadingShowsChevron: true
        ) {
            back()
        }
        .toolbar(.hidden, for: .tabBar)
        .sheet(item: $editor) { active in
            CheckEditorSheet(editor: active.editor)
        }
        .task { await startLocationIfAllowed() }
    }

    private func back() {
        ctl.flow.backToNewRequest()
        nav.pop()
    }

    // ---------------------------------------------------------------- rows

    private func summaryRow(_ row: CheckModel.Row, _ check: CheckModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            EditableRow(
                label: row.label, value: row.value, detail: row.secondary, actionTitle: check.changeLabel
            ) { editor = ActiveEditor(editor: row.editor) }
            if row.offersTurnOn {
                Button(strings.common.turnOn, action: turnOnLocation)
                    .buttonStyle(.borderless)
                    .frame(minHeight: Theme.Size.minTouch)
            }
        }
    }

    private func questionView(_ question: CheckModel.Question) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack {
                Text(question.text).font(.body)
                Spacer(minLength: 0)
                Button {
                    noVoiceInstalled = !model.speak(question.text)
                } label: {
                    Image(systemName: "speaker.wave.2.fill")
                        .frame(width: Theme.Size.minTouch, height: Theme.Size.minTouch)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(question.hearLabel)
            }
            ChipFlow {
                ForEach(question.options, id: \.value) { option in
                    TintedChip(option.label, isSelected: option.isSelected) {
                        ctl.flow.setAnswer(question.id, option.value)
                    }
                }
            }
        }
        .padding(.vertical, Theme.Space.xs)
    }

    private func urgency(_ check: CheckModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            GlassSegmentedCapsule(
                options: check.urgencyWords,
                selection: Binding(get: { check.urgency }, set: { ctl.flow.setUrgency($0) }))
            Text(check.urgencyHelp).font(.subheadline).foregroundStyle(.secondary)
        }
    }

    private func sendBar(_ check: CheckModel) -> some View {
        VStack(spacing: Theme.Space.s) {
            Button { Task { await send() } } label: {
                Label(check.sendTitle, systemImage: "paperplane.fill")
            }
            .buttonStyle(.relayFilled(Theme.Palette.accent))
            .disabled(!check.sendEnabled)
            Text(check.footnote).font(.footnote).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .padding(.horizontal, Theme.Space.gutter)
        .padding(.vertical, Theme.Space.s)
        .background(.bar)
    }

    // ---------------------------------------------------------------- location and send

    /// Location is asked at the first Send; if it was already allowed, the fix starts while the person reads.
    private func startLocationIfAllowed() async {
        if model.permissions.location.isGranted { _ = await model.requestLocationFix() }
    }

    private func turnOnLocation() {
        Task {
            if model.permissions.location == .notDetermined {
                if await model.ensureLocation() { _ = await model.requestLocationFix() }
            } else {
                model.openAppSettings()
            }
        }
    }

    /// Send: ask for location the first time, wait briefly for a fix, hand the NEED to the mesh, open its detail.
    /// The NEED goes out whatever the answer; without a fix it says so honestly (no coordinates).
    private func send() async {
        guard ctl.flow.beginLocating() else { return }
        _ = await model.requestLocationFix()
        var fix = model.locationFix.flatMap { FixPolicy.isUsable($0, nowMs: currentTimeMs()) ? $0 : nil }
        if fix == nil, LocationState.shouldWaitForFix(model.locationUi) {
            fix = await model.awaitLocationFix()
        }
        let need = ctl.flow.buildNeed(
            msgId: NodeId.random(), originNode: model.nodeId, createdAtSec: Int64(Date().timeIntervalSince1970),
            lang: lang, fix: fix)
        do {
            try model.submit(need)
        } catch {
            ctl.flow.endSend()
            return
        }
        ctl.flow.endSend()
        nav.finishAsk(msgId: need.msgIdHex)
    }
}

/// Which editor sheet is open.
struct ActiveEditor: Identifiable {
    let editor: CheckEditor
    var id: CheckEditor { editor }
}
