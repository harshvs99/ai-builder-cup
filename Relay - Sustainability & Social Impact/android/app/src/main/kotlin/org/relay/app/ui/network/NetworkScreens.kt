package org.relay.app.ui.network

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.MeshRepository
import org.relay.app.ThemeMode
import org.relay.app.ui.DeveloperSettingsScreen
import org.relay.app.ui.RelayNav
import org.relay.app.ui.RelayScreen
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.PlaceholderScreen
import org.relay.app.ui.components.SegmentedChoice
import org.relay.app.ui.components.SettingSwitchRow
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings

// PLACEHOLDERS owned by the Network/Settings agent: network, console, settings, developer. `console` and
// `developer` wrap today's Relay tab (ui/RelayScreen.kt) and Settings tab (ui/SettingsScreen.kt) unchanged.

@Composable
fun NetworkScreen(nav: RelayNav) {
    PlaceholderScreen(LocalStrings.current.common.navNetwork, "Placeholder for the Network agent.") {
        PillButton("Developer console", { nav.openConsole() }, style = PillStyle.Tonal)
    }
}

/** Today's Relay tab: every number and the log. */
@Composable
fun ConsoleScreen(nav: RelayNav) {
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar("Developer console", onBack = nav::back)
        Box(Modifier.weight(1f)) { RelayScreen() }
    }
}

@Composable
fun SettingsScreen(nav: RelayNav) {
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    PlaceholderScreen(LocalStrings.current.common.navSettings, "Placeholder for the Network/Settings agent.") {
        SegmentedChoice(
            ThemeMode.entries, settings.themeMode,
            { m -> MeshRepository.settings.update { it.copy(themeMode = m) } },
            { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
        )
        SettingSwitchRow("Larger text", settings.largerText, { v -> MeshRepository.settings.update { it.copy(largerText = v) } })
        PillButton("Developer", { nav.openDeveloper() }, style = PillStyle.Tonal)
    }
}

/** Today's Settings tab: backend, device key, allowlist, permissions, clear store, node id. */
@Composable
fun DeveloperScreen(nav: RelayNav) {
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar("Developer", onBack = nav::back)
        Box(Modifier.weight(1f)) { DeveloperSettingsScreen(nav.setup) }
    }
}
