package org.relay.app.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Tonal chip with a speaker icon and [label] ("Hear it again", "Hear it in Hindi"). [onClick] should call the Speaker. */
@Composable
fun SpeakButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        modifier = modifier.height(32.dp),
        leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = MaterialTheme.shapes.small,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = null,
    )
}

/** Icon-only speaker (end of a language row or a question). [contentDescription] says what it speaks. */
@Composable
fun SpeakIconButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.primary)
    }
}

@Preview(showBackground = true)
@Composable
private fun SpeakButtonPreview() = RelayPreview {
    SpeakButton("Hear it again", {})
    SpeakIconButton("Hear it", {})
}

@Preview(showBackground = true)
@Composable
private fun SpeakButtonDarkPreview() = RelayPreview(dark = true) {
    SpeakButton("Hear it in Hindi", {})
}
