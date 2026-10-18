package org.relay.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.store.MyNeed
import org.relay.app.store.RequestTimeline
import org.relay.app.ui.i18n.CommonStrings
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

/** "Drinking water · 6 people": the item, or the category name when there is none, then the head count. */
fun requestTitle(need: MyNeed, c: CommonStrings): String =
    "${need.item.ifBlank { c.categoryName(need.category) }.replaceFirstChar { it.uppercase() }} · ${c.people(need.people)}"

/** The trailing label: the urgency word while open, "Closed" when resolved, "Cancelled" when cancelled. */
fun requestTrailing(need: MyNeed, c: CommonStrings): String = when {
    need.cancelled -> c.statusCancelled
    need.isClosed -> c.statusClosed
    else -> c.urgencyWord(need.urgency)
}

/**
 * One request in a list (Home and Requests tab): category circle (green check when closed), title, the latest
 * timeline step with relative time, and the urgency / Closed / Cancelled label. Tap opens the request.
 */
@Composable
fun RequestRow(need: MyNeed, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalStrings.current.common
    val title = requestTitle(need, c)
    val line = c.rowStatusLine(RequestTimeline.latest(need, now), now)
    val trailing = requestTrailing(need, c)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = "$title. $line. $trailing" }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryIcon(need.category, closed = need.isClosed)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            val loud = !need.isClosed && need.urgency >= 2
            Text(
                trailing, style = MaterialTheme.typography.labelSmall,
                color = if (loud) RelayTheme.colors.warningText else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun sample(item: String, cat: Category, urgency: Int, ack: Int = -1, cancelled: Boolean = false): MyNeed {
    val now = System.currentTimeMillis()
    return MyNeed(
        msgIdHex = "7f3a0001", createdAtMs = now - 180_000, category = cat, urgency = urgency, people = 6,
        item = item, landmark = "", summary = "", lang = Lang.EN, needPayloadB64 = "", sentToMesh = true, ackStatus = ack,
        sentToMeshAtMs = now - 120_000, receivedAtMs = if (ack >= 0) now - 120_000 else 0,
        resolvedAtMs = if (ack >= 3) now - 90_000_000 else 0,
        cancelled = cancelled, cancelledAtMs = if (cancelled) now - 120_000 else 0,
    )
}

@Composable
private fun RowsDemo() {
    val now = System.currentTimeMillis()
    Column {
        RequestRow(sample("Drinking water", Category.WATER, 2), now, {})
        RequestRow(sample("", Category.MEDICAL, 3, ack = 0), now, {})
        RequestRow(sample("Rice and dal", Category.FOOD, 1, ack = 3), now, {})
        RequestRow(sample("", Category.SHELTER, 0, cancelled = true), now, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun RequestRowPreview() = RelayPreview { RowsDemo() }

@Preview(showBackground = true)
@Composable
private fun RequestRowDarkPreview() = RelayPreview(dark = true) { RowsDemo() }
