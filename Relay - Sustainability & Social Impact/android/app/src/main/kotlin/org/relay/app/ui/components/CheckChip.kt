package org.relay.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.ui.theme.RelayTheme

/** Green "understood" chip with a check: what the intake engine has picked up so far. 8 dp radius. */
@Composable
fun CheckChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = RelayTheme.colors.successContainer,
        contentColor = RelayTheme.colors.onSuccessContainer,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = RelayTheme.colors.success)
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CheckChipPreview() = RelayPreview {
    CheckChip("Drinking water")
    CheckChip("6 people")
}

@Preview(showBackground = true)
@Composable
private fun CheckChipDarkPreview() = RelayPreview(dark = true) {
    CheckChip("Near the primary school")
}
