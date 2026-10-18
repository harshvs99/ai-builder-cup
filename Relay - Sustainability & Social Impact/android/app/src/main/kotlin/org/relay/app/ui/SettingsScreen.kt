package org.relay.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.relay.app.BackendDefaults
import org.relay.app.gateway.HealthKind
import org.relay.app.gateway.SyncClient
import org.relay.app.gateway.SyncHealthText
import org.relay.app.MeshRepository
import org.relay.app.Settings
import org.relay.app.ble.Allowlist
import org.relay.app.ble.BlePermissions

@Composable
fun DeveloperSettingsScreen(actions: SetupActions) {
    val ctx = LocalContext.current
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    var backend by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    var key by remember(settings.deviceKey) { mutableStateOf(settings.deviceKey) }
    var allow by remember(settings.allowlist) { mutableStateOf(settings.allowlist) }
    var confirmClear by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val status by MeshRepository.status.collectAsStateWithLifecycle()
    val nowMs by rememberNowMs()
    val defaultBackend = MeshRepository.settings.defaultBackend
    // Release builds: backend and key are fixed at build time, so their fields and the probe are hidden.
    val locked = MeshRepository.settings.connectionLocked

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { DisasterLab() }
        item { GemmaLab() }
        item { SectionTitle("Gateway backend") }
        if (!locked) item {
            OutlinedTextField(
                value = backend, onValueChange = { backend = it }, label = { Text("Backend URL") }, singleLine = true,
                supportingText = { Text("Default on this device: $defaultBackend") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            val line = SyncHealthText.line(status.syncHealth, status.isGateway, BackendDefaults.hostOf(settings.backendUrl), nowMs)
            val color = when (line.kind) {
                HealthKind.OK -> COLOR_OK
                HealthKind.FAILING -> COLOR_BAD
                else -> COLOR_NEUTRAL
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(line.text, color = color, fontWeight = FontWeight.SemiBold)
                if (line.kind == HealthKind.FAILING) {
                    SyncHealthText.failureDetail(status.syncHealth, nowMs)?.let { Text(it, color = color, style = MaterialTheme.typography.bodySmall) }
                }
                if (!locked) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !probing, onClick = {
                        val target = backend.trim().ifEmpty { defaultBackend }
                        probing = true; probe = "Testing ${BackendDefaults.hostOf(target)}…"; probeOk = false
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { SyncClient().probe(target, 5_000) }
                            probeOk = r.ok
                            probe = if (r.ok) "✓ ${BackendDefaults.hostOf(target)} ${r.detail}, ${r.latencyMs} ms" else "✗ ${BackendDefaults.hostOf(target)}: ${r.detail}"
                            probing = false
                        }
                    }) { Text("Test connection") }
                    probe?.let { Text(it, color = if (probing) COLOR_NEUTRAL else if (probeOk) COLOR_OK else COLOR_BAD, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
                }
            }
        }
        if (!locked) item {
            OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("Device key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(
                value = allow, onValueChange = { allow = it }, label = { Text("Chain allowlist (node-id hex prefixes, comma separated)") },
                supportingText = { Text("Demo only. Empty = talk to every Relay phone. Peers not matching are disconnected and ignored for 60 s.") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    MeshRepository.settings.update {
                        it.copy(
                            backendUrl = backend.trim().ifEmpty { defaultBackend },
                            deviceKey = key.trim().ifEmpty { Settings.DEFAULT_KEY },
                            allowlist = Allowlist.parse(allow).joinToString(","),
                        )
                    }
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    backend = defaultBackend; key = Settings.DEFAULT_KEY; allow = ""
                }) { Text("Defaults") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pretend offline (never act as gateway)", Modifier.weight(1f))
                Switch(checked = settings.pretendOffline, onCheckedChange = { v -> MeshRepository.settings.update { it.copy(gatewayMode = if (v) org.relay.app.GatewayMode.NEVER else org.relay.app.GatewayMode.AUTOMATIC) } })
            }
        }
        item { SectionTitle("This phone") }
        item {
            InfoCard {
                Text("Node id", style = MaterialTheme.typography.labelMedium)
                Text(MeshRepository.nodeIdHex, fontFamily = FontFamily.Monospace)
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Relay node id", MeshRepository.nodeIdHex))
                    // Android 13+ shows its own confirmation.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            }
        }
        item { SectionTitle("Bluetooth and permissions") }
        item {
            val bt = remember(actions.tick) { BlePermissions.isBluetoothOn(ctx) }
            val missing = remember(actions.tick) { BlePermissions.missingShort(ctx) }
            InfoCard {
                KeyValue("Bluetooth LE", if (!BlePermissions.hasBle(ctx)) "not supported" else if (bt) "on" else "off")
                KeyValue("Permissions", if (missing.isEmpty()) "all granted" else "missing: ${missing.joinToString()}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (missing.isNotEmpty()) Button(onClick = actions.requestAll) { Text("Grant") }
                    if (!bt) OutlinedButton(onClick = actions.enableBluetooth) { Text("Enable Bluetooth") }
                    OutlinedButton(onClick = actions.openAppSettings) { Text("App settings") }
                }
            }
        }
        item { SectionTitle("Data") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Clear store") }
                OutlinedButton(onClick = { MeshRepository.clearMyNeeds() }) { Text("Clear my needs") }
            }
        }
        item {
            Text(
                "POC build: the backend connection is plain HTTP (cleartext allowed by the network security config).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear store?") },
            text = { Text("Deletes every stored NEED and ACK on this phone, including packets it is relaying for others. They may come back from neighbours.") },
            confirmButton = { TextButton(onClick = { MeshRepository.clearStore(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
