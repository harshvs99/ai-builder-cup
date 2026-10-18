import SwiftUI
import RelayCore
import RelayKit

/// Drives the triage conversation (Android `TriageViewModel`): the state machine is `TriageSession` (platform-free); this
/// adds the model call. Every spoken answer is also read by the rules, whichever reading is more alarmed winning; the model
/// (when this phone can run it) only helps with free speech, and never writes a question or advice.
@MainActor @Observable
final class TriageController {
    private(set) var session = TriageSession()

    @ObservationIgnored private var brain: (any TriageBrain)?
    @ObservationIgnored private var lang: Lang = .en
    @ObservationIgnored private var warmUp: Task<Void, Never>?
    @ObservationIgnored private var answering: Task<Void, Never>?

    var ui: TriageUi { session.ui }

    /// Start from the first thing the person said; the same text keeps the conversation (back from Check and send).
    /// `disasters` is what the control room declared and is still inside the 24 h window; `agesH` tells the model how old.
    func begin(text: String, categories: [NeedCategory], lang: Lang, peopleHint: Int, disasters: DisasterGate.Active, agesH: @escaping @Sendable () -> [String: Int64]) {
        var b = GemmaTriageBrain()
        b.declaredAgesH = agesH
        guard session.begin(text: text, categories: categories, lang: lang, hasBrain: true, peopleHint: peopleHint, disasters: disasters) else { return }
        answering?.cancel()
        warmUp?.cancel()
        let brain = b
        self.brain = brain
        self.lang = lang
        let first = session.state
        // The model loads only now, after the router sent this request to triage; the first question is already on screen.
        warmUp = Task { [weak self] in
            let ok = await brain.warmUp()
            guard !Task.isCancelled else { return }
            self?.session.setBrain(ok ? .ready : .rulesOnly)
            // The model reads the first description too: who is hurt, where they are. Folded in only if nobody has answered yet.
            guard ok, let first, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
            let reading = await brain.interpret(utterance: text, asked: nil, state: first, lang: lang)
            guard !Task.isCancelled, let reading else { return }
            self?.session.absorbFirstReading(reading)
        }
    }

    /// The person spoke an answer.
    func heard(_ text: String) {
        guard let pending = session.pending, session.startHeard(text) else { return }
        let model: (any TriageBrain)? = session.ui.brain == .ready ? brain : nil
        let lang = lang
        answering = Task { [weak self] in
            let rules = RuleInterpreter.interpret(text, asked: pending.asked, state: pending.state)
            let read = await model?.interpret(utterance: text, asked: pending.asked, state: pending.state, lang: lang)
            guard !Task.isCancelled else { return }
            self?.session.finishHeard(TriageMerge.merge(rules, read))
        }
    }

    func tap(_ a: Tri, label: String) {
        answering?.cancel()
        session.tap(a, label: label)
    }

    func tapOption(_ option: ChoiceOption?, dontKnowLabel: String) {
        answering?.cancel()
        session.tapOption(option, dontKnowLabel: dontKnowLabel)
    }

    func finishNow() {
        answering?.cancel()
        session.finishNow()
    }
}

/// Between New request and Check and send for medical and rescue requests: the phone asks a few short questions out loud,
/// listens for each answer, then reads out what to do while the request goes on to Check and send. Questions and advice are
/// fixed text (`TriageBank`).
struct TriageScreen: View {
    @Environment(AppModel.self) private var model
    @Environment(TabNavigator.self) private var nav
    @Environment(\.strings) private var strings

    /// A yes/no answer spoken in a conversation ends after this much quiet; a story told at New request waits longer.
    private static let answerQuietSec = 3.5

    private var ctl: AskFlowController { nav.ensureFlow() }
    private var triage: TriageController { ctl.triage }
    private var lang: Lang { model.settings.effectiveLanguage }
    private var ui: TriageUi { triage.ui }
    private var voiceAvailable: Bool { model.isVoiceInputAvailable(for: lang) }

    /// What the speaking-and-listening step reacts to.
    private struct Step: Hashable { var phase: TriagePhase; var ask: Ask?; var unclear: Bool }
    private var step: Step { Step(phase: ui.phase, ask: ui.ask, unclear: ui.unclear) }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Theme.Space.l) {
                    if let brain = brainText {
                        Text(brain).font(.footnote).foregroundStyle(.secondary)
                    }
                    if let note = ui.disasterNote {
                        Label(strings.ask.triageDeclared(note), systemImage: "exclamationmark.bubble")
                            .font(.footnote.weight(.medium)).foregroundStyle(Theme.Palette.urgentOrange)
                    }
                    history
                    switch ui.phase {
                    case .asking: questionCard
                    case .thinking: Text(strings.ask.triageThinking).font(.headline)
                    case .done: if let r = ui.result?.value { resultView(r) }
                    case .idle: EmptyView()
                    }
                }
                .padding(.horizontal, Theme.Space.gutter)
                .padding(.bottom, Theme.Space.l)
            }
            bottomBar
        }
        .background(Theme.Palette.surface.ignoresSafeArea())
        .relayStepNavigation(title: strings.ask.triageTitle, step: "", leadingTitle: strings.common.back, leadingShowsChevron: true) {
            leave()
            nav.pop()
        }
        .toolbar(.hidden, for: .tabBar)
        .onAppear {
            let controller = triage
            model.onTranscript = { text in controller.heard(text) }
            let ages = model.disasterAgesHours() // hours change slowly: read once, on the main actor
            triage.begin(
                text: ctl.flow.state.transcript, categories: ctl.flow.draft.categories, lang: lang, peopleHint: ctl.flow.draft.people,
                disasters: model.activeDisasters(), agesH: { ages })
        }
        .onDisappear(perform: leave)
        .task(id: step) { await speakThenListen() }
    }

    // ---------------------------------------------------------------- speak, then listen

    private func leave() {
        model.onTranscript = nil
        model.cancelListening()
        model.stopSpeaking()
    }

    /// Say the question (or the advice), then listen. No voice for this language: listen at once, the text is on screen.
    private func speakThenListen() async {
        model.cancelListening()
        model.stopSpeaking()
        switch ui.phase {
        case .asking:
            let line = ui.unclear ? strings.ask.triageUnclear + " " + ui.questionText : ui.questionText
            let spoke = model.speakAll([line]) { listen() }
            if !spoke { listen() }
        case .done:
            if let r = ui.result?.value { model.speakAll(adviceLines(r)) }
        case .idle, .thinking:
            break
        }
    }

    private func listen() {
        guard ui.phase == .asking, voiceAvailable else { return }
        Task { await model.startListening(quietSec: Self.answerQuietSec) }
    }

    private func adviceLines(_ r: TriageResult) -> [String] {
        r.advisories.prefix(3).flatMap { [$0.title(lang)] + $0.lines(lang) } + [TriageBank.sentNote(lang)]
    }

    // ---------------------------------------------------------------- layout

    private var brainText: String? {
        switch ui.brain {
        case .ready: return strings.ask.triageModelReady
        case .loading: return strings.ask.triageModelLoading
        case .rulesOnly: return strings.ask.triageModelOff
        case .none: return nil
        }
    }

    /// What was said so far, oldest first, without the question on screen now.
    private var history: some View {
        let lines = ui.phase == .asking ? Array(ui.lines.dropLast()) : ui.lines
        return VStack(alignment: .leading, spacing: Theme.Space.s) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(line.fromPerson ? "“\(line.text)”" : line.text)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: line.fromPerson ? .trailing : .leading)
            }
        }
    }

    private var listening: Bool { model.speechState.isListening }

    private var partial: String {
        if case .listening(let partial, _, _) = model.speechState { return partial }
        return ""
    }

    private var voiceProblem: Bool {
        if case .problem = model.speechState { return true }
        return false
    }

    private var questionCard: some View {
        VStack(alignment: .leading, spacing: Theme.Space.m) {
            Text(ui.questionText).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if ui.unclear {
                Text(strings.ask.triageUnclear).font(.subheadline).foregroundStyle(Theme.Palette.cancelRed)
            }
            HStack(spacing: Theme.Space.s) {
                Image(systemName: "waveform").foregroundStyle(listening ? Theme.Palette.accent : Color.secondary)
                Text(listening ? (partial.isEmpty ? strings.ask.listening : "“\(partial)”") : strings.ask.triageAnswerHint)
                    .font(.body)
                    .foregroundStyle(listening ? Theme.Palette.accent : Color.secondary)
            }
            if voiceProblem {
                Text(strings.ask.didntCatch).font(.subheadline).foregroundStyle(Theme.Palette.cancelRed)
            }
        }
        .padding(Theme.Space.l)
        .frame(maxWidth: .infinity, alignment: .leading)
        .relaySurfaceCard()
    }

    @ViewBuilder
    private func resultView(_ r: TriageResult) -> some View {
        if r.critical {
            Label(strings.ask.triageCritical, systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(Theme.Palette.cancelRed)
                .padding(Theme.Space.l)
                .frame(maxWidth: .infinity, alignment: .leading)
                .relaySurfaceCard()
        }
        if !r.advisories.isEmpty {
            Text(strings.ask.triageAdviceTitle).font(.title2.bold())
            Button {
                model.speakAll(adviceLines(r))
            } label: {
                Label(strings.ask.hearItAgain, systemImage: "speaker.wave.2.fill").font(.subheadline.weight(.medium))
            }
            .buttonStyle(.relayTinted(Theme.Palette.accent))
            ForEach(Array(r.advisories.prefix(3).enumerated()), id: \.offset) { _, adv in
                VStack(alignment: .leading, spacing: Theme.Space.s) {
                    Text(adv.title(lang)).font(.headline)
                    ForEach(Array(adv.lines(lang).enumerated()), id: \.offset) { i, line in
                        Text("\(i + 1). \(line)").font(.body)
                    }
                }
                .padding(Theme.Space.l)
                .frame(maxWidth: .infinity, alignment: .leading)
                .relaySurfaceCard()
            }
        }
        Text(TriageBank.sentNote(lang)).font(.subheadline).foregroundStyle(.secondary)
    }

    private var bottomBar: some View {
        VStack(spacing: Theme.Space.s) {
            if ui.phase == .asking {
                ChipFlow {
                    if ui.ask?.choice != nil {
                        ForEach(ui.options, id: \.key) { o in
                            TintedChip(o.label) { pick(o) }
                        }
                        TintedChip(strings.ask.triageDontKnow) { pick(nil) }
                    } else {
                        TintedChip(strings.ask.triageYes) { answer(.yes, strings.ask.triageYes) }
                        TintedChip(strings.ask.triageNo) { answer(.no, strings.ask.triageNo) }
                        TintedChip(strings.ask.triageDontKnow) { answer(.unknown, strings.ask.triageDontKnow) }
                    }
                    if !listening && voiceAvailable {
                        TintedChip(strings.ask.triageStartMic, systemImage: "mic.fill") { model.stopSpeaking(); listen() }
                    }
                }
            }
            if ui.phase == .done {
                Button(strings.ask.triageReview) {
                    leave()
                    if let r = ui.result?.value { ctl.flow.applyTriage(r) }
                    if ctl.flow.goToCheck() { nav.toCheck() }
                }
                .buttonStyle(.relayFilled(Theme.Palette.accent))
            } else if ui.phase != .idle {
                Button(strings.ask.triageSkip) {
                    model.cancelListening()
                    model.stopSpeaking()
                    triage.finishNow()
                }
                .font(.body)
                .frame(minHeight: Theme.Size.minTouch)
            }
        }
        .padding(.horizontal, Theme.Space.gutter)
        .padding(.vertical, Theme.Space.s)
        .background(.bar)
    }

    private func pick(_ option: ChoiceOption?) {
        model.cancelListening()
        model.stopSpeaking()
        triage.tapOption(option, dontKnowLabel: strings.ask.triageDontKnow)
    }

    private func answer(_ a: Tri, _ label: String) {
        model.cancelListening()
        model.stopSpeaking()
        triage.tap(a, label: label)
    }
}
