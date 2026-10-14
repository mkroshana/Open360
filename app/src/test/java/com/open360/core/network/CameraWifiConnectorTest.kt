package com.open360.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraWifiConnectorTest {

    @Test
    fun testDetermineCameraSsidGo3S() {
        val ssid = CameraWifiConnector.determineCameraSsid("Insta360 GO 3S 8JY3HF", "8JY3HF")
        assertEquals("GO 3S 8JY3HF.OSC", ssid)
    }

    @Test
    fun testDetermineCameraSsidGo3() {
        val ssid = CameraWifiConnector.determineCameraSsid("GO 3 123456", "123456")
        assertEquals("GO 3 123456.OSC", ssid)
    }

    @Test
    fun testDetermineCameraSsidX4() {
        val ssid = CameraWifiConnector.determineCameraSsid("X4 999999", "999999")
        assertEquals("Insta360 X4 999999.OSC", ssid)
    }

    @Test
    fun testDetermineCameraSsidFallback() {
        val ssid = CameraWifiConnector.determineCameraSsid(null, "8JY3HF")
        assertEquals("GO 3S 8JY3HF.OSC", ssid)
    }
}
