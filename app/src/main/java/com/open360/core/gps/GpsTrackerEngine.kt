package com.open360.core.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * High-precision GPS tracking engine using Android LocationManager.
 *
 * Configured for 1 Hz (1000ms) high-frequency updates, raw GNSS satellite tracking,
 * and real-time speed/altitude calculations.
 */
class GpsTrackerEngine(private val context: Context) {

    companion object {
        private const val TAG = "GpsTrackerEngine"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val MIN_DISTANCE_METERS = 0f
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val _telemetry = MutableStateFlow(GpsTelemetry())
    val telemetry: StateFlow<GpsTelemetry> = _telemetry.asStateFlow()

    private var isTracking = false
    private var locationCallback: ((Double, Double, Double) -> Unit)? = null

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speedMps = if (location.hasSpeed()) location.speed else 0f
            val speedKmh = speedMps * 3.6f
            val speedMph = speedMps * 2.23694f
            val altitude = if (location.hasAltitude()) location.altitude else 0.0
            val bearing = if (location.hasBearing()) location.bearing else 0f
            val accuracy = if (location.hasAccuracy()) location.accuracy else 0f

            _telemetry.update { current ->
                current.copy(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    altitudeMeters = altitude,
                    speedKmh = speedKmh,
                    speedMph = speedMph,
                    bearingDegrees = bearing,
                    accuracyMeters = accuracy,
                    hasFix = true,
                    timestampEpochMs = location.time
                )
            }

            locationCallback?.invoke(location.latitude, location.longitude, altitude)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private var gnssCallback: GnssStatus.Callback? = null

    /**
     * Starts receiving continuous 1 Hz GPS updates and GNSS satellite statistics.
     */
    @SuppressLint("MissingPermission")
    fun startTracking(onLocationUpdate: ((latitude: Double, longitude: Double, altitude: Double) -> Unit)? = null) {
        if (isTracking) return
        if (!hasLocationPermission()) {
            Log.w(TAG, "Cannot start GPS tracking: missing location permission")
            return
        }

        val lm = locationManager ?: run {
            Log.w(TAG, "LocationManager not available")
            return
        }

        locationCallback = onLocationUpdate
        isTracking = true

        try {
            // Subscribe to GPS provider for high accuracy
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_METERS,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            // Also request network provider for immediate coarse fix while satellites lock
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    UPDATE_INTERVAL_MS,
                    MIN_DISTANCE_METERS,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            // Track satellite counts and GNSS fix
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val cb = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        var inFixCount = 0
                        val total = status.satelliteCount
                        for (i in 0 until total) {
                            if (status.usedInFix(i)) {
                                inFixCount++
                            }
                        }
                        _telemetry.update {
                            it.copy(
                                satellitesInFix = inFixCount,
                                satellitesTotal = total,
                                hasFix = inFixCount >= 4 || it.hasFix
                            )
                        }
                    }
                }
                gnssCallback = cb
                lm.registerGnssStatusCallback(cb, null)
            }

            Log.i(TAG, "GPS tracking started at 1 Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS tracking", e)
            isTracking = false
        }
    }

    /**
     * Stops receiving GPS updates.
     */
    @SuppressLint("MissingPermission")
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        locationCallback = null

        val lm = locationManager ?: return
        try {
            lm.removeUpdates(locationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
                gnssCallback = null
            }
            _telemetry.update { it.copy(hasFix = false) }
            Log.i(TAG, "GPS tracking stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping GPS tracking", e)
        }
    }
}
