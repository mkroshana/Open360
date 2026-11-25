package com.open360.core.ble

import com.open360.core.model.Insta360CameraModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleScanMatcherTest {

    @Test
    fun matchesKnownServiceUuidsAndManufacturerId() {
        assertTrue(BleScanMatcher.evaluate(null, listOf(BleScanMatcher.SERVICE_BE80)).isMatch)
        assertTrue(BleScanMatcher.evaluate(null, listOf(BleScanMatcher.SERVICE_CE80)).isMatch)
        assertTrue(BleScanMatcher.evaluate(null, listOf(BleScanMatcher.SERVICE_AE00)).isMatch)
        assertTrue(
            BleScanMatcher.evaluate(
                deviceName = null,
                serviceUuids = emptyList(),
                manufacturerIds = listOf(BleScanMatcher.MANUFACTURER_ID_INSTA360_OG)
            ).isMatch
        )
    }

    @Test
    fun matchesOfficialCameraNamesAndSerialSuffix() {
        val go3s = BleScanMatcher.evaluate("GO 3S 8JY3HF", emptyList())
        assertTrue(go3s.isMatch)
        assertEquals(Insta360CameraModel.GO_3S, go3s.model)

        val x5 = BleScanMatcher.evaluate("X5 A1B2C3", emptyList())
        assertTrue(x5.isMatch)
        assertEquals(Insta360CameraModel.X5, x5.model)

        val x4Air = BleScanMatcher.evaluate("X4 Air 123ABC", emptyList())
        assertTrue(x4Air.isMatch)
        assertEquals(Insta360CameraModel.X4_AIR, x4Air.model)

        val acePro2 = BleScanMatcher.evaluate("Ace Pro 2 ABC123", emptyList())
        assertTrue(acePro2.isMatch)
        assertEquals(Insta360CameraModel.ACE_PRO_2, acePro2.model)

        val aceWithSerial = BleScanMatcher.evaluate("Ace 8JY3HF", emptyList())
        assertTrue(aceWithSerial.isMatch)
        assertEquals(Insta360CameraModel.ACE, aceWithSerial.model)

        val insta360Named = BleScanMatcher.evaluate("Insta360 X3 998877", emptyList())
        assertTrue(insta360Named.isMatch)
        assertEquals(Insta360CameraModel.X3, insta360Named.model)

        assertTrue(BleScanMatcher.evaluate("CustomName 8JY3HF", emptyList(), targetSerialSuffix = "8JY3HF").isMatch)
    }

    @Test
    fun rejectsBystanderDevicesWithoutServices() {
        assertFalse(BleScanMatcher.evaluate("BT-Speaker", emptyList()).isMatch)
        assertFalse(BleScanMatcher.evaluate("BMW X5", emptyList()).isMatch)
        assertFalse(BleScanMatcher.evaluate("Pace Watch", emptyList()).isMatch)
        assertFalse(BleScanMatcher.evaluate("Ace Hardware", emptyList()).isMatch)
        assertFalse(BleScanMatcher.evaluate("X500 Earbuds", emptyList()).isMatch)
        assertFalse(BleScanMatcher.evaluate("Random Device", emptyList()).isMatch)
    }
}
