package org.relay.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Sos
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.relay.app.ui.theme.RelayTheme
import org.relay.core.payload.Category
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance

/** The icon for a request category (water drop, food, medicine, rescue, shelter, person, other). */
fun categoryIcon(c: Category): ImageVector = when (c) {
    Category.WATER -> Icons.Outlined.WaterDrop
    Category.FOOD -> Icons.Outlined.Restaurant
    Category.MEDICAL -> Icons.Outlined.MedicalServices
    Category.RESCUE -> Icons.Outlined.Sos
    Category.SHELTER -> Icons.Outlined.Home
    Category.MISSING_PERSON -> Icons.Outlined.PersonSearch
    Category.OTHER -> Icons.AutoMirrored.Outlined.HelpOutline
}

private data class Tint(val container: Color, val content: Color)

private fun tint(c: Category, dark: Boolean): Tint = when (c) {
    Category.WATER -> if (dark) Tint(Color(0xFF0842A0), Color(0xFFA8C7FA)) else Tint(Color(0xFFD3E3FD), Color(0xFF0B57D0))
    Category.FOOD -> if (dark) Tint(Color(0xFF5C3A00), Color(0xFFFFB86C)) else Tint(Color(0xFFFFE3C2), Color(0xFF8A4B00))
    Category.MEDICAL -> if (dark) Tint(Color(0xFF8C1D18), Color(0xFFF2B8B5)) else Tint(Color(0xFFF9DEDC), Color(0xFFB3261E))
    Category.RESCUE -> if (dark) Tint(Color(0xFF7A2B00), Color(0xFFFFB59A)) else Tint(Color(0xFFFFDBCF), Color(0xFFA23B00))
    Category.SHELTER -> if (dark) Tint(Color(0xFF0B4F4A), Color(0xFF7FD8CF)) else Tint(Color(0xFFC8F1EC), Color(0xFF00695F))
    Category.MISSING_PERSON -> if (dark) Tint(Color(0xFF4B2E83), Color(0xFFD0BCFF)) else Tint(Color(0xFFE8DEFF), Color(0xFF5B3FA6))
    Category.OTHER -> if (dark) Tint(Color(0xFF333537), Color(0xFFC4C7C5)) else Tint(Color(0xFFE6E8EE), Color(0xFF444746))
}

/**
 * Leading circle of a request row: the category icon on a tint, or, when [closed], a green check circle.
 * Decorative (the row's text carries the meaning).
 */
@Composable
fun CategoryIcon(category: Category, modifier: Modifier = Modifier, closed: Boolean = false, size: Dp = 40.dp) {
    val colors = RelayTheme.colors
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f // follows the in-app theme, not the system
    val container: Color
    val content: Color
    val icon: ImageVector
    if (closed) {
        container = colors.successContainer; content = colors.success; icon = Icons.Filled.Check
    } else {
        val t = tint(category, dark)
        container = t.container; content = t.content; icon = categoryIcon(category)
    }
    Box(modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.55f))
    }
}

@Preview(showBackground = true)
@Composable
private fun CategoryIconPreview() = RelayPreview {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Category.entries.forEach { CategoryIcon(it) }
        CategoryIcon(Category.WATER, closed = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun CategoryIconDarkPreview() = RelayPreview(dark = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Category.entries.forEach { CategoryIcon(it) }
        CategoryIcon(Category.WATER, closed = true)
    }
}
