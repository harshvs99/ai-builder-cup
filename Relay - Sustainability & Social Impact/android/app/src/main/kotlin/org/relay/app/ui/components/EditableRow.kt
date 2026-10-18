package org.relay.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.ui.i18n.LocalStrings

/**
 * One row of the "Is this right?" card: small [label], the [value], an optional [secondary] line and a "Change"
 * text button at the end. [secondaryAction] is a slot under the secondary line (e.g. a "Turn on" button).
 */
@Composable
fun EditableRow(
    label: String,
    value: String,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    secondaryColor: Color = Color.Unspecified,
    changeLabel: String = LocalStrings.current.common.change,
    secondaryAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (secondary != null) {
                Text(
                    secondary, style = MaterialTheme.typography.bodyMedium,
                    color = if (secondaryColor == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else secondaryColor,
                )
            }
            secondaryAction?.invoke()
        }
        TextButton(onClick = onChange) { Text(changeLabel) }
    }
}

@Preview(showBackground = true)
@Composable
private fun EditableRowPreview() = RelayPreview {
    SectionCard {
        EditableRow("Need", "Drinking water", {})
        EditableRow("People", "6 · children, elderly", {})
        EditableRow("Where", "Near the primary school", {}, secondary = "GPS · about 40 m accuracy")
    }
}

@Preview(showBackground = true)
@Composable
private fun EditableRowDarkPreview() = RelayPreview(dark = true) {
    SectionCard { EditableRow("Note", "Pump broken since yesterday", {}) }
}
