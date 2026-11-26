package com.open360.wear.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearHapticsTest {

    @Test
    fun allPatternsHaveDistinctValidNonEmptyWaveforms() {
        val seen = mutableSetOf<Pair<List<Long>, List<Int>>>()

        for (pattern in WearHapticPattern.entries) {
            val (timings, amplitudes) = WearHaptics.waveformFor(pattern)
            assertTrue("Timings empty for $pattern", timings.isNotEmpty())
            assertEquals("Timings/amplitudes length mismatch for $pattern", timings.size, amplitudes.size)
            assertTrue("Timings must be non-negative for $pattern", timings.all { it >= 0L })
            assertTrue("Must have positive pulse duration for $pattern", timings.any { it > 0L })
            assertTrue("Amplitudes out of 0..255 for $pattern", amplitudes.all { it in 0..255 })
            assertTrue("Must have non-zero amplitude for $pattern", amplitudes.any { it > 0 })
            assertTrue("Duplicate waveform for $pattern", seen.add(timings.toList() to amplitudes.toList()))
        }

        assertEquals(WearHapticPattern.entries.size, seen.size)
    }

    @Test
    fun waveformDefinitionsMatchExpectedVocabulary() {
        val shutter = WearHaptics.waveformFor(WearHapticPattern.SHUTTER_TAP)
        assertArrayEquals(longArrayOf(0L, 28L), shutter.first)
        assertArrayEquals(intArrayOf(0, 180), shutter.second)

        val recordingStarted = WearHaptics.waveformFor(WearHapticPattern.RECORDING_STARTED)
        assertArrayEquals(longArrayOf(0L, 45L, 70L, 65L), recordingStarted.first)
        assertArrayEquals(intArrayOf(0, 210, 0, 255), recordingStarted.second)

        val clipSaved = WearHaptics.waveformFor(WearHapticPattern.CLIP_SAVED)
        assertArrayEquals(longArrayOf(0L, 75L), clipSaved.first)
        assertArrayEquals(intArrayOf(0, 230), clipSaved.second)

        val photoTaken = WearHaptics.waveformFor(WearHapticPattern.PHOTO_TAKEN)
        assertArrayEquals(longArrayOf(0L, 35L), photoTaken.first)
        assertArrayEquals(intArrayOf(0, 220), photoTaken.second)

        val failure = WearHaptics.waveformFor(WearHapticPattern.FAILURE)
        assertArrayEquals(longArrayOf(0L, 55L, 55L, 55L, 55L, 80L), failure.first)
        assertArrayEquals(intArrayOf(0, 255, 0, 255, 0, 255), failure.second)

        val linkAction = WearHaptics.waveformFor(WearHapticPattern.LINK_ACTION)
        assertArrayEquals(longArrayOf(0L, 25L), linkAction.first)
        assertArrayEquals(intArrayOf(0, 150), linkAction.second)

        val unlock = WearHaptics.waveformFor(WearHapticPattern.UNLOCK)
        assertArrayEquals(longArrayOf(0L, 25L, 45L, 45L), unlock.first)
        assertArrayEquals(intArrayOf(0, 140, 0, 210), unlock.second)
    }
}
