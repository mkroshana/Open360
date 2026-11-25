package com.open360.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

val EaseOutCubic = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
val EaseInOutCubic = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
val EaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * Returns `true` when system animator scale is 0 (Android "Remove animations" / reduced motion),
 * refreshing whenever the host resumes from system settings.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var reducedMotion by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return reducedMotion
}

/**
 * Responsive scale feedback on press using Emil's `EaseOutCubic` (140ms).
 * Snaps to static `1f` when reduced motion is enabled.
 */
@Composable
fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    targetScale: Float = 0.97f
): State<Float> {
    val reducedMotion = rememberReducedMotion()
    val isPressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (!reducedMotion && isPressed) targetScale else 1f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 140, easing = EaseOutCubic),
        label = "pressScale"
    )
}

/**
 * GPU-friendly recording dot pulse alpha (`State<Float>`) so callers can read `.value` inside
 * `Modifier.graphicsLayer { alpha = ... }` without recomposing parent composables.
 */
@Composable
fun rememberRecordingPulseAlpha(active: Boolean): State<Float> {
    val reducedMotion = rememberReducedMotion()
    if (!active || reducedMotion) {
        return rememberUpdatedState(1f)
    }
    val transition = rememberInfiniteTransition(label = "recordingPulse")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingPulseAlpha"
    )
}

/**
 * Middle-truncates identifiers and filenames (such as `VID_20261124_183022_00_066.mp4`) on Unicode
 * code-point boundaries so distinguishing suffixes and extensions stay visible.
 */
fun middleEllipsize(text: String, maxLength: Int = 28): String {
    if (text.isEmpty() || maxLength <= 0) return ""
    val codePointCount = text.codePointCount(0, text.length)
    if (codePointCount <= maxLength) return text
    if (maxLength <= 1) return "…"

    val keepTotal = maxLength - 1
    val headCount = (keepTotal + 1) / 2
    val tailCount = keepTotal - headCount

    val headEndIndex = text.offsetByCodePoints(0, headCount)
    val tailStartIndex = text.offsetByCodePoints(text.length, -tailCount)
    return buildString(headEndIndex + 1 + (text.length - tailStartIndex)) {
        append(text, 0, headEndIndex)
        append('…')
        append(text, tailStartIndex, text.length)
    }
}

/**
 * Locale-formatted count with proper singular/plural word selection (e.g. `1 point`, `1,284 points`).
 */
fun pluralize(count: Int, singular: String, plural: String): String {
    val formatted = String.format(Locale.US, "%,d", count)
    val word = if (count == 1) singular else plural
    return "$formatted $word"
}
