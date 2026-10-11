package com.open360.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Binds the app process to the Wi-Fi network interface.
 *
 * CRITICAL ON ANDROID:
 * Action cameras (like Insta360) provide a local Wi-Fi AP with NO internet access.
 * Android's default behavior is to route all HTTP and socket traffic over Cellular (LTE/5G)
 * when a Wi-Fi network lacks internet. Without bindProcessToNetwork, 192.168.42.1 is unreachable.
 */
class WifiNetworkBinder(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    var isBoundToWifi: Boolean = false
        private set

    fun startListening(onWifiAvailable: (() -> Unit)? = null) {
        if (networkCallback != null) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    connectivityManager.bindProcessToNetwork(network)
                    isBoundToWifi = true
                    Log.i("WifiNetworkBinder", "Successfully bound process to Wi-Fi network: $network")
                    onWifiAvailable?.invoke()
                } catch (e: Exception) {
                    Log.e("WifiNetworkBinder", "Failed to bind process to Wi-Fi network", e)
                }
            }

            override fun onLost(network: Network) {
                if (connectivityManager.boundNetworkForProcess == network) {
                    connectivityManager.bindProcessToNetwork(null)
                    isBoundToWifi = false
                    Log.i("WifiNetworkBinder", "Wi-Fi network lost, unbound process")
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e("WifiNetworkBinder", "Failed to register network callback", e)
        }
    }

    fun stopListening() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
        try {
            connectivityManager.bindProcessToNetwork(null)
        } catch (_: Exception) {}
        isBoundToWifi = false
    }
}
