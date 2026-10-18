package org.relay.app.ui.ready

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.relay.app.MeshRepository
import org.relay.app.ready.ReadyRepository
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SettingSwitchRow
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings
import org.relay.core.ready.Household
import org.relay.core.ready.RefreshTrigger

/** Settings → "My household" (contract v2 section 3.2): asked once, editable later, kept on the phone. */
@Composable
fun HouseholdScreen(nav: RelayNav) {
    val ctx = LocalContext.current
    val r = LocalStrings.current.ready
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    var h by remember { mutableStateOf(settings.household) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        StepTopAppBar(r.householdTitle, onBack = { nav.back() })
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(r.householdHelp, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.people, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(
                        onClick = { h = h.copy(people = (h.people - 1).coerceAtLeast(Household.MIN_PEOPLE)) }, enabled = h.people > Household.MIN_PEOPLE,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Remove, contentDescription = r.fewerPeople) }
                    Text(
                        "${h.people}", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "${r.people}: ${h.people}" },
                    )
                    IconButton(
                        onClick = { h = h.copy(people = (h.people + 1).coerceAtMost(Household.MAX_PEOPLE)) }, enabled = h.people < Household.MAX_PEOPLE,
                        modifier = Modifier.size(48.dp),
                    ) { Icon(Icons.Filled.Add, contentDescription = r.morePeople) }
                }
                SettingSwitchRow(r.elderly, h.elderly, { h = h.copy(elderly = it) })
                SettingSwitchRow(r.children, h.children, { h = h.copy(children = it) })
                SettingSwitchRow(r.infants, h.infants, { h = h.copy(infants = it) })
                SettingSwitchRow(r.pregnant, h.pregnant, { h = h.copy(pregnant = it) })
                SettingSwitchRow(r.disability, h.disability, { h = h.copy(disability = it) })
                SettingSwitchRow(r.livestock, h.livestock, { h = h.copy(livestock = it) })
            }
            OutlinedTextField(
                value = h.medicalNeeds, onValueChange = { h = h.copy(medicalNeeds = it.take(Household.MAX_MEDICAL)) },
                label = { Text(r.medical) }, placeholder = { Text(r.medicalHint) },
                supportingText = { Text("${h.medicalNeeds.length}/${Household.MAX_MEDICAL}") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (saved) Text(r.savedHousehold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        PillButton(
            r.saveHousehold,
            {
                MeshRepository.settings.update { it.copy(household = h.normalized(), householdSet = true) }
                saved = true
                // the plan is made for this household: fetch the new one now if there is signal (a no-op without a warning)
                scope.launch { ReadyRepository.refresh(ctx, RefreshTrigger.MANUAL) }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
