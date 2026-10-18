package org.relay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.relay.app.DisasterStore
import org.relay.app.MeshRepository
import org.relay.app.location.LocationProvider
import org.relay.app.location.MapPack
import org.relay.app.location.MapPackUpdater
import org.relay.core.payload.Geo
import org.relay.core.triage.DisasterType

/**
 * Developer-only: declare a disaster as if the control room had, to try triage's disaster-specific questions. The real source
 * is a BROADCAST arriving over the mesh (SPEC 5.6); this one is put into the mesh by this phone, so it also tests the carrying.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisasterLab() {
    val ctx = LocalContext.current
    val store = remember { DisasterStore.get(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Control-room disaster (developer)")
        tick.let {
            val now = System.currentTimeMillis() / 1000
            val live = store.all().filter { now - it.issuedAtSec <= org.relay.core.triage.DisasterGate.MAX_AGE_SEC }
            Text(
                if (live.isEmpty()) "None declared: triage asks no disaster-specific questions."
                else live.joinToString("\n") { "${it.type.key} · ${(now - it.issuedAtSec) / 3600} h ago" },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DisasterType.entries.forEach { t ->
                OutlinedButton(onClick = {
                    val engine = MeshRepository.engine
                    if (engine != null) { engine.declareDisaster(t); note = "Broadcast ${t.key} sent from this phone" }
                    else { store.put(org.relay.core.triage.DisasterContext(t, System.currentTimeMillis() / 1000)); note = "Mesh not running: ${t.key} set locally" }
                    tick++
                }) { Text("Declare ${t.key}") }
            }
            DisasterType.entries.forEach { t ->
                OutlinedButton(onClick = {
                    val engine = MeshRepository.engine
                    if (engine != null) { engine.declareDisaster(t, cleared = true); note = "All-clear for ${t.key} sent from this phone" }
                    else note = "Mesh not running"
                    tick++
                }) { Text("End ${t.key}") }
            }
            OutlinedButton(onClick = { store.clear(); note = "Cleared on this phone"; tick++ }) { Text("Clear here") }
        }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.labelMedium)

        SectionTitle("Offline map (developer)")
        tick.let {
            val pack = MapPack.stored(ctx)
            Text(
                if (pack == null) "No map on this phone: triage gives no place advice. Fetched automatically outside a disaster."
                else "${pack.places.size} places within ${pack.radiusKm} km, fetched ${(System.currentTimeMillis() - pack.fetchedAtMs) / 3_600_000} h ago",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        OutlinedButton(onClick = {
            val fix = LocationProvider.fix.value
            val here = fix?.let { Geo.toE5(it.lat) to Geo.toE5(it.lon) }
            note = if (MapPackUpdater.inCrisis(ctx, here)) "A disaster is declared here: maps are not downloaded now"
            else { MapPackUpdater.maybeUpdate(ctx, force = true); "Fetching the map in the background" }
            tick++
        }) { Text("Update map now") }
    }
}
