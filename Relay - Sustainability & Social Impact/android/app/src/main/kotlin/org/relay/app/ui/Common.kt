package org.relay.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.delay
import org.relay.core.payload.Category
import org.relay.core.payload.Lang

val CATEGORY_EMOJI: Map<Category, String> = mapOf(
    Category.MEDICAL to "🩺",
    Category.RESCUE to "🆘",
    Category.WATER to "💧",
    Category.FOOD to "🍚",
    Category.SHELTER to "🏠",
    Category.MISSING_PERSON to "🔎",
    Category.OTHER to "❓",
)

fun Category.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

val LANGUAGE_LABELS: List<Pair<Lang, String>> = listOf(
    Lang.EN to "English",
    Lang.HI to "हिन्दी",
    Lang.OR to "ଓଡ଼ିଆ",
    Lang.TL to "Tagalog",
    Lang.WAR to "Waray",
    Lang.CEB to "Cebuano",
)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 8.dp))
}

@Composable
fun Pill(text: String, container: Color, content: Color = Color.White) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** Wall-clock time that ticks every [intervalMs] while composed, for "12 s ago" style text. */
@Composable
fun rememberNowMs(intervalMs: Long = 1_000L): State<Long> =
    produceState(System.currentTimeMillis(), intervalMs) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

val COLOR_OK = Color(0xFF1B7F3B)
val COLOR_WARN = Color(0xFF8A6D00)
val COLOR_BAD = Color(0xFFB3261E)
val COLOR_NEUTRAL = Color(0xFF6B6B6B)
