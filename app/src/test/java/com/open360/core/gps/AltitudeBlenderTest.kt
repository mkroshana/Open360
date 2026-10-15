package com.open360.core.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

class AltitudeBlenderTest {

    /** The pressure in hPa at [altitudeM] in the standard atmosphere, the inverse of the blender's formula. */
    private fun pressureAt(altitudeM: Double): Float = (1013.25 * (1.0 - altitudeM / 44330.0).pow(5.255)).toFloat()

    @Test
    fun testPressureAltitudeFormula() {
        assertEquals(0.0, AltitudeBlender.pressureAltitudeM(1013.25), 1e-9)
        assertEquals(100.0, AltitudeBlender.pressureAltitudeM(pressureAt(100.0).toDouble()), 0.05)
        assertEquals(1000.0, AltitudeBlender.pressureAltitudeM(pressureAt(1000.0).toDouble()), 0.1)
    }

    @Test
    fun testWithoutABarometerTheGpsAltitudeIsUsed() {
        val blender = AltitudeBlender()
        assertEquals(42.0, blender.blend(42.0, 1_000L), 0.0)
        assertFalse(blender.usingBarometer)
    }

    @Test
    fun testStartsAtTheGpsLevelAndFollowsTheBarometerFromThere() {
        val blender = AltitudeBlender()
        // The weather puts pressure altitude 30 m below the true height
        blender.addPressure(pressureAt(20.0), 0L)
        assertEquals(50.0, blender.blend(50.0, 0L), 0.01)
        assertTrue(blender.usingBarometer)

        // Climbing 5 m in a second, while GPS happens to say 1 m. GPS pulls the level only
        // slowly, by about 0.1 m in these 3 s.
        for (t in 1_000L..3_000L step 500L) blender.addPressure(pressureAt(25.0), t)
        val climbed = blender.blend(51.0, 3_000L)
        assertEquals(55.0, climbed, 0.3)
    }

    @Test
    fun testGpsJitterDoesNotReachTheAltitude() {
        val blender = AltitudeBlender()
        val outputs = ArrayList<Double>()
        // Standing still for ten minutes, with GPS altitude 5 m either side of 50 m
        for (second in 0..600) {
            val t = second * 1_000L
            blender.addPressure(pressureAt(10.0), t)
            outputs += blender.blend(if (second % 2 == 0) 55.0 else 45.0, t)
        }
        // From one second to the next it barely moves
        val largestStep = outputs.zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("step $largestStep", largestStep < 0.15)
        // and it settles at the GPS average
        assertEquals(50.0, outputs.last(), 0.5)
    }

    @Test
    fun testTheLevelFollowsGpsWhenTheWeatherChanges() {
        val blender = AltitudeBlender()
        // Over twenty minutes the pressure falls as if 10 m higher, while the phone stays at 50 m
        for (second in 0..1200) {
            val t = second * 1_000L
            blender.addPressure(pressureAt(10.0 + 10.0 * second / 1200), t)
            blender.blend(50.0, t)
        }
        // It trails a steady drift by the drift over the time constant, about 0.8 m here
        blender.addPressure(pressureAt(20.0), 1_201_000L)
        assertEquals(50.0, blender.blend(50.0, 1_201_000L), 1.5)
    }

    @Test
    fun testUncertainGpsAltitudeDoesNotMoveTheLevel() {
        val blender = AltitudeBlender(timeConstantMs = 10_000L)
        blender.addPressure(pressureAt(0.0), 0L)
        blender.blend(50.0, 0L, verticalAccuracyM = 3f)
        for (second in 1..60) {
            blender.addPressure(pressureAt(0.0), second * 1_000L)
            blender.blend(90.0, second * 1_000L, verticalAccuracyM = 40f)
        }
        blender.addPressure(pressureAt(0.0), 61_000L)
        assertEquals(50.0, blender.blend(90.0, 61_000L, verticalAccuracyM = 40f), 0.01)
    }

    @Test
    fun testAfterTheBarometerStopsTheLevelIsTakenAgainFromGps() {
        val blender = AltitudeBlender()
        blender.addPressure(pressureAt(0.0), 0L)
        blender.blend(50.0, 0L)
        // No reading for 10 s: GPS altitude is passed straight through
        assertEquals(70.0, blender.blend(70.0, 10_000L), 0.0)
        assertFalse(blender.usingBarometer)
        // Readings again: it starts from the GPS altitude of that moment
        blender.addPressure(pressureAt(3.0), 11_000L)
        assertEquals(72.0, blender.blend(72.0, 11_000L), 0.01)
        assertTrue(blender.usingBarometer)
    }
}
