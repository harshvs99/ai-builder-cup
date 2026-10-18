package org.relay.app.ui.ask

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.relay.app.ui.ask.AskLogic.pruned
import org.relay.core.intake.IntakeEngine
import org.relay.core.intake.IntakeField
import org.relay.core.intake.RuleIntake
import org.relay.core.payload.Category
import org.relay.core.payload.Geo
import org.relay.core.payload.Lang
import org.relay.core.payload.Need
import org.relay.core.payload.VulnFlag

data class AskState(
    val draft: AskDraft = AskDraft(),
    /** What was heard or typed; the card shows it in quotes (voice) or in the text field (typing). */
    val transcript: String = "",
    /** The card is a text field rather than the listening card. */
    val typing: Boolean = false,
)

/**
 * State shared by New request and Check and send (scoped to the `askflow` graph entry, so it lives from the first
 * ask to Send). Android-free: the screens own the microphone, location and the actual send, and feed results in.
 * [engine] is injectable so a model-backed engine can replace [RuleIntake] without touching the screens.
 */
class AskViewModel(private val engine: IntakeEngine = RuleIntake) : ViewModel() {
    private val _state = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = _state.asStateFlow()

    private var prefilled = false
    private var lastLang = Lang.EN

    /** Voice starts by itself only the first time the screen shows (not when coming back from Check). */
    var autoStartHandled = false

    /** Heard (partial or final) or typed text: re-run extraction and fold it into the draft. */
    fun onTranscript(text: String, lang: Lang) {
        lastLang = lang
        _state.update { s ->
            val intake = engine.extract(text, lang)
            s.copy(transcript = text, draft = AskLogic.applyIntake(s.draft, intake))
        }
    }

    fun setTyping(typing: Boolean) = _state.update { it.copy(typing = typing) }

    /** Add to request: start from an existing request's category and landmark. Applies once. */
    fun prefill(category: Category, landmark: String) {
        if (prefilled) return
        prefilled = true
        _state.update { s ->
            val d = AskLogic.setCategory(s.draft, category).let {
                if (landmark.isBlank()) it else AskLogic.setLandmark(it, landmark)
            }
            s.copy(draft = d)
        }
    }

    private fun edit(f: (AskDraft) -> AskDraft) = _state.update { it.copy(draft = f(it.draft)) }

    /** A tap on a category chip sets it; tapping the chosen one again hands the decision back to extraction. */
    fun toggleCategory(c: Category) = _state.update { s ->
        val d = s.draft
        if (d.category == c && IntakeField.CATEGORY in d.edited) {
            val cleared = AskLogic.setCategory(d, null)
            s.copy(draft = AskLogic.applyIntake(cleared, engine.extract(s.transcript, lastLang)))
        } else {
            s.copy(draft = AskLogic.setCategory(d, c))
        }
    }

    fun setCategory(c: Category) = edit { AskLogic.setCategory(it, c) }
    fun setItem(text: String) = edit { AskLogic.setItem(it, text) }
    fun setPeople(n: Int) = edit { AskLogic.setPeople(it, n) }
    fun toggleFlag(f: VulnFlag) = edit { AskLogic.toggleFlag(it, f) }
    fun setLandmark(text: String) = edit { AskLogic.setLandmark(it, text) }
    fun setSummary(text: String) = edit { AskLogic.setSummary(it, text) }
    fun setUrgency(u: Int) = edit { AskLogic.setUrgency(it, u) }
    fun setAnswer(questionId: Int, value: Int) = edit { AskLogic.setAnswer(it, questionId, value) }

    /**
     * The wire NEED for the current draft. [fix] is (lat, lon, loc_acc) or null for "no location" (zeros, loc_acc 0).
     * The caller submits it through MeshRepository.
     */
    fun buildNeed(
        msgId: ByteArray, originNode: ByteArray, createdAtSec: Long, lang: Lang, fix: Triple<Double, Double, Int>?,
    ): Need {
        val d = _state.value.draft.pruned()
        return Need(
            msgId = msgId, originNode = originNode, createdAt = createdAtSec,
            category = d.category ?: Category.OTHER, urgency = d.urgency, people = d.people,
            vulnFlags = VulnFlag.toMask(d.vulnFlags),
            latE5 = fix?.let { Geo.toE5(it.first) } ?: 0, lonE5 = fix?.let { Geo.toE5(it.second) } ?: 0,
            locAcc = fix?.third ?: 0, lang = lang,
            item = AskLogic.wireItem(d), landmark = d.landmark.trim(), summary = d.summary.trim(),
            answers = AskLogic.wireAnswers(d),
        )
    }
}
