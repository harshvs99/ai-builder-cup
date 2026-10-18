import Foundation

// ====================================================================== routing

/// Which path a request takes after it is spoken.
public enum TriageRoute: Sendable { case simple, triage }

/// Splits simple requests (water, food, shelter: the existing one-shot path) from ones that need a conversation
/// (a medical problem, a rescue, a named danger). Rules only, so it costs nothing and never needs the model; the model is
/// loaded only after this says `.triage`.
public enum ComplexityRouter {
    public static func route(text: String, categories: [NeedCategory], vuln: Set<VulnFlag> = []) -> TriageRoute {
        if categories.contains(where: { $0 == .medical || $0 == .rescue }) { return .triage }
        if vuln.contains(.injured) || vuln.contains(.trapped) { return .triage }
        if HazardLexicon.detect(text).contains(where: { $0.lifeThreatening }) { return .triage }
        return .simple
    }
}

// ====================================================================== what one utterance told us

/// What was understood from one utterance. `facts` and `choices` hold only what the person said or clearly meant; absent =
/// not said. `people`, `landmark`, `patients`, `next` and `done` come from the model only: who needs medical help (relations
/// from the fixed list), and which allowed question it would ask next, or that it has heard enough.
public struct Interpretation: Equatable, Sendable {
    public var facts: [FactKey: Tri]
    public var hazards: Set<Hazard>
    public var people: Int?
    public var landmark: String?
    public var choices: [Choice: String]
    public var patients: Set<Relation>
    public var next: String?
    public var done: Bool

    public init(facts: [FactKey: Tri] = [:], hazards: Set<Hazard> = [], people: Int? = nil, landmark: String? = nil,
                choices: [Choice: String] = [:], patients: Set<Relation> = [], next: String? = nil, done: Bool = false) {
        self.facts = facts; self.hazards = hazards; self.people = people; self.landmark = landmark
        self.choices = choices; self.patients = patients; self.next = next; self.done = done
    }
}

/// Yes / no in the app's languages, for answers to a yes-or-no question. Nil when the words say neither, or both.
public enum YesNo {
    private static let yes = Lex("yes", "yeah", "yep", "yup", "ya", "sure", "correct", "still", "haan", "han", "haa", "ha", "ji", "jee", "हाँ", "हां", "हा", "जी", "ହଁ", "ହାଁ", "hau", "oo", "opo", "oho", "oo po", "tama")
    private static let no = Lex("no", "nope", "nah", "not", "never", "nothing", "none", "cannot", "can't", "isn't", "aren't", "doesn't", "don't", "won't", "stopped", "nahi", "nahin", "nai", "na", "naa", "नहीं", "नही", "ना", "न", "ନା", "ନାହିଁ", "ନାହିଁ", "hindi", "wala", "dili", "diri", "waray")
    private static let unsure = Lex("don't know", "do not know", "dont know", "not sure", "unsure", "pata nahi", "पता नहीं", "ଜଣା ନାହିଁ", "hindi alam", "ewan", "diri aram")

    public static func detect(_ text: String) -> Tri? {
        let t = normalizeText(text)
        if t.isEmpty { return nil }
        if unsure.matches(t) { return .unknown }
        let y = yes.matches(t)
        let n = no.matches(t)
        if y && !n { return .yes }
        if n && !y { return .no }
        return nil
    }
}

/// Reads an utterance with rules alone. This is always run, even when a model is also used, so a model can never hide a danger.
public enum RuleInterpreter {
    public static func interpret(_ text: String, asked: Ask?, state: TriageState? = nil) -> Interpretation {
        let hazards = HazardLexicon.detect(text)
        var facts: [FactKey: Tri] = [:]
        var choices: [Choice: String] = [:]
        // A danger named about "him" belongs to the person just asked about, else the first patient.
        let who = (asked?.fact?.scope == .person ? asked?.subject : nil) ?? 1
        if hazards.contains(.notBreathing) { facts[FactKey(.breathing, who)] = .no }
        if hazards.contains(.unconscious) { facts[FactKey(.conscious, who)] = .no }

        // A bare yes/no belongs to the question just asked, unless the sentence itself named the danger (above).
        if let asked, let f = asked.fact {
            let key = FactKey(f, asked.subject)
            if facts[key] == nil, let a = YesNo.detect(text) { facts[key] = a }
        }
        if let c = asked?.choice {
            let offered = state.map { Planner.options(c, $0) }
            if let o = Options.detect(c, text, offered: offered) { choices[c] = o }
        }
        // Said in passing: only the unambiguous places (a roof is a roof; "road" or "open" could be anything).
        if choices[.location] == nil, let o = Options.detect(.location, text, offered: ["roof", "rubble", "basement"]) { choices[.location] = o }
        return Interpretation(facts: facts, hazards: hazards, choices: choices)
    }
}

/// Combines the rules' reading with a model's. The dangerous reading wins when they disagree.
public enum TriageMerge {
    public static func merge(_ rules: Interpretation, _ model: Interpretation?) -> Interpretation {
        guard let model else { return rules }
        var facts = rules.facts
        for (k, m) in model.facts {
            let r = facts[k]
            if r == nil || r == .unknown { facts[k] = m }
            else if m == .unknown || m == r { }
            else if k.fact.noIsDangerous { facts[k] = .no }
            else if k.fact.yesIsDangerous { facts[k] = .yes }
        }
        // A word list that matched is better evidence of a place or a depth than a model's reading; the model fills the gaps.
        var choices = model.choices
        for (c, o) in rules.choices { choices[c] = o }
        return Interpretation(
            facts: facts, hazards: rules.hazards.union(model.hazards), people: model.people ?? rules.people,
            landmark: model.landmark ?? rules.landmark, choices: choices, patients: rules.patients.union(model.patients),
            next: model.next, done: model.done)
    }
}

// ====================================================================== the conversation

public struct TriageState: Equatable, Sendable {
    public var lang: Lang
    public var medical: Bool
    public var rescue: Bool
    /// The people being asked about; empty = one unnamed patient, until `Choice.patients` or a relation says otherwise.
    public var subjects: [Subject] = []
    public var facts: [FactKey: Tri] = [:]
    public var choices: [Choice: String] = [:]
    public var hazards: Set<Hazard> = []
    /// Ids of the questions asked (`Ask.id`), in order.
    public var asked: [String] = []
    public var people: Int?
    public var landmark: String?
    /// How many people the request is for (from New request), to know whether to ask how many need help.
    public var peopleHint = 1
    /// Disasters the control room declared and that are still inside the freshness window; empty = no disaster-specific questions.
    public var disasters: Set<DisasterType> = []
    /// Every declaration is older than `DisasterGate.freshSec`: the premise may have changed.
    public var aging = false

    public init(lang: Lang, medical: Bool, rescue: Bool, subjects: [Subject] = [], facts: [FactKey: Tri] = [:],
                choices: [Choice: String] = [:], hazards: Set<Hazard> = [], asked: [String] = [], people: Int? = nil,
                landmark: String? = nil, peopleHint: Int = 1, disasters: Set<DisasterType> = [], aging: Bool = false) {
        self.lang = lang; self.medical = medical; self.rescue = rescue; self.subjects = subjects; self.facts = facts
        self.choices = choices; self.hazards = hazards; self.asked = asked; self.people = people; self.landmark = landmark
        self.peopleHint = peopleHint; self.disasters = disasters; self.aging = aging
    }

    public var patients: [Subject] { subjects.isEmpty ? [Subject(1)] : subjects }
}

public struct TriageResult: Sendable {
    /// Medical first when both; the app turns each into its own ticket.
    public let categories: [NeedCategory]
    /// 0..3; the app never lowers a higher urgency the request already had.
    public let urgency: Int
    public let vuln: Set<VulnFlag>
    public let people: Int?
    public let landmark: String?
    /// Wire answers (QuestionBank ids), at most 3, for the first category.
    public let answers: [NeedAnswer]
    /// English, at most 40 bytes, for the control room.
    public let summary: String
    /// Highest priority first.
    public let advisories: [TriageBank.Advisory]
    public let facts: [FactKey: Tri]
    public let choices: [Choice: String]
    public let hazards: Set<Hazard>
    public let subjects: [Subject]

    /// A danger to life now: the screen shows the send button first and says why.
    public var critical: Bool { urgency >= 3 }
}

public enum TriageEngine {
    /// Begin from the first thing the person said (all of it) and the categories already understood.
    public static func start(
        text: String, categories: [NeedCategory], lang: Lang, peopleHint: Int = 1, disasters: DisasterGate.Active = .none
    ) -> TriageState {
        let first = RuleInterpreter.interpret(text, asked: nil)
        let medicalByWords = !first.hazards.isDisjoint(with: medicalHazards)
        let subjects = Subjects.fromText(text)
        return absorb(
            TriageState(
                lang: lang,
                medical: categories.contains(.medical) || medicalByWords,
                rescue: categories.contains(.rescue) || !first.hazards.isDisjoint(with: rescueHazards),
                subjects: subjects, peopleHint: peopleHint, disasters: disasters.types, aging: disasters.aging),
            first)
    }

    private static let medicalHazards: Set<Hazard> = [.notBreathing, .unconscious, .bleeding, .snake, .labour]
    private static let rescueHazards: Set<Hazard> = [.trapped]

    /// The next question, or nil when the conversation should end and the advice be given.
    public static func next(_ s: TriageState, heard: Interpretation? = nil) -> Ask? {
        Planner.pick(s, suggestion: heard?.next, done: heard?.done ?? false)
    }

    /// The person answered yes/no question `ask`: a direct answer replaces what was inferred. `.unknown` never replaces a known fact.
    public static func record(_ s: TriageState, _ ask: Ask, _ answer: Tri, heard: Interpretation = Interpretation()) -> TriageState {
        guard let fact = ask.fact else { return s }
        let key = FactKey(fact, ask.subject)
        var next = s
        if answer != .unknown || next.facts[key] == nil { next.facts[key] = answer }
        if !next.asked.contains(ask.id) { next.asked.append(ask.id) }
        if fact == .anyoneHurt && answer == .yes { next.medical = true }
        var h = heard
        h.facts[key] = nil
        return absorb(next, h)
    }

    /// Convenience for the common single-patient / scene case.
    public static func record(_ s: TriageState, fact: Fact, answer: Tri, heard: Interpretation = Interpretation(), subject: Int = 1) -> TriageState {
        record(s, .fact(fact, subject), answer, heard: heard)
    }

    /// The person picked `option` for a choice.
    public static func recordChoice(_ s: TriageState, _ choice: Choice, _ option: String, heard: Interpretation = Interpretation()) -> TriageState {
        var next = s
        next.choices[choice] = option
        if !next.asked.contains(choice.key) { next.asked.append(choice.key) }
        if choice == .patients { next.subjects = patientsFor(next.subjects, option) }
        var h = heard
        h.choices[choice] = nil
        return absorb(next, h)
    }

    /// Mark a question as asked without an answer ("I don't know" to a choice, or unclear twice).
    public static func skip(_ s: TriageState, _ ask: Ask) -> TriageState {
        var n = s
        if !n.asked.contains(ask.id) { n.asked.append(ask.id) }
        return n
    }

    private static func patientsFor(_ existing: [Subject], _ option: String) -> [Subject] {
        let n: Int
        switch option { case "one": n = 1; case "two": n = 2; default: n = 3 }
        let named = Array(existing.prefix(n))
        return named + (named.count + 1 ..< n + 1).map { Subject($0) }
    }

    /// Fold in what an utterance said on the side (a danger, a fact, a place): the dangerous reading wins over what is known.
    public static func absorb(_ s: TriageState, _ heard: Interpretation) -> TriageState {
        var facts = s.facts
        for (k, v) in heard.facts {
            let old = facts[k]
            if old == nil || old == .unknown { facts[k] = v }
            else if v == .unknown || v == old { }
            else if k.fact.noIsDangerous { facts[k] = .no }
            else if k.fact.yesIsDangerous { facts[k] = .yes }
        }
        var choices = s.choices
        for (c, o) in heard.choices where choices[c] == nil { choices[c] = o }
        let hazards = s.hazards.union(heard.hazards)
        var subjects = s.subjects
        if !heard.patients.isEmpty { subjects = refine(subjects, heard.patients, facts) }
        var n = s
        n.facts = facts
        n.choices = choices
        n.hazards = hazards
        n.subjects = subjects
        n.medical = s.medical || !hazards.isDisjoint(with: medicalHazards)
        n.rescue = s.rescue || !hazards.isDisjoint(with: rescueHazards) || choices[.location] == "rubble"
        n.people = heard.people ?? s.people
        if let l = heard.landmark, !l.isEmpty { n.landmark = l }
        return n
    }

    /// Keep the people the model says need medical help (plus any already asked about); add ones it names that rules missed.
    private static func refine(_ subjects: [Subject], _ patients: Set<Relation>, _ facts: [FactKey: Tri]) -> [Subject] {
        let kept = subjects.filter { s in
            (s.relation.map { patients.contains($0) } ?? false) || facts.keys.contains { $0.fact.scope == .person && $0.subject == s.id }
        }
        let named = Set(kept.compactMap(\.relation))
        var nextId = kept.map(\.id).max() ?? 0
        // Relations in a stable order (the model's set has none).
        let added = Relation.allCases.filter { patients.contains($0) && !named.contains($0) }.map { r -> Subject in nextId += 1; return Subject(nextId, r) }
        // Ids stay what facts were recorded under; positions in the list do not matter.
        let all = Array((kept + added).prefix(Subjects.max))
        return all.isEmpty ? subjects : all
    }

    public static func result(_ s: TriageState) -> TriageResult {
        let f = s.facts
        let h = s.hazards
        let loc = s.choices[.location]
        let depth = s.choices[.waterDepth]
        func any(_ fact: Fact, _ v: Tri) -> Bool { f.contains { $0.key.fact == fact && $0.value == v } }

        var categories: [NeedCategory] = []
        if s.medical { categories.append(.medical) }
        if s.rescue { categories.append(.rescue) }
        if categories.isEmpty { categories = [.other] }

        let alwaysCritical: Set<Hazard> = [.notBreathing, .unconscious, .snake, .labour, .trapped]
        let critical = any(.breathing, .no) || any(.conscious, .no) || any(.heavyBleeding, .yes) ||
            any(.liveWire, .yes) || any(.gasSmell, .yes) || (s.rescue && any(.waterRising, .yes)) ||
            (s.rescue && any(.slopeMoving, .yes)) || depth == "chest" || depth == "over_head" || loc == "rubble" ||
            !h.isDisjoint(with: alwaysCritical)
        let urgency = critical ? 3 : ((s.medical || s.rescue) ? 2 : 1)

        var vuln = Set<VulnFlag>()
        if any(.heavyBleeding, .yes) || (h.contains(.bleeding) && !any(.heavyBleeding, .no)) { vuln.insert(.injured) }
        if h.contains(.trapped) || loc == "rubble" { vuln.insert(.trapped) }
        if h.contains(.labour) { vuln.insert(.pregnant) }
        if s.subjects.contains(where: { $0.relation == .baby || $0.relation == .child }) { vuln.insert(.child) }
        if s.subjects.contains(where: { $0.relation == .grandmother || $0.relation == .grandfather }) { vuln.insert(.elderly) }

        // The wire has room for three answers. Several patients: the worst one speaks for all ("anyone unresponsive").
        func yesNo(_ v: Tri) -> Int? { v == .yes ? 1 : (v == .no ? 2 : nil) }
        var answers: [NeedAnswer] = []
        if any(.conscious, .no) { answers.append(NeedAnswer(1, 2)) } else if any(.conscious, .yes) { answers.append(NeedAnswer(1, 1)) }
        if let v = f[FactKey(.roadReachable, 0)], let x = yesNo(v) { answers.append(NeedAnswer(2, x)) }
        if let v = f[FactKey(.waterRising, 0)], let x = yesNo(v) { answers.append(NeedAnswer(3, x)) }
        switch loc {
        case "roof", "upper_floor": answers.append(NeedAnswer(4, 1))
        case "ground_floor", "basement", "outdoors", "vehicle", "rubble": answers.append(NeedAnswer(4, 2))
        default: break
        }
        answers = Array(answers.sorted { $0.questionId < $1.questionId }.prefix(3))

        return TriageResult(
            categories: categories, urgency: urgency, vuln: vuln, people: s.people, landmark: s.landmark,
            answers: answers, summary: summary(s), advisories: advisories(s), facts: f, choices: s.choices, hazards: h,
            subjects: s.subjects)
    }

    /// English words for the control room, most dangerous first, cut to the wire limit on a part boundary.
    private static func summary(_ s: TriageState) -> String {
        let f = s.facts
        let h = s.hazards
        let loc = s.choices[.location]
        let multi = s.patients.count > 1
        var parts: [String] = []
        for sub in s.patients {
            let p = multi ? "\(sub.relation?.key ?? "person \(sub.id)") " : ""
            let first = sub.id == s.patients[0].id
            if f[FactKey(.breathing, sub.id)] == .no || (h.contains(.notBreathing) && first) { parts.append("\(p)not breathing") }
            else if f[FactKey(.conscious, sub.id)] == .no || (h.contains(.unconscious) && first) { parts.append("\(p)unresponsive") }
            else if f[FactKey(.heavyBleeding, sub.id)] == .yes { parts.append("\(p)heavy bleeding") }
            else if h.contains(.bleeding) && first && f[FactKey(.heavyBleeding, sub.id)] != .no { parts.append("\(p)bleeding") }
        }
        if h.contains(.snake) { parts.append("snake bite") }
        if h.contains(.labour) { parts.append("in labour") }
        if f[FactKey(.gasSmell, 0)] == .yes { parts.append("gas smell") }
        if f[FactKey(.liveWire, 0)] == .yes || h.contains(.electric) { parts.append("live wire near") }
        switch loc {
        case "rubble": parts.append("under rubble")
        case "roof": parts.append("on roof")
        case "upper_floor":
            switch s.choices[.floor] {
            case "floor_1": parts.append("1 floor up")
            case "floor_2": parts.append("2 floors up")
            case "floor_3plus": parts.append("3+ floors up")
            default: parts.append("upper floor")
            }
        case "ground_floor": parts.append("ground floor")
        case "basement": parts.append("basement")
        case "vehicle": parts.append("in vehicle")
        case "outdoors": parts.append("outdoors")
        default: break
        }
        if h.contains(.trapped) && loc != "rubble" { parts.append("trapped") }
        if f[FactKey(.waterRising, 0)] == .yes { parts.append("water rising") }
        if let d = s.choices[.waterDepth] { parts.append("water \(d.replacingOccurrences(of: "_", with: " "))") }
        if f[FactKey(.buildingUnsafe, 0)] == .yes { parts.append("building unsafe") }
        if f[FactKey(.slopeMoving, 0)] == .yes { parts.append("slope moving") }
        if f[FactKey(.shelterHolding, 0)] == .no { parts.append("shelter failing") }
        if f[FactKey(.roadReachable, 0)] == .no { parts.append("no road access") }

        var out = ""
        for p in parts {
            let next = out.isEmpty ? p : "\(out), \(p)"
            if next.utf8.count > 40 { continue }
            out = next
        }
        return out
    }

    private static func advisories(_ s: TriageState) -> [TriageBank.Advisory] {
        let f = s.facts
        let h = s.hazards
        func any(_ fact: Fact, _ v: Tri) -> Bool { f.contains { $0.key.fact == fact && $0.value == v } }
        var ids: [TriageBank.AdvisoryId] = []
        func add(_ id: TriageBank.AdvisoryId) { if !ids.contains(id) { ids.append(id) } }
        let notBreathing = any(.breathing, .no) || h.contains(.notBreathing)
        if notBreathing { add(.cpr) }
        if !notBreathing && (any(.conscious, .no) || h.contains(.unconscious)) { add(.recovery) }
        if any(.heavyBleeding, .yes) || (h.contains(.bleeding) && !any(.heavyBleeding, .no)) { add(.bleeding) }
        if h.contains(.snake) { add(.snake) }
        if h.contains(.labour) { add(.labour) }
        if h.contains(.trapped) || s.choices[.location] == "rubble" { add(.trapped) }
        if any(.gasSmell, .yes) { add(.gas) }
        if any(.buildingUnsafe, .yes) { add(.unsafeBuilding) }
        if any(.liveWire, .yes) || h.contains(.electric) { add(.electric) }
        if any(.waterRising, .yes) || h.contains(.flood) { add(.flood) }
        if h.contains(.cyclone) || any(.shelterHolding, .no) { add(.cyclone) }
        if h.contains(.landslide) || any(.slopeMoving, .yes) { add(.landslide) }
        return ids.compactMap { TriageBank.advisories[$0] }.sorted { $0.priority > $1.priority }
    }
}
