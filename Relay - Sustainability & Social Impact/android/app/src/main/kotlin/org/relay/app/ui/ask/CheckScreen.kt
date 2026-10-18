package org.relay.app.ui.ask

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.relay.app.LocalSpeaker
import org.relay.app.MeshRepository
import org.relay.app.ble.BlePermissions
import org.relay.app.location.Fix
import org.relay.app.location.FixPolicy
import org.relay.app.location.FixWait
import org.relay.app.location.LocUi
import org.relay.app.location.LocationProvider
import org.relay.app.location.LocationState
import org.relay.app.rememberPermissions
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.EditableRow
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.RelayPreview
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SegmentedChoice
import org.relay.app.ui.components.SpeakIconButton
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.Strings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.rememberNowMs
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.ids.NodeId
import org.relay.core.intake.QuestionBank
import org.relay.core.payload.Category
import org.relay.core.payload.Lang
import org.relay.core.payload.VulnFlag
import kotlin.math.roundToInt

enum class SendPhase { IDLE, LOCATING }

/** Which field's bottom sheet is open. */
enum class CheckEditor { NEED, PEOPLE, WHERE, NOTE }

// ================================================================ Check and send (step 2 of 3)

@Composable
fun CheckScreen(nav: RelayNav, flowOwner: ViewModelStoreOwner) {
    val vm: AskViewModel = viewModel(viewModelStoreOwner = flowOwner)
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val lang = settings.language
    val perms = rememberPermissions()
    val speaker = LocalSpeaker.current
    val nowMs by rememberNowMs()
    var phase by remember { mutableStateOf(SendPhase.IDLE) }
    var askedLocation by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<NoLocationPrompt?>(null) }
    var confirmArmed by remember { mutableStateOf(false) }
    var openWhere by remember { mutableStateOf(false) }

    // ---- location: re-checked on resume (back from settings) and when a permission result arrives
    var hasPerm by remember { mutableStateOf(BlePermissions.hasLocation(ctx)) }
    var locationOn by remember { mutableStateOf(LocationProvider.isSystemEnabled(ctx)) }
    val fix by LocationProvider.fix.collectAsStateWithLifecycle()
    val listening by LocationProvider.listening.collectAsStateWithLifecycle()
    fun refreshLocation() {
        hasPerm = BlePermissions.hasLocation(ctx)
        locationOn = LocationProvider.isSystemEnabled(ctx)
        if (hasPerm && locationOn) LocationProvider.start(ctx)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refreshLocation()
                Lifecycle.Event.ON_PAUSE -> LocationProvider.stop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); LocationProvider.stop() }
    }
    LaunchedEffect(perms.tick) { refreshLocation() }
    val locUi = LocationState.ui(hasPerm, locationOn, fix, nowMs)
    // The 30 s window lapsed while the person is still reading: listen again.
    LaunchedEffect(locUi, listening) {
        if (LocationState.shouldRestart(locUi, listening, cardVisible = true)) LocationProvider.start(ctx)
    }

    fun turnOnLocation() {
        if (!hasPerm) {
            // First tap asks; later taps (denied for good) go to the app's settings page.
            if (!askedLocation) { askedLocation = true; perms.ensureLocation { refreshLocation() } } else perms.openAppSettings()
        } else {
            try {
                ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                Toast.makeText(ctx, s.ask.locationOff, Toast.LENGTH_LONG).show()
            }
        }
    }

    // The control room cannot act on a request it cannot place: without a fix, ask before anything leaves.
    // [force] = the person chose "Send without location" in the dialog.
    fun doSend(force: Boolean = false) {
        if (phase != SendPhase.IDLE) return
        scope.launch {
            LocationProvider.start(ctx) // reopen the listening window; a no-op without permission or Location
            var current: Fix? = LocationProvider.fix.value?.takeIf { FixPolicy.isUsable(it, System.currentTimeMillis()) }
            if (current == null && !force) {
                val ui = LocationState.ui(BlePermissions.hasLocation(ctx), LocationProvider.isSystemEnabled(ctx), null, System.currentTimeMillis())
                if (LocationState.shouldWaitForFix(ui)) {
                    phase = SendPhase.LOCATING
                    current = FixWait.await(LocationProvider.fix, MutableStateFlow(false), SEND_FIX_WAIT_MS)
                    phase = SendPhase.IDLE
                }
            }
            if (current == null && !force) {
                val p = NoLocationLogic.promptOnSend(
                    permissionGranted = BlePermissions.hasLocation(ctx), servicesEnabled = LocationProvider.isSystemEnabled(ctx),
                    fixPresent = false, waiting = false, landmarkEmpty = vm.state.value.draft.landmark.isBlank(),
                )
                if (p != null) {
                    confirmArmed = false
                    prompt = p
                    return@launch
                }
            }
            val need = vm.buildNeed(
                msgId = NodeId.random(), originNode = MeshRepository.nodeId, createdAtSec = System.currentTimeMillis() / 1000,
                lang = lang, fix = current?.let { Triple(it.lat, it.lon, it.locAcc) },
            )
            MeshRepository.submitNeed(need, ctx)
            LocationProvider.stop()
            LocationProvider.reset()
            nav.finishAskTo(need.msgIdHex)
        }
    }

    // Dialog title and body are also spoken when "Read messages aloud" is on.
    LaunchedEffect(prompt) {
        val p = prompt ?: return@LaunchedEffect
        if (settings.readAloud) speaker?.speak(noLocationSpeech(s, p, state.draft.landmark), lang)
    }

    CheckContent(
        s = s, lang = lang, draft = state.draft, locUi = locUi, accuracyM = fix?.accuracyM, phase = phase,
        prompt = prompt, confirmArmed = confirmArmed,
        onPromptTurnOn = {
            prompt = null
            if (hasPerm && locationOn) LocationProvider.start(ctx) else turnOnLocation()
        },
        onPromptLandmark = { prompt = null; openWhere = true },
        onPromptSendAnyway = {
            val p = prompt
            if (p != null && p.confirmSendAnyway && !confirmArmed) confirmArmed = true
            else { prompt = null; confirmArmed = false; doSend(force = true) }
        },
        onPromptDismiss = { prompt = null; confirmArmed = false },
        openWhereEditor = openWhere, onWhereEditorOpened = { openWhere = false },
        onBack = nav::back,
        onTurnOnLocation = ::turnOnLocation,
        onSetCategory = vm::setCategory,
        onSetItem = vm::setItem,
        onSetPeople = vm::setPeople,
        onToggleFlag = vm::toggleFlag,
        onSetLandmark = vm::setLandmark,
        onSetSummary = vm::setSummary,
        onSetUrgency = vm::setUrgency,
        onAnswer = vm::setAnswer,
        onHearQuestion = { text ->
            if (speaker?.speak(text, lang) != true) Toast.makeText(ctx, s.ask.noVoiceInstalled, Toast.LENGTH_SHORT).show()
        },
        // Location is asked at the first Send; the NEED goes out whatever the answer, with the honest no-location state.
        onSend = { if (!perms.hasLocation()) { askedLocation = true; perms.ensureLocation { doSend() } } else doSend() },
        onChangeSomething = nav::back,
    )
}

private const val SEND_FIX_WAIT_MS = 6_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckContent(
    s: Strings,
    lang: Lang,
    draft: AskDraft,
    locUi: LocUi,
    accuracyM: Float?,
    phase: SendPhase,
    onBack: () -> Unit,
    onTurnOnLocation: () -> Unit,
    onSetCategory: (Category) -> Unit,
    onSetItem: (String) -> Unit,
    onSetPeople: (Int) -> Unit,
    onToggleFlag: (VulnFlag) -> Unit,
    onSetLandmark: (String) -> Unit,
    onSetSummary: (String) -> Unit,
    onSetUrgency: (Int) -> Unit,
    onAnswer: (Int, Int) -> Unit,
    onHearQuestion: (String) -> Unit,
    onSend: () -> Unit,
    onChangeSomething: () -> Unit,
    initialEditor: CheckEditor? = null,
    prompt: NoLocationPrompt? = null,
    confirmArmed: Boolean = false,
    onPromptTurnOn: () -> Unit = {},
    onPromptLandmark: () -> Unit = {},
    onPromptSendAnyway: () -> Unit = {},
    onPromptDismiss: () -> Unit = {},
    openWhereEditor: Boolean = false,
    onWhereEditorOpened: () -> Unit = {},
) {
    var editor by remember { mutableStateOf(initialEditor) }
    LaunchedEffect(openWhereEditor) { if (openWhereEditor) { editor = CheckEditor.WHERE; onWhereEditorOpened() } }
    val common = s.common
    val sending = phase != SendPhase.IDLE

    val density = LocalDensity.current
    // Height of the pinned Send area; the scroll content ends with this much padding so nothing sits under it.
    var sendAreaHeight by remember { mutableStateOf(0.dp) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(s.ask.checkTitle, onBack = onBack, stepText = s.ask.stepCounter(2, 3))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(s.ask.isThisRight, style = MaterialTheme.typography.headlineSmall)

            SectionCard {
                EditableRow(s.ask.rowNeed, AskLogic.needWords(common, draft).ifEmpty { s.ask.nothingChosen }, { editor = CheckEditor.NEED })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EditableRow(s.ask.rowPeople, AskLogic.peopleLine(common, draft.people, draft.vulnFlags), { editor = CheckEditor.PEOPLE })
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EditableRow(
                    s.ask.rowWhere,
                    if (draft.landmark.isBlank()) s.ask.noLandmark else s.ask.nearLandmark(draft.landmark),
                    { editor = CheckEditor.WHERE },
                    secondary = if (locUi == LocUi.READY) locationText(s, locUi, accuracyM) else null,
                    secondaryAction = if (locUi != LocUi.READY) {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WarningLine(s, locationText(s, locUi, accuracyM), Modifier.weight(1f))
                                if (locUi == LocUi.OFF || locUi == LocUi.NO_PERMISSION) {
                                    TextButton(onClick = onTurnOnLocation, modifier = Modifier.heightIn(min = 48.dp)) { Text(common.turnOn) }
                                }
                            }
                        }
                    } else null,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                EditableRow(
                    s.ask.rowNote, draft.summary.ifBlank { s.ask.noNote }, { editor = CheckEditor.NOTE },
                    secondary = if (!common.isEnglish && draft.summary.isNotBlank()) s.ask.sentInEnglish else null,
                )
            }

            val questions = draft.questionIds.mapNotNull { QuestionBank.byId(it) }
            if (questions.isNotEmpty()) {
                SectionCard(title = s.ask.quickQuestions) {
                    questions.forEachIndexed { i, q ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.text(lang), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            SpeakIconButton(s.ask.hearQuestion(q.text(lang)), { onHearQuestion(q.text(lang)) })
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            q.options.forEach { o ->
                                SimpleChip(o.label(lang), draft.answers[q.id] == o.value, compact = true) { onAnswer(q.id, o.value) }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.ask.howUrgent, style = MaterialTheme.typography.titleMedium)
                SegmentedChoice(
                    options = listOf(0, 1, 2, 3), selected = draft.urgency, onSelect = onSetUrgency,
                    label = common::urgencyWord, warningSelected = true,
                )
                Text(common.urgencyHelp(draft.urgency), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            PillButton(s.ask.changeSomething, onChangeSomething, style = PillStyle.Outlined, enabled = !sending)
            Spacer(Modifier.height(sendAreaHeight))
        }
    }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .onSizeChanged { sendAreaHeight = with(density) { it.height.toDp() } }
                .padding(horizontal = 16.dp, vertical = 8.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                text = if (phase == SendPhase.LOCATING) s.ask.sendingGettingLocation else common.send,
                onClick = onSend, icon = Icons.AutoMirrored.Filled.Send, enabled = !sending,
            )
            Text(
                s.ask.footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    prompt?.let { p ->
        NoLocationDialog(s, p, draft.landmark, confirmArmed, onPromptTurnOn, onPromptLandmark, onPromptSendAnyway, onPromptDismiss)
    }

    editor?.let { which ->
        EditorSheet(onDismiss = { editor = null }, doneLabel = common.done) {
            when (which) {
                CheckEditor.NEED -> NeedEditor(s, draft, onSetCategory, onSetItem)
                CheckEditor.PEOPLE -> PeopleEditor(s, draft, onSetPeople, onToggleFlag)
                CheckEditor.WHERE -> WhereEditor(s, draft, locUi, accuracyM, onSetLandmark, onTurnOnLocation)
                CheckEditor.NOTE -> NoteEditor(s, draft, onSetSummary)
            }
        }
    }
}

/** "GPS · about 40 m accuracy", "Getting location…", or the honest "Location is off" line. */
private fun locationText(s: Strings, ui: LocUi, accuracyM: Float?): String =
    when (NoLocationLogic.reason(ui != LocUi.NO_PERMISSION, ui != LocUi.OFF && ui != LocUi.NO_PERMISSION, ui == LocUi.READY)) {
        null -> if (accuracyM == null || accuracyM.isNaN() || accuracyM < 0f) "GPS" else s.ask.locationAccuracy(accuracyM.roundToInt().coerceAtLeast(1))
        NoLocationReason.PERMISSION_OFF -> s.ask.whereReasonPermissionOff
        NoLocationReason.SERVICES_OFF -> s.ask.whereReasonServicesOff
        NoLocationReason.FINDING -> s.ask.whereReasonFinding
    }

/** Warning icon + amber text: "there is no GPS fix, and here is why". */
@Composable
private fun WarningLine(s: Strings, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = s.ask.noLocationWarningA11y, tint = RelayTheme.colors.warningText, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = RelayTheme.colors.warningText)
    }
}

private fun noLocationBody(s: Strings, p: NoLocationPrompt, landmark: String): String =
    if (p.bodyWithLandmark) s.ask.noLocationBodyWithLandmark(landmark.trim()) else s.ask.noLocationBodyNoLandmark

private fun noLocationSpeech(s: Strings, p: NoLocationPrompt, landmark: String): String =
    s.ask.noLocationTitle + ". " + noLocationBody(s, p, landmark)

/** Shown on Send when there is no fix. Never blocks: "Send without location" is always there. */
@Composable
private fun NoLocationDialog(
    s: Strings, p: NoLocationPrompt, landmark: String, confirmArmed: Boolean,
    onTurnOn: () -> Unit, onLandmark: () -> Unit, onSendAnyway: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.ask.noLocationTitle, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(noLocationBody(s, p, landmark), style = MaterialTheme.typography.bodyLarge)
                PillButton(s.ask.turnOnLocation, onTurnOn)
                PillButton(
                    if (p.addLandmark) s.ask.addLandmark else s.ask.changeLandmark, onLandmark, style = PillStyle.Tonal,
                )
                TextButton(
                    onClick = onSendAnyway,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(
                        if (p.confirmSendAnyway && confirmArmed) s.ask.sendAnywayConfirm else s.ask.sendWithoutLocation,
                        color = if (confirmArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {},
    )
}

// ================================================================ bottom-sheet editors (one field each)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorSheet(onDismiss: () -> Unit, doneLabel: String, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
            PillButton(doneLabel, onDismiss)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedEditor(s: Strings, d: AskDraft, onCategory: (Category) -> Unit, onItem: (String) -> Unit) {
    Text(s.ask.editNeedTitle, style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (AskLogic.mainCategories + AskLogic.moreCategories).forEach { c ->
            CategoryChip(s.common.categoryName(c), c, d.category == c) { onCategory(c) }
        }
    }
    LimitedField(s, s.ask.itemLabel, d.item, AskLogic.MAX_ITEM, onItem)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleEditor(s: Strings, d: AskDraft, onPeople: (Int) -> Unit, onFlag: (VulnFlag) -> Unit) {
    Text(s.ask.editPeopleTitle, style = MaterialTheme.typography.titleLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onPeople(d.people - 1) }, enabled = d.people > 1,
            modifier = Modifier.size(56.dp).semantics { contentDescription = s.ask.fewerPeople },
        ) { Icon(Icons.Filled.Remove, contentDescription = null) }
        Text(
            "${d.people}", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        FilledTonalIconButton(
            onClick = { onPeople(d.people + 1) }, enabled = d.people < AskLogic.MAX_PEOPLE,
            modifier = Modifier.size(56.dp).semantics { contentDescription = s.ask.morePeople },
        ) { Icon(Icons.Filled.Add, contentDescription = null) }
    }
    Text(s.ask.whoIsWithYou, style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AskLogic.flagOrder.forEach { f -> SimpleChip(s.common.vulnFlag(f), f in d.vulnFlags) { onFlag(f) } }
    }
}

@Composable
private fun WhereEditor(
    s: Strings, d: AskDraft, locUi: LocUi, accuracyM: Float?, onLandmark: (String) -> Unit, onTurnOn: () -> Unit,
) {
    Text(s.ask.editWhereTitle, style = MaterialTheme.typography.titleLarge)
    LimitedField(s, s.ask.landmarkLabel, d.landmark, AskLogic.MAX_LANDMARK, onLandmark)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            locationText(s, locUi, accuracyM), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        if (locUi == LocUi.OFF || locUi == LocUi.NO_PERMISSION) {
            TextButton(onClick = onTurnOn, modifier = Modifier.heightIn(min = 48.dp)) { Text(s.common.turnOn) }
        }
    }
}

@Composable
private fun NoteEditor(s: Strings, d: AskDraft, onSummary: (String) -> Unit) {
    Text(s.ask.editNoteTitle, style = MaterialTheme.typography.titleLarge)
    if (!s.common.isEnglish) {
        Text(s.ask.noteSentInEnglish, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    LimitedField(s, s.ask.noteLabel, d.summary, AskLogic.MAX_SUMMARY, onSummary, singleLine = false)
}

/** Text field that cannot go past [maxBytes] UTF-8 bytes; a quiet "N characters left" line shows the room. */
@Composable
private fun LimitedField(
    s: Strings, label: String, value: String, maxBytes: Int, onChange: (String) -> Unit, singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(AskLogic.fit(it, maxBytes)) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        maxLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        supportingText = { Text(s.ask.charsLeft(AskLogic.charsLeft(value, maxBytes))) },
    )
}

// ================================================================ previews

private val previewDraft = AskDraft(
    category = Category.WATER, people = 6, vulnFlags = setOf(VulnFlag.CHILD, VulnFlag.ELDERLY),
    landmark = "primary school", summary = "Pump broken since yesterday", urgency = 2, answers = mapOf(6 to 2),
)

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun CheckPreview() = RelayPreview {
    CheckContent(
        stringsFor(Lang.EN), Lang.EN, previewDraft, LocUi.READY, 40f, SendPhase.IDLE,
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {},
    )
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun CheckDarkPreview() = RelayPreview(dark = true) {
    CheckContent(
        stringsFor(Lang.HI), Lang.HI, previewDraft.copy(category = Category.MEDICAL, answers = emptyMap()), LocUi.OFF, null, SendPhase.IDLE,
        {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {},
    )
}
