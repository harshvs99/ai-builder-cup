package org.relay.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.ui.theme.RelayTheme

/**
 * Equal-width segmented single choice over any [options]. The selected segment is filled with primaryContainer, or
 * with the warning colour when [warningSelected] (the urgency control). [label] gives each option's text.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    warningSelected: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(12.dp)
    val selectedFill = if (warningSelected) RelayTheme.colors.warning else MaterialTheme.colorScheme.primaryContainer
    val selectedText = if (warningSelected) RelayTheme.colors.onWarning else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.selectableGroup()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .selectable(selected = isSelected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = if (isSelected) selectedFill else androidx.compose.ui.graphics.Color.Transparent,
                        contentColor = if (isSelected) selectedText else MaterialTheme.colorScheme.onSurface,
                        shape = shape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                label(option),
                                style = MaterialTheme.typography.labelLarge,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SegmentedChoicePreview() = RelayPreview {
    val urgency = listOf("Can wait", "Soon", "Urgent", "Life at risk")
    SegmentedChoice(urgency, "Urgent", {}, { it }, warningSelected = true)
    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
    SegmentedChoice(listOf("Slow", "Normal"), "Normal", {}, { it })
}

@Preview(showBackground = true)
@Composable
private fun SegmentedChoiceDarkPreview() = RelayPreview(dark = true) {
    SegmentedChoice(listOf("Light", "Dark", "Auto"), "Auto", {}, { it })
}
