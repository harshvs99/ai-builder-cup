package org.relay.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.ui.theme.RelayTheme

/** A number tile for the Network 2x2 grid: big [value] over a small [label]. Pass `valueColor` for e.g. green. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                value, style = MaterialTheme.typography.headlineMedium,
                color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor,
            )
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StatTilePreview() = RelayPreview {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Phones nearby", "3", Modifier.weight(1f))
        StatTile("Battery used today", "4%", Modifier.weight(1f), valueColor = RelayTheme.colors.success)
    }
}

@Preview(showBackground = true)
@Composable
private fun StatTileDarkPreview() = RelayPreview(dark = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Waiting to pass on", "1", Modifier.weight(1f))
        StatTile("Carried for neighbours", "12", Modifier.weight(1f))
    }
}
