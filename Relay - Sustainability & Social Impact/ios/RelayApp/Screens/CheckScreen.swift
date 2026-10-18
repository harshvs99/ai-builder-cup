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
    /// The no-location alert shown on Send without a fix, and the second "send anyway" confirmation when there is no
    /// landmark either.
    @State private var prompt: NoLocationPrompt?
    @State private var showNoLocation = false
    @State private var showConfirmAnyway = false

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
        // The control room cannot act on a request it cannot place: without a fix, ask before anything leaves. Never
        // blocks: "Send without location" is always there (with a second confirmation when there is no landmark).
        .alert(strings.ask.noLocationTitle, isPresented: $showNoLocation, presenting: prompt) { p in
            Button(strings.ask.turnOnLocation) { turnOn(p.turnOn) }
            Button(p.addLandmark ? strings.ask.addLandmark : strings.ask.changeLandmark) {
                editor = ActiveEditor(editor: .location)
            }
            Button(strings.ask.sendWithoutLocation, role: .destructive) { sendAnywayTapped(p) }
        } message: { p in
            Text(NoLocationLogic.body(strings.ask, p, landmark: ctl.flow.draft.landmark))
        }
        .alert(strings.ask.sendAnywayConfirm, isPresented: $showConfirmAnyway) {
            Button(strings.ask.sendWithoutLocation, role: .destructive) { Task { await send(force: true) } }
            Button(strings.common.cancel, role: .cancel) {}
        }
        // Title and body are also spoken when "Read messages aloud" is on.
        .onChange(of: showNoLocation) { _, shown in
            if shown, model.settings.readAloud, let p = prompt {
                model.speak(NoLocationLogic.speech(strings.ask, p, landmark: ctl.flow.draft.landmark))
            }
        }
    }

    /// "Turn on location": permission missing: ask (or open Settings once it was denied); Location Services off: open
    /// Settings (iOS offers no deep link to the system switch, so the app's page is the closest); otherwise just try
    /// for a fix again.
    private func turnOn(_ action: TurnOnAction) {
        switch action {
        case .requestPermission, .openLocationSettings: turnOnLocation()
        case .retryFix: Task { _ = await model.requestLocationFix() }
        }
    }

    /// "Send without location": a second tap when there is no landmark either. iOS alerts always dismiss on a button,
    /// so the second tap is a second alert, shown once the first has finished dismissing.
    private func sendAnywayTapped(_ p: NoLocationPrompt) {
        if p.confirmSendAnyway {
            Task {
                try? await Task.sleep(nanoseconds: 350_000_000)
                showConfirmAnyway = true
            }
        } else {
            Task { await send(force: true) }
        }
    }

    private func back() {
        ctl.flow.backToNewRequest()
        nav.pop()
    }

    // ---------------------------------------------------------------- rows

    private func summaryRow(_ row: CheckModel.Row, _ check: CheckModel) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            EditableRow(
                label: row.label, value: row.value, detail: row.secondary,
                detailIsWarning: row.secondaryIsWarning, warningLabel: strings.ask.noLocationWarningA11y,
                actionTitle: check.changeLabel
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

    /// Send: ask for location the first time, wait briefly for a fix, and without one ask before anything leaves
    /// (`NoLocationLogic`). Then hand the NEED to the mesh and open its detail. [force] = the person chose "Send without
    /// location" in the alert: no wait, no prompt; the NEED says so honestly (no coordinates).
    private func send(force: Bool = false) async {
        guard ctl.flow.beginLocating() else { return }
        _ = await model.requestLocationFix()
        var fix = model.locationFix.flatMap { FixPolicy.isUsable($0, nowMs: currentTimeMs()) ? $0 : nil }
        if fix == nil, !force, LocationState.shouldWaitForFix(model.locationUi) {
            fix = await model.awaitLocationFix()
        }
        if fix == nil, !force {
            let ui = model.locationUi
            if let p = NoLocationLogic.promptOnSend(
                permissionGranted: ui != .noPermission, servicesEnabled: ui != .off && ui != .noPermission,
                fixPresent: false, waiting: false, landmarkEmpty: ctl.flow.draft.landmark.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) {
                ctl.flow.endSend()
                prompt = p
                showNoLocation = true
                return
            }
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
