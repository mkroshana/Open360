package com.open360.core.connection

import android.content.Context
import androidx.core.content.edit
import com.open360.core.model.CameraTransportMode
import com.open360.core.model.Insta360CameraModel
import com.open360.core.network.CameraWifiResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Persisted connection transport settings supporting all 5 Insta360 transport architectures
 * and manual credentials / LAN host overrides.
 */
data class ConnectionSettings(
    val preferredTransport: CameraTransportMode = CameraTransportMode.BLE_DIRECT,
    val selectedModelOverride: Insta360CameraModel? = null,
    val manualWifiSsid: String = "",
    val manualWifiPassword: String = "",
    val stationHostIp: String = CameraWifiResolver.STATION_DEFAULT_HOST,
    val classicSppAddress: String = "",
    val gpsRemoteServerEnabled: Boolean = false
)

class ConnectionPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "open360_connection_prefs"
        private const val KEY_TRANSPORT = "preferred_transport"
        private const val KEY_MODEL = "selected_model"
        private const val KEY_WIFI_SSID = "manual_wifi_ssid"
        private const val KEY_WIFI_PASS = "manual_wifi_password"
        private const val KEY_STATION_IP = "station_host_ip"
        private const val KEY_SPP_ADDR = "classic_spp_address"
        private const val KEY_GPS_REMOTE = "gps_remote_server_enabled"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ConnectionSettings> = _settings.asStateFlow()

    private fun load(): ConnectionSettings {
        val transportName = prefs.getString(KEY_TRANSPORT, CameraTransportMode.BLE_DIRECT.name)
        val transport = CameraTransportMode.entries.firstOrNull { it.name == transportName }
            ?: CameraTransportMode.BLE_DIRECT
        val modelId = prefs.getString(KEY_MODEL, null)
        val model = modelId?.let { id -> Insta360CameraModel.entries.firstOrNull { it.configId == id } }
        return ConnectionSettings(
            preferredTransport = transport,
            selectedModelOverride = model,
            manualWifiSsid = prefs.getString(KEY_WIFI_SSID, "") ?: "",
            manualWifiPassword = prefs.getString(KEY_WIFI_PASS, "") ?: "",
            stationHostIp = prefs.getString(KEY_STATION_IP, CameraWifiResolver.STATION_DEFAULT_HOST)
                ?.takeIf { it.isNotBlank() }
                ?: CameraWifiResolver.STATION_DEFAULT_HOST,
            classicSppAddress = prefs.getString(KEY_SPP_ADDR, "") ?: "",
            gpsRemoteServerEnabled = prefs.getBoolean(KEY_GPS_REMOTE, false)
        )
    }

    fun setPreferredTransport(mode: CameraTransportMode) {
        prefs.edit { putString(KEY_TRANSPORT, mode.name) }
        _settings.update { it.copy(preferredTransport = mode) }
    }

    fun setSelectedModelOverride(model: Insta360CameraModel?) {
        prefs.edit {
            if (model == null) remove(KEY_MODEL) else putString(KEY_MODEL, model.configId)
        }
        _settings.update { it.copy(selectedModelOverride = model) }
    }

    fun setManualWifiCredentials(ssid: String, password: String) {
        prefs.edit {
            putString(KEY_WIFI_SSID, ssid.trim())
            putString(KEY_WIFI_PASS, password)
        }
        _settings.update { it.copy(manualWifiSsid = ssid.trim(), manualWifiPassword = password) }
    }

    fun setStationHostIp(ip: String) {
        val clean = ip.trim().ifEmpty { CameraWifiResolver.STATION_DEFAULT_HOST }
        prefs.edit { putString(KEY_STATION_IP, clean) }
        _settings.update { it.copy(stationHostIp = clean) }
    }

    fun setClassicSppAddress(address: String) {
        val clean = address.trim()
        prefs.edit { putString(KEY_SPP_ADDR, clean) }
        _settings.update { it.copy(classicSppAddress = clean) }
    }

    fun setGpsRemoteServerEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_GPS_REMOTE, enabled) }
        _settings.update { it.copy(gpsRemoteServerEnabled = enabled) }
    }
}
