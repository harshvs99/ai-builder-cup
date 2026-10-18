import Foundation
import RelayCore
import RelayKit

/// Gemma 4 on this phone through `GemmaRunner`: reads a triage answer into facts and dangers (see `TriageBrain`). The model
/// is loaded by the runner on first use, only when a conversation reaches triage. It never writes questions or advice.
struct GemmaTriageBrain: TriageBrain {
    /// Hours since each declared disaster was issued, by type key (the prompt tells the model how old the declaration is).
    var declaredAgesH: @Sendable () -> [String: Int64] = { [:] }

    /// A conversation cannot wait long for the model; the rules' reading is already in hand.
    static let timeoutSeconds = 30.0

    func warmUp() async -> Bool { await GemmaRunner.shared.ensureLoaded().ok }

    func interpret(utterance: String, asked: Ask?, state: TriageState, lang: Lang) async -> Interpretation? {
        let message = TriagePrompt.turn(utterance: utterance, asked: asked, state: state, langHint: lang.tag, issuedAgesH: declaredAgesH())
        // Race the model against the clock; the loser is cancelled (the runner's stream stops with it).
        return await withTaskGroup(of: Interpretation?.self) { group in
            group.addTask {
                let run = await GemmaRunner.shared.runCustom(system: TriagePrompt.system, instruction: message)
                if run.error != nil { return nil }
                return TriagePrompt.parse(run.raw, asked: asked)
            }
            group.addTask {
                try? await Task.sleep(for: .seconds(Self.timeoutSeconds))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}
