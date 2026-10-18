import Foundation
import RelayCore

/// What the New request card shows besides the draft.
public struct AskState: Equatable, Sendable {
    public var draft: AskDraft
    /// What was heard or typed; the card shows it in quotes (voice) or in the text field (typing).
    public var transcript: String
    /// The card is a text field rather than the listening card.
    public var typing: Bool

    public init(draft: AskDraft = AskDraft(), transcript: String = "", typing: Bool = false) {
        self.draft = draft; self.transcript = transcript; self.typing = typing
    }
}

/// The two screens of the ask flow ("1 of 3" and "2 of 3"); step 3 is Request detail, which is not part of the flow.
public enum AskStep: Int, Sendable, Hashable {
    case newRequest = 1, check = 2
}

/// Check and send is idle, or waiting for a location fix after Send ("Getting your location…").
public enum SendPhase: Sendable, Hashable { case idle, locating }

/// Which field's editor sheet is open on Check and send (Android `WHERE` is `location` here, `where` being a keyword).
public enum CheckEditor: Sendable, Hashable { case need, people, location, note }

/// The New request -> Check and send state machine, platform-free: draft, transcript, step and send phase. Port of
/// Android `AskViewModel` plus the step handling its navigation did. The SwiftUI view model holds one of these in a
/// `var`, calls the `mutating` methods and re-reads `state`; the screens own the microphone, location and the actual
/// submit and feed results in.
///
/// `engine` is injectable so a model-backed engine can replace `RuleIntake` without touching the screens.
public struct AskFlow: Sendable {
    public private(set) var state = AskState()
    public private(set) var step: AskStep = .newRequest
    public private(set) var sendPhase: SendPhase = .idle
    /// Voice starts by itself only the first time the screen shows (not when coming back from Check).
    public var autoStartHandled = false

    private let engine: any IntakeEngine
    private var prefilled = false
    private var lastLang: Lang = .en

    public init(engine: any IntakeEngine = RuleIntake.shared) { self.engine = engine }

    public var draft: AskDraft { state.draft }

    // ---------------------------------------------------------------- New request

    /// Heard (partial or final) or typed text: re-run extraction and fold it into the draft.
    public mutating func onTranscript(_ text: String, lang: Lang) {
        lastLang = lang
        let intake = engine.extract(text, langHint: lang)
        state.transcript = text
        state.draft = AskLogic.applyIntake(state.draft, intake)
    }

    /// The triage conversation finished: its findings join the draft (see `AskLogic.applyTriage`).
    public mutating func applyTriage(_ r: TriageResult) { state.draft = AskLogic.applyTriage(state.draft, r) }

    public mutating func setTyping(_ typing: Bool) { state.typing = typing }

    /// Add to request: start from an existing request's category and landmark. Applies once.
    public mutating func prefill(category: NeedCategory, landmark: String) {
        if prefilled { return }
        prefilled = true
        var d = AskLogic.setCategory(state.draft, category)
        if !landmark.trimmed.isEmpty { d = AskLogic.setLandmark(d, landmark) }
        state.draft = d
    }

    /// A tap on a category chip adds it (several can be chosen); tapping a chosen one removes it, and removing the
    /// last hands the decision back to extraction.
    public mutating func toggleCategory(_ c: NeedCategory) {
        let next = AskLogic.toggleCategory(state.draft, c)
        if next.category == nil {
            state.draft = AskLogic.applyIntake(next, engine.extract(state.transcript, langHint: lastLang))
        } else {
            state.draft = next
        }
    }

    /// A tap on the "Transport out" chip.
    public mutating func toggleTransport() { state.draft = AskLogic.toggleTransport(state.draft) }

    /// A tap on an exact category in the Need editor (water and food are separate there); a sibling is swapped.
    public mutating func pickCategory(_ c: NeedCategory) {
        let next = AskLogic.pickCategory(state.draft, c)
        state.draft = next.category == nil ? AskLogic.applyIntake(next, engine.extract(state.transcript, langHint: lastLang)) : next
    }

    /// True when "Done, check it" may be tapped: something was said, typed or chosen.
    public var canContinue: Bool { AskLogic.hasContent(state.draft, state.transcript) }

    /// New request -> Check and send. False (and no change) while there is nothing to check yet.
    @discardableResult
    public mutating func goToCheck() -> Bool {
        guard canContinue else { return false }
        step = .check
        return true
    }

    /// Check and send -> New request ("‹ Back" and "Change something"); the draft is kept.
    public mutating func backToNewRequest() {
        step = .newRequest
        sendPhase = .idle
    }

    // ---------------------------------------------------------------- Check and send

    public mutating func setCategory(_ c: NeedCategory) { state.draft = AskLogic.setCategory(state.draft, c) }
    public mutating func setItem(_ text: String) { state.draft = AskLogic.setItem(state.draft, text) }
    public mutating func setPeople(_ n: Int) { state.draft = AskLogic.setPeople(state.draft, n) }
    public mutating func toggleFlag(_ f: VulnFlag) { state.draft = AskLogic.toggleFlag(state.draft, f) }
    public mutating func setLandmark(_ text: String) { state.draft = AskLogic.setLandmark(state.draft, text) }
    public mutating func setSummary(_ text: String) { state.draft = AskLogic.setSummary(state.draft, text) }
    public mutating func setUrgency(_ u: Int) { state.draft = AskLogic.setUrgency(state.draft, u) }
    public mutating func setAnswer(_ questionId: Int, _ value: Int) { state.draft = AskLogic.setAnswer(state.draft, questionId, value) }

    /// True while a Send is under way (the Send and "Change something" buttons are disabled).
    public var isSending: Bool { sendPhase != .idle }

    /// Send tapped: false if a send is already running, else the flow is now `locating`.
    @discardableResult
    public mutating func beginLocating() -> Bool {
        if isSending { return false }
        sendPhase = .locating
        return true
    }

    /// The NEED was handed to the mesh (or the send was abandoned): back to idle.
    public mutating func endSend() { sendPhase = .idle }

    /// After a successful Send the next ask starts clean.
    public mutating func reset() {
        let e = engine
        self = AskFlow(engine: e)
    }

    /// One wire NEED per chosen category (a single one when only one or none is chosen); each gets a fresh id from
    /// [newMsgId]. The caller submits them in order through `MeshRepository`.
    public func buildNeeds(newMsgId: () -> [UInt8], originNode: [UInt8], createdAtSec: Int64, lang: Lang, fix: Fix?) -> [Need] {
        AskLogic.perCategory(state.draft).map { d in
            var f = self
            f.state.draft = d
            return f.buildNeed(msgId: newMsgId(), originNode: originNode, createdAtSec: createdAtSec, lang: lang, fix: fix)
        }
    }

    /// The wire NEED for the current draft's primary category. [fix] is the position fix or nil for "no location" (zeros, loc_acc 0).
    /// The caller submits it through `MeshRepository`.
    public func buildNeed(msgId: [UInt8], originNode: [UInt8], createdAtSec: Int64, lang: Lang, fix: Fix?) -> Need {
        let d = state.draft.pruned()
        return Need(
            msgId: msgId, originNode: originNode, createdAt: createdAtSec,
            category: d.category ?? .other, urgency: d.urgency, people: d.people,
            vulnFlags: VulnFlag.toMask(d.vulnFlags),
            latE5: fix.map { Geo.toE5($0.lat) } ?? 0, lonE5: fix.map { Geo.toE5($0.lon) } ?? 0,
            locAcc: fix?.locAcc ?? 0, lang: lang,
            item: AskLogic.wireItem(d), landmark: d.landmark.trimmed, summary: d.summary.trimmed,
            answers: AskLogic.wireAnswers(d)
        )
    }
}
