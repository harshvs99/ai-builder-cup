import Foundation
import RelayCore

// Display models of Language, Home, New request and Check and send. Each is built from plain state plus `Strings` and
// holds only finished text and flags, so the SwiftUI views stay layout and the rules stay testable.

/// "Choose your language" (first run).
public struct LanguageScreenModel: Equatable, Sendable {
    public struct Row: Equatable, Sendable, Identifiable {
        public var id: Lang { lang }
        public let lang: Lang
        public let nativeName: String
        public let englishName: String
        public let isSelected: Bool
        /// "Hear English": the speaker button's accessibility label.
        public let speakLabel: String
        /// What the speaker says: the language's name and a short greeting, in that language.
        public let greeting: String
    }

    public let appName: String
    public let title: String
    public let subtitle: String
    public let continueLabel: String
    public let rows: [Row]

    public static func make(selected: Lang, strings s: Strings) -> LanguageScreenModel {
        LanguageScreenModel(
            appName: s.home.title, title: s.home.chooseTitle, subtitle: s.home.chooseSubtitle,
            continueLabel: s.common.continueAction,
            rows: AppLanguages.all.map {
                Row(lang: $0.lang, nativeName: $0.nativeName, englishName: $0.englishName, isSelected: $0.lang == selected,
                    speakLabel: s.home.speakLanguage($0.englishName), greeting: s.home.greeting($0.lang))
            }
        )
    }

    /// The line under the list when the speaker button finds no voice installed for [lang].
    public static func noVoiceMessage(_ lang: Lang, strings s: Strings) -> String { s.home.noVoice(AppLanguages.of(lang).englishName) }
}

/// Home (spec section 2): header, status card, the mic, recent requests and the help sheet.
public struct HomeModel: Equatable, Sendable {
    public let title: String
    /// The trimmed "Your area" text; empty when not set.
    public let area: String
    /// "Reachable", "Waiting", "Searching" or "Off" (the iPhone design shows it in small caps).
    public let statusWord: String
    public let statusTone: StatusTone
    /// "Ward 4, Puri district · Reachable", or just the word without an area.
    public let headerLine: String
    /// Status card title and its one line, e.g. "Your messages can reach help" / "Gateway 2 hops away".
    public let statusTitle: String
    public let statusDetail: String
    /// One VoiceOver element for the status card: "title. detail. word. Opens Network".
    public let statusAccessibility: String
    /// The "Turn on" button inside the card when Bluetooth is off.
    public let showsTurnOnBluetooth: Bool
    public let turnOnLabel: String
    public let micTitle: String
    /// "Water · food · medicine · rescue · shelter".
    public let micSubtitle: String
    public let micAccessibility: String
    public let typeInstead: String
    public let yourRequests: String
    public let seeAll: String
    public let noRequests: String
    /// The two most recent requests.
    public let recent: [RequestRowModel]
    public let help: Help

    public struct Help: Equatable, Sendable {
        public let title: String
        public let lines: [String]
        public let close: String
    }

    public static func make(villager: VillagerStatus, area: String, needs: [MyNeed], now: Int64, strings s: Strings) -> HomeModel {
        let c = s.common
        let word = c.villagerWord(villager)
        let title = c.villagerTitle(villager)
        let line = c.villagerLine(villager, now)
        let trimmedArea = area.trimmed
        return HomeModel(
            title: s.home.title, area: trimmedArea, statusWord: word, statusTone: villager.dot.tone,
            headerLine: trimmedArea.isEmpty ? word : "\(trimmedArea) · \(word)",
            statusTitle: title, statusDetail: line,
            statusAccessibility: "\(title). \(line). \(word). \(s.home.statusOpensNetwork)",
            showsTurnOnBluetooth: villager.kind == .bluetoothOff, turnOnLabel: c.turnOn,
            micTitle: s.home.pressAndSay, micSubtitle: s.home.categoriesLine,
            micAccessibility: "\(s.home.pressAndSay). \(s.home.categoriesLine)",
            typeInstead: s.home.typeInstead, yourRequests: s.home.yourRequests, seeAll: c.seeAll,
            noRequests: s.home.noRequestsShort,
            recent: RequestLists.recent(needs).map { RequestRowModel.make($0, now: now, strings: s) },
            help: Help(title: s.home.helpLabel, lines: [s.home.helpLine1, s.home.helpLine2, s.home.helpLine3], close: c.close)
        )
    }
}

/// How a voice problem reads and what the button beside it does.
public struct VoiceProblem: Equatable, Sendable {
    public enum Kind: Sendable, Hashable { case voicePackMissing, micDenied, didntCatch, unavailable }
    public enum Action: Sendable, Hashable { case openVoiceSettings, openAppSettings }

    public let kind: Kind
    /// Plain words, never an error code or developer text.
    public let message: String
    /// The button's action and label ("Open settings"), nil when settings cannot fix it.
    public let action: Action?
    public let actionLabel: String?

    /// From the speech engine's decision (`SpeechLogic.onError`). Nil for `retryOnline`, which shows nothing.
    public static func from(_ decision: SpeechLogic.Decision, lang: Lang, strings s: Strings) -> VoiceProblem? {
        switch decision.kind {
        case .retryOnline: return nil
        case .languageUnavailable: return make(.voicePackMissing, lang: lang, s)
        case .permission: return make(.micDenied, lang: lang, s)
        case .noSpeech: return make(.didntCatch, lang: lang, s)
        case .busy, .failed: return make(.unavailable, lang: lang, s)
        }
    }

    /// Android's mapping from a stored problem: settings offers decide first; "catch" in the text is "didn't catch
    /// that"; anything else is a plain "not working".
    public static func from(text: String, offerVoiceSettings: Bool, offerAppSettings: Bool, lang: Lang, strings s: Strings) -> VoiceProblem {
        if offerVoiceSettings { return make(.voicePackMissing, lang: lang, s) }
        if offerAppSettings { return make(.micDenied, lang: lang, s) }
        if text.lowercased().contains("catch") { return make(.didntCatch, lang: lang, s) }
        return make(.unavailable, lang: lang, s)
    }

    private static func make(_ kind: Kind, lang: Lang, _ s: Strings) -> VoiceProblem {
        switch kind {
        case .voicePackMissing:
            return VoiceProblem(kind: kind, message: s.ask.voicePackMissing(AppLanguages.of(lang).englishName), action: .openVoiceSettings, actionLabel: s.ask.openSettings)
        case .micDenied:
            return VoiceProblem(kind: kind, message: s.ask.micDenied, action: .openAppSettings, actionLabel: s.ask.openSettings)
        case .didntCatch:
            return VoiceProblem(kind: kind, message: s.ask.didntCatch, action: nil, actionLabel: nil)
        case .unavailable:
            return VoiceProblem(kind: kind, message: s.ask.voiceUnavailable, action: nil, actionLabel: nil)
        }
    }
}

/// What the listening card shows about the microphone.
public enum VoiceUi: Equatable, Sendable {
    case paused
    case listening(level: Double)
    case problem(VoiceProblem)
}

/// A category chip of New request or the Check editor.
public struct CategoryChipModel: Equatable, Sendable, Identifiable {
    public var id: Category { category }
    public let category: Category
    public let label: String
    public let isSelected: Bool
}

/// New request, step 1 of 3 (spec section 3).
public struct AskScreenModel: Equatable, Sendable {
    public let title: String
    /// "1 of 3".
    public let stepText: String
    public let headline: String
    public let hearItAgain: String
    /// "Listening" / "Paused" (the iPhone design shows it in small caps); nil while typing.
    public let listeningWord: String?
    public let isListening: Bool
    /// Native name of the language being recognised ("हिन्दी").
    public let languageName: String
    /// The transcript in quotes, or the example prompt while nothing was heard.
    public let transcriptText: String
    public let transcriptIsPrompt: Bool
    public let problem: VoiceProblem?
    public let understoodTitle: String
    public let understoodChips: [String]
    public let nothingYet: String
    public let orTapWhat: String
    public let somethingElse: String
    public let mainChips: [CategoryChipModel]
    /// Missing person and Other; empty until "Something else" is opened or one of them is chosen.
    public let moreChips: [CategoryChipModel]
    public let moreIsOpen: Bool
    public let doneLabel: String
    public let canContinue: Bool
    /// The round mic/stop button's accessibility label ("Stop listening" / "Start listening"); nil while typing.
    public let micButtonLabel: String?
    /// "Type instead" / "Use voice instead".
    public let switchModeLabel: String
    public let typeHint: String
    public let typePlaceholder: String

    /// - Parameters:
    ///   - busy: "Done, check it" was tapped and the recogniser is finishing; the button stays disabled.
    ///   - moreOpened: the person opened "Something else".
    public static func make(
        state: AskState, voice: VoiceUi, lang: Lang, strings s: Strings, busy: Bool = false, moreOpened: Bool = false
    ) -> AskScreenModel {
        let d = state.draft
        var listening = false
        var problem: VoiceProblem?
        switch voice {
        case .listening: listening = true
        case .problem(let p): problem = p
        case .paused: break
        }
        func chips(_ cats: [Category]) -> [CategoryChipModel] {
            cats.map { CategoryChipModel(category: $0, label: s.common.categoryName($0), isSelected: d.category == $0) }
        }
        let showMore = AskLogic.showsMoreCategories(d, opened: moreOpened)
        return AskScreenModel(
            title: s.ask.newRequestTitle, stepText: s.ask.newRequestStep, headline: s.ask.headline,
            hearItAgain: s.ask.hearItAgain,
            listeningWord: state.typing ? nil : (listening ? s.ask.listening : s.ask.paused), isListening: listening,
            languageName: AppLanguages.of(lang).nativeName,
            transcriptText: state.transcript.trimmed.isEmpty ? s.ask.sayItPrompt : "“\(state.transcript)”",
            transcriptIsPrompt: state.transcript.trimmed.isEmpty,
            problem: state.typing ? nil : problem,
            understoodTitle: s.ask.understoodSoFar,
            understoodChips: AskLogic.understood(s.common, near: s.ask.nearLandmark, d),
            nothingYet: s.ask.nothingYet, orTapWhat: s.ask.orTapWhat, somethingElse: s.common.somethingElse,
            mainChips: chips(AskLogic.mainCategories), moreChips: showMore ? chips(AskLogic.moreCategories) : [],
            moreIsOpen: showMore, doneLabel: s.ask.doneCheckIt,
            canContinue: AskLogic.hasContent(d, state.transcript) && !busy,
            micButtonLabel: state.typing ? nil : (listening ? s.ask.stopListening : s.ask.startListening),
            switchModeLabel: state.typing ? s.ask.useVoiceInstead : s.ask.typeInstead,
            typeHint: s.ask.typeHint, typePlaceholder: s.ask.typePlaceholder
        )
    }
}

/// Check and send, step 2 of 3 (spec section 4).
public struct CheckModel: Equatable, Sendable {
    /// One tappable row ("Change") of the summary list.
    public struct Row: Equatable, Sendable, Identifiable {
        public var id: CheckEditor { editor }
        public let editor: CheckEditor
        public let label: String
        public let value: String
        /// Second line: the location line under Where, the "sent in English" note under Note.
        public let secondary: String?
        /// The "Turn on" button beside the location line.
        public let offersTurnOn: Bool
        /// `secondary` is the reason there is no fix: shown as a warning (icon + amber text), not as a caption.
        public let secondaryIsWarning: Bool
    }

    public struct Option: Equatable, Sendable {
        public let value: Int
        public let label: String
        public let isSelected: Bool
    }

    /// One quick question with its answer chips, in the requester's language.
    public struct Question: Equatable, Sendable, Identifiable {
        public let id: Int
        public let text: String
        public let hearLabel: String
        public let options: [Option]
    }

    public let title: String
    /// "2 of 3".
    public let stepText: String
    public let isThisRight: String
    public let rows: [Row]
    public let changeLabel: String
    public let quickQuestionsTitle: String
    public let questions: [Question]
    public let howUrgent: String
    /// The four urgency words, "Can wait" .. "Life at risk"; index = urgency.
    public let urgencyWords: [String]
    public let urgency: Int
    public let urgencyHelp: String
    public let changeSomething: String
    /// "Send" or "Getting your location…".
    public let sendTitle: String
    public let footnote: String
    public let sendEnabled: Bool

    public static func make(draft d: AskDraft, locUi: LocUi, accuracyM: Double?, phase: SendPhase, lang: Lang, strings s: Strings) -> CheckModel {
        let c = s.common
        let sending = phase != .idle
        let rows = [
            Row(editor: .need, label: s.ask.rowNeed, value: AskLogic.needWords(c, d).isEmpty ? s.ask.nothingChosen : AskLogic.needWords(c, d),
                secondary: nil, offersTurnOn: false, secondaryIsWarning: false),
            Row(editor: .people, label: s.ask.rowPeople, value: AskLogic.peopleLine(c, d.people, d.vulnFlags), secondary: nil, offersTurnOn: false,
                secondaryIsWarning: false),
            Row(editor: .location, label: s.ask.rowWhere,
                value: d.landmark.trimmed.isEmpty ? s.ask.noLandmark : s.ask.nearLandmark(d.landmark),
                secondary: AskLogic.locationText(s.ask, locUi, accuracyM: accuracyM), offersTurnOn: AskLogic.offersTurnOnLocation(locUi),
                secondaryIsWarning: NoLocationLogic.reason(for: locUi) != nil),
            Row(editor: .note, label: s.ask.rowNote, value: d.summary.trimmed.isEmpty ? s.ask.noNote : d.summary,
                secondary: !c.isEnglish && !d.summary.trimmed.isEmpty ? s.ask.sentInEnglish : nil, offersTurnOn: false,
                secondaryIsWarning: false),
        ]
        let questions: [Question] = d.questionIds.compactMap { QuestionBank.byId($0) }.map { q in
            Question(
                id: q.id, text: q.text(lang), hearLabel: s.ask.hearQuestion(q.text(lang)),
                options: q.options.map { Option(value: $0.value, label: $0.label(lang), isSelected: d.answers[q.id] == $0.value) }
            )
        }
        return CheckModel(
            title: s.ask.checkTitle, stepText: s.ask.checkStep, isThisRight: s.ask.isThisRight, rows: rows, changeLabel: c.change,
            quickQuestionsTitle: s.ask.quickQuestions, questions: questions, howUrgent: s.ask.howUrgent,
            urgencyWords: (0...3).map { c.urgencyWord($0) }, urgency: min(max(d.urgency, 0), 3), urgencyHelp: c.urgencyHelp(d.urgency),
            changeSomething: s.ask.changeSomething, sendTitle: phase == .locating ? s.ask.sendingGettingLocation : c.send,
            footnote: s.ask.footnote, sendEnabled: !sending
        )
    }

    /// The category chips of the Need editor (all seven, main first).
    public static func needEditorChips(_ d: AskDraft, strings s: Strings) -> [CategoryChipModel] {
        (AskLogic.mainCategories + AskLogic.moreCategories).map {
            CategoryChipModel(category: $0, label: s.common.categoryName($0), isSelected: d.category == $0)
        }
    }
}
