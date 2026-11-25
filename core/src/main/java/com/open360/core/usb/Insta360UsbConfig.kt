package com.open360.core.usb

/**
 * USB-C / OTG bulk protocol parameters and camera USB Vendor/Product ID compatibility profiles.
 */
object Insta360UsbConfig {
    /** Primary Arashi Vision / Insta360 camera USB Vendor ID. */
    const val VID_INSTA360_PRIMARY: Int = 0x2E1A

    /** Secondary Insta360 USB Vendor ID used on newer camera hardware. */
    const val VID_INSTA360_SECONDARY: Int = 0x4255

    /** Alternative camera USB Vendor ID. */
    const val VID_INSTA360_ALT: Int = 0x2E1C

    /** Known camera USB Vendor IDs that expose bulk command/stream endpoints (`0x81` / `0x01`). */
    val CAMERA_VENDOR_IDS: Set<Int> = setOf(
        VID_INSTA360_PRIMARY,
        VID_INSTA360_SECONDARY,
        VID_INSTA360_ALT
    )

    /** Quick Reader / card reader USB Vendor IDs (storage-only, not camera bulk control). */
    val QUICK_READER_VENDOR_IDS: Set<Int> = setOf(
        0x2537,
        0x070A,
        0x05E3
    )

    /** Bulk IN endpoint address (`0x81`) for USB bulk transfers. */
    const val BULK_ENDPOINT_IN: Int = 0x81

    /** Bulk OUT endpoint address (`0x01`) for USB bulk transfers. */
    const val BULK_ENDPOINT_OUT: Int = 0x01

    /** USB bulk transfer buffer size (`0x10000` / 64 KB). */
    const val TRANSFER_BUFFER_SIZE_BYTES: Int = 65_536

    /** USB bulk transfer timeout in milliseconds (`5000` ms). */
    const val TRANSFER_TIMEOUT_MS: Int = 5_000

    /** Compatible camera VID+PID pairs for USB bulk control detection. */
    val KNOWN_CAMERA_VID_PID_PAIRS: Set<Pair<Int, Int>> = setOf(
        VID_INSTA360_PRIMARY to 0x0001,
        VID_INSTA360_PRIMARY to 0x0002,
        VID_INSTA360_PRIMARY to 0x0003,
        VID_INSTA360_PRIMARY to 0x0004,
        VID_INSTA360_PRIMARY to 0x0005,
        VID_INSTA360_PRIMARY to 0x0006,
        VID_INSTA360_PRIMARY to 0x0007,
        VID_INSTA360_PRIMARY to 0x0008,
        VID_INSTA360_PRIMARY to 0x0009,
        VID_INSTA360_PRIMARY to 0x000A,
        VID_INSTA360_PRIMARY to 0x000B,
        VID_INSTA360_PRIMARY to 0x000C,
        VID_INSTA360_PRIMARY to 0x000D,
        VID_INSTA360_PRIMARY to 0x000E,
        VID_INSTA360_PRIMARY to 0x0010,
        VID_INSTA360_PRIMARY to 0x0011,
        VID_INSTA360_PRIMARY to 0x0012,
        VID_INSTA360_PRIMARY to 0x0013,
        VID_INSTA360_PRIMARY to 0x0014,
        VID_INSTA360_PRIMARY to 0x1001,
        VID_INSTA360_PRIMARY to 0x1002,
        VID_INSTA360_ALT to 0x0001,
        VID_INSTA360_SECONDARY to 0x0001,
        VID_INSTA360_SECONDARY to 0x0002
    )

    enum class UsbDeviceRole {
        CAMERA_BULK_CONTROL,
        QUICK_READER_STORAGE,
        UNSUPPORTED
    }

    /**
     * Classifies an attached USB device without Android `UsbDevice` dependencies.
     */
    fun classifyDevice(
        vendorId: Int,
        productId: Int,
        hasBulkInAndOutEndpoints: Boolean
    ): UsbDeviceRole {
        if (vendorId in CAMERA_VENDOR_IDS) {
            return if (hasBulkInAndOutEndpoints || (vendorId to productId) in KNOWN_CAMERA_VID_PID_PAIRS) {
                UsbDeviceRole.CAMERA_BULK_CONTROL
            } else {
                UsbDeviceRole.UNSUPPORTED
            }
        }
        if (vendorId in QUICK_READER_VENDOR_IDS) {
            return UsbDeviceRole.QUICK_READER_STORAGE
        }
        return UsbDeviceRole.UNSUPPORTED
    }
}
