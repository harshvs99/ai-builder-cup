package org.relay.app.ui.network

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.relay.app.VillagerStatus
import org.relay.app.ui.components.RelayPreview
import org.relay.app.ui.i18n.NetworkSettingsStrings
import org.relay.app.ui.i18n.stringsFor
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Lang

private val NODE = 48.dp

/** Caption under a node, in the words of [n]. */
internal fun ChainNode.caption(s: NetworkSettingsStrings): String = when (kind) {
    ChainNode.Kind.YOU -> s.chainThisPhone
    ChainNode.Kind.YOU_GATEWAY -> s.chainGatewayItself
    ChainNode.Kind.HOP -> s.chainHop(hops ?: 1)
    ChainNode.Kind.GATEWAY -> s.chainGateway(hops ?: 1)
    ChainNode.Kind.UNKNOWN -> s.chainUnknown
}

/**
 * You -- hop -- Gateway, joined by dashed connectors (a Canvas line with a dash path effect). Collapsing follows
 * [ChainModel]. The whole diagram is one TalkBack element that reads the path in words.
 */
@Composable
fun ChainDiagram(nodes: List<ChainNode>, strings: NetworkSettingsStrings, modifier: Modifier = Modifier) {
    val captions = nodes.map { it.caption(strings) }
    val description = strings.chainDescription(
        nodes.mapIndexed { i, n -> if (n.kind == ChainNode.Kind.YOU) strings.chainYou else captions[i] },
    )
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.Top,
    ) {
        nodes.forEachIndexed { i, node ->
            if (i > 0) {
                Canvas(Modifier.weight(1f).height(NODE).padding(horizontal = 4.dp)) {
                    val y = size.height / 2
                    drawLine(
                        color = outline, start = androidx.compose.ui.geometry.Offset(0f, y),
                        end = androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
                    )
                }
            }
            Column(Modifier.widthIn(max = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NodeCircle(node, strings)
                Text(
                    captions[i], style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NodeCircle(node: ChainNode, strings: NetworkSettingsStrings) {
    val c = RelayTheme.colors
    val scheme = MaterialTheme.colorScheme
    when (node.kind) {
        ChainNode.Kind.YOU -> Box(Modifier.size(NODE).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
            Text(strings.chainYou, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary, maxLines = 1)
        }
        ChainNode.Kind.HOP -> Box(Modifier.size(NODE).clip(CircleShape).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = scheme.onSurfaceVariant)
        }
        ChainNode.Kind.GATEWAY, ChainNode.Kind.YOU_GATEWAY -> Box(Modifier.size(NODE).clip(CircleShape).background(c.success), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Sync, contentDescription = null, tint = c.onSuccess)
        }
        ChainNode.Kind.UNKNOWN -> Box(
            Modifier.size(NODE).clip(CircleShape).border(BorderStroke(2.dp, scheme.outline), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("?", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant) }
    }
}

private fun preview(kind: VillagerStatus.Kind, hops: Int?) =
    ChainModel.from(VillagerStatus(kind, VillagerStatus.Dot.GREEN, 3, hops, null))

@Preview(showBackground = true)
@Composable
private fun ChainDiagramPreview() = RelayPreview {
    val s = stringsFor(Lang.EN).network
    ChainDiagram(preview(VillagerStatus.Kind.REACHABLE, 2), s)
    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
    ChainDiagram(preview(VillagerStatus.Kind.REACHABLE, 1), s)
    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
    ChainDiagram(preview(VillagerStatus.Kind.THIS_PHONE_GATEWAY, null), s)
    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
    ChainDiagram(preview(VillagerStatus.Kind.SEARCHING, null), s)
}

@Preview(showBackground = true)
@Composable
private fun ChainDiagramDarkPreview() = RelayPreview(dark = true) {
    ChainDiagram(preview(VillagerStatus.Kind.REACHABLE, 2), stringsFor(Lang.HI).network)
}
