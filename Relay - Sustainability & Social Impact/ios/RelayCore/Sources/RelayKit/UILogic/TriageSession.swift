import Foundation
import RelayCore

/// One line of the triage conversation as shown on screen.
public struct ChatLine: Equatable, Sendable {
    public let fromPerson: Bool
    public let text: String
    public init(fromPerson: Bool, text: String) { self.fromPerson = fromPerson; self.text = text }
}

public enum TriagePhase: Sendable, Hashable { case idle, asking, thinking, done }

/// Whether the on-device model is helping read answers. Rules always run; this only says if the model is added to them.
public enum BrainStatus: Sendable, Hashable { case none, loading, ready, rulesOnly }

/// One button of a choice question.
public struct ChoiceOption: Equatable, Sendable, Hashable {
    public let key: String
    public let label: String
    public init(key: String, label: String) { self.key = key; self.label = label }
}

public struct TriageUi: Equatable, Sendable {
    public var phase: TriagePhase = .idle
    /// The question on screen; nil when none.
    public var ask: Ask?
    public var questionText = ""
    /// The buttons of a choice question; empty for a yes/no question.
    public var options: [ChoiceOption] = []
    public var lines: [ChatLine] = []
    /// The last spoken answer was not understood: the screen asks again, once.
    public var unclear = false
    public var result: TriageResultBox?
    public var brain: BrainStatus = .none
    /// Disasters in play (declared by the control room within 24 h); shown so the person knows why a question was asked.
    public var disasterNote: String?

    public init() {}
}

/// `Equatable` wrapper so `TriageUi` can be compared (the result holds advisories, which are not `Equatable`).
public struct TriageResultBox: Sendable {
    public let value: TriageResult
    public init(_ value: TriageResult) { self.value = value }
}
extension TriageResultBox: Equatable {
    public static func == (a: TriageResultBox, b: TriageResultBox) -> Bool {
        a.value.urgency == b.value.urgency && a.value.categories == b.value.categories && a.value.summary == b.value.summary &&
            a.value.advisories.map(\.id) == b.value.advisories.map(\.id)
    }
}

/// The triage conversation's state machine, platform-free: port of Android `TriageViewModel` minus the coroutines. The
/// controller owns the microphone, the voice and the model call; it feeds what was heard in and reads `ui` back.
/// Questions and advice come from `TriageBank` only; `Planner` decides which questions are allowed.
public struct TriageSession: Sendable {
    public private(set) var ui = TriageUi()

    private var engine: TriageState?
    private var lang: Lang = .en
    private var startedWith: String?
    private var unclearCount = 0
    /// The model's last view of what to ask next; used once, by the next `advance`.
    private var lastHeard: Interpretation?

    public init() {}

    /// The conversation so far (for the model call).
    public var state: TriageState? { engine }

    /// Start from the first thing the person said. Calling again with the same text keeps the conversation (coming back
    /// from Check and send); different text starts over. `disasters` is what the control room has declared and is still
    /// inside the freshness window: nothing disaster-specific is asked without it. Returns true when a new conversation started.
    @discardableResult
    public mutating func begin(
        text: String, categories: [NeedCategory], lang: Lang, hasBrain: Bool,
        peopleHint: Int = 1, disasters: DisasterGate.Active = .none
    ) -> Bool {
        if startedWith == text && ui.phase != .idle { return false }
        startedWith = text
        self.lang = lang
        unclearCount = 0
        lastHeard = nil
        engine = TriageEngine.start(text: text, categories: categories, lang: lang, peopleHint: peopleHint, disasters: disasters)
        ui = TriageUi()
        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { ui.lines = [ChatLine(fromPerson: true, text: text)] }
        ui.brain = hasBrain ? .loading : .none
        if !disasters.types.isEmpty {
            ui.disasterNote = DisasterType.allCases.filter { disasters.types.contains($0) }.map(\.key).joined(separator: ", ")
        }
        advance()
        return true
    }

    public mutating func setBrain(_ status: BrainStatus) { ui.brain = status }

    /// The model's reading of the first description (who is hurt, where they are). Folded in only if nobody has answered yet.
    public mutating func absorbFirstReading(_ first: Interpretation) {
        guard ui.phase == .asking, let s = engine, s.asked.isEmpty else { return }
        let n = TriageEngine.absorb(s, first)
        engine = n
        lastHeard = first
        // The question on screen may no longer be on the menu (the model says the son is fine): replace it.
        if ui.ask == nil || !Planner.menu(n).contains(where: { $0.ask == ui.ask }) { advance() }
    }

    /// The state the model call needs, and the question being answered. Nil unless a question is waiting for an answer.
    public var pending: (state: TriageState, asked: Ask)? {
        guard ui.phase == .asking, let s = engine, let a = ui.ask else { return nil }
        return (s, a)
    }

    /// The person spoke an answer: show it and wait for the reading. False if no question was waiting.
    @discardableResult
    public mutating func startHeard(_ text: String) -> Bool {
        guard pending != nil, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        ui.phase = .thinking
        ui.unclear = false
        ui.lines.append(ChatLine(fromPerson: true, text: text))
        return true
    }

    /// The reading of the spoken answer (rules merged with the model's) is in.
    public mutating func finishHeard(_ merged: Interpretation) {
        guard let s = engine, let ask = ui.ask else { return }
        let tri = ask.fact.flatMap { merged.facts[FactKey($0, ask.subject)] }
        let option = ask.choice.flatMap { c in merged.choices[c].flatMap { Planner.options(c, s).contains($0) ? $0 : nil } }
        if tri == nil && option == nil {
            unclearCount += 1
            if unclearCount < 2 {
                // Not an answer: keep what else was said (a danger named in passing), ask the same question once more.
                var m = merged
                m.next = nil; m.done = false
                engine = TriageEngine.absorb(s, m)
                ui.phase = .asking
                ui.unclear = true
                return
            }
        }
        unclearCount = 0
        lastHeard = merged
        if let c = ask.choice, let option {
            engine = TriageEngine.recordChoice(s, c, option, heard: merged)
        } else if ask.fact != nil {
            engine = TriageEngine.record(s, ask, tri ?? .unknown, heard: merged)
        } else {
            engine = TriageEngine.skip(TriageEngine.absorb(s, merged), ask)
        }
        advance()
    }

    /// A button press on a yes/no question: Yes, No or Don't know.
    public mutating func tap(_ a: Tri, label: String) {
        guard let s = engine, let ask = ui.ask, ui.phase == .asking, ask.fact != nil else { return }
        unclearCount = 0
        lastHeard = nil
        ui.unclear = false
        ui.lines.append(ChatLine(fromPerson: true, text: label))
        engine = TriageEngine.record(s, ask, a)
        advance()
    }

    /// A button press on a choice question; `option` nil = "Don't know".
    public mutating func tapOption(_ option: ChoiceOption?, dontKnowLabel: String) {
        guard let s = engine, let ask = ui.ask, let choice = ask.choice, ui.phase == .asking else { return }
        unclearCount = 0
        lastHeard = nil
        ui.unclear = false
        ui.lines.append(ChatLine(fromPerson: true, text: option?.label ?? dontKnowLabel))
        engine = option.map { TriageEngine.recordChoice(s, choice, $0.key) } ?? TriageEngine.skip(s, ask)
        advance()
    }

    /// "Skip questions": go straight to the advice with what is known.
    public mutating func finishNow() { finish() }

    private mutating func advance() {
        guard let s = engine else { return }
        let heard = lastHeard
        lastHeard = nil
        guard let ask = TriageEngine.next(s, heard: heard) else { finish(); return }
        let text = Planner.text(ask, s, lang)
        ui.phase = .asking
        ui.ask = ask
        ui.questionText = text
        ui.options = ask.choice.map { c in Planner.options(c, s).map { ChoiceOption(key: $0, label: TriageBank.optionText($0, lang)) } } ?? []
        ui.unclear = false
        ui.lines.append(ChatLine(fromPerson: false, text: text))
    }

    private mutating func finish() {
        guard let s = engine else { return }
        ui.phase = .done
        ui.ask = nil
        ui.questionText = ""
        ui.options = []
        ui.unclear = false
        ui.result = TriageResultBox(TriageEngine.result(s))
    }
}
