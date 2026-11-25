package com.open360.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Open360SplashPolicyTest {

    @Test
    fun coldLaunchWithAnimatorsEnabledHoldsAndAnimatesIcon() {
        val plan = Open360SplashPolicy.resolve(
            isRestored = false,
            animatorsEnabled = true,
            coldHoldMs = 340L,
            exitDurationMs = 260L
        )

        assertEquals(340L, plan.holdDurationMs)
        assertEquals(260L, plan.exitDurationMs)
        assertTrue(plan.animateIconTransform)
        assertFalse(plan.immediateReveal)
    }

    @Test
    fun coldLaunchWithReducedMotionSkipsHoldAndUsesShortFade() {
        val plan = Open360SplashPolicy.resolve(
            isRestored = false,
            animatorsEnabled = false,
            coldHoldMs = 340L,
            exitDurationMs = 260L
        )

        assertEquals(0L, plan.holdDurationMs)
        assertEquals(Open360SplashPolicy.REDUCED_MOTION_EXIT_DURATION_MS, plan.exitDurationMs)
        assertFalse(plan.animateIconTransform)
        assertFalse(plan.immediateReveal)
    }

    @Test
    fun restoredActivityRevealsImmediatelyWithoutAnimation() {
        val plan = Open360SplashPolicy.resolve(
            isRestored = true,
            animatorsEnabled = true,
            coldHoldMs = 340L,
            exitDurationMs = 260L
        )

        assertEquals(0L, plan.holdDurationMs)
        assertEquals(0L, plan.exitDurationMs)
        assertFalse(plan.animateIconTransform)
        assertTrue(plan.immediateReveal)
    }
}
