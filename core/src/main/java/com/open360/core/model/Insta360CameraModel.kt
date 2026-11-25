package com.open360.core.model

/**
 * Wake-up transport strategy used by each Insta360 camera family when asleep over BLE.
 *
 * Derived from per-model camera compatibility profiles.
 */
enum class BleWakeStrategy {
    /** Wake authorization ID handshake (`FF 0D 03` / `wake_authorization_id`). */
    BLE_AUTH_ID,

    /** Fixed GO 2 wake-up GATT packet (`ff0640080008000000b00000016d90`). */
    GO2_FIXED_PACKET,

    /** Apple iBeacon `4C 00 02 15` / ORBIT-header wake advertisement broadcast. */
    IBEACON_ORBIT
}

/**
 * Supported communication transports for connecting to and controlling Insta360 cameras.
 */
enum class CameraTransportMode(
    val label: String,
    val shortLabel: String,
    val summary: String,
    val isExperimental: Boolean = false
) {
    BLE_DIRECT(
        label = "BLE Direct",
        shortLabel = "BLE",
        summary = "Low-energy Bluetooth control & GPS telemetry (BE80 / CE80 / AE00)",
        isExperimental = false
    ),
    USB_C_DIRECT(
        label = "USB-C Direct",
        shortLabel = "USB-C",
        summary = "Direct wired USB-C / OTG command, control & H.264 stream (VID 0x2E1A / 0x4255)",
        isExperimental = true
    ),
    WIFI_SOFTAP(
        label = "Wi-Fi Camera AP",
        shortLabel = "Camera AP",
        summary = "Join camera hotspot directly (192.168.42.1:6666)",
        isExperimental = false
    ),
    WIFI_STATION(
        label = "Wi-Fi Hotspot / LAN",
        shortLabel = "Station Wi-Fi",
        summary = "Connect over phone hotspot or shared LAN (192.168.49.1 or custom IP)",
        isExperimental = true
    ),
    GPS_REMOTE_SERVER(
        label = "GPS Remote Server",
        shortLabel = "GPS Remote",
        summary = "Phone acts as Insta360 GPS Preview Remote (CE80 / D0FF GATT server)",
        isExperimental = true
    ),
    BLUETOOTH_CLASSIC_SPP(
        label = "Bluetooth Classic SPP",
        shortLabel = "Classic SPP",
        summary = "Direct RFCOMM Serial Port Profile tunnel (legacy ONE X)",
        isExperimental = true
    )
}

/**
 * Catalog of 20 Insta360 camera compatibility profiles (GO 3S is hardware-verified;
 * additional camera profiles are experimental).
 */
enum class Insta360CameraModel(
    val configId: String,
    val displayName: String,
    val shortName: String,
    val officialTypeString: String,
    val family: String,
    val bleNamePrefixes: List<String>,
    val ibeaconPrefix: String,
    val wifiSsidPrefixes: List<String>,
    val usesDynamicBleWifiPassword: Boolean,
    val defaultWifiPassword: String,
    val supportsBleWakeAuth: Boolean,
    val wakeStrategy: BleWakeStrategy,
    val supportsUsbDirect: Boolean,
    val supportsStationWifiMode: Boolean,
    val supportsClassicSpp: Boolean,
    val drawableName: String,
    val blackDrawableName: String? = null,
    val previewPostureAdjustDegrees: Int = 0,
    val supportsVariableLivePreviewResolution: Boolean = true,
    val isHardwareVerified: Boolean = false
) {
    GO_3S(
        configId = "tc2",
        displayName = "Insta360 GO 3S",
        shortName = "GO 3S",
        officialTypeString = "Insta360 GO 3S",
        family = "GO Series",
        bleNamePrefixes = listOf("GO 3S", "Insta360 GO 3S"),
        ibeaconPrefix = "IAT",
        wifiSsidPrefixes = listOf("GO 3S", "Insta360 GO 3S"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_go3s",
        blackDrawableName = "cam_go3s_black",
        previewPostureAdjustDegrees = 90,
        supportsVariableLivePreviewResolution = false,
        isHardwareVerified = true
    ),
    GO_ULTRA(
        configId = "tc4",
        displayName = "Insta360 GO Ultra",
        shortName = "GO Ultra",
        officialTypeString = "Insta360 GO Ultra",
        family = "GO Series",
        bleNamePrefixes = listOf("GO Ultra", "Insta360 GO Ultra"),
        ibeaconPrefix = "IBE",
        wifiSsidPrefixes = listOf("GO Ultra", "Insta360 GO Ultra"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_goultra",
        blackDrawableName = "cam_goultra_black"
    ),
    GO_3(
        configId = "go3",
        displayName = "Insta360 GO 3",
        shortName = "GO 3",
        officialTypeString = "Insta360 GO 3",
        family = "GO Series",
        bleNamePrefixes = listOf("GO 3 ", "GO 3_", "GO 3-", "Insta360 GO 3"),
        ibeaconPrefix = "IBK",
        wifiSsidPrefixes = listOf("GO 3", "Insta360 GO 3"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_go3",
        blackDrawableName = "cam_go3_black",
        previewPostureAdjustDegrees = 180,
        supportsVariableLivePreviewResolution = false
    ),
    GO_2(
        configId = "go2",
        displayName = "Insta360 GO 2",
        shortName = "GO 2",
        officialTypeString = "Insta360 GO 2",
        family = "GO Series",
        bleNamePrefixes = listOf("GO 2", "Insta360 GO 2"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("GO 2", "Insta360 GO 2"),
        usesDynamicBleWifiPassword = false,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = false,
        wakeStrategy = BleWakeStrategy.GO2_FIXED_PACKET,
        supportsUsbDirect = true,
        supportsStationWifiMode = false,
        supportsClassicSpp = false,
        drawableName = "cam_go2",
        previewPostureAdjustDegrees = 180,
        supportsVariableLivePreviewResolution = false
    ),
    X5(
        configId = "a3",
        displayName = "Insta360 X5",
        shortName = "X5",
        officialTypeString = "Insta360 X5",
        family = "X Series",
        bleNamePrefixes = listOf("X5", "Insta360 X5"),
        ibeaconPrefix = "IAH",
        wifiSsidPrefixes = listOf("X5", "Insta360 X5"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_x5"
    ),
    X6(
        configId = "c9",
        displayName = "Insta360 X6",
        shortName = "X6",
        officialTypeString = "Insta360 X6",
        family = "X Series",
        bleNamePrefixes = listOf("X6", "Insta360 X6"),
        ibeaconPrefix = "BX",
        wifiSsidPrefixes = listOf("X6", "Insta360 X6"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_x6"
    ),
    X4_AIR(
        configId = "b2",
        displayName = "Insta360 X4 Air",
        shortName = "X4 Air",
        officialTypeString = "Insta360 X4 Air",
        family = "X Series",
        bleNamePrefixes = listOf("X4 Air", "Insta360 X4 Air"),
        ibeaconPrefix = "IAF",
        wifiSsidPrefixes = listOf("X4 Air", "Insta360 X4 Air"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_x4_air"
    ),
    X4(
        configId = "dlc4",
        displayName = "Insta360 X4",
        shortName = "X4",
        officialTypeString = "Insta360 X4",
        family = "X Series",
        bleNamePrefixes = listOf("X4 ", "X4_", "X4-", "Insta360 X4"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("X4", "Insta360 X4"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_x4"
    ),
    X3(
        configId = "x3",
        displayName = "Insta360 X3",
        shortName = "X3",
        officialTypeString = "Insta360 X3",
        family = "X Series",
        bleNamePrefixes = listOf("X3", "Insta360 X3"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("X3", "Insta360 X3"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = false,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_x3",
        previewPostureAdjustDegrees = 180
    ),
    ONE_X2(
        configId = "onex2",
        displayName = "Insta360 ONE X2",
        shortName = "ONE X2",
        officialTypeString = "Insta360 OneX2",
        family = "X Series",
        bleNamePrefixes = listOf("ONE X2", "OneX2", "Insta360 OneX2", "Insta360 ONE X2"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("ONE X2", "OneX2", "Insta360 OneX2"),
        usesDynamicBleWifiPassword = false,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = false,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = false,
        supportsClassicSpp = false,
        drawableName = "cam_onex2",
        previewPostureAdjustDegrees = 180
    ),
    ONE_X(
        configId = "onex",
        displayName = "Insta360 ONE X",
        shortName = "ONE X",
        officialTypeString = "Insta360 One2",
        family = "X Series",
        bleNamePrefixes = listOf("ONE X", "OneX", "One2", "Insta360 One2", "Insta360 ONE X"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("ONE X", "OneX", "One2", "Insta360 One"),
        usesDynamicBleWifiPassword = false,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = false,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = false,
        supportsClassicSpp = true,
        drawableName = "cam_onex"
    ),
    ONE_RS(
        configId = "oners",
        displayName = "Insta360 ONE RS",
        shortName = "ONE RS",
        officialTypeString = "Insta360 ONE RS",
        family = "ONE R / RS",
        bleNamePrefixes = listOf("ONE RS", "Insta360 ONE RS"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("ONE RS", "Insta360 ONE RS"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = false,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_oners",
        previewPostureAdjustDegrees = 180
    ),
    ONE_R(
        configId = "oner",
        displayName = "Insta360 ONE R",
        shortName = "ONE R",
        officialTypeString = "Insta360 ONE R",
        family = "ONE R / RS",
        bleNamePrefixes = listOf("ONE R ", "ONE R_", "ONE R-", "Insta360 ONE R"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("ONE R", "Insta360 ONE R"),
        usesDynamicBleWifiPassword = false,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = false,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = false,
        supportsClassicSpp = false,
        drawableName = "cam_oner",
        previewPostureAdjustDegrees = 180
    ),
    ACE_PRO_3(
        configId = "iac4",
        displayName = "Insta360 Ace Pro 3",
        shortName = "Ace Pro 3",
        officialTypeString = "Insta360 Ace Pro 3",
        family = "Ace Series",
        bleNamePrefixes = listOf("Ace Pro 3", "Insta360 Ace Pro 3"),
        ibeaconPrefix = "BU",
        wifiSsidPrefixes = listOf("Ace Pro 3", "Insta360 Ace Pro 3"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_ace_pro_3"
    ),
    ACE_PRO_2(
        configId = "iac3",
        displayName = "Insta360 Ace Pro 2",
        shortName = "Ace Pro 2",
        officialTypeString = "Insta360 Ace Pro 2",
        family = "Ace Series",
        bleNamePrefixes = listOf("Ace Pro 2", "Insta360 Ace Pro 2"),
        ibeaconPrefix = "IBG",
        wifiSsidPrefixes = listOf("Ace Pro 2", "Insta360 Ace Pro 2"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_ace_pro_2"
    ),
    ACE_PRO(
        configId = "iac2",
        displayName = "Insta360 Ace Pro",
        shortName = "Ace Pro",
        officialTypeString = "Insta360 Ace Pro",
        family = "Ace Series",
        bleNamePrefixes = listOf("Ace Pro ", "Ace Pro_", "Ace Pro-", "Insta360 Ace Pro"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("Ace Pro", "Insta360 Ace Pro"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_ace_pro"
    ),
    ACE(
        configId = "iac1",
        displayName = "Insta360 Ace",
        shortName = "Ace",
        officialTypeString = "Insta360 Ace",
        family = "Ace Series",
        bleNamePrefixes = listOf("Ace ", "Ace_", "Ace-", "Insta360 Ace"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("Ace", "Insta360 Ace"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_ace"
    ),
    SPHERE(
        configId = "sphere",
        displayName = "Insta360 Sphere",
        shortName = "Sphere",
        officialTypeString = "Insta360 Sphere",
        family = "Specialty",
        bleNamePrefixes = listOf("Sphere", "Insta360 Sphere"),
        ibeaconPrefix = "",
        wifiSsidPrefixes = listOf("Sphere", "Insta360 Sphere"),
        usesDynamicBleWifiPassword = false,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = false,
        wakeStrategy = BleWakeStrategy.IBEACON_ORBIT,
        supportsUsbDirect = true,
        supportsStationWifiMode = false,
        supportsClassicSpp = false,
        drawableName = "cam_sphere"
    ),
    LUNA_ULTRA(
        configId = "z03",
        displayName = "Insta360 Luna Ultra",
        shortName = "Luna Ultra",
        officialTypeString = "Insta360 Luna Ultra",
        family = "Specialty",
        bleNamePrefixes = listOf("Luna Ultra", "Insta360 Luna Ultra"),
        ibeaconPrefix = "BT",
        wifiSsidPrefixes = listOf("Luna Ultra", "Insta360 Luna Ultra"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_luna_ultra",
        previewPostureAdjustDegrees = 270
    ),
    LUNA_PRO(
        configId = "z05",
        displayName = "Insta360 Luna Pro",
        shortName = "Luna Pro",
        officialTypeString = "Insta360 Luna Pro",
        family = "Specialty",
        bleNamePrefixes = listOf("Luna Pro", "Insta360 Luna Pro"),
        ibeaconPrefix = "BT",
        wifiSsidPrefixes = listOf("Luna Pro", "Insta360 Luna Pro"),
        usesDynamicBleWifiPassword = true,
        defaultWifiPassword = "88888888",
        supportsBleWakeAuth = true,
        wakeStrategy = BleWakeStrategy.BLE_AUTH_ID,
        supportsUsbDirect = true,
        supportsStationWifiMode = true,
        supportsClassicSpp = false,
        drawableName = "cam_luna_pro",
        previewPostureAdjustDegrees = 270
    );

    /** Returns the transport modes supported by this camera model. */
    fun supportedTransports(): List<CameraTransportMode> = buildList {
        add(CameraTransportMode.BLE_DIRECT)
        if (supportsUsbDirect) add(CameraTransportMode.USB_C_DIRECT)
        add(CameraTransportMode.WIFI_SOFTAP)
        if (supportsStationWifiMode) add(CameraTransportMode.WIFI_STATION)
        add(CameraTransportMode.GPS_REMOTE_SERVER)
        if (supportsClassicSpp) add(CameraTransportMode.BLUETOOTH_CLASSIC_SPP)
    }

    companion object {
        /**
         * Resolves a camera model from a reported `deviceType`, BLE advertised name, Wi-Fi SSID,
         * `configId`, or iBeacon serial prefix (`IAT...`, `IAH...`, etc.). Longest specific match
         * wins so `GO 3S` never collides with `GO 3` and `X4 Air` never collides with `X4`.
         */
        fun resolveByIdentity(
            deviceType: String? = null,
            deviceName: String? = null,
            wifiSsid: String? = null,
            serialNumber: String? = null
        ): Insta360CameraModel? {
            val candidates = listOfNotNull(deviceType, deviceName, wifiSsid)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (candidates.isNotEmpty()) {
                for (raw in candidates) {
                    entries.firstOrNull {
                        it.configId.equals(raw, ignoreCase = true) ||
                            it.officialTypeString.equals(raw, ignoreCase = true) ||
                            it.displayName.equals(raw, ignoreCase = true) ||
                            it.shortName.equals(raw, ignoreCase = true)
                    }?.let { return it }
                }

                // Match longest delimiter-bounded prefix first so GO 3S / X4 Air / Ace Pro 3 / ONE RS stay distinct
                // without matching substrings inside unrelated device names (e.g. "Pace Watch" or "BMW X5").
                var bestModel: Insta360CameraModel? = null
                var bestLength = -1
                for (raw in candidates) {
                    val cleanRaw = raw.trim()
                    for (model in entries) {
                        val keys = (model.bleNamePrefixes + model.wifiSsidPrefixes + model.shortName + model.displayName)
                            .distinct()
                        for (key in keys) {
                            val cleanKey = key.trim()
                            if (matchesBoundedPrefix(cleanRaw, cleanKey) && cleanKey.length > bestLength) {
                                bestModel = model
                                bestLength = cleanKey.length
                            }
                        }
                    }
                }
                if (bestModel != null) return bestModel
            }

            val serial = serialNumber?.trim().orEmpty()
            if (serial.length >= 2) {
                return entries
                    .filter { it.ibeaconPrefix.isNotEmpty() }
                    .sortedByDescending { it.ibeaconPrefix.length }
                    .firstOrNull { serial.startsWith(it.ibeaconPrefix, ignoreCase = true) }
            }
            return null
        }

        private fun matchesBoundedPrefix(candidate: String, prefix: String): Boolean {
            if (prefix.isEmpty() || !candidate.startsWith(prefix, ignoreCase = true)) return false
            if (candidate.length == prefix.length) return true
            val next = candidate[prefix.length]
            return next == ' ' || next == '_' || next == '-' || next == '.'
        }
    }
}
