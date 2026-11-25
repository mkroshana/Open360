package com.open360.core.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class Insta360UsbConfigTest {

    @Test
    fun classifiesCameraVidsQuickReadersAndUnsupportedDevices() {
        assertEquals(
            Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL,
            Insta360UsbConfig.classifyDevice(Insta360UsbConfig.VID_INSTA360_PRIMARY, 0x0001, true)
        )
        assertEquals(
            Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL,
            Insta360UsbConfig.classifyDevice(Insta360UsbConfig.VID_INSTA360_SECONDARY, 0x0001, true)
        )
        assertEquals(
            Insta360UsbConfig.UsbDeviceRole.CAMERA_BULK_CONTROL,
            Insta360UsbConfig.classifyDevice(Insta360UsbConfig.VID_INSTA360_ALT, 0x0001, true)
        )
        assertEquals(
            Insta360UsbConfig.UsbDeviceRole.QUICK_READER_STORAGE,
            Insta360UsbConfig.classifyDevice(0x2537, 0x1066, false)
        )
        assertEquals(
            Insta360UsbConfig.UsbDeviceRole.QUICK_READER_STORAGE,
            Insta360UsbConfig.classifyDevice(0x070A, 0x0001, false)
        )
        assertEquals(
            Insta360UsbConfig.UsbDeviceRole.QUICK_READER_STORAGE,
            Insta360UsbConfig.classifyDevice(0x05E3, 0x0749, false)
        )
        assertEquals(
            Insta360UsbConfig.UsbDeviceRole.UNSUPPORTED,
            Insta360UsbConfig.classifyDevice(0x18D1, 0x4EE7, true)
        )
    }

    @Test
    fun exposesVerifiedLibinsusbBulkParameters() {
        assertEquals(0x81, Insta360UsbConfig.BULK_ENDPOINT_IN)
        assertEquals(0x01, Insta360UsbConfig.BULK_ENDPOINT_OUT)
        assertEquals(65_536, Insta360UsbConfig.TRANSFER_BUFFER_SIZE_BYTES)
        assertEquals(5_000, Insta360UsbConfig.TRANSFER_TIMEOUT_MS)
    }
}
