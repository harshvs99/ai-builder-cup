import SwiftUI
import RelayCore
import RelayKit

/// New request, step 1 of 3: voice (live transcript, what was understood) or the typed path, plus category chips.
struct NewRequestScreen: View {
    var mode: AskMode
    var prefillFrom: String?

    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings
    @State private var noVoiceInstalled = false
    @FocusState private var typingFocused: Bool

    private var ctl: AskFlowController { nav.ensureFlow() }
    private var lang: Lang { model.settings.language }
    private var voiceAvailable: Bool { model.isVoiceInputAvailable(for: lang) }

    var body: some View {
        let screen = AskScreenModel.make(
            state: ctl.flow.state, voice: voiceUi, lang: lang, strings: strings, busy: ctl.busy, moreOpened: ctl.moreOpened)
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.l) {
                    headline(screen)
                    card(screen)
                    categories(screen)
                }
                .padding(.horizontal, Theme.Space.gutter)
                .padding(.bottom, Theme.Space.l)
            }
            .scrollDismissesKeyboard(.interactively)
            bottomBar(screen)
        }
        .background(Theme.Palette.surface.ignoresSafeArea())
        .relayStepNavigation(title: screen.title, step: screen.stepText, leadingTitle: strings.common.cancel) {
            model.cancelListening()
            nav.cancelAsk()
        }
        .toolbar(.hidden, for: .tabBar)
        .onAppear(perform: appear)
        .onDisappear {
            model.onTranscript = nil
            model.cancelListening()
        }
        .onChange(of: partialText) { _, text in
            if !text.trimmingCharacters(in: .whitespaces).isEmpty { ctl.flow.onTranscript(text, lang: lang) }
        }
        .onChange(of: problemNow) { _, problem in
            // A voice problem leaves the person on the text field, never on a dead end.
            if problem != nil { ctl.flow.setTyping(true) }
        }
        .onChange(of: ctl.flow.state.typing) { _, typing in if typing { typingFocused = true } }
    }

    // ---------------------------------------------------------------- voice state

    private var partialText: String {
        if case .listening(let partial, _, _) = model.speechState { return partial }
        return ""
    }

    /// The speech engine's problem in plain words. iOS has no voice pack to download, so that case reads as "not working".
    private var problemNow: VoiceProblem? {
        guard case .problem(let text, let offerVoice, let offerApp) = model.speechState else { return nil }
        let p = VoiceProblem.from(text: text, offerVoiceSettings: offerVoice, offerAppSettings: offerApp, lang: lang, strings: strings)
        if p.kind == .voicePackMissing {
            return VoiceProblem.from(text: "", offerVoiceSettings: false, offerAppSettings: false, lang: lang, strings: strings)
        }
        return p
    }

    private var voiceUi: VoiceUi {
        if model.speechState.isListening { return .listening(level: 0) }
        if let p = problemNow { return .problem(p) }
        return .paused
    }

    // ---------------------------------------------------------------- start

    private func appear() {
        let controller = ctl
        let currentLang = lang
        model.onTranscript = { text in controller.flow.onTranscript(text, lang: currentLang) }
        if let id = prefillFrom, let need = model.myNeeds.first(where: { $0.msgIdHex == id }) {
            ctl.flow.prefill(category: need.category, landmark: need.landmark)
        }
        if ctl.flow.autoStartHandled { return }
        ctl.flow.autoStartHandled = true
        if mode == .text || !voiceAvailable {
            ctl.flow.setTyping(true)
            typingFocused = true
        } else {
            startVoice()
        }
    }

    private func startVoice() {
        Task { await model.startListening() }
    }

    /// "Done, check it": let the recogniser deliver its last words, then move on.
    private func done() {
        ctl.busy = true
        Task {
            if model.speechState.isListening {
                model.stopListening()
                for _ in 0..<20 where model.speechState.isListening { try? await Task.sleep(nanoseconds: 100_000_000) }
            }
            model.cancelListening()
            ctl.busy = false
            if ctl.flow.goToCheck() { nav.toCheck() }
        }
    }

    // ---------------------------------------------------------------- layout

    private func headline(_ screen: AskScreenModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text(screen.headline).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            Button {
                noVoiceInstalled = !model.speak(strings.ask.headline)
            } label: {
                Label(screen.hearItAgain, systemImage: "speaker.wave.2.fill").font(.subheadline.weight(.medium))
            }
            .buttonStyle(.relayTinted(Theme.Palette.accent))
            if noVoiceInstalled {
                Text(strings.ask.noVoiceInstalled).font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func card(_ screen: AskScreenModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            if ctl.flow.state.typing {
                typedField(screen)
            } else {
                listening(screen)
            }
            Divider()
            SmallCapsLabel(screen.understoodTitle)
            if screen.understoodChips.isEmpty {
                Text(screen.nothingYet).font(.subheadline).foregroundStyle(.secondary)
            } else {
                ChipFlow {
                    ForEach(screen.understoodChips, id: \.self) { chip in
                        TintedChip(chip, systemImage: "checkmark", tint: Theme.Palette.statusGreen)
                    }
                }
            }
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relaySurfaceCard()
    }

    private func listening(_ screen: AskScreenModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            HStack {
                if let word = screen.listeningWord {
                    SmallCapsLabel(word, color: screen.isListening ? Theme.Palette.accent : Color.secondary)
                }
                Spacer()
                Text(screen.languageName).font(.footnote).foregroundStyle(.secondary)
            }
            Text(screen.transcriptText)
                .font(screen.transcriptIsPrompt ? .body : .title3)
                .foregroundStyle(screen.transcriptIsPrompt ? Color.secondary : Color.primary)
            if let problem = screen.problem { problemView(problem) }
        }
    }

    private func typedField(_ screen: AskScreenModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            if let problem = problemNow {
                problemView(problem)
            } else if !voiceAvailable {
                Text(strings.ask.voiceUnavailable).font(.footnote).foregroundStyle(.secondary)
            }
            Text(screen.typeHint).font(.footnote).foregroundStyle(.secondary)
            TextField(screen.typePlaceholder, text: Binding(
                get: { ctl.flow.state.transcript },
                set: { ctl.flow.onTranscript($0, lang: lang) }
            ), axis: .vertical)
            .lineLimit(3...6)
            .textInputAutocapitalization(.sentences)
            .focused($typingFocused)
            .padding(Theme.Space.m)
            .background(Theme.Palette.surface, in: RoundedRectangle(cornerRadius: Theme.Radius.row, style: .continuous))
        }
    }

    private func problemView(_ problem: VoiceProblem) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.xs) {
            Text(problem.message).font(.subheadline).foregroundStyle(Theme.Palette.cancelRed)
            if let label = problem.actionLabel, problem.action != nil {
                // Both actions land on the app's page in Settings on iOS (there is no separate voice settings page).
                Button(label) { model.openAppSettings() }.font(.subheadline.weight(.semibold))
            }
        }
    }

    private func categories(_ screen: AskScreenModel) -> some View {
        VStack(alignment: .leading, spacing: Theme.Space.s) {
            Text(screen.orTapWhat).font(.headline)
            ChipFlow {
                ForEach(screen.mainChips) { chip in categoryChip(chip) }
                TintedChip(screen.somethingElse, isSelected: screen.moreIsOpen) { ctl.moreOpened.toggle() }
            }
            if !screen.moreChips.isEmpty {
                ChipFlow {
                    ForEach(screen.moreChips) { chip in categoryChip(chip) }
                }
            }
        }
    }

    private func categoryChip(_ chip: CategoryChipModel) -> some View {
        let category = RequestCategory(chip.category)
        return TintedChip(chip.label, systemImage: category.systemImage, tint: category.tint, isSelected: chip.isSelected) {
            ctl.flow.toggleCategory(chip.category)
        }
    }

    private func bottomBar(_ screen: AskScreenModel) -> some View {
        VStack(spacing: Theme.Space.s) {
            HStack(spacing: Theme.Space.m) {
                if !ctl.flow.state.typing {
                    if screen.isListening {
                        StopCircleButton(accessibilityLabel: screen.micButtonLabel ?? "") { model.stopListening() }
                    } else {
                        Button(action: startVoice) {
                            Image(systemName: "mic.fill")
                                .font(.title2.weight(.semibold))
                                .foregroundStyle(Theme.Palette.onMicYellow)
                                .frame(width: Theme.Size.stopButton, height: Theme.Size.stopButton)
                                .relayGlassCircle(tint: Theme.Palette.micYellow)
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(screen.micButtonLabel ?? "")
                    }
                }
                Button(screen.doneLabel, action: done)
                    .buttonStyle(.relayFilled(Theme.Palette.accent))
                    .disabled(!screen.canContinue)
            }
            if voiceAvailable || !ctl.flow.state.typing {
                Button(screen.switchModeLabel, action: switchMode)
                    .font(.body)
                    .frame(minHeight: Theme.Size.minTouch)
            }
        }
        .padding(.horizontal, Theme.Space.gutter)
        .padding(.vertical, Theme.Space.s)
        .background(.bar)
    }

    private func switchMode() {
        if ctl.flow.state.typing {
            ctl.flow.setTyping(false)
            startVoice()
        } else {
            model.cancelListening()
            ctl.flow.setTyping(true)
        }
    }
}
