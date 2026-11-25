package com.open360.core.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.splashscreen.SplashScreen
import java.util.concurrent.atomic.AtomicBoolean

data class SplashTransitionPlan(
    val holdDurationMs: Long,
    val exitDurationMs: Long,
    val animateIconTransform: Boolean,
    val immediateReveal: Boolean
)

object Open360SplashPolicy {
    const val DEFAULT_COLD_HOLD_MS = 340L
    const val DEFAULT_EXIT_DURATION_MS = 260L
    const val REDUCED_MOTION_EXIT_DURATION_MS = 140L

    fun resolve(
        isRestored: Boolean,
        animatorsEnabled: Boolean,
        coldHoldMs: Long = DEFAULT_COLD_HOLD_MS,
        exitDurationMs: Long = DEFAULT_EXIT_DURATION_MS,
        reducedExitDurationMs: Long = REDUCED_MOTION_EXIT_DURATION_MS
    ): SplashTransitionPlan = when {
        isRestored -> SplashTransitionPlan(0L, 0L, animateIconTransform = false, immediateReveal = true)
        !animatorsEnabled -> SplashTransitionPlan(0L, reducedExitDurationMs, animateIconTransform = false, immediateReveal = false)
        else -> SplashTransitionPlan(coldHoldMs, exitDurationMs, animateIconTransform = true, immediateReveal = false)
    }
}

/**
 * Coordinates the Android 12+ SplashScreen AVD hold and synchronized exit transition with the
 * Compose surface reveal. Ensures [onRevealStarted] runs at most once and always fires even if
 * a platform skips [SplashScreen.OnExitAnimationListener].
 */
fun Activity.configureOpen360Splash(
    splashScreen: SplashScreen,
    savedInstanceState: Bundle?,
    coldHoldMs: Long = Open360SplashPolicy.DEFAULT_COLD_HOLD_MS,
    exitDurationMs: Long = Open360SplashPolicy.DEFAULT_EXIT_DURATION_MS,
    onRevealStarted: () -> Unit
) {
    val plan = Open360SplashPolicy.resolve(
        isRestored = savedInstanceState != null,
        animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
        coldHoldMs = coldHoldMs,
        exitDurationMs = exitDurationMs
    )
    val revealFired = AtomicBoolean(false)
    fun fireRevealOnce() {
        if (revealFired.compareAndSet(false, true)) {
            onRevealStarted()
        }
    }

    if (plan.immediateReveal) {
        fireRevealOnce()
        splashScreen.setOnExitAnimationListener { provider ->
            runCatching { provider.remove() }
        }
        return
    }

    var keepOnScreen = plan.holdDurationMs > 0L
    splashScreen.setKeepOnScreenCondition { keepOnScreen }
    if (keepOnScreen) {
        window.decorView.postDelayed({
            keepOnScreen = false
            window.decorView.invalidate()
        }, plan.holdDurationMs)
    }
    window.decorView.postDelayed({ fireRevealOnce() }, plan.holdDurationMs + 350L)

    splashScreen.setOnExitAnimationListener { splashViewProvider ->
        fireRevealOnce()
        val removed = AtomicBoolean(false)
        fun removeOnce() {
            if (removed.compareAndSet(false, true)) {
                runCatching { splashViewProvider.remove() }
            }
        }

        val easeOutCubic = PathInterpolator(0.23f, 1f, 0.32f, 1f)
        val animators = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(splashViewProvider.view, View.ALPHA, 1f, 0f).apply {
                duration = plan.exitDurationMs
                interpolator = easeOutCubic
            }
        )

        if (plan.animateIconTransform) {
            val iconView = runCatching { splashViewProvider.iconView }.getOrNull()
            if (iconView != null) {
                val liftPx = -8f * resources.displayMetrics.density
                val iconFadeMs = (plan.exitDurationMs * 0.78f).toLong().coerceAtLeast(120L)
                animators += ObjectAnimator.ofFloat(iconView, View.SCALE_X, 1f, 1.16f).apply {
                    duration = plan.exitDurationMs
                    interpolator = easeOutCubic
                }
                animators += ObjectAnimator.ofFloat(iconView, View.SCALE_Y, 1f, 1.16f).apply {
                    duration = plan.exitDurationMs
                    interpolator = easeOutCubic
                }
                animators += ObjectAnimator.ofFloat(iconView, View.TRANSLATION_Y, 0f, liftPx).apply {
                    duration = plan.exitDurationMs
                    interpolator = easeOutCubic
                }
                animators += ObjectAnimator.ofFloat(iconView, View.ALPHA, 1f, 0f).apply {
                    duration = iconFadeMs
                    interpolator = easeOutCubic
                }
            }
        }

        AnimatorSet().apply {
            playTogether(animators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = removeOnce()
                override fun onAnimationCancel(animation: Animator) = removeOnce()
            })
            start()
        }
    }
}
