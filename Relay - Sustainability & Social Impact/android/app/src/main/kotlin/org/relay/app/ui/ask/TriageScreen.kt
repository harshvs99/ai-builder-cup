package org.relay.app.ui.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.relay.app.DisasterStore
import org.relay.app.LocalSpeaker
import org.relay.app.MeshRepository
import org.relay.app.VoiceInput
import org.relay.app.VoiceState
import org.relay.app.gemma.GemmaRunner
import org.relay.app.gemma.GemmaTriageBrain
import org.relay.app.gemma.TriageBrain
import org.relay.app.location.LocationProvider
import org.relay.app.location.MapPack
import org.relay.app.rememberPermissions
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SpeakButton
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings
import org.relay.core.payload.Geo
import org.relay.core.payload.Lang
import org.relay.core.triage.Tri
import org.relay.core.triage.TriageBank
import org.relay.core.triage.TriageResult

/** A yes/no answer spoken in a conversation ends after this much quiet; a story told at New request waits longer. */
private const val ANSWER_QUIET_MS = 3_500L

/**
 * Step between New request and Check and send for medical and rescue requests: the phone asks a few short questions out
 * loud, listens for each answer, then reads out what to do while the request goes to the control room. Questions and
 * advice are fixed text ([TriageBank]); the on-device model, when this phone can run it, only helps understand the answers.
 */
@Composable
fun TriageScreen(nav: RelayNav, flowOwner: ViewModelStoreOwner) {
    val askVm: AskViewModel = viewModel(viewModelStoreOwner = flowOwner)
    val vm: TriageViewModel = viewModel(viewModelStoreOwner = flowOwner)
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val ask by askVm.state.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val lang = settings.effectiveLanguage
    val speaker = LocalSpeaker.current
    val perms = rememberPermissions()

    // The model is only touched here, after the router sent the request to triage; the runner loads it on demand.
    val store = remember { DisasterStore.get(ctx) }
    val brain: TriageBrain? = remember { GemmaRunner.get(ctx).takeIf { it.supported }?.let { r -> GemmaTriageBrain(r) { store.agesHours() } } }
    val voice = remember { VoiceInput(ctx, endAfterSpeechMs = ANSWER_QUIET_MS) { heard -> vm.heard(heard) } }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    val phase by rememberUpdatedState(ui.phase)

    fun listen() {
        if (phase != TriagePhase.ASKING) return
        perms.ensureMicrophone { ok -> if (ok && phase == TriagePhase.ASKING) voice.start(lang) else if (!ok) voice.fail("microphone permission denied") }
    }

    LaunchedEffect(Unit) {
        // Where the person is (the fix New request took, if any): which area-limited declarations apply, and what the
        // offline map shows around them. No fix = every declaration counts and there is no map advice.
        val fix = LocationProvider.fix.value
        val here = fix?.let { Geo.toE5(it.lat) to Geo.toE5(it.lon) }
        vm.begin(
            ask.transcript, ask.draft.categories, lang, brain, ask.draft.people, store.active(here = here),
            map = MapPack.around(ctx, fix),
        )
    }

    // Say the question (or the advice), then listen. No voice for this language: listen at once, the text is on screen.
    LaunchedEffect(ui.phase, ui.ask?.id, ui.unclear) {
        voice.cancel()
        speaker?.stop()
        when (ui.phase) {
            TriagePhase.ASKING -> {
                val line = if (ui.unclear) s.ask.triageUnclear + " " + ui.questionText else ui.questionText
                if (speaker?.speakAll(listOf(line), lang) { listen() } != true) listen()
            }
            TriagePhase.DONE -> ui.result?.let { r -> speaker?.speakAll(adviceLines(r, lang), lang) }
            else -> {}
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { voice.cancel(); speaker?.stop() }
    DisposableEffect(Unit) { onDispose { voice.destroy(); speaker?.stop() } }

    TriageContent(
        s = s, ui = ui, listening = voiceState is VoiceState.Listening, partial = (voiceState as? VoiceState.Listening)?.partial.orEmpty(),
        voiceProblem = voiceState is VoiceState.Problem,
        onBack = nav::back,
        onAnswer = { a, label -> speaker?.stop(); voice.cancel(); vm.tap(a, label) },
        onOption = { o -> speaker?.stop(); voice.cancel(); vm.tapOption(o, s.ask.triageDontKnow) },
        onMic = { speaker?.stop(); listen() },
        onSkip = { speaker?.stop(); voice.cancel(); vm.finishNow() },
        onHearAdvice = { ui.result?.let { r -> speaker?.speakAll(adviceLines(r, lang), lang) } },
        onReview = {
            speaker?.stop()
            ui.result?.let { askVm.applyTriage(it) }
            nav.toCheck()
        },
    )
}

/** Titles and steps in order, then the note about what happens to the request. */
internal fun adviceLines(r: TriageResult, lang: Lang): List<String> =
    r.advisories.take(3).flatMap { a -> listOf(a.title(lang)) + a.lines(lang) } + r.places.take(MAX_PLACES).map { it.text(lang) } +
        TriageBank.sentNote(lang)

/** Place advice after the first aid: two at most, so the steps that keep someone alive are not buried. */
internal const val MAX_PLACES = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TriageContent(
    s: org.relay.app.ui.i18n.Strings,
    ui: TriageUi,
    listening: Boolean,
    partial: String,
    voiceProblem: Boolean,
    onBack: () -> Unit,
    onAnswer: (Tri, String) -> Unit,
    onOption: (ChoiceOption?) -> Unit,
    onMic: () -> Unit,
    onSkip: () -> Unit,
    onHearAdvice: () -> Unit,
    onReview: () -> Unit,
) {
    val a = s.ask
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(a.triageTitle, onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val brainText = when (ui.brain) {
                BrainStatus.READY -> a.triageModelReady
                BrainStatus.LOADING -> a.triageModelLoading
                BrainStatus.RULES_ONLY -> a.triageModelOff
                BrainStatus.NONE -> null
            }
            ui.disasterNote?.let { Text(a.triageDeclared(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            brainText?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            // What was said so far, oldest first, without the question on screen now.
            val history = if (ui.phase == TriagePhase.ASKING) ui.lines.dropLast(1) else ui.lines
            history.forEach { line ->
                Text(
                    if (line.fromPerson) "“${line.text}”" else line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(start = if (line.fromPerson) 24.dp else 0.dp),
                )
            }

            when (ui.phase) {
                TriagePhase.ASKING -> SectionCard {
                    Text(ui.questionText, style = MaterialTheme.typography.headlineSmall)
                    if (ui.unclear) Text(a.triageUnclear, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.GraphicEq, contentDescription = null,
                            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (listening && partial.isNotBlank()) "“$partial”" else if (listening) a.listening else a.triageAnswerHint,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (voiceProblem) Text(a.didntCatch, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                TriagePhase.THINKING -> Text(a.triageThinking, style = MaterialTheme.typography.titleMedium)
                TriagePhase.DONE -> ui.result?.let { r -> Result(s, r, onHearAdvice) }
                TriagePhase.IDLE -> {}
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ui.phase == TriagePhase.ASKING) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.options.isEmpty()) {
                        SimpleChip(a.triageYes, false) { onAnswer(Tri.YES, a.triageYes) }
                        SimpleChip(a.triageNo, false) { onAnswer(Tri.NO, a.triageNo) }
                        SimpleChip(a.triageDontKnow, false) { onAnswer(Tri.UNKNOWN, a.triageDontKnow) }
                    } else {
                        // A choice question: one button per option that fits this situation, then "don't know".
                        ui.options.forEach { o -> SimpleChip(o.label, false) { onOption(o) } }
                        SimpleChip(a.triageDontKnow, false) { onOption(null) }
                    }
                    if (!listening) SimpleChip(a.triageStartMic, false) { onMic() }
                }
            }
            if (ui.phase == TriagePhase.DONE) {
                PillButton(a.triageReview, onReview)
            } else if (ui.phase != TriagePhase.IDLE) {
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(a.triageSkip) }
            }
        }
    }
}

@Composable
private fun Result(s: org.relay.app.ui.i18n.Strings, r: TriageResult, onHear: () -> Unit) {
    val lang = s.lang
    if (r.critical) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(s.ask.triageCritical, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (r.advisories.isNotEmpty() || r.places.isNotEmpty()) {
        Text(s.ask.triageAdviceTitle, style = MaterialTheme.typography.titleLarge)
        SpeakButton(s.ask.hearItAgain, onHear)
        r.advisories.take(3).forEach { adv ->
            SectionCard(title = adv.title(lang)) {
                adv.lines(lang).forEachIndexed { i, line -> Text("${i + 1}. $line", style = MaterialTheme.typography.bodyLarge) }
            }
        }
        if (r.places.isNotEmpty()) {
            SectionCard(title = s.ask.triageNearbyTitle) {
                r.places.take(MAX_PLACES).forEach { p -> Text(p.text(lang), style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
    Text(TriageBank.sentNote(lang), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
