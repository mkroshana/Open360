package com.open360.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Insta360CameraModelTest {

    @Test
    fun catalogHasAllTwentyUniqueCameras() {
        assertEquals(20, Insta360CameraModel.entries.size)
        val configIds = Insta360CameraModel.entries.map { it.configId }
        val drawables = Insta360CameraModel.entries.map { it.drawableName }
        assertEquals(20, configIds.toSet().size)
        assertEquals(20, drawables.toSet().size)
    }

    @Test
    fun resolvesDisambiguatedModelsWithoutPrefixCollisions() {
        assertEquals(Insta360CameraModel.GO_3S, Insta360CameraModel.resolveByIdentity(deviceName = "GO 3S 8JY3HF"))
        assertEquals(Insta360CameraModel.GO_3, Insta360CameraModel.resolveByIdentity(deviceName = "GO 3 123456"))
        assertEquals(Insta360CameraModel.GO_ULTRA, Insta360CameraModel.resolveByIdentity(deviceName = "GO Ultra ABC123"))
        assertEquals(Insta360CameraModel.GO_2, Insta360CameraModel.resolveByIdentity(deviceName = "GO 2 ABCDEF"))

        assertEquals(Insta360CameraModel.X4_AIR, Insta360CameraModel.resolveByIdentity(deviceName = "X4 Air 123456"))
        assertEquals(Insta360CameraModel.X4, Insta360CameraModel.resolveByIdentity(deviceName = "X4 123456"))
        assertEquals(Insta360CameraModel.X5, Insta360CameraModel.resolveByIdentity(deviceName = "X5 A1B2C3"))
        assertEquals(Insta360CameraModel.X6, Insta360CameraModel.resolveByIdentity(deviceName = "X6 998877"))

        assertEquals(Insta360CameraModel.ONE_RS, Insta360CameraModel.resolveByIdentity(deviceName = "ONE RS 123456"))
        assertEquals(Insta360CameraModel.ONE_R, Insta360CameraModel.resolveByIdentity(deviceName = "ONE R 123456"))
        assertEquals(Insta360CameraModel.ONE_X2, Insta360CameraModel.resolveByIdentity(deviceName = "Insta360 OneX2"))
        assertEquals(Insta360CameraModel.ONE_X, Insta360CameraModel.resolveByIdentity(deviceType = "Insta360 One2"))

        assertEquals(Insta360CameraModel.ACE_PRO_3, Insta360CameraModel.resolveByIdentity(deviceName = "Ace Pro 3 111222"))
        assertEquals(Insta360CameraModel.ACE_PRO_2, Insta360CameraModel.resolveByIdentity(deviceName = "Ace Pro 2 111222"))
        assertEquals(Insta360CameraModel.ACE_PRO, Insta360CameraModel.resolveByIdentity(deviceName = "Ace Pro 111222"))
        assertEquals(Insta360CameraModel.ACE, Insta360CameraModel.resolveByIdentity(deviceName = "Ace 111222"))
    }

    @Test
    fun resolvesIbeaconSerialPrefixesOnlyFromSerialNumber() {
        assertEquals(Insta360CameraModel.GO_3S, Insta360CameraModel.resolveByIdentity(serialNumber = "IAT123456"))
        assertEquals(Insta360CameraModel.X5, Insta360CameraModel.resolveByIdentity(serialNumber = "IAH999888"))
        assertEquals(Insta360CameraModel.ACE_PRO_2, Insta360CameraModel.resolveByIdentity(serialNumber = "IBG111222"))
        assertEquals(Insta360CameraModel.X6, Insta360CameraModel.resolveByIdentity(serialNumber = "BX123456"))
        assertEquals(Insta360CameraModel.ACE_PRO_3, Insta360CameraModel.resolveByIdentity(serialNumber = "BU123456"))
        assertEquals(Insta360CameraModel.LUNA_ULTRA, Insta360CameraModel.resolveByIdentity(serialNumber = "BT123456"))
    }

    @Test
    fun rejectsSubstringsAndBystanderNames() {
        assertNull(Insta360CameraModel.resolveByIdentity(deviceName = "Pace Watch"))
        assertNull(Insta360CameraModel.resolveByIdentity(deviceName = "BMW X5"))
        assertNull(Insta360CameraModel.resolveByIdentity(deviceName = "BT-Speaker"))
        assertNull(Insta360CameraModel.resolveByIdentity(deviceName = "X500 Earbuds"))
    }

    @Test
    fun reportsExpectedTransportsAndWakeStrategies() {
        assertEquals(BleWakeStrategy.GO2_FIXED_PACKET, Insta360CameraModel.GO_2.wakeStrategy)
        assertEquals(BleWakeStrategy.BLE_AUTH_ID, Insta360CameraModel.GO_3S.wakeStrategy)
        assertEquals(BleWakeStrategy.IBEACON_ORBIT, Insta360CameraModel.X4.wakeStrategy)
        assertTrue(Insta360CameraModel.ONE_X.supportedTransports().contains(CameraTransportMode.BLUETOOTH_CLASSIC_SPP))
        assertTrue(Insta360CameraModel.X5.supportedTransports().contains(CameraTransportMode.WIFI_STATION))
    }

    @Test
    fun reportsExpectedPreviewPostureOffsetsAndResolutionSupport() {
        assertEquals(90, Insta360CameraModel.GO_3S.previewPostureAdjustDegrees)
        assertEquals(false, Insta360CameraModel.GO_3S.supportsVariableLivePreviewResolution)
        assertEquals(180, Insta360CameraModel.GO_3.previewPostureAdjustDegrees)
        assertEquals(false, Insta360CameraModel.GO_3.supportsVariableLivePreviewResolution)
        assertEquals(0, Insta360CameraModel.X4.previewPostureAdjustDegrees)
        assertEquals(true, Insta360CameraModel.X4.supportsVariableLivePreviewResolution)
        assertEquals(270, Insta360CameraModel.LUNA_ULTRA.previewPostureAdjustDegrees)
    }

    @Test
    fun marksOnlyGo3sAsHardwareVerified() {
        assertTrue(Insta360CameraModel.GO_3S.isHardwareVerified)
        val unverified = Insta360CameraModel.entries.filter { it != Insta360CameraModel.GO_3S }
        assertEquals(19, unverified.size)
        assertTrue(unverified.none { it.isHardwareVerified })
    }

    @Test
    fun marksExperimentalTransportsCorrectly() {
        assertFalse(CameraTransportMode.BLE_DIRECT.isExperimental)
        assertFalse(CameraTransportMode.WIFI_SOFTAP.isExperimental)
        assertTrue(CameraTransportMode.USB_C_DIRECT.isExperimental)
        assertTrue(CameraTransportMode.WIFI_STATION.isExperimental)
        assertTrue(CameraTransportMode.GPS_REMOTE_SERVER.isExperimental)
        assertTrue(CameraTransportMode.BLUETOOTH_CLASSIC_SPP.isExperimental)
    }
}
