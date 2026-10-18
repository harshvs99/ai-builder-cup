import Foundation
import RelayCore

/// The model's two jobs in a triage conversation, done in one call per turn: READ what the person just said (facts, a place,
/// who is hurt, dangers named in passing) and ADVISE which allowed question to ask next. It never writes a question or
/// advice (those are fixed text in `TriageBank`), and code decides what is allowed (`Planner`): a suggestion outside the menu
/// is ignored, a mandatory question cannot be skipped, and its reading is merged with the rules', the more alarming one
/// winning. Port of Android `TriageBrain.kt`.
public protocol TriageBrain: Sendable {
    /// Loads the model if it may be loaded (enough RAM, file present). False = rules only for this conversation.
    func warmUp() async -> Bool

    /// Nil when the model is not available or gave nothing usable: the caller carries on with the rules alone.
    func interpret(utterance: String, asked: Ask?, state: TriageState, lang: Lang) async -> Interpretation?
}

public enum TriagePrompt {
    /// The decision architecture, as the model sees it (verbatim from the Android prompt).
    public static let system = #"""
You are the reading-and-planning brain of an offline emergency triage helper on a phone. A person in a disaster is being asked short questions by voice. You do NOT write questions and you do NOT give advice: fixed texts do that. You do two things and output ONLY one JSON object (no prose, no markdown):

1. READ what the person just said, in any language or code-mix.
2. CHOOSE which question from the MENU to ask next, or say you are done.

Output schema (omit a key when unsupported by the words):
{"answer":"<yes|no|unknown, or an option key when the question was a choice>",
 "facts":{"<fact id>":"yes|no|unknown"},
 "choices":{"<choice>":"<option key>"},
 "hazards":[subset of "NOT_BREATHING","UNCONSCIOUS","BLEEDING","SNAKE","LABOUR","ELECTRIC","FLOOD","TRAPPED","CYCLONE","LANDSLIDE"],
 "patients":[relations of people who need medical help, from "MOTHER","FATHER","WIFE","HUSBAND","SON","DAUGHTER","CHILD","BABY","GRANDMOTHER","GRANDFATHER","BROTHER","SISTER"],
 "people":<headcount who need rescue or help, or 0>,
 "landmark":"<bare English place noun or empty>",
 "next":"<an id from the MENU, or empty>",
 "done":<true when nothing on the MENU would change what rescuers do>}

Reading rules:
- A fact id is the name from the MENU, with @N for a person (conscious@2 = the second person). Put a fact only when the words support it, even if it was not the question asked ("he is not breathing" -> breathing@1 no).
- When several people are named, keep their facts apart: "mother is awake, son is not" -> conscious@1 yes, conscious@2 no. Use the numbering under PEOPLE.
- "answer" is the reply to the question just asked, by meaning: yes / no / unknown ("I don't know", unclear). For a choice, the option key that fits; if none fits, "unknown".
- Never invent a danger. Never assume a disaster that is not declared below.

Planning rules (how to choose "next"):
1. Order of care: life first. Awake and breathing for every hurt person, then bleeding, then the place and the surroundings.
2. Ask about each person separately; do not merge two people into one question.
3. Pick only ids that appear in the MENU, and prefer the ones marked MUST first. Never repeat a question that is already known.
4. Skip a question the person has already answered in passing. Prefer the question whose answer would change what rescuers do.
5. Ask disaster-specific questions only if they are on the MENU: they are there only because the control room declared that disaster. Without a declaration, never bring up floods, quakes, storms or landslides yourself.
6. If the person sounds confused or says they cannot talk, set done to true.

Example:
CASE: patients: 1 your mother; 2 your son. Known: none. Disaster: none declared.
MENU: conscious@1 [MUST] Is your mother awake and answering you? | conscious@2 [MUST] Is your son awake and answering you? | location Where exactly are you?
Question asked: (first description)
Person said: "my mother fell and is not moving, my son is fine but crying, we are on the roof"
Output: {"facts":{"conscious@1":"no"},"choices":{"location":"roof"},"hazards":["UNCONSCIOUS"],"patients":["MOTHER"],"people":2,"landmark":"","next":"breathing@1","done":false}
"""#

    /// The per-turn message: the case so far, the allowed questions, what was asked, what was said.
    public static func turn(utterance: String, asked: Ask?, state: TriageState, langHint: String, issuedAgesH: [String: Int64] = [:]) -> String {
        let patients = state.patients.map { sub -> String in
            let who = sub.relation.map { "your \($0.key)" } ?? (state.patients.count > 1 ? "person \(sub.id)" : "the person")
            return "\(sub.id) \(who)"
        }.joined(separator: "; ")
        var knownParts = state.facts.sorted { $0.key.id < $1.key.id }.map { "\($0.key.id)=\($0.value.key)" }
        knownParts += state.choices.sorted { $0.key.key < $1.key.key }.map { "\($0.key.key)=\($0.value)" }
        let known = knownParts.isEmpty ? "none" : knownParts.joined(separator: ", ")
        var disaster = "none declared"
        if !state.disasters.isEmpty {
            disaster = DisasterType.allCases.filter { state.disasters.contains($0) }.map { d in
                d.key + (issuedAgesH[d.key].map { " (declared \($0) h ago)" } ?? "")
            }.joined(separator: ", ")
            if state.aging { disaster += ". The declaration is more than 12 hours old: the situation may have changed; confirm before relying on it." }
        }
        let menuItems = Planner.menu(state).map { m -> String in
            let q = Planner.text(m.ask, state, .en)
            let opts = m.ask.choice.map { c in " options: " + Planner.options(c, state).joined(separator: "/") } ?? ""
            return "\(m.ask.id)\(m.mandatory ? " [MUST]" : "") \(q)\(opts)"
        }
        let menu = menuItems.isEmpty ? "(empty: you are done)" : menuItems.joined(separator: " | ")
        let q: String
        if let a = asked {
            let opts = a.choice.map { c in " (options: " + Planner.options(c, state).joined(separator: ", ") + ")" } ?? ""
            q = "Question asked: \"\(Planner.text(a, state, .en))\" (\(a.id))\(opts)"
        } else {
            q = "Question asked: (first description)"
        }
        return "CASE: patients: \(patients). Known: \(known). Disaster: \(disaster). Questions asked so far: \(state.asked.count).\n" +
            "MENU: \(menu)\n\(q)\nPerson said (language hint: \(langHint)): \"\(utterance.replacingOccurrences(of: "\"", with: "'"))\"\nOutput:"
    }

    /// Parses the reply; nil when there is no usable JSON. Unknown keys, ids or option keys are ignored, never trusted.
    public static func parse(_ raw: String, asked: Ask?) -> Interpretation? {
        guard let json = GemmaNeedParser.extractJsonObject(raw),
              let data = json.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        var facts: [FactKey: Tri] = [:]
        if let fo = o["facts"] as? [String: Any] {
            for (k, v) in fo {
                guard let key = FactKey.parse(k), let t = Tri.fromKey(v as? String) else { continue }
                facts[key] = t
            }
        }
        var choices: [Choice: String] = [:]
        if let co = o["choices"] as? [String: Any] {
            for (k, v) in co {
                guard let c = Choice.fromKey(k) else { continue }
                let opt = ((v as? String) ?? "").trimmingCharacters(in: .whitespaces).lowercased()
                if c.hasOption(opt) { choices[c] = opt }
            }
        }
        let answer = ((o["answer"] as? String) ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        if let a = asked, let f = a.fact, let t = Tri.fromKey(answer) {
            let key = FactKey(f, a.subject)
            if facts[key] == nil { facts[key] = t }
        }
        if let a = asked, let c = a.choice, c.hasOption(answer), choices[c] == nil { choices[c] = answer }
        var hazards = Set<Hazard>()
        for h in (o["hazards"] as? [Any]) ?? [] {
            let n = (h as? String)?.trimmingCharacters(in: .whitespaces).uppercased()
            if let hz = Hazard.allCases.first(where: { $0.name == n }) { hazards.insert(hz) }
        }
        var patients = Set<Relation>()
        for p in (o["patients"] as? [Any]) ?? [] { if let r = Relation.fromKey(p as? String) { patients.insert(r) } }
        let peopleRaw = (o["people"] as? NSNumber)?.intValue ?? 0
        let people: Int? = (1...255).contains(peopleRaw) ? peopleRaw : nil
        let lm = Utf8.truncate(((o["landmark"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines), 24)
        let landmark: String? = lm.isEmpty ? nil : lm
        let nextRaw = ((o["next"] as? String) ?? "").trimmingCharacters(in: .whitespaces)
        let next: String? = (!nextRaw.isEmpty && Ask.parse(nextRaw) != nil) ? nextRaw : nil
        let done = (o["done"] as? Bool) ?? false
        if facts.isEmpty && choices.isEmpty && hazards.isEmpty && patients.isEmpty && people == nil && landmark == nil && next == nil && !done { return nil }
        return Interpretation(facts: facts, hazards: hazards, people: people, landmark: landmark, choices: choices, patients: patients, next: next, done: done)
    }
}
