package com.open360.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

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
        const val DEFAULT_STATION_IP = "192.168.49.1"
        const val CONNECTION_TIMEOUT_MS = 25_000
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
    private val connectMutex = Mutex()

    @Volatile
    var boundNetwork: Network? = null
        private set

    @Volatile
    private var cachedGatewayIp: String? = null

    @Volatile
    var lastFreshConnectElapsedMs: Long = 0L
        private set

    fun getBoundGatewayIp(): String? = cachedGatewayIp ?: boundNetwork?.let { extractGatewayIp(it) }

    /**
     * Checks whether the device is already connected to a camera Wi-Fi network (preferring a
     * SoftAP network at `192.168.42.*` or matching camera SSID over dual-STA interfaces) and
     * immediately binds the process to it if so.
     */
    @Suppress("DEPRECATION")
    fun checkAndBindExistingCameraNetwork(
        allowStationMode: Boolean = false,
        targetSsid: String? = null
    ): Boolean {
        val candidates = linkedSetOf<Network>()
        boundNetwork?.let { existing ->
            if (connectivityManager.getNetworkCapabilities(existing) != null) {
                candidates.add(existing)
            }
        }
        connectivityManager.allNetworks.forEach { candidates.add(it) }
        connectivityManager.activeNetwork?.let { candidates.add(it) }

        val wifiCandidates = candidates.mapNotNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            network to caps
        }

        // Priority 1: definite camera SoftAP by 192.168.42.* LinkProperties or camera SSID.
        wifiCandidates.firstOrNull { (network, caps) ->
            hasCameraSoftApProperties(network, caps, targetSsid)
        }?.let { (network, caps) ->
            bindProcess(network, caps)
            return true
        }

        // Priority 2: any local-only or unvalidated Wi-Fi network (covers manually joined camera APs
        // where Android reports NET_CAPABILITY_INTERNET without NET_CAPABILITY_VALIDATED).
        if (!allowStationMode) {
            wifiCandidates.firstOrNull { (_, caps) ->
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }?.let { (network, caps) ->
                bindProcess(network, caps)
                return true
            }
            return false
        }

        // Priority 3: Station / LAN mode allows validated Wi-Fi networks, preferring activeNetwork.
        val activeWifi = connectivityManager.activeNetwork?.let { active ->
            wifiCandidates.firstOrNull { (network, _) -> network == active }
        } ?: wifiCandidates.firstOrNull()

        return if (activeWifi != null) {
            bindProcess(activeWifi.first, activeWifi.second)
            true
        } else {
            false
        }
    }

    private fun hasCameraSoftApProperties(
        network: Network,
        caps: NetworkCapabilities,
        targetSsid: String?
    ): Boolean {
        val lp = connectivityManager.getLinkProperties(network)
        if (lp != null) {
            val hasSoftApAddr = lp.linkAddresses.any { linkAddr ->
                CameraWifiResolver.isCameraSoftApIp((linkAddr.address as? Inet4Address)?.hostAddress)
            }
            if (hasSoftApAddr) return true
            if (CameraWifiResolver.isCameraSoftApIp(extractGatewayIp(lp))) return true
        }
        val ssid = extractSsid(caps)
        return CameraWifiResolver.isLikelyCameraSsid(ssid, targetSsid)
    }

    private fun extractSsid(caps: NetworkCapabilities): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val info = caps.transportInfo as? WifiInfo
            return CameraWifiResolver.normalizeSsid(info?.ssid)
        }
        return legacyConnectedSsid()
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun legacyConnectedSsid(): String? {
        val info = runCatching { wifiManager?.connectionInfo }.getOrNull() ?: return null
        return CameraWifiResolver.normalizeSsid(info.ssid)
    }

    private fun extractGatewayIp(network: Network): String? {
        val lp = connectivityManager.getLinkProperties(network) ?: return null
        return extractGatewayIp(lp)
    }

    private fun extractGatewayIp(lp: LinkProperties): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcp = (lp.dhcpServerAddress as? Inet4Address)?.hostAddress
            if (!dhcp.isNullOrBlank() && dhcp != "0.0.0.0") return dhcp
        }
        for (route in lp.routes) {
            if (route.isDefaultRoute) {
                val gw = (route.gateway as? Inet4Address)?.hostAddress
                if (!gw.isNullOrBlank() && gw != "0.0.0.0") return gw
            }
        }
        for (linkAddr in lp.linkAddresses) {
            val host = (linkAddr.address as? Inet4Address)?.hostAddress
            if (CameraWifiResolver.isCameraSoftApIp(host)) {
                return DEFAULT_CAMERA_IP
            }
        }
        return null
    }

    private fun bindProcess(network: Network, caps: NetworkCapabilities? = null) {
        try {
            val effectiveCaps = caps ?: connectivityManager.getNetworkCapabilities(network)
            val lp = connectivityManager.getLinkProperties(network)
            val gateway = lp?.let { extractGatewayIp(it) }
            val ssid = effectiveCaps?.let { extractSsid(it) }
            connectivityManager.bindProcessToNetwork(network)
            boundNetwork = network
            cachedGatewayIp = gateway
            if (ssid != null) {
                _activeSsid.value = ssid
            }
            _status.value = CameraWifiStatus.CONNECTED
            _statusMessage.value = "Connected to camera Wi-Fi"
            val localIps = lp?.linkAddresses
                ?.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                ?.joinToString()
                .orEmpty()
            Log.i(
                TAG,
                "Bound process to Wi-Fi network=$network handle=${network.networkHandle} iface=${lp?.interfaceName} " +
                    "ssid=$ssid localIps=[$localIps] gateway=$gateway caps=$effectiveCaps"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind process to camera network", e)
        }
    }

    /**
     * Suspends until the app process is connected and bound to the camera Wi-Fi network.
     * Serializes concurrent connection attempts via [connectMutex].
     */
    suspend fun ensureConnected(targetSsid: String, passphrase: String): Result<Unit> =
        connectMutex.withLock {
            if (checkAndBindExistingCameraNetwork(targetSsid = targetSsid)) {
                _activeSsid.value = targetSsid
                return@withLock Result.success(Unit)
            }
            suspendCancellableCoroutine { cont ->
                val settled = AtomicBoolean(false)
                cont.invokeOnCancellation {
                    if (settled.compareAndSet(false, true)) {
                        disconnect()
                    }
                }
                connect(
                    targetSsid = targetSsid,
                    passphrase = passphrase,
                    onConnected = {
                        if (settled.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(Result.success(Unit))
                        }
                    },
                    onFailed = { message ->
                        if (settled.compareAndSet(false, true) && cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException(message)))
                        }
                    }
                )
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
        if (checkAndBindExistingCameraNetwork(targetSsid = targetSsid)) {
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
                if (activeCallback !== this) return
                lastFreshConnectElapsedMs = SystemClock.elapsedRealtime()
                try {
                    bindProcess(network)
                    _statusMessage.value = "Connected to $targetSsid"
                    onConnected?.invoke()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to bind process to camera network", e)
                    _status.value = CameraWifiStatus.FAILED
                    _statusMessage.value = "Failed to bind network: ${e.message}"
                    onFailed?.invoke(e.message ?: "Failed to bind network")
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (activeCallback !== this) return
                if (boundNetwork == network) {
                    cachedGatewayIp = extractGatewayIp(linkProperties) ?: cachedGatewayIp
                    Log.i(
                        TAG,
                        "Camera Wi-Fi LinkProperties updated: iface=${linkProperties.interfaceName} " +
                            "gateway=$cachedGatewayIp addrs=${linkProperties.linkAddresses}"
                    )
                }
            }

            override fun onLost(network: Network) {
                if (activeCallback !== this) return
                if (boundNetwork == network) {
                    try {
                        connectivityManager.bindProcessToNetwork(null)
                    } catch (_: Exception) {}
                    boundNetwork = null
                    cachedGatewayIp = null
                    _status.value = CameraWifiStatus.DISCONNECTED
                    _statusMessage.value = "Camera Wi-Fi disconnected"
                    Log.i(TAG, "Camera Wi-Fi network lost, unbound process")
                }
            }

            override fun onUnavailable() {
                if (activeCallback !== this) return
                Log.w(TAG, "Camera Wi-Fi network unavailable or request cancelled")
                _status.value = CameraWifiStatus.FAILED
                _statusMessage.value = "Could not find camera Wi-Fi. Ensure camera Wi-Fi is turned on."
                onFailed?.invoke("Could not find camera Wi-Fi. Ensure camera Wi-Fi is turned on.")
            }
        }

        activeCallback = callback

        try {
            connectivityManager.requestNetwork(request, callback, CONNECTION_TIMEOUT_MS)
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
        cachedGatewayIp = null
        lastFreshConnectElapsedMs = 0L
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
