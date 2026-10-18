package org.relay.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import org.relay.app.ThemeMode
import org.relay.app.ui.findActivity

/** "Larger text" multiplies all text by this on top of the system font scale. */
const val LARGER_TEXT_SCALE = 1.2f

/** Shared shapes: cards 16 dp, chips 8 dp (use `MaterialTheme.shapes.small` for chips). */
private val RelayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDarkFor(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.AUTO -> isSystemInDarkTheme()
}

/**
 * Material 3 theme from the design spec tokens. [mode] picks light, dark or the system setting; [largerText] scales
 * all text by [LARGER_TEXT_SCALE]. Extra tokens: `RelayTheme.colors`.
 */
@Composable
fun RelayTheme(mode: ThemeMode = ThemeMode.AUTO, largerText: Boolean = false, content: @Composable () -> Unit) {
    val dark = isDarkFor(mode)
    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = LocalContext.current.findActivity()
        SideEffect {
            val window = (activity as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    val base = LocalDensity.current
    val density = if (largerText) Density(base.density, base.fontScale * LARGER_TEXT_SCALE) else base
    CompositionLocalProvider(
        LocalRelayColors provides (if (dark) DarkRelayColors else LightRelayColors),
        LocalDensity provides density,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, shapes = RelayShapes, content = content)
    }
}

/** Access to the non-Material tokens: `RelayTheme.colors.success`. */
object RelayTheme {
    val colors: RelayColors
        @Composable get() = LocalRelayColors.current
}
