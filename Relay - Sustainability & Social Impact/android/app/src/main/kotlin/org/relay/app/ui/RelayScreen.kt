package org.relay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.MeshRepository
import java.text.DateFormat
import java.util.Date

@Composable
fun RelayScreen() {
    val status by MeshRepository.status.collectAsStateWithLifecycle()
    val log by MeshRepository.logLines.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Button(
                onClick = { MeshRepository.engine?.resyncNow() },
                enabled = status.serviceRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Resync now") }
        }
        item {
            InfoCard {
                KeyValue("Mesh service", if (status.serviceRunning) "running" else "stopped")
                KeyValue("Bluetooth", if (status.bluetoothOn) "on" else "off")
                val t = status.transport
                KeyValue("BLE transport", if (t == null) "-" else if (t.running) "running" else "waiting")
                if (t != null) {
                    KeyValue("Scanning / advertising", "${t.scanning} / ${t.advertising}")
                    KeyValue("Central / peripheral links", "${t.centralLinks} / ${t.peripheralLinks}")
                    t.connecting?.let { KeyValue("Connecting", it) }
                }
            }
        }
        item {
            InfoCard {
                KeyValue("Gateway", if (status.isGateway) "yes (internet=${status.internetAvailable})" else "no")
                KeyValue("Hops to gateway", if (status.hopsToGateway >= 0xFF) "unknown" else status.hopsToGateway.toString())
                if (status.isGateway) {
                    KeyValue("Pending uploads", status.pendingUploads.toString())
                    KeyValue("Last sync", if (status.lastSyncOkAtMs == 0L) "never" else DateFormat.getTimeInstance().format(Date(status.lastSyncOkAtMs)))
                    status.lastSyncError?.let { Text("Sync error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { SectionTitle("Peers (${status.peers.size})") }
        if (status.peers.isEmpty()) {
            item { Text("No live links.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(status.peers, key = { it.linkId }) { p ->
                InfoCard {
                    Text(p.linkId, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("node ${p.nodeIdHex ?: "(awaiting HELLO)"}", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (p.isGateway) Pill("gateway", Color(0xFF1B7F3B))
                        Text("hops: ${if (p.hops >= 0xFF) "?" else p.hops.toString()}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item {
            SectionTitle("Store and stats")
            InfoCard {
                KeyValue("Stored NEEDs", status.storedNeeds.toString())
                KeyValue("Stored ACKs", status.storedAcks.toString())
                val s = status.stats
                KeyValue("Frames received", s.framesReceived.toString())
                KeyValue("Frames rejected", s.framesRejected.toString())
                KeyValue("Duplicates", s.duplicates.toString())
                KeyValue("Frames sent", s.framesSent.toString())
                KeyValue("NEEDs delivered", s.needsDelivered.toString())
                KeyValue("ACKs delivered", s.acksDelivered.toString())
            }
        }
        item { SectionTitle("Log (last ${log.size})") }
        items(log.asReversed()) { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.fillMaxWidth())
        }
        item { Column {} }
    }
}
