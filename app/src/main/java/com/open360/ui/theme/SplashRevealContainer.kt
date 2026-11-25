package com.open360.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * GPU-composited entrance wrapper synchronized with the system splash exit transition.
 */
@Composable
fun SplashRevealContainer(
    revealed: Boolean,
    content: @Composable () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val alpha = animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (reducedMotion) 140 else 260,
            easing = EaseOutCubic
        ),
        label = "splashRevealAlpha"
    )
    val scale = animateFloatAsState(
        targetValue = if (revealed || reducedMotion) 1f else 0.975f,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 260, easing = EaseOutCubic),
        label = "splashRevealScale"
    )
    val startOffsetPx = with(LocalDensity.current) { 10.dp.toPx() }
    val offsetPx = animateFloatAsState(
        targetValue = if (revealed || reducedMotion) 0f else startOffsetPx,
        animationSpec = if (reducedMotion) snap() else tween(durationMillis = 260, easing = EaseOutCubic),
        label = "splashRevealOffset"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value
                    scaleY = scale.value
                    translationY = offsetPx.value
                }
        ) {
            content()
        }
    }
}
