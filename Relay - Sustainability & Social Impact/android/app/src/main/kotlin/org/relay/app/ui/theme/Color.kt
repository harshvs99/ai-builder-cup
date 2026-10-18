package org.relay.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Tokens the Material 3 scheme has no slot for (design spec, Visual system). Read with `RelayTheme.colors`
 * or `LocalRelayColors.current`.
 */
@Immutable
data class RelayColors(
    /** Check chips, "Reachable", done timeline steps. */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** Urgent badge and the selected urgency segment: a fill, with [onWarning] text in both themes. */
    val warning: Color,
    val onWarning: Color,
    /** Waiting / amber status text and dots on a surface (a darker tone in light theme for contrast). */
    val warningText: Color,
    /** The big mic button (Google yellow) and its icon, both themes. */
    val micYellow: Color,
    val onMicYellow: Color,
)

internal val LightRelayColors = RelayColors(
    success = Color(0xFF146C2E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFC4EED0),
    onSuccessContainer = Color(0xFF00210A),
    warning = Color(0xFFF9AB00),
    onWarning = Color(0xFF3F2E00),
    warningText = Color(0xFF7A5400),
    micYellow = Color(0xFFFBBC04),
    onMicYellow = Color(0xFF1F1F1F),
)

internal val DarkRelayColors = RelayColors(
    success = Color(0xFF6DD58C),
    onSuccess = Color(0xFF003914),
    successContainer = Color(0xFF0F5223),
    onSuccessContainer = Color(0xFFC4EED0),
    warning = Color(0xFFF9AB00),
    onWarning = Color(0xFF3F2E00),
    warningText = Color(0xFFF9AB00),
    micYellow = Color(0xFFFBBC04),
    onMicYellow = Color(0xFF1F1F1F),
)

val LocalRelayColors = staticCompositionLocalOf { LightRelayColors }

internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF0B57D0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD3E3FD), // navigation pill indicator
    onSecondaryContainer = Color(0xFF041E49),
    background = Color(0xFFF8F9FC),
    onBackground = Color(0xFF1F1F1F),
    surface = Color(0xFFF8F9FC),
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFF444746),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F9),
    surfaceContainer = Color(0xFFEEF0F5),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFDFE2E8),
    outline = Color(0xFF747775),
    outlineVariant = Color(0xFFC4C7C5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF062E6F),
    primaryContainer = Color(0xFF0842A0),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFFA8C7FA),
    onSecondary = Color(0xFF062E6F),
    secondaryContainer = Color(0xFF0842A0),
    onSecondaryContainer = Color(0xFFD3E3FD),
    background = Color(0xFF131314),
    onBackground = Color(0xFFE3E3E3),
    surface = Color(0xFF131314),
    onSurface = Color(0xFFE3E3E3),
    surfaceVariant = Color(0xFF282A2C),
    onSurfaceVariant = Color(0xFFC4C7C5),
    surfaceContainerLowest = Color(0xFF0E0E0F),
    surfaceContainerLow = Color(0xFF1A1B1C),
    surfaceContainer = Color(0xFF1E1F20),
    surfaceContainerHigh = Color(0xFF282A2C),
    surfaceContainerHighest = Color(0xFF333537),
    outline = Color(0xFF8E918F),
    outlineVariant = Color(0xFF444746),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)
