package org.relay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import org.relay.app.store.RequestTimeline
import org.relay.app.ui.i18n.CommonStrings
import org.relay.app.ui.theme.RelayTheme

enum class TimelineState { Done, Current, Future }

/** One display row of [Timeline]. [tag] is a small chip beside the title (e.g. "LIVE"). */
data class TimelineStep(
    val title: String,
    val state: TimelineState,
    val subtitle: String? = null,
    val tag: String? = null,
)

/** Maps the pure [RequestTimeline] steps to display rows in the app language. */
fun List<RequestTimeline.Step>.toTimelineSteps(common: CommonStrings): List<TimelineStep> = map { s ->
    TimelineStep(
        title = common.stepTitle(s.key),
        state = if (s.state == RequestTimeline.State.CURRENT) TimelineState.Current else TimelineState.Done,
        subtitle = common.stepSubtitle(s).ifBlank { null },
        tag = if (s.live) common.liveTag else null,
    )
}

/**
 * Vertical timeline: done steps are green check circles joined by a line, the current step an outlined primary
 * circle, future steps (if any are passed) a quiet grey ring.
 */
@Composable
fun Timeline(steps: List<TimelineStep>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, step ->
            val last = i == steps.lastIndex
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Marker(step.state)
                    if (!last) {
                        Box(
                            Modifier.width(2.dp).weight(1f).background(
                                if (step.state == TimelineState.Done) RelayTheme.colors.success.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            step.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (step.state == TimelineState.Future) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (step.tag != null) {
                            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                                Text(step.tag, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    if (step.subtitle != null) {
                        Text(step.subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Marker(state: TimelineState) {
    when (state) {
        TimelineState.Done -> Box(Modifier.size(24.dp).background(RelayTheme.colors.success, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = RelayTheme.colors.onSuccess, modifier = Modifier.size(16.dp))
        }
        TimelineState.Current -> Box(Modifier.size(24.dp).border(3.dp, MaterialTheme.colorScheme.primary, CircleShape))
        TimelineState.Future -> Box(Modifier.size(24.dp).border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
    }
}

private val previewSteps = listOf(
    TimelineStep("Saved on this phone", TimelineState.Done, "2:02 PM"),
    TimelineStep("Passed to a neighbour's phone", TimelineState.Done, "2:03 PM"),
    TimelineStep("Reached a gateway phone", TimelineState.Done, "2:05 PM · uploaded to the control room"),
    TimelineStep("Team on the way", TimelineState.Current, "Expected by 4:30 PM", tag = "LIVE"),
)

@Preview(showBackground = true)
@Composable
private fun TimelinePreview() = RelayPreview { Timeline(previewSteps) }

@Preview(showBackground = true)
@Composable
private fun TimelineDarkPreview() = RelayPreview(dark = true) { Timeline(previewSteps) }
