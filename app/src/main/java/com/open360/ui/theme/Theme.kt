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
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF2B2930),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
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
    recording = Color(0xFFBA1A1A),
    ready = Color(0xFF1D6D35),
    waiting = Color(0xFF855300)
)

private val LocalStatusColors = staticCompositionLocalOf { DarkStatusColors }

val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current

/** Digits of one width, so a speed or a running time does not shift as it changes. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

/**
 * Material 3 Expressive, with the wallpaper's colours from Android 12 on: its springier motion,
 * and the components' Expressive shapes and press feedback.
 */
@Composable
fun Open360Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
