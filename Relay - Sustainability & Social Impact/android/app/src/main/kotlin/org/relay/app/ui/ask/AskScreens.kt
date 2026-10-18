package org.relay.app.ui.ask

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.relay.app.LocalSpeaker
import org.relay.app.MeshRepository
import org.relay.app.VoiceInput
import org.relay.app.VoiceState
import org.relay.app.rememberPermissions
import org.relay.app.ui.AskMode
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.CheckChip
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.RelayPreview
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SpeakButton
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.components.categoryIcon
import org.relay.app.ui.i18n.AppLanguages
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.Strings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

/** What the listening card shows about the microphone. Built from [VoiceState] with plain-words problems. */
sealed interface VoiceUi {
    data object Paused : VoiceUi
    data class Listening(val level: Float = 0f) : VoiceUi
    /** [message] is plain words; [action] (label to callback) is set when a settings screen can fix it. */
    data class Problem(val message: String, val action: Pair<String, () -> Unit>? = null) : VoiceUi
}

// ================================================================ New request (step 1 of 3)

@Composable
fun AskScreen(nav: RelayNav, mode: AskMode, prefillFrom: String?, flowOwner: ViewModelStoreOwner) {
    val vm: AskViewModel = viewModel(viewModelStoreOwner = flowOwner)
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val myNeeds by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    val lang = settings.language
    val langNow by rememberUpdatedState(lang)
    val perms = rememberPermissions()
    val speaker = LocalSpeaker.current

    val voice = remember { VoiceInput(ctx) { heard -> vm.onTranscript(heard, langNow) } }
    val voiceState by voice.state.collectAsStateWithLifecycle()
    var doneRequested by remember { mutableStateOf(false) }

    fun startVoice() {
        perms.ensureMicrophone { ok ->
            if (ok) voice.start(langNow) else voice.fail("microphone permission denied", offerAppSettings = true)
        }
    }

    // First show: prefill (Add to request), then voice or text mode. Coming back from Check starts nothing.
    LaunchedEffect(Unit) {
        prefillFrom?.let { id ->
            myNeeds.firstOrNull { it.msgIdHex == id }?.let { vm.prefill(it.category, it.landmark) }
        }
        if (!vm.autoStartHandled) {
            vm.autoStartHandled = true
            if (mode == AskMode.TEXT) vm.setTyping(true) else startVoice()
        }
    }
    // Partial results feed extraction as they arrive.
    val partial = (voiceState as? VoiceState.Listening)?.partial.orEmpty()
    LaunchedEffect(partial) { if (partial.isNotBlank()) vm.onTranscript(partial, langNow) }
    // A voice problem leaves the person on the text field, never on a dead end.
    LaunchedEffect(voiceState is VoiceState.Problem) { if (voiceState is VoiceState.Problem) vm.setTyping(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { voice.cancel() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { voice.destroy() } }

    // "Done, check it": let the recogniser deliver its last words, then move on.
    LaunchedEffect(doneRequested) {
        if (!doneRequested) return@LaunchedEffect
        if (voiceState is VoiceState.Listening) {
            voice.stop()
            withTimeoutOrNull(2_000) { voice.state.first { it !is VoiceState.Listening } }
        }
        voice.cancel()
        doneRequested = false
        nav.toCheck()
    }

    val voiceUi: VoiceUi = when (val v = voiceState) {
        is VoiceState.Listening -> VoiceUi.Listening(v.level)
        is VoiceState.Problem -> when {
            v.offerVoiceSettings -> VoiceUi.Problem(
                s.ask.voicePackMissing(AppLanguages.of(lang).englishName),
                s.ask.openSettings to { VoiceInput.openVoiceSettings(ctx) },
            )
            v.offerAppSettings -> VoiceUi.Problem(s.ask.micDenied, s.ask.openSettings to { perms.openAppSettings() })
            // SpeechLogic's "didn't catch that" is the common case; anything else is a plain "not working".
            v.text.contains("catch", ignoreCase = true) -> VoiceUi.Problem(s.ask.didntCatch)
            else -> VoiceUi.Problem(s.ask.voiceUnavailable)
        }
        else -> VoiceUi.Paused
    }

    AskContent(
        s = s, lang = lang, state = state, voice = voiceUi,
        onBack = nav::back,
        onHear = { if (speaker?.speak(s.ask.headline, lang) != true) Toast.makeText(ctx, s.ask.noVoiceInstalled, Toast.LENGTH_SHORT).show() },
        onTranscript = { vm.onTranscript(it, lang) },
        onToggleCategory = vm::toggleCategory,
        onStop = { voice.stop() },
        onResume = { startVoice() },
        onDone = { doneRequested = true },
        onTypeInstead = { voice.cancel(); vm.setTyping(true) },
        onUseVoice = { vm.setTyping(false); startVoice() },
        busy = doneRequested,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskContent(
    s: Strings,
    lang: Lang,
    state: AskState,
    voice: VoiceUi,
    onBack: () -> Unit,
    onHear: () -> Unit,
    onTranscript: (String) -> Unit,
    onToggleCategory: (Category) -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onDone: () -> Unit,
    onTypeInstead: () -> Unit,
    onUseVoice: () -> Unit,
    busy: Boolean = false,
) {
    val draft = state.draft
    val canContinue = AskLogic.hasContent(draft, state.transcript) && !busy
    var showMore by rememberSaveable { mutableStateOf(false) }
    val listening = voice is VoiceUi.Listening

    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(s.ask.newRequestTitle, onBack = onBack, stepText = s.ask.stepCounter(1, 3))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.ask.headline, style = MaterialTheme.typography.headlineSmall)
                SpeakButton(s.ask.hearItAgain, onHear)
            }

            SectionCard {
                if (state.typing) {
                    OutlinedTextField(
                        value = state.transcript,
                        onValueChange = onTranscript,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(s.ask.typeHint) },
                        placeholder = { Text(s.ask.typePlaceholder) },
                        minLines = 3,
                        maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.GraphicEq, contentDescription = null,
                            tint = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (listening) s.ask.listening else s.ask.paused,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (listening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            AppLanguages.of(lang).nativeName, style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.transcript.isBlank()) {
                        Text(s.ask.sayItPrompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("“${state.transcript}”", style = MaterialTheme.typography.titleLarge)
                    }
                    if (voice is VoiceUi.Problem) {
                        Text(voice.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        voice.action?.let { (label, go) -> TextButton(onClick = go) { Text(label) } }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(s.ask.understoodSoFar, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val chips = AskLogic.understood(s.common, s.ask::nearLandmark, draft)
                if (chips.isEmpty()) {
                    Text(s.ask.nothingYet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        chips.forEach { CheckChip(it) }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.ask.orTapWhat, style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AskLogic.mainCategories.forEach { c ->
                        CategoryChip(s.common.categoryName(c), c, draft.category == c) { onToggleCategory(c) }
                    }
                    val moreSelected = draft.category in AskLogic.moreCategories
                    SimpleChip(s.common.somethingElse, moreSelected || showMore) { showMore = !showMore }
                }
                if (showMore || draft.category in AskLogic.moreCategories) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AskLogic.moreCategories.forEach { c ->
                            CategoryChip(s.common.categoryName(c), c, draft.category == c) { onToggleCategory(c) }
                        }
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!state.typing) {
                    if (listening) {
                        SquareButton(
                            description = s.ask.stopListening, onClick = onStop,
                            container = MaterialTheme.colorScheme.errorContainer, content = MaterialTheme.colorScheme.onErrorContainer,
                        ) { Icon(Icons.Filled.Stop, contentDescription = null) }
                    } else {
                        SquareButton(
                            description = s.ask.startListening, onClick = onResume,
                            container = RelayTheme.colors.micYellow, content = RelayTheme.colors.onMicYellow,
                        ) { Icon(Icons.Filled.Mic, contentDescription = null) }
                    }
                }
                PillButton(s.ask.doneCheckIt, onDone, modifier = Modifier.weight(1f), enabled = canContinue)
            }
            TextButton(
                onClick = if (state.typing) onUseVoice else onTypeInstead,
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
            ) {
                if (!state.typing) {
                    Icon(Icons.Outlined.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.typing) s.ask.useVoiceInstead else s.ask.typeInstead)
            }
        }
    }
}

@Composable
private fun SquareButton(
    description: String,
    onClick: () -> Unit,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    icon: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(56.dp).semantics { contentDescription = description },
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
internal fun CategoryChip(label: String, category: Category, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.heightIn(min = 48.dp),
        leadingIcon = { Icon(categoryIcon(category), contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = MaterialTheme.shapes.small,
        colors = chipColors(),
    )
}

@Composable
internal fun SimpleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        colors = chipColors(),
    )
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

// ================================================================ previews

private val previewDraft = AskDraft(
    category = Category.WATER, people = 6, vulnFlags = setOf(org.relay.core.payload.VulnFlag.CHILD, org.relay.core.payload.VulnFlag.ELDERLY),
    landmark = "primary school", summary = "Pump broken since yesterday", urgency = 2,
)
private val previewState = AskState(draft = previewDraft, transcript = "We are 6 people, no drinking water, near the primary school")

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun AskPreview() = RelayPreview {
    AskContent(stringsFor(Lang.EN), Lang.EN, previewState, VoiceUi.Listening(), {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun AskDarkPreview() = RelayPreview(dark = true) {
    AskContent(stringsFor(Lang.HI), Lang.HI, previewState.copy(typing = true), VoiceUi.Paused, {}, {}, {}, {}, {}, {}, {}, {}, {})
}
