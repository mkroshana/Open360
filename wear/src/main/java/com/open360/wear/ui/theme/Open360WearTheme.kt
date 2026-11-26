package com.open360.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * Open360's curated editorial dark palette mapped onto Wear Compose Material 3 with a true OLED
 * black canvas (`#000000`) that blends seamlessly into round watch bezels.
 */
val Open360WearColorScheme = ColorScheme(
    primary = Color(0xFFF0EBE1),
    primaryDim = Color(0xFFD6D0C4),
    primaryContainer = Color(0xFF33312C),
    onPrimary = Color(0xFF151412),
    onPrimaryContainer = Color(0xFFF5F1E9),
    secondary = Color(0xFFC8C2B7),
    secondaryDim = Color(0xFFAFA99E),
    secondaryContainer = Color(0xFF2D2C28),
    onSecondary = Color(0xFF1C1B19),
    onSecondaryContainer = Color(0xFFF3EFE8),
    tertiary = Color(0xFFFFB95C),
    tertiaryDim = Color(0xFFE09E44),
    tertiaryContainer = Color(0xFF3D2E16),
    onTertiary = Color(0xFF1C1B19),
    onTertiaryContainer = Color(0xFFFFE2B8),
    surfaceContainerLow = Color(0xFF151513),
    surfaceContainer = Color(0xFF191917),
    surfaceContainerHigh = Color(0xFF22211F),
    onSurface = Color(0xFFF3EFE8),
    onSurfaceVariant = Color(0xFFA39E94),
    outline = Color(0xFF5E5A53),
    outlineVariant = Color(0xFF34322E),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF3EFE8),
    error = Color(0xFFFFB4AB),
    errorDim = Color(0xFFFF897D),
    errorContainer = Color(0xFF5C1510),
    onError = Color(0xFF690005),
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * Semantic status colors shared with the mobile app's `DarkStatusColors`: recording, ready, and
 * waiting/standby states. Kept constant regardless of watch-face dynamic tinting.
 */
@Immutable
data class WearStatusColors(
    val recording: Color,
    val ready: Color,
    val waiting: Color
)

private val DarkWearStatusColors = WearStatusColors(
    recording = Color(0xFFFF5449),
    ready = Color(0xFF78DC8A),
    waiting = Color(0xFFFFB95C)
)

private val LocalWearStatusColors = staticCompositionLocalOf { DarkWearStatusColors }

val MaterialTheme.wearStatusColors: WearStatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalWearStatusColors.current

/** Fixed-width digits so timers, speeds, battery percentages, and BPM readings never jitter. */
val WearTabularFigures = TextStyle(fontFeatureSettings = "tnum")

/**
 * Applies Open360's curated editorial dark theme by default, or watch-face dynamic colors when
 * [dynamicColor] is `true` and supported by the host watch OS.
 */
@Composable
fun Open360WearTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (dynamicColor) {
        dynamicColorScheme(context) ?: Open360WearColorScheme
    } else {
        Open360WearColorScheme
    }

    CompositionLocalProvider(LocalWearStatusColors provides DarkWearStatusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
