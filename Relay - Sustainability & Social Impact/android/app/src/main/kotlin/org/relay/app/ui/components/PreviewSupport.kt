package org.relay.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.relay.app.ThemeMode
import org.relay.app.ui.theme.RelayTheme

/** Wraps a `@Preview` in the Relay theme on a page background, light or dark. */
@Composable
fun RelayPreview(dark: Boolean = false, content: @Composable () -> Unit) {
    RelayTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}
