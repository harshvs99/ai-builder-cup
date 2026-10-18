package org.relay.app.ui.ask

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.lifecycle.ViewModelStoreOwner
import org.relay.app.ui.AskMode
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.i18n.LocalStrings

// PLACEHOLDERS owned by the Ask agent: New request (step 1 of 3) and Check and send (step 2 of 3). The bottom bar
// is hidden on both. Share state between them with `viewModel(viewModelStoreOwner = flowOwner)` where flowOwner is
// the `ask flow` graph entry (RelayNav.askFlowOwner()): one AskViewModel, alive from first ask to Send.
// Flow screens own their insets: StepTopAppBar handles the status bar; add navigationBarsPadding()/imePadding()
// to the bottom buttons.

@Composable
fun AskScreen(nav: RelayNav, mode: AskMode, prefillFrom: String?, flowOwner: ViewModelStoreOwner) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(s.ask.newRequestTitle, onBack = nav::back, stepText = s.ask.stepCounter(1, 3))
        Column(Modifier.padding(16.dp)) {
            Text("Placeholder for the Ask agent. mode=${mode.arg} prefillFrom=$prefillFrom")
            PillButton(s.common.continueAction, { nav.toCheck() })
        }
    }
}

@Composable
fun CheckScreen(nav: RelayNav, flowOwner: ViewModelStoreOwner) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxSize()) {
        StepTopAppBar("Check and send", onBack = nav::back, stepText = s.ask.stepCounter(2, 3))
        Column(Modifier.padding(16.dp)) { Text("Placeholder for the Ask agent.") }
    }
}
