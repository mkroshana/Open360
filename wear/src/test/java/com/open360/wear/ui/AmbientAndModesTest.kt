package com.open360.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientAndModesTest {

    @Test
    fun ambientBurnInOffsetDpReturnsZeroWhenProtectionDisabled() {
        listOf(-10, -1, 0, 1, 2, 7, 15, 1439).forEach { minute ->
            assertEquals(0f to 0f, ambientBurnInOffsetDp(minute, burnInProtection = false))
        }
    }

    @Test
    fun ambientBurnInOffsetDpStaysWithinBounds() {
        for (minute in -16..1440) {
            val (x, y) = ambientBurnInOffsetDp(minute, burnInProtection = true)
            assertTrue("Expected x=$x in [-4f, 4f] for minute=$minute", x in -4f..4f)
            assertTrue("Expected y=$y in [-4f, 4f] for minute=$minute", y in -4f..4f)
        }
    }

    @Test
    fun ambientBurnInOffsetDpConsecutiveMinutesYieldDistinctOffsets() {
        val ringOffsets = (0 until 8).map { ambientBurnInOffsetDp(it, burnInProtection = true) }
        assertEquals(8, ringOffsets.toSet().size)

        for (minute in -24..120) {
            assertNotEquals(
                "Expected distinct consecutive offsets for $minute and ${minute + 1}",
                ambientBurnInOffsetDp(minute, burnInProtection = true),
                ambientBurnInOffsetDp(minute + 1, burnInProtection = true)
            )
        }
    }

    @Test
    fun ambientBurnInOffsetDpHandlesNegativeMinutes() {
        assertEquals(-2.2f to -2.2f, ambientBurnInOffsetDp(-1, burnInProtection = true))
        assertEquals(ambientBurnInOffsetDp(7, burnInProtection = true), ambientBurnInOffsetDp(-1, burnInProtection = true))
        assertEquals(0f to -3f, ambientBurnInOffsetDp(-8, burnInProtection = true))
        assertEquals(ambientBurnInOffsetDp(0, burnInProtection = true), ambientBurnInOffsetDp(-8, burnInProtection = true))
    }

    @Test
    fun ambientBurnInOffsetDpCyclesEveryEightMinutes() {
        for (minute in -32..256) {
            assertEquals(
                ambientBurnInOffsetDp(minute, burnInProtection = true),
                ambientBurnInOffsetDp(minute + 8, burnInProtection = true)
            )
        }
    }

    @Test
    fun orderedModeGroupsPlacesActiveGroupFirst() {
        assertEquals(listOf(false, true), orderedModeGroups(activeIsPhoto = false))
        assertEquals(listOf(true, false), orderedModeGroups(activeIsPhoto = true))
    }
}
