package org.relay.app.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.relay.app.gemma.TriageBrain
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.triage.Ask
import org.relay.core.triage.DisasterGate
import org.relay.core.triage.FactKey
import org.relay.core.triage.Interpretation
import org.relay.core.triage.MapContext
import org.relay.core.triage.Planner
import org.relay.core.triage.RuleInterpreter
import org.relay.core.triage.Tri
import org.relay.core.triage.TriageBank
import org.relay.core.triage.TriageEngine
import org.relay.core.triage.TriageMerge
import org.relay.core.triage.TriageResult
import org.relay.core.triage.TriageState

/** One line of the conversation as shown on screen. */
data class ChatLine(val fromPerson: Boolean, val text: String)

enum class TriagePhase { IDLE, ASKING, THINKING, DONE }

/** Whether the on-device model is helping read answers. Rules always run; this only says if the model is added to them. */
enum class BrainStatus { NONE, LOADING, READY, RULES_ONLY }

/** One button of a choice question. */
data class ChoiceOption(val key: String, val label: String)

data class TriageUi(
    val phase: TriagePhase = TriagePhase.IDLE,
    /** The question on screen; null when none. */
    val ask: Ask? = null,
    val questionText: String = "",
    /** The buttons of a choice question; empty for a yes/no question. */
    val options: List<ChoiceOption> = emptyList(),
    val lines: List<ChatLine> = emptyList(),
    /** The last spoken answer was not understood: the screen asks again, once. */
    val unclear: Boolean = false,
    val result: TriageResult? = null,
    val brain: BrainStatus = BrainStatus.NONE,
    /** Disasters in play (declared by the control room within 24 h); shown so the person knows why a question was asked. */
    val disasterNote: String? = null,
)

/**
 * The triage conversation. Android-free: the screen owns the microphone and the voice, and calls [heard] with what the
 * person said or [tap] / [tapOption] with a button press. Questions and advice come from `TriageBank` only; [brain] (the
 * on-device model) helps read free speech and advises which allowed question goes next, and every turn also runs the
 * rules, whichever is more alarmed winning (see `TriageMerge`, `Planner`).
 */
class TriageViewModel : ViewModel() {
    private val _ui = MutableStateFlow(TriageUi())
    val ui: StateFlow<TriageUi> = _ui.asStateFlow()

    private var engine: TriageState? = null
    private var lang = Lang.EN
    private var startedWith: String? = null
    private var brain: TriageBrain? = null
    private var unclearCount = 0
    private var job: Job? = null
    private var session = 0
    /** The model's last view of what to ask next; used once, by the next [advance]. */
    private var lastHeard: Interpretation? = null

    /**
     * Start from the first thing the person said. Calling again with the same text keeps the conversation (coming back
     * from Check and send); different text starts over. [disasters] is what the control room has declared and is still
     * inside the freshness window: nothing disaster-specific is asked without it.
     */
    fun begin(
        text: String, categories: Collection<Category>, lang: Lang, brain: TriageBrain?,
        peopleHint: Int = 1, disasters: DisasterGate.Active = DisasterGate.Active.NONE, map: MapContext? = null,
    ) {
        if (startedWith == text && _ui.value.phase != TriagePhase.IDLE) return
        job?.cancel()
        val mySession = ++session
        startedWith = text
        this.lang = lang
        this.brain = brain
        unclearCount = 0
        lastHeard = null
        val s0 = TriageEngine.start(text, categories, lang, peopleHint, disasters, map)
        engine = s0
        _ui.value = TriageUi(
            lines = if (text.isBlank()) emptyList() else listOf(ChatLine(true, text)),
            brain = if (brain == null) BrainStatus.NONE else BrainStatus.LOADING,
            disasterNote = disasters.types.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.key },
        )
        advance()
        if (brain != null) viewModelScope.launch {
            val ok = try { brain.warmUp() } catch (_: Exception) { false }
            if (mySession != session) return@launch
            _ui.update { it.copy(brain = if (ok) BrainStatus.READY else BrainStatus.RULES_ONLY) }
            // The model reads the first description too: who is hurt, where they are. Folded in only if nobody has answered yet.
            if (ok && text.isNotBlank()) {
                val first = withTimeoutOrNull(MODEL_TIMEOUT_MS) { try { brain.interpret(text, null, s0, lang) } catch (_: Exception) { null } }
                if (first != null && mySession == session && _ui.value.phase == TriagePhase.ASKING && engine?.asked?.isEmpty() == true) {
                    engine = TriageEngine.absorb(engine!!, first)
                    lastHeard = first
                    val cur = _ui.value.ask
                    // The question on screen may no longer be on the menu (the model says the son is fine): replace it.
                    if (cur == null || Planner.menu(engine!!).none { it.ask == cur }) advance()
                }
            }
        }
    }

    /** The person spoke an answer. */
    fun heard(text: String) {
        val s = engine ?: return
        val ask = _ui.value.ask
        if (_ui.value.phase != TriagePhase.ASKING || ask == null || text.isBlank()) return
        _ui.update { it.copy(phase = TriagePhase.THINKING, unclear = false, lines = it.lines + ChatLine(true, text)) }
        val mySession = session
        job = viewModelScope.launch {
            val rules = RuleInterpreter.interpret(text, ask, s)
            val model: Interpretation? = if (_ui.value.brain == BrainStatus.READY) {
                withTimeoutOrNull(MODEL_TIMEOUT_MS) { try { brain?.interpret(text, ask, s, lang) } catch (_: Exception) { null } }
            } else null
            if (mySession != session) return@launch
            val merged = TriageMerge.merge(rules, model)
            val fact = ask.fact
            val choice = ask.choice
            val tri = fact?.let { merged.facts[FactKey(it, ask.subject)] }
            val option = choice?.let { merged.choices[it]?.takeIf { o -> o in Planner.options(choice, s) } }
            if (tri == null && option == null && ++unclearCount < 2) {
                // Not an answer: keep what else was said (a danger named in passing), ask the same question once more.
                engine = TriageEngine.absorb(s, merged.copy(next = null, done = false))
                _ui.update { it.copy(phase = TriagePhase.ASKING, unclear = true) }
            } else {
                unclearCount = 0
                lastHeard = merged
                engine = when {
                    choice != null && option != null -> TriageEngine.recordChoice(s, choice, option, merged)
                    fact != null -> TriageEngine.record(s, ask, tri ?: Tri.UNKNOWN, merged)
                    else -> TriageEngine.skip(TriageEngine.absorb(s, merged), ask)
                }
                advance()
            }
        }
    }

    /** A button press on a yes/no question: Yes, No or Don't know. */
    fun tap(a: Tri, label: String) {
        val s = engine ?: return
        val ask = _ui.value.ask ?: return
        if (_ui.value.phase != TriagePhase.ASKING || ask.fact == null) return
        job?.cancel()
        unclearCount = 0
        lastHeard = null
        _ui.update { it.copy(unclear = false, lines = it.lines + ChatLine(true, label)) }
        engine = TriageEngine.record(s, ask, a)
        advance()
    }

    /** A button press on a choice question; [option] null = "Don't know". */
    fun tapOption(option: ChoiceOption?, dontKnowLabel: String) {
        val s = engine ?: return
        val ask = _ui.value.ask ?: return
        val choice = ask.choice ?: return
        if (_ui.value.phase != TriagePhase.ASKING) return
        job?.cancel()
        unclearCount = 0
        lastHeard = null
        _ui.update { it.copy(unclear = false, lines = it.lines + ChatLine(true, option?.label ?: dontKnowLabel)) }
        engine = if (option != null) TriageEngine.recordChoice(s, choice, option.key) else TriageEngine.skip(s, ask)
        advance()
    }

    /** "Skip questions": go straight to the advice with what is known. */
    fun finishNow() {
        job?.cancel()
        finish()
    }

    private fun advance() {
        val s = engine ?: return
        val ask = TriageEngine.next(s, lastHeard)
        lastHeard = null
        if (ask == null) { finish(); return }
        val text = Planner.text(ask, s, lang)
        val options = ask.choice?.let { c -> Planner.options(c, s).map { ChoiceOption(it, TriageBank.optionText(it, lang)) } }.orEmpty()
        _ui.update {
            it.copy(
                phase = TriagePhase.ASKING, ask = ask, questionText = text, options = options, unclear = false,
                lines = it.lines + ChatLine(false, text),
            )
        }
    }

    private fun finish() {
        val s = engine ?: return
        _ui.update { it.copy(phase = TriagePhase.DONE, ask = null, questionText = "", options = emptyList(), unclear = false, result = TriageEngine.result(s)) }
    }

    companion object {
        /** A conversation cannot wait long for the model; the rules' reading is already in hand. */
        const val MODEL_TIMEOUT_MS = 30_000L
    }
}
