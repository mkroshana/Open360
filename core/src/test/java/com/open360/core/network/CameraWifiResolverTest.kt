package com.open360.core.network

import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraWifiResolverTest {

    @Test
    fun resolvesSoftApAndStationEndpoints() {
        val ap = CameraWifiResolver.resolveEndpoint(CameraTransportMode.WIFI_SOFTAP)
        assertEquals("192.168.42.1", ap.host)
        assertEquals(6666, ap.socketPort)
        assertTrue(ap.requiresNoInternetCapability)

        val stationDefault = CameraWifiResolver.resolveEndpoint(CameraTransportMode.WIFI_STATION)
        assertEquals("192.168.49.1", stationDefault.host)
        assertFalse(stationDefault.requiresNoInternetCapability)

        val stationCustom = CameraWifiResolver.resolveEndpoint(
            CameraTransportMode.WIFI_STATION,
            customHostIp = "192.168.1.45"
        )
        assertEquals("192.168.1.45", stationCustom.host)
        assertEquals("http://192.168.1.45", stationCustom.httpBaseUrl)
    }

    @Test
    fun softApAndBleDirectIgnoreStationDefaultIpOverride() {
        val bleDirect = CameraWifiResolver.resolveEndpoint(
            CameraTransportMode.BLE_DIRECT,
            customHostIp = "192.168.49.1"
        )
        assertEquals("192.168.42.1", bleDirect.host)

        val softAp = CameraWifiResolver.resolveEndpoint(
            CameraTransportMode.WIFI_SOFTAP,
            customHostIp = "192.168.49.1"
        )
        assertEquals("192.168.42.1", softAp.host)

        val bleWithGateway = CameraWifiResolver.resolveEndpoint(
            CameraTransportMode.BLE_DIRECT,
            customHostIp = "192.168.49.1",
            discoveredGatewayIp = "192.168.42.1"
        )
        assertEquals("192.168.42.1", bleWithGateway.host)

        val stationWithSoftApGateway = CameraWifiResolver.resolveEndpoint(
            CameraTransportMode.WIFI_STATION,
            customHostIp = "192.168.49.1",
            discoveredGatewayIp = "192.168.42.1"
        )
        assertEquals("192.168.42.1", stationWithSoftApGateway.host)

        val stationWithExplicitCustomIp = CameraWifiResolver.resolveEndpoint(
            CameraTransportMode.WIFI_STATION,
            customHostIp = "192.168.1.45",
            discoveredGatewayIp = "192.168.42.1"
        )
        assertEquals("192.168.1.45", stationWithExplicitCustomIp.host)
    }

    @Test
    fun identifiesCameraSoftApIpsAndNormalizedSsids() {
        assertTrue(CameraWifiResolver.isCameraSoftApIp("192.168.42.1"))
        assertTrue(CameraWifiResolver.isCameraSoftApIp("192.168.42.5"))
        assertFalse(CameraWifiResolver.isCameraSoftApIp("10.10.10.1"))
        assertFalse(CameraWifiResolver.isCameraSoftApIp("192.168.49.1"))
        assertFalse(CameraWifiResolver.isCameraSoftApIp(null))

        assertEquals("GO 3S 8JY3HF.OSC", CameraWifiResolver.normalizeSsid("GO 3S 8JY3HF.OSC"))
        assertEquals("GO 3S 8JY3HF.OSC", CameraWifiResolver.normalizeSsid("\"GO 3S 8JY3HF.OSC\""))
        assertNull(CameraWifiResolver.normalizeSsid("<unknown ssid>"))
        assertNull(CameraWifiResolver.normalizeSsid("   "))

        assertTrue(CameraWifiResolver.isLikelyCameraSsid("GO 3S 8JY3HF.OSC"))
        assertTrue(CameraWifiResolver.isLikelyCameraSsid("\"GO 3S 8JY3HF.OSC\""))
        assertTrue(CameraWifiResolver.isLikelyCameraSsid("CustomCam", targetSsid = "CustomCam"))
        assertFalse(CameraWifiResolver.isLikelyCameraSsid("Stark Industries Inc."))
        assertFalse(CameraWifiResolver.isLikelyCameraSsid("<unknown ssid>"))
        assertFalse(CameraWifiResolver.isLikelyCameraSsid("10.10.10.1"))
    }

    @Test
    fun appliesCredentialCascadeManualOverDynamicOverLegacyDefault() {
        val manual = CameraWifiResolver.resolveCredentials(
            manualSsid = "X5_TEST",
            manualPassword = "custom_pw",
            bleSsid = "GO 3S_BLE",
            blePassword = "ble_pw"
        )
        assertEquals("X5_TEST", manual?.ssid)
        assertEquals("custom_pw", manual?.password)
        assertEquals(CameraWifiResolver.CredentialSource.MANUAL, manual?.source)

        val dynamic = CameraWifiResolver.resolveCredentials(
            manualSsid = "",
            manualPassword = "",
            bleSsid = "GO 3S_BLE",
            blePassword = "ble_pw"
        )
        assertEquals("GO 3S_BLE", dynamic?.ssid)
        assertEquals("ble_pw", dynamic?.password)
        assertEquals(CameraWifiResolver.CredentialSource.BLE_DYNAMIC, dynamic?.source)

        val legacyFallback = CameraWifiResolver.resolveCredentials(
            bleSsid = "ONE_X2_NET",
            blePassword = "",
            model = Insta360CameraModel.ONE_X2
        )
        assertEquals("ONE_X2_NET", legacyFallback?.ssid)
        assertEquals("88888888", legacyFallback?.password)
        assertEquals(CameraWifiResolver.CredentialSource.LEGACY_DEFAULT, legacyFallback?.source)

        assertNull(CameraWifiResolver.resolveCredentials())
    }
}
