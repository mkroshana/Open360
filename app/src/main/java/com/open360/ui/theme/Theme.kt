package com.open360.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFF0EBE1),
    onPrimary = Color(0xFF151412),
    primaryContainer = Color(0xFF33312C),
    onPrimaryContainer = Color(0xFFF5F1E9),
    secondary = Color(0xFFC8C2B7),
    onSecondary = Color(0xFF1C1B19),
    secondaryContainer = Color(0xFF2D2C28),
    onSecondaryContainer = Color(0xFFF3EFE8),
    background = Color(0xFF0E0E0D),
    onBackground = Color(0xFFF3EFE8),
    surface = Color(0xFF0E0E0D),
    onSurface = Color(0xFFF3EFE8),
    surfaceVariant = Color(0xFF262522),
    onSurfaceVariant = Color(0xFFA39E94),
    surfaceContainerLowest = Color(0xFF090908),
    surfaceContainerLow = Color(0xFF151513),
    surfaceContainer = Color(0xFF191917),
    surfaceContainerHigh = Color(0xFF22211F),
    surfaceContainerHighest = Color(0xFF2A2926),
    outline = Color(0xFF5E5A53),
    outlineVariant = Color(0xFF34322E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1510),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1C1B19),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DFD1),
    onPrimaryContainer = Color(0xFF1C1B19),
    secondary = Color(0xFF5A554C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBE5D8),
    onSecondaryContainer = Color(0xFF1C1B19),
    background = Color(0xFFF6F3EC),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFF6F3EC),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFECE7DC),
    onSurfaceVariant = Color(0xFF635E55),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F3),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEFEAE0),
    surfaceContainerHighest = Color(0xFFEAE4D7),
    outline = Color(0xFF9C9588),
    outlineVariant = Color(0xFFDDD6C8),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

/**
 * The colours that mean something Material's colour roles have no role for: recording, a camera
 * or GPS that is ready, and one that is waiting. Fixed tones, not taken from the wallpaper, at the
 * lightness Material uses for its own accents: tone 80 or so in the dark theme, 40 in the light,
 * with recording a little stronger. Anything else, connecting included, uses the theme's roles.
 */
@Immutable
data class StatusColors(
    val recording: Color,
    val ready: Color,
    val waiting: Color
)

private val DarkStatusColors = StatusColors(
    recording = Color(0xFFFF5449),
    ready = Color(0xFF78DC8A),
    waiting = Color(0xFFFFB95C)
)

private val LightStatusColors = StatusColors(
    recording = Color(0xFFC62828),
    ready = Color(0xFF1B7A3E),
    waiting = Color(0xFFB45309)
)

private val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/** Digits of one width, so a speed or a running time does not shift as it changes. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

/**
 * Material 3 Expressive with Open360's curated editorial dark / warm light schemes by default, or
 * Android 12+ dynamic wallpaper colours when [dynamicColor] is enabled.
 */
@Composable
fun Open360Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatusColors else LightStatusColors) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content
        )
    }
}
