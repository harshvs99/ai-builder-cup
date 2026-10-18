import Foundation

/// One question the phone can ask: a yes/no `fact` about `subject`, or a `choice` (subject 0).
public struct Ask: Equatable, Hashable, Sendable {
    public let fact: Fact?
    public let choice: Choice?
    public let subject: Int

    private init(fact: Fact?, choice: Choice?, subject: Int) { self.fact = fact; self.choice = choice; self.subject = subject }

    public static func fact(_ f: Fact, _ subject: Int = 1) -> Ask { Ask(fact: f, choice: nil, subject: f.scope == .scene ? 0 : subject) }
    public static func choice(_ c: Choice) -> Ask { Ask(fact: nil, choice: c, subject: 0) }

    /// The id in the model's menu and replies: `conscious@2`, `water_rising`, `location`.
    public var id: String {
        if let f = fact { return FactKey(f, f.scope == .person ? max(subject, 1) : 0).id }
        return choice!.key
    }

    public static func parse(_ id: String?) -> Ask? {
        guard let id else { return nil }
        let raw = id.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if let c = Choice.fromKey(raw) { return .choice(c) }
        guard let k = FactKey.parse(raw) else { return nil }
        return .fact(k.fact, k.subject)
    }
}

/// A question that is allowed right now. `mandatory` ones cannot be skipped or reordered behind optional ones.
public struct MenuItem: Equatable, Sendable {
    public let ask: Ask
    public let priority: Int
    public let mandatory: Bool
}

/// The decision architecture: from the case so far, which questions may be asked now, and which one goes next.
///
/// Code decides what is ALLOWED (the `menu`): disaster-specific questions only inside the control room's freshness window,
/// per-person questions for every patient, a question budget, no repeats, and "stop and give first aid" when someone is not
/// breathing. The model, when there is one, decides the ORDER among the allowed non-mandatory questions and may say it has
/// heard enough (`pick`); it can never add a question that is not on the menu or skip a mandatory one.
public enum Planner {
    private struct Spec {
        var fact: Fact?
        var choice: Choice?
        var priority: Int
        var mandatory: Bool
        var applies: @Sendable (TriageState, Int) -> Bool
    }

    private static func f(_ fact: Fact, _ priority: Int, mandatory: Bool = false, _ applies: @escaping @Sendable (TriageState, Int) -> Bool) -> Spec {
        Spec(fact: fact, choice: nil, priority: priority, mandatory: mandatory, applies: applies)
    }
    private static func c(_ choice: Choice, _ priority: Int, mandatory: Bool = false, _ applies: @escaping @Sendable (TriageState, Int) -> Bool) -> Spec {
        Spec(fact: nil, choice: choice, priority: priority, mandatory: mandatory, applies: applies)
    }

    private static let specs: [Spec] = [
        c(.patients, 110, mandatory: true) { s, _ in s.medical && s.subjects.isEmpty && s.peopleHint >= 2 },
        f(.conscious, 100, mandatory: true) { s, _ in s.medical },
        f(.breathing, 95, mandatory: true) { s, sub in s.medical && s.facts[FactKey(.conscious, sub)] == .no },
        f(.heavyBleeding, 80) { s, sub in s.medical && s.facts[FactKey(.breathing, sub)] != .no },
        // Where the person is: asked of anyone who needs getting out, in any situation, worded for the disaster when one is known.
        c(.location, 93) { s, _ in s.rescue },
        c(.floor, 92) { s, _ in s.choices[.location] == "upper_floor" },
        f(.anyoneHurt, 85) { s, _ in s.rescue && !s.medical },
        f(.roadReachable, 30) { s, _ in s.medical },
        // Disaster-specific: the gate in `menu` keeps these off unless the control room declared the disaster.
        f(.gasSmell, 91) { s, _ in s.rescue || s.medical },
        f(.liveWire, 90) { s, _ in (s.rescue || s.medical) && !s.hazards.contains(.electric) },
        f(.slopeMoving, 90) { s, _ in s.rescue },
        f(.buildingUnsafe, 89) { s, _ in s.rescue },
        f(.waterRising, 88) { s, _ in s.rescue },
        f(.shelterHolding, 85) { s, _ in s.rescue },
        c(.waterDepth, 84) { s, _ in s.rescue },
        f(.stillShaking, 60) { s, _ in s.rescue },
    ]

    /// Questions in one conversation: 5 for one patient (6 when getting out is part of it), 3 more for each further patient, never above 12.
    public static func budget(_ s: TriageState) -> Int { min(12, 5 + (s.rescue ? 1 : 0) + 3 * (s.patients.count - 1)) }

    private static func allowedBy(_ disasters: Set<DisasterType>, _ s: TriageState) -> Bool {
        disasters.isEmpty || !disasters.isDisjoint(with: s.disasters)
    }

    public static func menu(_ s: TriageState) -> [MenuItem] {
        if s.asked.count >= budget(s) { return [] }
        // Someone is not breathing: the next minute belongs to chest pushes, not questions.
        if s.facts.contains(where: { $0.key.fact == .breathing && $0.value == .no }) { return [] }
        var out: [MenuItem] = []
        for spec in specs {
            if let fact = spec.fact {
                if !allowedBy(fact.disasters, s) { continue }
                let subjects = fact.scope == .person ? s.patients.map(\.id) : [0]
                for sub in subjects {
                    let ask = Ask.fact(fact, sub)
                    if s.asked.contains(ask.id) || s.facts[FactKey(fact, sub)] != nil { continue }
                    if spec.applies(s, sub) { out.append(MenuItem(ask: ask, priority: spec.priority, mandatory: spec.mandatory)) }
                }
            }
            if let choice = spec.choice {
                if !allowedBy(choice.disasters, s) { continue }
                if s.asked.contains(choice.key) || s.choices[choice] != nil { continue }
                if spec.applies(s, 0) { out.append(MenuItem(ask: .choice(choice), priority: spec.priority, mandatory: spec.mandatory)) }
            }
        }
        // Highest priority first; across people the same question goes to each in turn (everyone awake? then everyone breathing?).
        return out.enumerated().sorted { a, b in
            if a.element.mandatory != b.element.mandatory { return a.element.mandatory }
            if a.element.priority != b.element.priority { return a.element.priority > b.element.priority }
            if a.element.ask.subject != b.element.ask.subject { return a.element.ask.subject < b.element.ask.subject }
            return a.offset < b.offset
        }.map(\.element)
    }

    /// The options offered for a choice in this situation (and the order they are shown).
    public static func options(_ choice: Choice, _ s: TriageState) -> [String] {
        switch choice {
        case .patients: return ["one", "two", "three_plus"]
        case .floor: return ["floor_1", "floor_2", "floor_3plus"]
        case .waterDepth: return ["ankle", "knee", "waist", "chest", "over_head"]
        case .location:
            if s.disasters.contains(.flood) { return ["roof", "upper_floor", "ground_floor", "vehicle", "outdoors"] }
            if s.disasters.contains(.earthquake) { return ["rubble", "upper_floor", "ground_floor", "outdoors", "vehicle"] }
            if s.disasters.contains(.landslide) { return ["rubble", "ground_floor", "outdoors", "vehicle"] }
            if s.disasters.contains(.cyclone) { return ["roof", "upper_floor", "ground_floor", "outdoors", "vehicle"] }
            return ["roof", "upper_floor", "ground_floor", "basement", "outdoors", "vehicle", "rubble"]
        }
    }

    /// The next question, or nil to end the conversation. `suggestion` / `done` are the model's view: an id from the menu
    /// is used unless a mandatory question is waiting (and then only a mandatory one is accepted); "done" is honoured only
    /// when nothing mandatory is waiting. With no usable suggestion the menu's first item goes.
    public static func pick(_ s: TriageState, suggestion: String? = nil, done: Bool = false) -> Ask? {
        let menu = menu(s)
        if menu.isEmpty { return nil }
        let mandatory = menu.filter(\.mandatory)
        if mandatory.isEmpty && done { return nil }
        let pool = mandatory.isEmpty ? menu : mandatory
        let wanted = Ask.parse(suggestion)
        return (pool.first { $0.ask == wanted } ?? pool[0]).ask
    }

    /// The question as the person reads it, in `lang`.
    public static func text(_ ask: Ask, _ s: TriageState, _ lang: Lang) -> String {
        if let f = ask.fact { return TriageBank.question(f, subject: ask.subject, subjects: s.patients, lang) }
        return TriageBank.question(ask.choice!, lang)
    }
}
