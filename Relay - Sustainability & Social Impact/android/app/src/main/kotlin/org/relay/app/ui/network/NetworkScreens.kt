package org.relay.app.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.GatewayMode
import org.relay.app.MeshRepository
import org.relay.app.MeshStatus
import org.relay.app.PeerInfo
import org.relay.app.Settings
import org.relay.app.VillagerStatus
import org.relay.app.ui.DeveloperSettingsScreen
import org.relay.app.ui.RelayNav
import org.relay.app.ui.RelayScreen
import org.relay.app.ui.components.RelayPreview
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SettingSwitchRow
import org.relay.app.ui.components.StatTile
import org.relay.app.ui.components.StatusLabel
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.components.tone
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.Strings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.rememberNowMs
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Lang

/** Network tab (design spec section 7). */
@Composable
fun NetworkScreen(nav: RelayNav) {
    val status by MeshRepository.status.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val myNeeds by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    val nowMs by rememberNowMs(5_000L)
    val villager = VillagerStatus.from(status, nowMs)
    val stats = remember(status, myNeeds) {
        // Own NEEDs still held in the packet store (cancelled ones were dropped from it).
        val own = myNeeds.filter { !it.cancelled && MeshRepository.packetStore.contains(it.msgIdHex) }
        NetworkStats.from(status, ownStored = own.size, ownDelivered = own.count { it.uploadedAtMs > 0 || it.receivedAtMs > 0 })
    }
    NetworkContent(
        villager, stats, settings, LocalStrings.current,
        onConsole = nav::openConsole,
        onGateway = { on -> MeshRepository.settings.update { it.copy(gatewayMode = GatewayChoice.fromToggle(on)) } },
        onCarry = { on -> MeshRepository.settings.update { it.copy(carryInBackground = on) } },
    )
}

@Composable
internal fun NetworkContent(
    villager: VillagerStatus,
    stats: NetworkStats,
    settings: Settings,
    strings: Strings,
    onConsole: () -> Unit,
    onGateway: (Boolean) -> Unit,
    onCarry: (Boolean) -> Unit,
) {
    val s = strings.network
    val common = strings.common
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.networkTitle, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.meshWord} · ${common.phones(villager.peers)} · ",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusLabel(villager.dot.tone(), common.villagerWord(villager))
                }
            }
            // "< >": the developer console, every number kept.
            IconButton(onClick = onConsole) {
                Icon(Icons.Outlined.Code, contentDescription = s.consoleDescription)
            }
        }

        SectionCard(title = common.villagerTitle(villager)) {
            Text(s.statusSentence(villager), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ChainDiagram(ChainModel.from(villager), s)
        }

        val hours = when (val v = MeshHoursText.of(stats)) {
            MeshHoursText.Value.Unknown -> s.unknownValue
            MeshHoursText.Value.Charging -> s.charging
            MeshHoursText.Value.UnderOne -> s.hoursUnder1
            is MeshHoursText.Value.Hours -> s.hoursValue(v.n)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TileRow(s.phonesNearby, stats.phonesNearby.toString(), s.carriedForNeighbours, stats.carriedForNeighbours.toString())
            // "Battery used today" needs a measured per-day figure; until there is one this shows the estimate we have.
            TileRow(s.waitingToPassOn, stats.waitingToPassOn.toString(), s.meshHoursLeft, hours, RelayTheme.colors.success)
        }

        SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            SettingSwitchRow(
                s.gatewayToggle, GatewayChoice.isOn(settings.gatewayMode), onGateway, supporting = s.gatewayToggleHelp,
            )
            SettingSwitchRow(s.carryToggle, settings.carryInBackground, onCarry, supporting = s.carryToggleHelp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TileRow(
    labelA: String, valueA: String, labelB: String, valueB: String,
    colorB: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(labelA, valueA, Modifier.weight(1f).fillMaxHeight())
        StatTile(labelB, valueB, Modifier.weight(1f).fillMaxHeight(), valueColor = colorB)
    }
}

/** Today's Relay tab: every number and the log. */
@Composable
fun ConsoleScreen(nav: RelayNav) {
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(LocalStrings.current.network.consoleDescription, onBack = nav::back)
        Box(Modifier.weight(1f)) { RelayScreen() }
    }
}

/** Today's Settings tab: backend, device key, allowlist, permissions, clear store, node id. */
@Composable
fun DeveloperScreen(nav: RelayNav) {
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(LocalStrings.current.network.developer, onBack = nav::back)
        Box(Modifier.weight(1f)) { DeveloperSettingsScreen(nav.setup) }
    }
}

// ---------------------------------------------------------------- previews

private fun previewStatus(peers: Int, hops: Int, gateway: Boolean = false) = MeshStatus(
    serviceRunning = true, bluetoothOn = true,
    peers = List(peers) { PeerInfo("l$it", "n$it", false, 1) },
    isGateway = gateway, hopsToGateway = hops, storedNeeds = 4, storedAcks = 9,
    pendingUploads = 1, meshHoursRemaining = 38.4, batteryPercent = 71,
)

private fun previewStats(s: MeshStatus) = NetworkStats.from(s, ownStored = 1, ownDelivered = 0)

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun NetworkPreview() = RelayPreview {
    val st = previewStatus(3, 2)
    NetworkContent(VillagerStatus.from(st, 0), previewStats(st), Settings(), stringsFor(Lang.EN), {}, {}, {})
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun NetworkDarkPreview() = RelayPreview(dark = true) {
    val st = previewStatus(0, 0xFF)
    NetworkContent(
        VillagerStatus.from(st, 0), previewStats(st), Settings(gatewayMode = GatewayMode.NEVER, carryInBackground = false),
        stringsFor(Lang.EN), {}, {}, {},
    )
}
