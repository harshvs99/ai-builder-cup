package org.relay.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.theme.RelayTheme

/**
 * Small badge with the urgency word for [urgency] 0..3 (can wait, soon, urgent, life at risk). Urgent is the
 * warning fill, life at risk the error container, the two lower levels a quiet grey. [text] overrides the word.
 */
@Composable
fun UrgencyBadge(urgency: Int, modifier: Modifier = Modifier, text: String = LocalStrings.current.common.urgencyWord(urgency)) {
    val u = urgency.coerceIn(0, 3)
    val container = when (u) {
        3 -> MaterialTheme.colorScheme.errorContainer
        2 -> RelayTheme.colors.warning
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (u) {
        3 -> MaterialTheme.colorScheme.onErrorContainer
        2 -> RelayTheme.colors.onWarning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun UrgencyBadgePreview() = RelayPreview {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (0..3).forEach { UrgencyBadge(it) } }
}

@Preview(showBackground = true)
@Composable
private fun UrgencyBadgeDarkPreview() = RelayPreview(dark = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (0..3).forEach { UrgencyBadge(it) } }
}
