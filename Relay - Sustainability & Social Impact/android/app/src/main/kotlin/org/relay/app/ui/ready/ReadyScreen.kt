package org.relay.app.ui.ready

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.relay.app.MeshRepository
import org.relay.app.ready.ReadyRepository
import org.relay.app.LocalSpeaker
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Lang
import org.relay.core.ready.ReadyPack
import org.relay.core.ready.ReadyPlan
import org.relay.core.ready.ReadyView
import org.relay.core.ready.ReadyWarning
import org.relay.core.ready.RefreshTrigger
import org.relay.core.ready.WarningLevel

/** Container colours of the warning level (Home banner and Ready warning card): watch is neutral, warning amber, alert red. */
@Composable
fun levelColors(level: WarningLevel): Pair<Color, Color> = when (level) {
    WarningLevel.WATCH -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
    WarningLevel.WARNING -> RelayTheme.colors.warning to RelayTheme.colors.onWarning
    WarningLevel.ALERT -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
}

/**
 * "Get ready": the warning, the household's plan, the nearest shelters and a checklist, all read from storage so it works in
 * airplane mode (contract v2 section 3.4). A refresh only updates the stored copy.
 */
@Composable
fun ReadyScreen(nav: RelayNav) {
    val ctx = LocalContext.current
    val s = LocalStrings.current
    val r = s.ready
    val snap by ReadyRepository.snapshot.collectAsStateWithLifecycle()
    val needsDistrict by ReadyRepository.needsDistrict.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val speaker = LocalSpeaker.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var playMessage by remember { mutableStateOf<String?>(null) }
    val nowSec = System.currentTimeMillis() / 1000
    val pack = snap.pack
    val warning = ReadyView.activeWarning(pack, nowSec)
    val plan = snap.currentPlan
    val uiLang = settings.effectiveLanguage

    LaunchedEffect(Unit) { ReadyRepository.refresh(ctx, RefreshTrigger.APP_OPEN) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        StepTopAppBar(r.readyTitle, onBack = { nav.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pack == null) {
                EmptyState(needsDistrict) { id -> ReadyRepository.chooseDistrict(id); scope.launch { ReadyRepository.refresh(ctx, RefreshTrigger.MANUAL) } }
            } else {
                if (warning != null) WarningCard(warning, nowSec) else Text(r.noWarningNow, style = MaterialTheme.typography.bodyLarge)
                if (warning != null && plan != null) PlanCard(plan, uiLang, snap.audio, playMessage) { playMessage = it }
                ShelterCard(pack, plan)
                if (pack.checklist.isNotEmpty()) ChecklistCard(pack, uiLang)
                Footer(pack)
            }
            if (failed) Text(r.refreshFailed, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            PillButton(
                if (refreshing) r.refreshing else r.refresh,
                {
                    scope.launch {
                        refreshing = true
                        failed = ReadyRepository.refresh(ctx, RefreshTrigger.MANUAL).error != null
                        refreshing = false
                    }
                },
                style = PillStyle.Tonal, enabled = !refreshing,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EmptyState(needsDistrict: Boolean, onDistrict: (String) -> Unit) {
    val r = LocalStrings.current.ready
    SectionCard(title = r.emptyTitle) {
        Text(r.emptyBody, style = MaterialTheme.typography.bodyLarge)
    }
    if (needsDistrict) {
        SectionCard(title = r.whichDistrict) {
            org.relay.core.payload.AnnouncementDistricts.IDS.forEach { id ->
                PillButton(r.districtName(id), { onDistrict(id) }, style = PillStyle.Tonal)
            }
        }
    }
}

@Composable
private fun WarningCard(w: ReadyWarning, nowSec: Long) {
    val r = LocalStrings.current.ready
    val level = ReadyView.level(w)
    val (container, onContainer) = levelColors(level)
    val hours = ReadyView.landfallHours(w, nowSec)
    Column(
        Modifier.fillMaxWidth().background(container, MaterialTheme.shapes.medium).padding(16.dp)
            .semantics(mergeDescendants = true) { contentDescription = r.bannerDescription(level, w.headline, hours) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("${r.levelWord(level)} · ${r.source(w.source)}", style = MaterialTheme.typography.labelLarge, color = onContainer)
        Text(w.headline, style = MaterialTheme.typography.titleLarge, color = onContainer, modifier = Modifier.semantics { heading() })
        if (w.issuedAt > 0) Text(r.issued(w.issuedAt), style = MaterialTheme.typography.bodyMedium, color = onContainer)
        w.expectedLandfallAt?.let { Text(r.landfallAt(it), style = MaterialTheme.typography.bodyMedium, color = onContainer) }
        hours?.let { Text(r.landfallIn(it), style = MaterialTheme.typography.titleMedium, color = onContainer) }
    }
}

@Composable
private fun PlanCard(plan: ReadyPlan, uiLang: Lang, wav: java.io.File?, message: String?, onMessage: (String?) -> Unit) {
    val r = LocalStrings.current.ready
    val speaker = LocalSpeaker.current
    val player = remember { PlanPlayer() }
    var playing by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { player.stop(); speaker?.stop() } }
    SectionCard(title = r.yourPlan) {
        if (ReadyView.stepsInOtherLanguage(plan, uiLang.tag)) {
            Text(r.shownInEnglish, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        plan.steps.forEachIndexed { i, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text("${i + 1}.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 1.dp))
                Text(step, style = MaterialTheme.typography.bodyLarge)
            }
        }
        // One large speaker button: the stored WAV when there is one, else on-device speech in the language of the steps.
        PillButton(
            if (playing) r.stopPlan else r.hearPlan,
            {
                if (playing) { player.stop(); speaker?.stop(); playing = false; return@PillButton }
                val stepsLang = Lang.fromTag(plan.stepsLang) ?: Lang.EN
                val ok = if (wav != null) {
                    player.play(wav) { playing = false }
                } else {
                    speaker != null && speaker.isVoiceAvailable(stepsLang) && speaker.speakAll(plan.steps, stepsLang) { playing = false }
                }
                playing = ok
                onMessage(if (ok) null else r.noVoicePlan)
            },
            icon = Icons.AutoMirrored.Filled.VolumeUp,
        )
        if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShelterCard(pack: ReadyPack, plan: ReadyPlan?) {
    val r = LocalStrings.current.ready
    SectionCard(title = r.nearestShelters) {
        val near = plan?.nearestShelters.orEmpty()
        if (near.isEmpty() && pack.shelters.isEmpty()) {
            Text(r.noShelters, style = MaterialTheme.typography.bodyLarge)
        }
        near.forEach { n ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(n.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(r.kmAway(n.distanceKm), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChecklistCard(pack: ReadyPack, uiLang: Lang) {
    val r = LocalStrings.current.ready
    val ticks: SnapshotStateList<Int> = remember(pack.version) { ReadyRepository.ticks(pack.version).toMutableStateList() }
    SectionCard(title = r.checklist) {
        if (ReadyView.checklistInOtherLanguage(pack, uiLang.tag)) {
            Text(r.shownInEnglish, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        pack.checklist.forEachIndexed { i, item ->
            val done = i in ticks
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .toggleable(value = done, role = Role.Checkbox) { v ->
                        if (v) ticks.add(i) else ticks.remove(i)
                        ReadyRepository.setTick(pack.version, i, v)
                    },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = done, onCheckedChange = null, modifier = Modifier.size(48.dp))
                Text(item, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Footer(pack: ReadyPack) {
    val r = LocalStrings.current.ready
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(r.updated(pack.generatedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (ReadyView.shelterFooter(pack)) {
            ReadyView.ShelterFooter.VERIFIED -> Text(r.verifiedOn(pack.sheltersPublishedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReadyView.ShelterFooter.DEMO -> Text(r.demoList, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ReadyView.ShelterFooter.NONE -> {}
        }
    }
}
