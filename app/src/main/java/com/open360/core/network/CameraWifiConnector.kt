package com.open360.core.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraWifiStatus {
    IDLE,
    CONNECTING,
    CONNECTED,
    FAILED,
    DISCONNECTED
}

/**
 * Manages connecting to Insta360 camera Wi-Fi access points programmatically.
 *
 * Uses:
 * 1. WifiNetworkSpecifier (Android 10+) to join the camera's access point for this app only,
 *    with the name and password the camera gives over Bluetooth.
 * 2. ConnectivityManager.bindProcessToNetwork to route the app's traffic to the camera
 *    (192.168.42.1) over that link even though it has no internet, preserving Cellular/Primary Wi-Fi.
 */
class CameraWifiConnector(private val context: Context) {

    companion object {
        private const val TAG = "CameraWifiConnector"
        const val DEFAULT_CAMERA_IP = "192.168.42.1"
        const val CONNECTION_TIMEOUT_MS = 20000
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _status = MutableStateFlow(CameraWifiStatus.IDLE)
    val status: StateFlow<CameraWifiStatus> = _status.asStateFlow()

    private val _activeSsid = MutableStateFlow<String?>(null)
    val activeSsid: StateFlow<String?> = _activeSsid.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var activeCallback: ConnectivityManager.NetworkCallback? = null
    var boundNetwork: Network? = null
        private set

    /**
     * Checks whether the device is already connected to a Wi-Fi network without internet
     * (the camera AP) and immediately binds the process to it if so.
     */
    fun checkAndBindExistingCameraNetwork(): Boolean {
        // First check active network
        connectivityManager.activeNetwork?.let { network ->
            if (isCameraNetwork(network)) {
                bindProcess(network)
                return true
            }
        }

        // Check any other active networks (e.g. secondary STA interface wlan1 on dual-wifi devices)
        for (network in connectivityManager.allNetworks) {
            if (isCameraNetwork(network)) {
                bindProcess(network)
                return true
            }
        }

        return false
    }

    private fun isCameraNetwork(network: Network): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasNoInternet = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        return isWifi && hasNoInternet
    }

    private fun bindProcess(network: Network) {
        try {
            connectivityManager.bindProcessToNetwork(network)
            boundNetwork = network
            _status.value = CameraWifiStatus.CONNECTED
            _statusMessage.value = "Connected to camera Wi-Fi"
            Log.i(TAG, "Successfully bound process to camera Wi-Fi network: $network")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind process to camera network", e)
        }
    }

    /**
     * Requests connection to the camera's Wi-Fi network programmatically.
     */
    fun connect(
        targetSsid: String,
        passphrase: String,
        onConnected: (() -> Unit)? = null,
        onFailed: ((String) -> Unit)? = null
    ) {
        if (checkAndBindExistingCameraNetwork()) {
            _activeSsid.value = targetSsid
            onConnected?.invoke()
            return
        }

        disconnect()

        _status.value = CameraWifiStatus.CONNECTING
        _activeSsid.value = targetSsid
        _statusMessage.value = "Connecting to $targetSsid..."
        Log.i(TAG, "Initiating connection to camera Wi-Fi: $targetSsid")

        removeOldSuggestions()

        // Request the network via WifiNetworkSpecifier
        val requestBuilder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(targetSsid)
                .setWpa2Passphrase(passphrase)
                .build()
            requestBuilder.setNetworkSpecifier(specifier)
        }

        val request = requestBuilder.build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    connectivityManager.bindProcessToNetwork(network)
                    boundNetwork = network
                    _status.value = CameraWifiStatus.CONNECTED
                    _statusMessage.value = "Connected to $targetSsid"
                    Log.i(TAG, "Connected and bound process to camera network: $network")
                    onConnected?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind process to camera network", e)
                    _status.value = CameraWifiStatus.FAILED
                    _statusMessage.value = "Failed to bind network: ${e.message}"
                    onFailed?.invoke(e.message ?: "Failed to bind network")
                }
            }

            override fun onLost(network: Network) {
                if (boundNetwork == network) {
                    try {
                        connectivityManager.bindProcessToNetwork(null)
                    } catch (_: Exception) {}
                    boundNetwork = null
                    _status.value = CameraWifiStatus.DISCONNECTED
                    _statusMessage.value = "Camera Wi-Fi disconnected"
                    Log.i(TAG, "Camera Wi-Fi network lost, unbound process")
                }
            }

            override fun onUnavailable() {
                Log.w(TAG, "Camera Wi-Fi network unavailable or request cancelled")
                _status.value = CameraWifiStatus.FAILED
                _statusMessage.value = "Could not find camera Wi-Fi. Ensure camera Wi-Fi is turned on."
                onFailed?.invoke("Could not find camera Wi-Fi. Ensure camera Wi-Fi is turned on.")
            }
        }

        activeCallback = callback

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                connectivityManager.requestNetwork(request, callback, CONNECTION_TIMEOUT_MS)
            } else {
                connectivityManager.requestNetwork(request, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request network", e)
            _status.value = CameraWifiStatus.FAILED
            _statusMessage.value = "Connection request failed: ${e.message}"
            onFailed?.invoke(e.message ?: "Failed to request network")
        }
    }

    /**
     * Withdraws the network suggestion earlier versions registered for the camera. It held the
     * password 88888888, which the GO 3S does not use, and Android tried to join the camera as
     * the phone's main Wi-Fi with it. The request above needs no suggestion.
     */
    private fun removeOldSuggestions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wifiManager != null) {
            try {
                // An empty list withdraws every suggestion this app has made
                wifiManager.removeNetworkSuggestions(emptyList())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove network suggestions", e)
            }
        }
    }

    /**
     * Unregisters the network request callback and unbinds the app process from the camera network.
     */
    fun disconnect() {
        activeCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        activeCallback = null

        if (boundNetwork != null) {
            try {
                connectivityManager.bindProcessToNetwork(null)
            } catch (_: Exception) {}
            boundNetwork = null
        }
        _status.value = CameraWifiStatus.IDLE
        _activeSsid.value = null
        _statusMessage.value = null
    }

    /**
     * Opens native Android Internet panel or Wi-Fi settings as a manual fallback.
     */
    fun openWifiSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
                return
            } catch (_: Exception) {}
        }

        val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(wifiIntent)
        } catch (_: Exception) {}
    }
}
