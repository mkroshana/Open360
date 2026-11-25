package com.open360.core.network

import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel

/**
 * Pure Kotlin resolver for Insta360 Wi-Fi host IPs and credential cascades across SoftAP,
 * Station/Hotspot mode, manual credentials, and legacy static `"88888888"` passwords.
 */
object CameraWifiResolver {
    const val SOFTAP_DEFAULT_HOST: String = "192.168.42.1"
    const val STATION_DEFAULT_HOST: String = "192.168.49.1"
    const val DEFAULT_SOCKET_PORT: Int = 6666
    const val DEFAULT_HTTP_PORT: Int = 80
    const val LEGACY_DEFAULT_PASSWORD: String = "88888888"

    data class WifiEndpoint(
        val host: String,
        val socketPort: Int,
        val httpBaseUrl: String,
        val requiresNoInternetCapability: Boolean
    )

    data class WifiCredentials(
        val ssid: String,
        val password: String,
        val source: CredentialSource
    )

    enum class CredentialSource {
        MANUAL,
        BLE_DYNAMIC,
        LEGACY_DEFAULT
    }

    private val CAMERA_SSID_PREFIXES = listOf(
        "GO 3",
        "GO 2",
        "Insta360",
        "ONE X",
        "ONE R",
        "ONE RS",
        "X3",
        "X4",
        "X5",
        "Ace"
    )

    /**
     * Resolves socket + HTTP endpoints for SoftAP (`192.168.42.1`) or Station/Hotspot/LAN
     * (`192.168.49.1`, gateway IP, or user override).
     */
    fun resolveEndpoint(
        transportMode: CameraTransportMode,
        customHostIp: String? = null,
        discoveredGatewayIp: String? = null
    ): WifiEndpoint {
        val manual = customHostIp?.trim()?.takeIf { it.isNotEmpty() }
        val discovered = discoveredGatewayIp?.trim()?.takeIf { it.isNotEmpty() }
        return when (transportMode) {
            CameraTransportMode.WIFI_STATION -> {
                val host = if (manual != null && !(manual == STATION_DEFAULT_HOST && discovered == SOFTAP_DEFAULT_HOST)) {
                    manual
                } else {
                    discovered ?: STATION_DEFAULT_HOST
                }
                WifiEndpoint(
                    host = host,
                    socketPort = DEFAULT_SOCKET_PORT,
                    httpBaseUrl = "http://$host",
                    requiresNoInternetCapability = false
                )
            }
            else -> {
                val host = discovered?.takeIf { isCameraSoftApIp(it) } ?: SOFTAP_DEFAULT_HOST
                WifiEndpoint(
                    host = host,
                    socketPort = DEFAULT_SOCKET_PORT,
                    httpBaseUrl = "http://$host",
                    requiresNoInternetCapability = true
                )
            }
        }
    }

    /** Returns true when [ip] is an IPv4 address in the camera SoftAP `192.168.42.*` subnet. */
    fun isCameraSoftApIp(ip: String?): Boolean {
        val trimmed = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val octets = trimmed.split('.')
        if (octets.size != 4) return false
        return octets[0] == "192" &&
            octets[1] == "168" &&
            octets[2] == "42" &&
            octets[3].toIntOrNull() in 1..254
    }

    /** Strips quotes and whitespace from an Android Wi-Fi SSID, returning null if unknown or blank. */
    fun normalizeSsid(rawSsid: String?): String? {
        val cleaned = rawSsid?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned.equals("<unknown ssid>", ignoreCase = true)) {
            return null
        }
        return cleaned
    }

    /**
     * Returns true when [ssid] matches [targetSsid] (if supplied) or resembles an Insta360 camera
     * SoftAP SSID (`.OSC` suffix or known camera model prefix).
     */
    fun isLikelyCameraSsid(ssid: String?, targetSsid: String? = null): Boolean {
        val normalized = normalizeSsid(ssid) ?: return false
        val normalizedTarget = normalizeSsid(targetSsid)
        if (normalizedTarget != null && normalized.equals(normalizedTarget, ignoreCase = true)) {
            return true
        }
        if (normalized.endsWith(".OSC", ignoreCase = true)) {
            return true
        }
        return CAMERA_SSID_PREFIXES.any { prefix ->
            normalized.startsWith(prefix, ignoreCase = true)
        }
    }

    /**
     * Resolves SSID and passphrase for connecting to the camera's Wi-Fi network:
     * 1. Manual user override when provided.
     * 2. Dynamic BLE `OPTION_WIFI_INFO` credentials when available.
     * 3. Known SSID + legacy `"88888888"` passphrase fallback for ONE X / ONE X2 / ONE R / GO 2
     *    or when dynamic BLE credentials were not returned.
     */
    fun resolveCredentials(
        manualSsid: String? = null,
        manualPassword: String? = null,
        bleSsid: String? = null,
        blePassword: String? = null,
        model: Insta360CameraModel? = null
    ): WifiCredentials? {
        val cleanManualSsid = manualSsid?.trim().orEmpty()
        val cleanManualPass = manualPassword?.trim().orEmpty()
        if (cleanManualSsid.isNotEmpty()) {
            val pw = cleanManualPass.ifEmpty {
                blePassword?.takeIf { it.isNotEmpty() }
                    ?: model?.defaultWifiPassword
                    ?: LEGACY_DEFAULT_PASSWORD
            }
            return WifiCredentials(cleanManualSsid, pw, CredentialSource.MANUAL)
        }

        val cleanBleSsid = bleSsid?.trim().orEmpty()
        val cleanBlePass = blePassword?.trim().orEmpty()
        if (cleanBleSsid.isNotEmpty() && cleanBlePass.isNotEmpty()) {
            return WifiCredentials(cleanBleSsid, cleanBlePass, CredentialSource.BLE_DYNAMIC)
        }

        if (cleanBleSsid.isNotEmpty()) {
            return WifiCredentials(
                ssid = cleanBleSsid,
                password = model?.defaultWifiPassword ?: LEGACY_DEFAULT_PASSWORD,
                source = CredentialSource.LEGACY_DEFAULT
            )
        }

        return null
    }
}
