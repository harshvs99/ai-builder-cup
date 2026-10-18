package org.relay.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.MeshRepository
import org.relay.app.rememberPermissions
import org.relay.app.ui.AskMode
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.PlaceholderScreen
import org.relay.app.ui.i18n.AppLanguages
import org.relay.app.ui.i18n.LocalStrings

// PLACEHOLDERS owned by the Home agent: language (first run), home, requests. Replace the bodies; keep the
// signatures (RelayRoot calls them) or change RelayRoot's call site in the same commit.

/** First run: pick the app language, then Home. Placeholder: one button per language. */
@Composable
fun LanguageScreen(nav: RelayNav) {
    PlaceholderScreen("Choose your language", "Placeholder for the Home agent.") {
        AppLanguages.all.forEach { e ->
            PillButton("${e.nativeName} / ${e.englishName}", {
                MeshRepository.settings.update { it.copy(language = e.lang, languageConfirmed = true) }
                nav.languageDone()
            }, style = PillStyle.Tonal)
        }
    }
}

@Composable
fun HomeScreen(nav: RelayNav) {
    val s = LocalStrings.current
    val permissions = rememberPermissions()
    // Bluetooth + notifications when Home first shows (the real screen explains why in one sentence first).
    LaunchedEffect(Unit) { permissions.ensureBluetooth() }
    val status by MeshRepository.status.collectAsStateWithLifecycle()
    PlaceholderScreen(s.home.title, "Placeholder for the Home agent. Bluetooth peers: ${status.peers.size}") {
        PillButton("Press and say what you need", { nav.startAsk(AskMode.VOICE) })
        PillButton("Type instead", { nav.startAsk(AskMode.TEXT) }, style = PillStyle.Tonal)
        PillButton(s.common.seeAll, { nav.toRequests() }, style = PillStyle.Outlined)
        PillButton("Old home screen (temporary)", { nav.openLegacyHome() }, style = PillStyle.Outlined)
    }
}

@Composable
fun RequestsScreen(nav: RelayNav) {
    val needs by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    PlaceholderScreen("Your requests", "Placeholder for the Home agent.") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            needs.forEach { n ->
                PillButton("${n.item.ifBlank { n.category.name }} · ${n.msgIdHex.take(4)}", { nav.openRequest(n.msgIdHex) }, style = PillStyle.Tonal)
            }
        }
    }
}
