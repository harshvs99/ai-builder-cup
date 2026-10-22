package org.relay.app.ui.ready

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.LocalSpeaker
import org.relay.app.location.LocationProvider
import org.relay.app.MeshRepository
import org.relay.app.ready.ReadyRepository
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings
import org.relay.core.ready.GeoPoint
import org.relay.core.ready.ReadyShelter
import org.relay.core.ready.ShelterLogic
import org.relay.core.ready.ShelterRow

/** The shelters this build may show, nearest first when the phone has a fix. Production builds: only an officer-published list. */
@Composable
private fun rememberShelterRows(): Pair<List<ShelterRow>, org.relay.core.ready.ReadyPack?> {
    val snap by ReadyRepository.snapshot.collectAsStateWithLifecycle()
    val fix by LocationProvider.fix.collectAsStateWithLifecycle()
    val shelters = ShelterLogic.visible(snap.pack, ReadyRepository.demoBuild)
    return ShelterLogic.sorted(shelters, fix?.let { GeoPoint(it.lat, it.lon) }) to snap.pack
}

@Composable
fun ShelterListScreen(nav: RelayNav) {
    val r = LocalStrings.current.ready
    val (rows, pack) = rememberShelterRows()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        StepTopAppBar(r.sheltersTitle, onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rows.isEmpty()) {
                Text(r.noSheltersList, style = MaterialTheme.typography.bodyLarge)
            } else {
                rows.forEach { row ->
                    SectionCard(onClick = { nav.openShelter(row.shelter.id) }, modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(row.shelter.name, r.kindName(row.shelter.kind), row.distanceKm?.let { r.kmAway(it) },
                            row.shelter.capacity?.let { r.capacity(it) }, if (ShelterLogic.isDemo(pack, row.shelter)) r.demoBadge else null).joinToString(". ")
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.shelter.name, style = MaterialTheme.typography.titleMedium)
                                Text(r.kindName(row.shelter.kind), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                row.shelter.capacity?.let { Text(r.capacity(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                row.distanceKm?.let { Text(r.kmAway(it), style = MaterialTheme.typography.titleMedium) }
                                if (ShelterLogic.isDemo(pack, row.shelter)) SuggestionChip(onClick = {}, label = { Text(r.demoBadge) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShelterDetailScreen(nav: RelayNav, id: String) {
    val ctx = LocalContext.current
    val r = LocalStrings.current.ready
    val speaker = LocalSpeaker.current
    val (rows, pack) = rememberShelterRows()
    val row = rows.firstOrNull { it.shelter.id == id }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        StepTopAppBar(row?.shelter?.name ?: r.sheltersTitle, onBack = { nav.back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (row == null) {
                Text(r.shelterNotFound, style = MaterialTheme.typography.bodyLarge)
            } else {
                val s = row.shelter
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    if (s.nameEn != s.name) Text(s.nameEn, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(r.kindName(s.kind), style = MaterialTheme.typography.bodyLarge)
                    if (ShelterLogic.isDemo(pack, s)) SuggestionChip(onClick = {}, label = { Text(r.demoBadge) })
                }
                SectionCard {
                    s.block?.let { Text("${r.address}: $it", style = MaterialTheme.typography.bodyLarge) }
                    s.capacity?.let { Text(r.capacity(it), style = MaterialTheme.typography.bodyLarge) }
                    row.distanceKm?.let { Text(r.kmAway(it), style = MaterialTheme.typography.bodyLarge) }
                    if (s.facilities.isNotEmpty()) Text("${r.facilities}: " + s.facilities.joinToString(", ") { r.facilityName(it) }, style = MaterialTheme.typography.bodyLarge)
                    Text("${r.coordinates}: ${ShelterLogic.coordinatesText(s)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OfflineMap(rows.map { it.shelter }, s)
                PillButton(r.directions, {
                    val ok = try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShelterLogic.geoUri(s)))); true } catch (e: Exception) { false }
                    message = if (ok) null else r.noMapsApp
                }, icon = Icons.Filled.Directions)
                PillButton(r.copyCoordinates, {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("coordinates", ShelterLogic.coordinatesText(s)))
                    message = r.copied
                }, style = PillStyle.Tonal, icon = Icons.Filled.ContentCopy)
                PillButton(r.speakShelter, {
                    val lang = org.relay.core.payload.Lang.fromTag(pack?.lang ?: "en") ?: org.relay.core.payload.Lang.EN
                    val ok = speaker != null && speaker.isVoiceAvailable(lang) && speaker.speak(ShelterLogic.spokenLine(row) { r.kmAway(it) }, lang)
                    message = if (ok) null else r.noVoicePlan
                }, style = PillStyle.Tonal, icon = Icons.AutoMirrored.Filled.VolumeUp)
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/**
 * A map that needs no network: the shelters of the list drawn as pins in the box that holds them (see [ShelterLogic.pins]), the open one
 * highlighted. There is no bundled district imagery yet; "Directions" opens the maps app, which may have offline data of its own.
 */
@Composable
private fun OfflineMap(all: List<ReadyShelter>, selected: ReadyShelter) {
    val r = LocalStrings.current.ready
    val pins = ShelterLogic.pins(all, selected.id)
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.primary
    val chosen = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f).heightIn(min = 160.dp).semantics { contentDescription = r.mapDescription(pins.size) },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val step = size.width / 8f
                var x = 0f
                while (x <= size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height)); x += step }
                var y = 0f
                while (y <= size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y)); y += step }
                pins.filter { !it.selected }.forEach { drawCircle(dot, 7.dp.toPx(), Offset((it.x * size.width).toFloat(), (it.y * size.height).toFloat())) }
                pins.filter { it.selected }.forEach { drawCircle(chosen, 11.dp.toPx(), Offset((it.x * size.width).toFloat(), (it.y * size.height).toFloat())) }
            }
        }
        Text(r.mapNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
