package org.relay.app.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.relay.app.BackendDefaults
import org.relay.app.MeshRepository
import org.relay.app.MeshStatus
import org.relay.app.SpeechLogic
import org.relay.app.VoiceInput
import org.relay.app.VoiceState
import org.relay.app.ble.BlePermissions
import org.relay.app.gateway.HealthKind
import org.relay.app.gateway.SyncHealthText
import org.relay.app.location.FixPolicy
import org.relay.app.location.FixWait
import org.relay.app.location.LocUi
import org.relay.app.location.LocationProvider
import org.relay.app.location.LocationState
import org.relay.app.store.MyNeed
import org.relay.app.store.NeedStatus
import org.relay.app.store.NeedStatusLogic
import org.relay.core.ids.NodeId
import org.relay.core.nlu.NeedDraft
import org.relay.core.nlu.RuleExtractor
import org.relay.core.payload.Category
import org.relay.core.payload.Geo
import org.relay.core.payload.Lang
import org.relay.core.payload.NeedCodec
import org.relay.core.payload.Utf8
import org.relay.core.payload.VulnFlag

@Composable
fun LegacyHomeScreen(actions: SetupActions) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by MeshRepository.status.collectAsStateWithLifecycle()
    val needs by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()

    var text by rememberSaveable { mutableStateOf("") }
    var draft by remember { mutableStateOf<NeedDraft?>(null) }
    var sendPhase by remember { mutableStateOf(SendPhase.IDLE) }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    val skipWait = remember { MutableStateFlow(false) }
    val lang = settings.language
    val nowMs by rememberNowMs()

    // ---- location: re-checked on every resume; warm-up while this screen is visible / the user is composing
    var hasPerm by remember { mutableStateOf(BlePermissions.hasLocation(ctx)) }
    var locationOn by remember { mutableStateOf(LocationProvider.isSystemEnabled(ctx)) }
    val fix by LocationProvider.fix.collectAsStateWithLifecycle()
    val listening by LocationProvider.listening.collectAsStateWithLifecycle()
    val langNow by rememberUpdatedState(lang)
    val textFocus = remember { FocusRequester() }
    val voice = remember {
        VoiceInput(ctx) { heard ->
            text = heard
            draft = RuleExtractor.extract(heard, langNow)
        }
    }
    val voiceState by voice.state.collectAsStateWithLifecycle()

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
                Lifecycle.Event.ON_PAUSE -> { LocationProvider.stop(); voice.cancel() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            LocationProvider.stop()
            voice.destroy()
        }
    }
    // The user started typing / reviewing: make sure a fresh listening window is open.
    LaunchedEffect(text.isNotBlank(), draft != null) {
        if (text.isNotBlank() || draft != null) refreshLocation()
    }
    val locUi = LocationState.ui(hasPerm, locationOn, fix, nowMs)
    // The window lapsed (30 s) while the confirm card is still open and there is still no fix: listen again.
    LaunchedEffect(locUi, listening, draft != null) {
        if (LocationState.shouldRestart(locUi, listening, draft != null)) LocationProvider.start(ctx)
    }

    val locPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshLocation()
        if (!BlePermissions.hasLocation(ctx)) {
            val act = ctx.findActivity()
            // Denied and the system will not ask again: send the user to the app's permission page.
            if (act != null && !ActivityCompat.shouldShowRequestPermissionRationale(act, Manifest.permission.ACCESS_FINE_LOCATION)) {
                actions.openAppSettings()
            }
        }
    }
    fun requestLocationPermission() {
        locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun openLocationSettings() {
        try { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            Toast.makeText(ctx, "Open Settings > Location and turn it on.", Toast.LENGTH_LONG).show()
        }
    }

    fun sendNeed(d: NeedDraft) {
        sendJob?.cancel()
        skipWait.value = false
        sendJob = scope.launch {
            LocationProvider.start(ctx) // extend / reopen the listening window; no-op without permission or Location
            var current = LocationProvider.fix.value?.takeIf { FixPolicy.isUsable(it, System.currentTimeMillis()) }
            if (current == null) {
                val ui = LocationState.ui(BlePermissions.hasLocation(ctx), LocationProvider.isSystemEnabled(ctx), null, System.currentTimeMillis())
                if (LocationState.shouldWaitForFix(ui)) {
                    sendPhase = SendPhase.WAITING_FOR_FIX
                    current = FixWait.await(LocationProvider.fix, skipWait)
                }
            }
            sendPhase = SendPhase.SENDING
            val need = d.toNeed(
                msgId = NodeId.random(),
                originNode = MeshRepository.nodeId,
                createdAtSec = System.currentTimeMillis() / 1000,
                latE5 = current?.let { Geo.toE5(it.lat) } ?: 0,
                lonE5 = current?.let { Geo.toE5(it.lon) } ?: 0,
                locAcc = current?.locAcc ?: 0,
                lang = lang,
            )
            MeshRepository.submitNeed(need, ctx)
            Toast.makeText(
                ctx,
                if (current == null) "Sent without location — rescuers will rely on the landmark"
                else "Sent with location (${LocationState.accuracyText(current.accuracyM)})",
                Toast.LENGTH_LONG,
            ).show()
            LocationProvider.stop()
            LocationProvider.reset()
            text = ""
            draft = null
            sendPhase = SendPhase.IDLE
            sendJob = null
        }
    }

    fun startVoice() {
        refreshLocation() // dictating counts as composing: warm up GPS now
        voice.start(langNow)
    }
    val audioPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice()
        else voice.fail("Microphone permission was denied. Type your request, or allow the microphone in App settings.", offerAppSettings = true)
    }
    // A voice problem leaves the user on the text field, never on a dead end.
    LaunchedEffect(voiceState is VoiceState.Problem) {
        if (voiceState is VoiceState.Problem) try { textFocus.requestFocus() } catch (_: Exception) {}
    }

    val setupNeeded = remember(actions.tick, status.serviceRunning) { BlePermissions.missing(ctx).isNotEmpty() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (setupNeeded) {
            item {
                InfoCard {
                    Text("Relay needs Bluetooth, location, microphone and notification permissions to work.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = actions.requestAll) { Text("Grant permissions") }
                }
            }
        }
        item { MeshStatusBar(status, settings.backendUrl, nowMs) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LANGUAGE_LABELS.forEach { (l, label) ->
                    FilterChip(selected = lang == l, onClick = { MeshRepository.settings.update { it.copy(language = l) } }, label = { Text(label) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).focusRequester(textFocus),
                    label = { Text("What do you need?") },
                    placeholder = { Text("e.g. my mother needs insulin, near the school") },
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                FilledTonalIconButton(onClick = {
                    if (voiceState is VoiceState.Listening) {
                        voice.stop()
                    } else if (!BlePermissions.hasRecordAudio(ctx)) {
                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        startVoice()
                    }
                }) { Text("🎤") }
            }
        }
        if (voiceState !is VoiceState.Idle) {
            item {
                VoicePanel(
                    state = voiceState,
                    languageName = SpeechLogic.languageName(lang),
                    onStop = voice::stop,
                    onCancel = voice::cancel,
                    onRetry = ::startVoice,
                    onDismiss = voice::dismiss,
                    onVoiceSettings = { VoiceInput.openVoiceSettings(ctx) },
                    onAppSettings = actions.openAppSettings,
                )
            }
        }
        item {
            Button(
                onClick = { draft = RuleExtractor.extract(text, lang) },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Review request") }
        }
        draft?.let { d ->
            item {
                ConfirmCard(
                    draft = d,
                    sendPhase = sendPhase,
                    locUi = locUi,
                    locLabel = LocationState.label(locUi, fix),
                    locNote = LocationState.fallbackNote(locUi),
                    onChange = { draft = it },
                    onCancel = {
                        sendJob?.cancel(); sendJob = null
                        sendPhase = SendPhase.IDLE
                        draft = null
                    },
                    onSend = { sendNeed(d) },
                    onSendNow = { skipWait.value = true },
                    onTurnOnLocation = ::openLocationSettings,
                    onAllowLocation = ::requestLocationPermission,
                )
            }
        }
        item { SectionTitle("My needs") }
        if (needs.isEmpty()) {
            item { Text("Nothing sent yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(needs, key = { it.msgIdHex }) { n ->
                val st = NeedStatusLogic.derive(
                    n,
                    isGateway = status.isGateway,
                    storedLocally = MeshRepository.packetStore.contains(n.msgIdHex),
                    uploaded = MeshRepository.gatewayState.isUploaded(n.msgIdHex),
                )
                NeedRow(n, st, backendFailing = status.isGateway && status.syncHealth.lastOk == false)
            }
        }
    }
}

@Composable
private fun MeshStatusBar(status: MeshStatus, backendUrl: String, nowMs: Long) {
    InfoCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Node ${MeshRepository.nodeIdHex.take(6)}…", fontWeight = FontWeight.SemiBold)
            Text(if (status.serviceRunning) "Peers ${status.peers.size}" else "Mesh stopped")
        }
        if (status.isGateway) {
            val line = SyncHealthText.line(status.syncHealth, true, BackendDefaults.hostOf(backendUrl), nowMs)
            val color = when (line.kind) {
                HealthKind.OK -> COLOR_OK
                HealthKind.FAILING -> COLOR_BAD
                else -> COLOR_NEUTRAL
            }
            Text(line.text, color = color, fontWeight = FontWeight.SemiBold)
            if (line.kind == HealthKind.FAILING) {
                SyncHealthText.failureDetail(status.syncHealth, nowMs)?.let {
                    Text(it, color = color, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                status.isGateway -> {}
                status.hopsToGateway < 0xFF -> Pill("gateway ${status.hopsToGateway} hops away", COLOR_WARN)
                else -> Pill("no gateway yet", COLOR_NEUTRAL)
            }
            if (status.serviceRunning && status.transport?.running == false) {
                Pill(if (!status.bluetoothOn) "Bluetooth off" else "BLE waiting", COLOR_BAD)
            }
        }
    }
}

@Composable
private fun VoicePanel(
    state: VoiceState,
    languageName: String,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onVoiceSettings: () -> Unit,
    onAppSettings: () -> Unit,
) {
    InfoCard {
        when (state) {
            is VoiceState.Listening -> {
                val pulse = rememberInfiniteTransition(label = "mic")
                val beat by pulse.animateFloat(
                    initialValue = 1f, targetValue = 1.25f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "beat",
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(40.dp).scale(beat + state.level * 0.4f).background(COLOR_BAD.copy(alpha = 0.25f), CircleShape),
                        )
                        Text("🎤", style = MaterialTheme.typography.headlineSmall)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Listening ($languageName)…", fontWeight = FontWeight.SemiBold)
                        Text(
                            state.partial.ifBlank { "Speak now" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.partial.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStop) { Text("Stop") }
                    FilledTonalButton(onClick = onCancel) { Text("Cancel") }
                }
            }
            is VoiceState.Problem -> {
                Text(state.text, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.offerVoiceSettings) Button(onClick = onVoiceSettings) { Text("Voice settings") }
                    if (state.offerAppSettings) Button(onClick = onAppSettings) { Text("App settings") }
                    FilledTonalButton(onClick = onRetry) { Text("Try again") }
                    OutlinedButton(onClick = onDismiss) { Text("Type instead") }
                }
            }
            VoiceState.Idle -> {}
        }
    }
}

private enum class SendPhase { IDLE, WAITING_FOR_FIX, SENDING }

@Composable
private fun ConfirmCard(
    draft: NeedDraft,
    sendPhase: SendPhase,
    locUi: LocUi,
    locLabel: String,
    locNote: String?,
    onChange: (NeedDraft) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onSendNow: () -> Unit,
    onTurnOnLocation: () -> Unit,
    onAllowLocation: () -> Unit,
) {
    val busy = sendPhase != SendPhase.IDLE
    InfoCard {
        Text("Check before sending", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Category.entries.forEach { c ->
                FilterChip(
                    selected = draft.category == c,
                    onClick = { onChange(draft.copy(category = c)) },
                    label = { Text("${CATEGORY_EMOJI[c]} ${c.label()}") },
                )
            }
        }
        OutlinedTextField(
            value = draft.item,
            onValueChange = { onChange(draft.copy(item = Utf8.truncate(it, NeedCodec.MAX_ITEM))) },
            label = { Text("Item (max ${NeedCodec.MAX_ITEM} bytes)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.landmark,
            onValueChange = { onChange(draft.copy(landmark = Utf8.truncate(it, NeedCodec.MAX_LANDMARK))) },
            label = { Text("Landmark (max ${NeedCodec.MAX_LANDMARK} bytes)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("People", Modifier.weight(1f))
            OutlinedButton(onClick = { onChange(draft.copy(people = (draft.people - 1).coerceAtLeast(1))) }) { Text("−") }
            Text("${draft.people}", fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { onChange(draft.copy(people = (draft.people + 1).coerceAtMost(255))) }) { Text("+") }
        }
        Text("Urgency", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("0 low", "1", "2", "3 life-threatening").forEachIndexed { i, label ->
                FilterChip(selected = draft.urgency == i, onClick = { onChange(draft.copy(urgency = i)) }, label = { Text(label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VulnFlag.entries.forEach { f ->
                FilterChip(
                    selected = f in draft.vulnFlags,
                    onClick = { onChange(draft.copy(vulnFlags = if (f in draft.vulnFlags) draft.vulnFlags - f else draft.vulnFlags + f)) },
                    label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Text("Summary: ${draft.summary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (locUi == LocUi.ACQUIRING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(locLabel, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            when (locUi) {
                LocUi.OFF -> FilledTonalButton(onClick = onTurnOnLocation, enabled = !busy) { Text("Turn on") }
                LocUi.NO_PERMISSION -> FilledTonalButton(onClick = onAllowLocation, enabled = !busy) { Text("Allow") }
                else -> {}
            }
        }
        if (locNote != null) Text(locNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(
                    when (sendPhase) {
                        SendPhase.IDLE -> "Send request"
                        SendPhase.WAITING_FOR_FIX -> "Waiting for GPS…"
                        SendPhase.SENDING -> "Sending…"
                    },
                )
            }
            if (sendPhase == SendPhase.WAITING_FOR_FIX) {
                FilledTonalButton(onClick = onSendNow) { Text("Send now") }
            }
            FilledTonalButton(onClick = onCancel, enabled = sendPhase != SendPhase.SENDING) { Text("Cancel") }
        }
    }
}

@Composable
private fun NeedRow(n: MyNeed, st: NeedStatus, backendFailing: Boolean) {
    InfoCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(CATEGORY_EMOJI[n.category] ?: "❓", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(n.item.ifBlank { n.summary.ifBlank { n.category.label() } }, fontWeight = FontWeight.SemiBold)
                if (n.summary.isNotBlank()) Text(n.summary, style = MaterialTheme.typography.bodySmall)
            }
            Text("×${n.people}", style = MaterialTheme.typography.labelMedium)
        }
        val (label, color) = when (st) {
            NeedStatus.QUEUED -> "Queued (no links yet)" to COLOR_NEUTRAL
            NeedStatus.SENT -> "Sent to mesh" to COLOR_WARN
            NeedStatus.WAITING_UPLOAD ->
                (if (backendFailing) "Waiting for upload — backend unreachable" else "Waiting for upload") to
                    (if (backendFailing) COLOR_BAD else COLOR_WARN)
            NeedStatus.UPLOADED -> "Uploaded — waiting for confirmation" to COLOR_WARN
            NeedStatus.RECEIVED -> "Received by control room ✓" to COLOR_OK
            NeedStatus.DISPATCHED -> "Help dispatched 🚑" to Color(0xFF0B57D0)
            NeedStatus.ON_THE_WAY -> "Help on the way 🚑" to Color(0xFF0B57D0)
            NeedStatus.RESOLVED -> "Resolved ✅" to COLOR_OK
        }
        Pill(label, color)
        if (n.ticketNo >= 0) Text("Ticket #${n.ticketNo}", style = MaterialTheme.typography.labelMedium)
        if (n.receivedText.isNotBlank()) Text(n.receivedText, style = MaterialTheme.typography.bodySmall)
        if (n.ackStatus >= 1 && n.ackText.isNotBlank()) Text(n.ackText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
