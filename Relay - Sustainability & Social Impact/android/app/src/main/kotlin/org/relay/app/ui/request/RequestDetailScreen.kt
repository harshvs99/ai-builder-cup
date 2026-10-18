package org.relay.app.ui.request

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.relay.app.MeshRepository
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings

// PLACEHOLDER owned by the Request agent: Request detail. The bottom bar stays visible (Requests tab is
// highlighted). Look the request up with MeshRepository.myNeeds; it may be missing (e.g. after Clear my needs).

@Composable
fun RequestDetailScreen(nav: RelayNav, msgId: String) {
    val s = LocalStrings.current
    val needs by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    val need = needs.firstOrNull { it.msgIdHex == msgId }
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(s.request.title, onBack = nav::back)
        Column(Modifier.padding(16.dp)) {
            Text("Placeholder for the Request agent. msgId=$msgId found=${need != null}")
        }
    }
}
