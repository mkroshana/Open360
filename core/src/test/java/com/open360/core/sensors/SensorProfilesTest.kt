package com.open360.core.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorProfilesTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun testStandardUuids() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", SensorProfiles.HEART_RATE_SERVICE.toString())
        assertEquals("00002a63-0000-1000-8000-00805f9b34fb", SensorProfiles.CYCLING_POWER_MEASUREMENT.toString())
    }

    @Test
    fun testHeartRateOneAndTwoByteValues() {
        // flags 0: one byte; flags 0x16 (contact detected, RR intervals) still one byte
        assertEquals(72, SensorProfiles.parseHeartRate(bytes(0x00, 72)))
        assertEquals(185, SensorProfiles.parseHeartRate(bytes(0x16, 185, 0x10, 0x03)))
        // flags bit 0: two bytes, little-endian (300 = 0x012C)
        assertEquals(300, SensorProfiles.parseHeartRate(bytes(0x01, 0x2C, 0x01)))
        assertNull(SensorProfiles.parseHeartRate(bytes(0x01, 0x2C)))
        assertNull(SensorProfiles.parseHeartRate(ByteArray(0)))
    }

    @Test
    fun testCscCrankDataWithAndWithoutWheelData() {
        // crank only: 1000 revolutions (0x03E8), event time 0x2000
        assertEquals(SensorProfiles.CrankData(1000, 0x2000), SensorProfiles.parseCscCrank(bytes(0x02, 0xE8, 0x03, 0x00, 0x20)))
        // wheel and crank: the 6 bytes of wheel data are skipped
        assertEquals(
            SensorProfiles.CrankData(5, 0x0400),
            SensorProfiles.parseCscCrank(bytes(0x03, 1, 0, 0, 0, 0, 0, 5, 0, 0x00, 0x04))
        )
        // wheel only: no cadence from it
        assertNull(SensorProfiles.parseCscCrank(bytes(0x01, 1, 0, 0, 0, 0, 0)))
    }

    @Test
    fun testCyclingPowerWithOptionalFields() {
        // flags 0: just the power, 250 W
        assertEquals(SensorProfiles.PowerMeasurement(250, null), SensorProfiles.parseCyclingPower(bytes(0x00, 0x00, 0xFA, 0x00)))
        // balance (bit 0) and crank data (bit 5): 0x0021, 180 W, balance byte, crank 300 revs at 0x1000
        assertEquals(
            SensorProfiles.PowerMeasurement(180, SensorProfiles.CrankData(300, 0x1000)),
            SensorProfiles.parseCyclingPower(bytes(0x21, 0x00, 0xB4, 0x00, 0x64, 0x2C, 0x01, 0x00, 0x10))
        )
        // accumulated torque (bit 2) and wheel data (bit 4) come before the crank data
        assertEquals(
            SensorProfiles.PowerMeasurement(90, SensorProfiles.CrankData(7, 0x0800)),
            SensorProfiles.parseCyclingPower(bytes(0x34, 0x00, 0x5A, 0x00, 0x11, 0x22, 1, 2, 3, 4, 5, 6, 7, 0, 0x00, 0x08))
        )
        // a negative reading counts as 0
        assertEquals(0, SensorProfiles.parseCyclingPower(bytes(0x00, 0x00, 0xF6, 0xFF))?.watts)
    }

    @Test
    fun testCadenceFromCrankReadings() {
        val calc = CadenceCalculator()
        assertNull(calc.update(SensorProfiles.CrankData(100, 0), 0))
        // one revolution in 0.75 s (768 ticks of 1/1024 s): 80 rpm
        assertEquals(80, calc.update(SensorProfiles.CrankData(101, 768), 1_000))
        // no new revolution yet: nothing new
        assertNull(calc.update(SensorProfiles.CrankData(101, 768), 2_000))
        // still nothing 3 s after the last revolution: stopped
        assertEquals(0, calc.update(SensorProfiles.CrankData(101, 768), 4_000))
        // counters wrap: from 65535 revs at 65000 ticks to 1 rev at 500 ticks, 2 revolutions in 1036 ticks
        val wrap = CadenceCalculator()
        wrap.update(SensorProfiles.CrankData(65535, 65000), 0)
        assertEquals(118, wrap.update(SensorProfiles.CrankData(1, 500), 1_000))
    }
}
