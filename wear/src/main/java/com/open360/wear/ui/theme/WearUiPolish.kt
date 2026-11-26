package com.open360.wear.ui.theme

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

val WearEaseOutCubic = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
val WearEaseInOutCubic = CubicBezierEasing(0.77f, 0f, 0.175f, 1f)
val WearEaseDrawer = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

/**
 * Returns `true` when system animator scale is 0 (Android "Remove animations" / reduced motion),
 * refreshing whenever the host resumes from system settings.
 */
@Composable
fun rememberWearReducedMotion(): Boolean {
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
 * Responsive scale feedback on press using `WearEaseOutCubic` (140ms).
 * Snaps to static `1f` when reduced motion is enabled.
 */
@Composable
fun rememberWearPressScale(
    interactionSource: MutableInteractionSource,
    targetScale: Float = 0.96f
): State<Float> {
    val reducedMotion = rememberWearReducedMotion()
    val isPressed by interactionSource.collectIsPressedAsState()
    return animateFloatAsState(
        targetValue = if (!reducedMotion && isPressed) targetScale else 1f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 140, easing = WearEaseOutCubic),
        label = "wearPressScale"
    )
}

/**
 * GPU-friendly recording dot pulse alpha (`State<Float>`) so callers can read `.value` inside
 * `Modifier.graphicsLayer { alpha = ... }` without recomposing parent composables.
 */
@Composable
fun rememberWearRecordingPulseAlpha(active: Boolean): State<Float> {
    val reducedMotion = rememberWearReducedMotion()
    if (!active || reducedMotion) {
        return rememberUpdatedState(1f)
    }
    val transition = rememberInfiniteTransition(label = "wearRecordingPulse")
    return transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = WearEaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wearRecordingPulseAlpha"
    )
}

/**
 * Middle-truncates identifiers and filenames (such as `VID_20261124_183022_00_066.mp4`) on Unicode
 * code-point boundaries so distinguishing suffixes and extensions stay visible on watch screens.
 */
fun wearMiddleEllipsize(text: String, maxLength: Int = 24): String {
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
