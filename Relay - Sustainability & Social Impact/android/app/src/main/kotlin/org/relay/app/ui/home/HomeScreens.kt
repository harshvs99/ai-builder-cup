package org.relay.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.relay.app.MeshRepository
import org.relay.app.LocalSpeaker
import org.relay.app.VillagerStatus
import org.relay.app.rememberPermissions
import org.relay.app.store.MyNeed
import org.relay.app.ui.AskMode
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.RelayPreview
import org.relay.app.ui.components.RequestRow
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SpeakIconButton
import org.relay.app.ui.components.StatusLabel
import org.relay.app.ui.components.tone
import org.relay.app.ui.i18n.AppLanguages
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

// Screens 1 (Choose your language), 2 (Home) and 6 (Requests tab) of the redesign spec.

/** Wall clock that ticks every 30 s so "2 min ago" stays fresh while the screen is open. */
@Composable
internal fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(30_000)
        value = System.currentTimeMillis()
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 1. Choose your language

/** First run: pick the app language (the app switches at once), then Continue goes to Home. */
@Composable
fun LanguageScreen(nav: RelayNav) {
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val speaker = LocalSpeaker.current
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val s = LocalStrings.current
    LanguageContent(
        selected = settings.language,
        voiceMessage = voiceMessage,
        onSelect = { l -> MeshRepository.settings.update { it.copy(language = l) } },
        onSpeak = { l ->
            val name = AppLanguages.of(l).englishName
            val ok = speaker != null && speaker.isVoiceAvailable(l) && speaker.speak(s.home.greeting(l), l)
            voiceMessage = if (ok) null else s.home.noVoice(name)
        },
        onContinue = {
            MeshRepository.settings.update { it.copy(languageConfirmed = true) }
            nav.languageDone()
        },
    )
}

@Composable
private fun LanguageContent(
    selected: Lang,
    voiceMessage: String?,
    onSelect: (Lang) -> Unit,
    onSpeak: (Lang) -> Unit,
    onContinue: () -> Unit,
) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.home.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(s.home.chooseTitle, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            Text(s.home.chooseSubtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp, horizontal = 8.dp)) {
                AppLanguages.all.forEach { e ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .selectable(selected = e.lang == selected, role = Role.RadioButton, onClick = { onSelect(e.lang) })
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = e.lang == selected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(e.nativeName, style = MaterialTheme.typography.titleLarge)
                            Text(e.englishName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SpeakIconButton(s.home.speakLanguage(e.englishName), { onSpeak(e.lang) })
                    }
                }
            }
            if (voiceMessage != null) {
                Text(voiceMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PillButton(s.common.continueAction, onContinue, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}


// ---------------------------------------------------------------------------------------------------------------
// 2. Home

/** Bluetooth permission is explained once per app run, then asked; later visits to Home only start the mesh. */
private var bluetoothExplainedThisRun = false

@Composable
fun HomeScreen(nav: RelayNav) {
    val permissions = rememberPermissions()
    val status by MeshRepository.status.collectAsStateWithLifecycle()
    val needs by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val now by rememberNow()
    val s = LocalStrings.current
    permissions.tick // recompose when permission or Bluetooth state changes (also on resume)
    val villager = VillagerStatus.from(status, now)

    // Bluetooth + notifications when Home first shows: one plain sentence, then the system prompts.
    var explain by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!bluetoothExplainedThisRun && !(permissions.hasBluetooth() && permissions.hasNotifications())) {
            bluetoothExplainedThisRun = true
            explain = true
        } else {
            permissions.ensureBluetooth()
        }
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = {},
            text = { Text(s.home.bluetoothWhy, style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = { explain = false; permissions.ensureBluetooth() }) { Text(s.common.continueAction) }
            },
        )
    }

    HomeContent(
        area = settings.areaLabel.trim(),
        villager = villager,
        now = now,
        recent = RequestLists.recent(needs),
        onStatusClick = { nav.toNetwork() },
        onTurnOnBluetooth = { permissions.enableBluetooth() },
        onMic = { permissions.ensureMicrophone { granted -> nav.startAsk(if (granted) AskMode.VOICE else AskMode.TEXT) } },
        onType = { nav.startAsk(AskMode.TEXT) },
        onSeeAll = { nav.toRequests() },
        onOpenRequest = { nav.openRequest(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    area: String,
    villager: VillagerStatus,
    now: Long,
    recent: List<MyNeed>,
    onStatusClick: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onMic: () -> Unit,
    onType: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenRequest: (String) -> Unit,
) {
    val s = LocalStrings.current
    val c = s.common
    var showHelp by remember { mutableStateOf(false) }
    val tone = villager.dot.tone()
    val word = c.villagerWord(villager)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Title, area + status, help
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.home.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (area.isNotEmpty()) {
                        Text("$area · ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusLabel(tone, word)
                }
            }
            IconButton(onClick = { showHelp = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = s.home.helpLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Status card -> Network
        val title = c.villagerTitle(villager)
        val line = c.villagerLine(villager, now)
        SectionCard(
            onClick = onStatusClick,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$title. $line. $word. ${s.home.statusOpensNetwork}" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (villager.kind == VillagerStatus.Kind.BLUETOOTH_OFF) {
                        TextButton(onClick = onTurnOnBluetooth) { Text(c.turnOn, style = MaterialTheme.typography.labelLarge) }
                    }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // The mic
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val micDesc = "${s.home.pressAndSay}. ${s.home.categoriesLine}"
            Box(
                Modifier.size(160.dp).clip(CircleShape).background(RelayTheme.colors.micYellow)
                    .clickable(onClick = onMic, role = Role.Button, onClickLabel = s.home.pressAndSay)
                    .semantics { contentDescription = micDesc },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = RelayTheme.colors.onMicYellow, modifier = Modifier.size(80.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(s.home.pressAndSay, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(s.home.categoriesLine, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            PillButton(s.home.typeInstead, onType, style = PillStyle.Tonal, icon = Icons.Outlined.Keyboard, fillWidth = false)
        }

        // Your requests
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(s.home.yourRequests, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(onClick = onSeeAll) { Text(c.seeAll, style = MaterialTheme.typography.labelLarge) }
        }
        if (recent.isEmpty()) {
            Text(s.home.noRequestsShort, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column {
                recent.forEach { RequestRow(it, now, { onOpenRequest(it.msgIdHex) }) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showHelp) {
        ModalBottomSheet(onDismissRequest = { showHelp = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.home.helpLabel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(s.home.helpLine1, style = MaterialTheme.typography.bodyLarge)
                Text(s.home.helpLine2, style = MaterialTheme.typography.bodyLarge)
                Text(s.home.helpLine3, style = MaterialTheme.typography.bodyLarge)
                PillButton(c.close, { showHelp = false }, style = PillStyle.Tonal)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// 6. Requests tab

@Composable
fun RequestsScreen(nav: RelayNav) {
    val needs by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    val now by rememberNow()
    RequestsContent(needs, now) { nav.openRequest(it) }
}

@Composable
private fun RequestsContent(needs: List<MyNeed>, now: Long, onOpen: (String) -> Unit) {
    val s = LocalStrings.current
    val (active, closed) = RequestLists.split(needs)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            s.home.requestsTitle, style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        if (needs.isEmpty()) {
            Text(
                s.home.emptyRequests, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        } else {
            if (active.isNotEmpty()) RequestSection(s.common.statusActive, active, now, onOpen)
            if (closed.isNotEmpty()) RequestSection(s.common.statusClosed, closed, now, onOpen)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RequestSection(title: String, items: List<MyNeed>, now: Long, onOpen: (String) -> Unit) {
    Text(
        title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
    Column {
        items.forEach { n -> RequestRow(n, now, { onOpen(n.msgIdHex) }) }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Previews

private fun demoNeed(id: String, cat: Category, item: String, urgency: Int, ack: Int, ageMs: Long, cancelled: Boolean = false): MyNeed {
    val now = System.currentTimeMillis()
    return MyNeed(
        msgIdHex = id, createdAtMs = now - ageMs - 60_000, category = cat, urgency = urgency, people = 6, item = item,
        landmark = "", summary = "", lang = Lang.EN, needPayloadB64 = "", sentToMesh = true, ackStatus = ack,
        sentToMeshAtMs = now - ageMs, receivedAtMs = if (ack >= 0) now - ageMs else 0,
        resolvedAtMs = if (ack >= 3) now - ageMs else 0, cancelled = cancelled, cancelledAtMs = if (cancelled) now - ageMs else 0,
    )
}

private val demoNeeds = listOf(
    demoNeed("1", Category.WATER, "Drinking water", 2, -1, 120_000),
    demoNeed("2", Category.MEDICAL, "", 3, 1, 3_600_000),
    demoNeed("3", Category.FOOD, "Rice and dal", 1, 3, 90_000_000),
    demoNeed("4", Category.SHELTER, "", 0, -1, 200_000_000, cancelled = true),
)

private val demoStatus = VillagerStatus(VillagerStatus.Kind.REACHABLE, VillagerStatus.Dot.GREEN, peers = 3, hopsToGateway = 2, syncedAgoMs = 60_000)

@Composable
private fun HomeDemo() = HomeContent(
    area = "Ward 4, Puri district", villager = demoStatus, now = System.currentTimeMillis(), recent = demoNeeds.take(2),
    onStatusClick = {}, onTurnOnBluetooth = {}, onMic = {}, onType = {}, onSeeAll = {}, onOpenRequest = {},
)

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomePreview() = RelayPreview { HomeDemo() }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomeDarkPreview() = RelayPreview(dark = true) { HomeDemo() }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RequestsPreview() = RelayPreview { RequestsContent(demoNeeds, System.currentTimeMillis()) {} }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RequestsDarkPreview() = RelayPreview(dark = true) { RequestsContent(demoNeeds, System.currentTimeMillis()) {} }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RequestsEmptyPreview() = RelayPreview { RequestsContent(emptyList(), System.currentTimeMillis()) {} }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun LanguagePreview() = RelayPreview { LanguageContent(Lang.HI, "No Waray voice is installed on this phone.", {}, {}, {}) }

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun LanguageDarkPreview() = RelayPreview(dark = true) { LanguageContent(Lang.EN, null, {}, {}, {}) }
