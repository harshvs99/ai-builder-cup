package org.relay.app.ui.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.GatewayMode
import org.relay.app.MeshRepository
import org.relay.app.Settings
import org.relay.app.ThemeMode
import org.relay.app.VoiceSpeed
import org.relay.app.ble.BlePermissions
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.RelayPreview
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SegmentedChoice
import org.relay.app.ui.components.SettingNavRow
import org.relay.app.ui.components.SettingSwitchRow
import org.relay.app.ui.i18n.AppLanguages
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.Strings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Lang

/** What the Settings permission rows show; read from the system by [SettingsScreen]. */
data class PermissionStates(val bluetooth: PermissionWord, val location: PermissionWord, val microphone: PermissionWord)

private const val AREA_MAX = 40

/** Settings tab (design spec section 8). */
@Composable
fun SettingsScreen(nav: RelayNav) {
    val ctx = LocalContext.current
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    // Reading the tick recomposes this on resume (back from system settings) and after a permission prompt.
    val perms = nav.setup.tick.let {
        PermissionStates(
            bluetooth = PermissionWord.of(BlePermissions.canRunMesh(ctx), askedWhenNeeded = false),
            location = PermissionWord.of(BlePermissions.hasLocation(ctx), askedWhenNeeded = true),
            microphone = PermissionWord.of(BlePermissions.hasRecordAudio(ctx), askedWhenNeeded = true),
        )
    }
    SettingsContent(
        settings, perms, LocalStrings.current,
        onUpdate = { f -> MeshRepository.settings.update(f) },
        onOpenSystemSettings = nav.setup.openAppSettings,
        onDeveloper = nav::openDeveloper,
    )
}

@Composable
internal fun SettingsContent(
    settings: Settings,
    perms: PermissionStates,
    strings: Strings,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onDeveloper: () -> Unit,
) {
    val s = strings.network
    var languageSheet by remember { mutableStateOf(false) }
    var areaDialog by remember { mutableStateOf(false) }
    var gatewayDialog by remember { mutableStateOf(false) }
    var comingSoon by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            s.settingsTitle, style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() },
        )

        Group(s.groupLanguageVoice) {
            SettingNavRow(s.language, { languageSheet = true }, value = AppLanguages.of(settings.effectiveLanguage).nativeName)
            SettingSwitchRow(s.readAloud, settings.readAloud, { v -> onUpdate { it.copy(readAloud = v) } })
            LabelledChoice(s.voiceSpeed) {
                SegmentedChoice(
                    VoiceSpeed.entries, settings.voiceSpeed, { v -> onUpdate { it.copy(voiceSpeed = v) } },
                    { if (it == VoiceSpeed.SLOW) s.speedSlow else s.speedNormal },
                )
            }
        }

        Group(s.groupScreenBattery) {
            LabelledChoice(s.theme) {
                SegmentedChoice(
                    listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AUTO), settings.themeMode, { m -> onUpdate { it.copy(themeMode = m) } },
                    { when (it) { ThemeMode.LIGHT -> s.themeLight; ThemeMode.DARK -> s.themeDark; ThemeMode.AUTO -> s.themeAuto } },
                )
            }
            SettingSwitchRow(s.largerText, settings.largerText, { v -> onUpdate { it.copy(largerText = v) } })
            Text(
                s.darkNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            SettingNavRow(s.yourArea, { areaDialog = true }, value = settings.areaLabel.ifBlank { s.areaNotSet })
        }

        Group(s.groupNetworkPermissions) {
            SettingNavRow(s.gatewayMode, { gatewayDialog = true }, value = gatewayName(GatewayChoice.effective(settings.gatewayMode), s))
            SettingSwitchRow(
                s.helpLocate, settings.helpLocateNeighbours, { v -> onUpdate { it.copy(helpLocateNeighbours = v) } },
                supporting = s.helpLocateHelp,
            )
            PermissionRow(s.permBluetooth, perms.bluetooth, strings, onOpenSystemSettings)
            PermissionRow(s.permLocation, perms.location, strings, onOpenSystemSettings)
            PermissionRow(s.permMicrophone, perms.microphone, strings, onOpenSystemSettings)
        }

        Group(s.groupBeforeDisaster) {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(s.householdPlan, style = MaterialTheme.typography.bodyLarge)
                    Text(s.householdPlanHelp, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PillButton(s.setUp, { comingSoon = true }, style = PillStyle.Tonal)
            }
        }

        // Last and small: today's developer settings, unchanged.
        SettingNavRow(
            s.developer, onDeveloper, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
    }

    if (languageSheet) LanguageSheet(settings.effectiveLanguage, settings.offeredLanguages, s.chooseLanguage, onPick = { l ->
        onUpdate { it.withLanguage(l) }
        languageSheet = false
    }, onDismiss = { languageSheet = false })

    if (areaDialog) AreaDialog(settings.areaLabel, strings, onSave = { v ->
        onUpdate { it.copy(areaLabel = v) }
        areaDialog = false
    }, onDismiss = { areaDialog = false })

    if (gatewayDialog) GatewayDialog(GatewayChoice.effective(settings.gatewayMode), strings, onPick = { m ->
        onUpdate { it.copy(gatewayMode = m) }
        gatewayDialog = false
    }, onDismiss = { gatewayDialog = false })

    if (comingSoon) ComingSoonSheet(s.comingSoonTitle, s.comingSoonBody, strings.common.close) { comingSoon = false }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
    )
    SectionCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) { content() }
}

@Composable
private fun LabelledChoice(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        content()
    }
}

private fun gatewayName(m: GatewayMode, s: org.relay.app.ui.i18n.NetworkSettingsStrings) =
    if (m == GatewayMode.NEVER) s.gatewayNever else s.gatewayAutomatic

@Composable
private fun PermissionRow(title: String, word: PermissionWord, strings: Strings, onClick: () -> Unit) {
    val s = strings.network
    val c = RelayTheme.colors
    val (text, color) = when (word) {
        PermissionWord.ALLOWED -> s.permAllowed to c.success
        PermissionWord.ASK_EACH_TIME -> s.permAsk to c.warningText
        PermissionWord.NOT_ALLOWED -> s.permNotAllowed to MaterialTheme.colorScheme.error
    }
    SettingNavRow(title, onClick, value = text, valueColor = color)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSheet(current: Lang, offered: List<Lang>, title: String, onPick: (Lang) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
            Column(Modifier.selectableGroup()) {
                AppLanguages.offered(offered).forEach { e ->
                    RadioRow(selected = e.lang == current, onClick = { onPick(e.lang) }) {
                        Text(e.nativeName, style = MaterialTheme.typography.bodyLarge)
                        Text(e.englishName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun AreaDialog(current: String, strings: Strings, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val s = strings.network
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.yourArea) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(AREA_MAX) }, singleLine = true,
                supportingText = { Text(s.areaHelp) }, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.common.cancel) } },
    )
}

/** Automatic or Never. ALWAYS is not offered: GatewayManager has no behaviour that tells it apart from Automatic. */
@Composable
private fun GatewayDialog(current: GatewayMode, strings: Strings, onPick: (GatewayMode) -> Unit, onDismiss: () -> Unit) {
    val s = strings.network
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.gatewayMode) },
        text = {
            Column(Modifier.selectableGroup()) {
                GatewayChoice.options.forEach { m ->
                    RadioRow(selected = m == current, onClick = { onPick(m) }) {
                        Text(gatewayName(m, s), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (m == GatewayMode.NEVER) s.gatewayNeverHelp else s.gatewayAutomaticHelp,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.common.close) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComingSoonSheet(title: String, body: String, closeText: String, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PillButton(closeText, onDismiss, style = PillStyle.Tonal)
        }
    }
}

// ---------------------------------------------------------------- previews

private val previewPerms = PermissionStates(PermissionWord.ALLOWED, PermissionWord.ALLOWED, PermissionWord.ASK_EACH_TIME)

@Preview(showBackground = true, heightDp = 1500)
@Composable
private fun SettingsPreview() = RelayPreview {
    SettingsContent(Settings(areaLabel = "Ward 4, Puri district"), previewPerms, stringsFor(Lang.EN), {}, {}, {})
}

@Preview(showBackground = true, heightDp = 1500)
@Composable
private fun SettingsDarkPreview() = RelayPreview(dark = true) {
    SettingsContent(
        Settings(themeMode = ThemeMode.DARK, readAloud = true, language = Lang.HI),
        PermissionStates(PermissionWord.NOT_ALLOWED, PermissionWord.ASK_EACH_TIME, PermissionWord.ALLOWED), stringsFor(Lang.HI), {}, {}, {},
    )
}
