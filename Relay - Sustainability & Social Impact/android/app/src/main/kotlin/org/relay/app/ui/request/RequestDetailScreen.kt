package org.relay.app.ui.request

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.relay.app.LocalSpeaker
import org.relay.app.MeshRepository
import org.relay.app.store.MyNeed
import org.relay.app.store.RequestTimeline
import org.relay.app.ui.AskMode
import org.relay.app.ui.RelayNav
import org.relay.app.ui.components.PillButton
import org.relay.app.ui.components.PillStyle
import org.relay.app.ui.components.RelayPreview
import org.relay.app.ui.components.SectionCard
import org.relay.app.ui.components.SpeakButton
import org.relay.app.ui.components.StepTopAppBar
import org.relay.app.ui.components.Timeline
import org.relay.app.ui.components.TimelineState
import org.relay.app.ui.components.TimelineStep
import org.relay.app.ui.components.UrgencyBadge
import org.relay.app.ui.components.toTimelineSteps
import org.relay.app.ui.i18n.AppLanguages
import org.relay.app.ui.i18n.LocalStrings
import org.relay.app.ui.i18n.Strings
import org.relay.core.payload.AckStatus
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

/** Request detail (design spec section 5). The request may be missing (e.g. after Clear my needs). */
@Composable
fun RequestDetailScreen(nav: RelayNav, msgId: String) {
    val s = LocalStrings.current
    val ctx = LocalContext.current
    val needs by MeshRepository.myNeeds.collectAsStateWithLifecycle()
    val settings by MeshRepository.settings.flow.collectAsStateWithLifecycle()
    val need = needs.firstOrNull { it.msgIdHex == msgId }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    // Auto-read a new control-room message once; the read log survives restarts.
    val speaker = LocalSpeaker.current
    var voiceMissing by remember { mutableStateOf(false) }
    val key = need?.let { RequestLogic.messageKey(it) }
    LaunchedEffect(key, settings.readAloud) {
        val n = need ?: return@LaunchedEffect
        val sp = speaker ?: return@LaunchedEffect
        if (RequestLogic.shouldAutoRead(settings.readAloud, n, ReadMessagesLog.read(ctx))) {
            if (sp.speak(n.ackText, n.lang)) ReadMessagesLog.mark(ctx, RequestLogic.messageKey(n))
        }
    }

    Column(Modifier.fillMaxSize()) {
        StepTopAppBar(
            s.request.title,
            onBack = nav::back,
            actions = {
                if (need != null) {
                    IconButton(onClick = { share(ctx, s, need, settings.areaLabel, now) }) {
                        Icon(Icons.Filled.Share, contentDescription = s.request.share)
                    }
                }
            },
        )
        if (need == null) {
            Text(s.request.gone, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
        } else {
            RequestDetailContent(
                need = need,
                now = now,
                area = settings.areaLabel,
                voiceMissing = voiceMissing,
                onHear = {
                    voiceMissing = speaker?.speak(need.ackText, need.lang) != true
                    if (!voiceMissing) ReadMessagesLog.mark(ctx, RequestLogic.messageKey(need))
                },
                onCancel = { MeshRepository.cancel(need.msgIdHex) },
                onAdd = { nav.startAsk(AskMode.VOICE, need.msgIdHex) },
            )
        }
    }
}

/** The scrolling body, stateless so previews can drive it. */
@Composable
internal fun RequestDetailContent(
    need: MyNeed,
    now: Long,
    area: String,
    voiceMissing: Boolean,
    onHear: () -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    val s = LocalStrings.current
    var confirming by remember { mutableStateOf(false) }
    val steps = remember(need, now, s) { timelineFor(need, now, s) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                RequestLogic.title(need, s.common), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!need.isClosed) UrgencyBadge(need.urgency)
                else Text(
                    if (need.cancelled) s.common.statusCancelled else s.common.statusClosed,
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                s.request.referenceLine(RequestLogic.reference(need), s.common.clock(need.createdAtMs), area),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Timeline(steps)

        if (RequestLogic.hasMessage(need)) {
            val langName = AppLanguages.of(need.lang).let { if (s.lang == Lang.EN) it.englishName else it.nativeName }
            SectionCard(title = s.request.fromControlRoom) {
                Text(need.ackText, style = MaterialTheme.typography.bodyLarge)
                SpeakButton(s.request.hearItIn(langName), onHear)
                if (voiceMissing) {
                    Text(s.request.noVoice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (!need.isClosed) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(s.request.addToRequest, onAdd, style = PillStyle.Tonal)
                TextButton(
                    onClick = { confirming = true },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    Text(
                        s.request.cancelRequest, style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(s.request.cancelDialogTitle) },
            text = { Text(s.request.cancelDialogBody) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onCancel() }) {
                    Text(s.request.cancelConfirm, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(s.request.cancelKeep) } },
        )
    }
}

/** Timeline rows from [RequestTimeline]; a cancelled request ends with a "Cancelled" row. */
internal fun timelineFor(need: MyNeed, now: Long, s: Strings): List<TimelineStep> {
    val rows = RequestTimeline.steps(need, now).toTimelineSteps(s.common)
    if (!need.cancelled) return rows
    val at = need.cancelledAtMs.takeIf { it > 0 }?.let { s.common.clock(it) }
    return rows + TimelineStep(s.common.statusCancelled, TimelineState.Done, at)
}

private fun share(ctx: Context, s: Strings, need: MyNeed, area: String, now: Long) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, RequestLogic.shareText(need, s, area, now))
    }
    try {
        ctx.startActivity(Intent.createChooser(send, s.request.shareChooser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // no share target installed
    }
}

/** Which control-room messages were already read aloud, so auto-read does not repeat after a restart. */
internal object ReadMessagesLog {
    private const val PREFS = "relay_read_messages"
    private const val KEY = "read"
    private const val MAX = 200

    fun read(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    @Synchronized fun mark(ctx: Context, key: String) {
        val cur = read(ctx).toMutableSet()
        if (!cur.add(key)) return
        // bounded: drop arbitrary old entries rather than grow forever
        val next = if (cur.size > MAX) cur.toList().takeLast(MAX).toSet() else cur
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, next).apply()
    }
}

private fun sample(cancelled: Boolean = false, delivered: Boolean = false): MyNeed {
    val t0 = System.currentTimeMillis() - 3_600_000
    var n = MyNeed(
        msgIdHex = "7f3a1c20", createdAtMs = t0, category = Category.WATER, urgency = 2, people = 6, item = "Drinking water",
        landmark = "primary school", summary = "Pump broken", lang = Lang.EN, needPayloadB64 = "",
    ).markSentToMesh(t0 + 60_000).markUploaded(t0 + 180_000)
        .withAck(AckStatus.RECEIVED, 42, "We have your request.", 0xFFFF, t0 + 240_000)
        .withAck(AckStatus.DISPATCHED, 42, "Help dispatched, ETA 45 min", 45, t0 + 600_000)
        .withAck(AckStatus.ON_THE_WAY, 42, "Team on the way, ETA 30 min", 30, t0 + 1_200_000)
    if (delivered) n = n.withAck(AckStatus.RESOLVED, 42, "Delivered. Stay safe.", 0xFFFF, t0 + 2_400_000)
    if (cancelled) n = n.cancel(t0 + 2_000_000)
    return n
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RequestDetailPreview() = RelayPreview {
    RequestDetailContent(sample(), System.currentTimeMillis(), "Ward 4", false, {}, {}, {})
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RequestDetailDarkPreview() = RelayPreview(dark = true) {
    RequestDetailContent(sample(), System.currentTimeMillis(), "Ward 4", true, {}, {}, {})
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RequestDetailCancelledPreview() = RelayPreview {
    RequestDetailContent(sample(cancelled = true), System.currentTimeMillis(), "", false, {}, {}, {})
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RequestDetailDeliveredDarkPreview() = RelayPreview(dark = true) {
    RequestDetailContent(sample(delivered = true), System.currentTimeMillis(), "Ward 4", false, {}, {}, {})
}
