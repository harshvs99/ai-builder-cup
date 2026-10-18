package org.relay.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

enum class PillStyle { Filled, Tonal, Outlined }

/**
 * The app's full-width pill button, 56 dp tall (more with larger text). One [PillStyle.Filled] per screen is the
 * primary action. [icon] shows before the text.
 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
) {
    val m = modifier.let { if (fillWidth) it.fillMaxWidth() else it }.heightIn(min = 56.dp)
    val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    val pad = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    when (style) {
        PillStyle.Filled -> Button(onClick, m, enabled, shape = CircleShape, contentPadding = pad, content = content)
        PillStyle.Tonal -> FilledTonalButton(
            onClick, m, enabled, shape = CircleShape, contentPadding = pad,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            content = content,
        )
        PillStyle.Outlined -> OutlinedButton(onClick, m, enabled, shape = CircleShape, contentPadding = pad, content = content)
    }
}

@Preview(showBackground = true)
@Composable
private fun PillButtonPreview() = RelayPreview {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Send", {}, icon = Icons.AutoMirrored.Filled.Send)
        PillButton("Type instead", {}, style = PillStyle.Tonal)
        PillButton("Change something", {}, style = PillStyle.Outlined)
        PillButton("Disabled", {}, enabled = false)
    }
}

@Preview(showBackground = true)
@Composable
private fun PillButtonDarkPreview() = RelayPreview(dark = true) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Continue", {})
        PillButton("Type instead", {}, style = PillStyle.Tonal)
        PillButton("Change something", {}, style = PillStyle.Outlined)
    }
}
