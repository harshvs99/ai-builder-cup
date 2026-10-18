package org.relay.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.ui.i18n.LocalStrings

/**
 * Top bar of the flow screens: back arrow, [title] and an optional [stepText] ("1 of 3", see
 * `AskStrings.stepCounter`) at the end. [actions] is a slot after the step text (e.g. a share icon).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepTopAppBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    stepText: String? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = LocalStrings.current.common.back)
            }
        },
        actions = {
            if (stepText != null) {
                Text(
                    stepText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Preview(showBackground = true)
@Composable
private fun StepTopAppBarPreview() = RelayPreview { StepTopAppBar("New request", {}, stepText = "1 of 3") }

@Preview(showBackground = true)
@Composable
private fun StepTopAppBarDarkPreview() = RelayPreview(dark = true) { StepTopAppBar("Check and send", {}, stepText = "2 of 3") }
