package org.relay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.relay.app.VillagerStatus
import org.relay.app.ui.theme.RelayTheme

/** Status is always a colour plus a word; [Tone] is the colour. */
enum class Tone { Good, Warning, Neutral, Bad }

/** Text-safe colour for a [Tone] on a surface (waiting/amber uses a darker amber in light theme). */
@Composable
fun Tone.color(): Color = when (this) {
    Tone.Good -> RelayTheme.colors.success
    Tone.Warning -> RelayTheme.colors.warningText
    Tone.Neutral -> MaterialTheme.colorScheme.outline
    Tone.Bad -> MaterialTheme.colorScheme.error
}

fun VillagerStatus.Dot.tone(): Tone = when (this) {
    VillagerStatus.Dot.GREEN -> Tone.Good
    VillagerStatus.Dot.AMBER -> Tone.Warning
    VillagerStatus.Dot.GREY -> Tone.Neutral
}

/** A small filled circle in the colour of [tone]. Decorative: pair it with a word. */
@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Spacer(modifier.size(size).background(tone.color(), CircleShape))
}

/** "● Reachable": a [StatusDot] followed by [text] in the same colour. */
@Composable
fun StatusLabel(tone: Tone, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(tone)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = tone.color())
    }
}

@Preview(showBackground = true)
@Composable
private fun StatusDotPreview() = RelayPreview {
    StatusLabel(Tone.Good, "Reachable")
    StatusLabel(Tone.Warning, "Waiting")
    StatusLabel(Tone.Neutral, "Searching")
    StatusLabel(Tone.Bad, "Failing")
}

@Preview(showBackground = true)
@Composable
private fun StatusDotDarkPreview() = RelayPreview(dark = true) {
    StatusLabel(Tone.Good, "Reachable")
    StatusLabel(Tone.Warning, "Waiting")
    StatusLabel(Tone.Neutral, "Searching")
}
