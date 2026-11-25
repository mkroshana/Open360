package com.open360.core.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
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
        private const val FIX_STALE_MS = 5500L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val _telemetry = MutableStateFlow(GpsTelemetry())
    val telemetry: StateFlow<GpsTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var isTracking = false
    private var locationCallback: ((Double, Double, Double) -> Unit)? = null
    private var locationThread: HandlerThread? = null
    private var locationHandler: Handler? = null
    private var altitudeSourceLogged = false

    private val staleFixRunnable = Runnable {
        if (!isTracking) return@Runnable
        _telemetry.update { current ->
            if (current.hasFix && SystemClock.elapsedRealtime() - current.fixElapsedRealtimeMs >= 5000L) {
                current.copy(hasFix = false)
            } else {
                current
            }
        }
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speedMps = if (location.hasSpeed()) location.speed else 0f
            val speedKmh = speedMps * 3.6f
            val speedMph = speedMps * 2.23694f
            val altitude = seaLevelAltitude(location)
            val altitudeAccuracy = if (altitude != null) altitudeAccuracy(location) else null
            Log.v(TAG, "Fix delivered ${SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / 1_000_000} ms after it was taken")
            val bearing = if (location.hasBearing()) location.bearing else 0f
            val accuracy = if (location.hasAccuracy()) location.accuracy else 0f

            _telemetry.update { current ->
                current.copy(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    // A fix without altitude (network provider) keeps the last known one
                    altitudeMeters = altitude ?: current.altitudeMeters,
                    verticalAccuracyMeters = if (altitude != null) altitudeAccuracy else current.verticalAccuracyMeters,
                    speedKmh = speedKmh,
                    speedMph = speedMph,
                    bearingDegrees = bearing,
                    accuracyMeters = accuracy,
                    hasFix = true,
                    timestampEpochMs = location.time,
                    fixElapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000
                )
            }
            locationHandler?.let {
                it.removeCallbacks(staleFixRunnable)
                it.postDelayed(staleFixRunnable, FIX_STALE_MS)
            }

            locationCallback?.invoke(location.latitude, location.longitude, altitude ?: _telemetry.value.altitudeMeters)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                locationHandler?.removeCallbacks(staleFixRunnable)
                _telemetry.update { it.copy(hasFix = false, satellitesInFix = 0, satellitesTotal = 0) }
            }
        }
    }

    private var gnssCallback: GnssStatus.Callback? = null

    /**
     * Height above mean sea level, or null when the fix has no altitude.
     *
     * Location.altitude is height above the WGS84 ellipsoid, which is about 100 m off sea level
     * in some regions. Android 14 and later can convert it; older versions keep the ellipsoid value.
     */
    private fun seaLevelAltitude(location: Location): Double? {
        if (!location.hasAltitude()) return null
        val ellipsoid = location.altitude
        val seaLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Api34.mslAltitude(context, location)
        } else {
            null
        }
        if (!altitudeSourceLogged) {
            altitudeSourceLogged = true
            Log.i(TAG, "Altitude: ellipsoid=$ellipsoid m, sea level=${seaLevel ?: "unavailable"} m")
        }
        return seaLevel ?: ellipsoid
    }

    /** Accuracy of the altitude seaLevelAltitude returned, which must be called first as it adds the sea level one. */
    private fun altitudeAccuracy(location: Location): Float? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude()) {
            return if (location.hasMslAltitudeAccuracy()) location.mslAltitudeAccuracyMeters else null
        }
        return if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        private val converter = AltitudeConverter()

        // Reads geoid data from disk the first time, so it must stay off the main thread.
        fun mslAltitude(context: Context, location: Location): Double? {
            if (!location.hasMslAltitude()) {
                try {
                    converter.addMslAltitudeToLocation(context, location)
                } catch (e: Exception) {
                    Log.w(TAG, "Sea level altitude unavailable: ${e.message}")
                    return null
                }
            }
            return if (location.hasMslAltitude()) location.mslAltitudeMeters else null
        }
    }

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
        altitudeSourceLogged = false

        // Fixes arrive on a worker thread because the sea level conversion can touch disk
        val thread = HandlerThread("GpsTrackerEngine").apply { start() }
        locationThread = thread
        val handler = Handler(thread.looper)
        locationHandler = handler

        try {
            // Subscribe to GPS provider for high accuracy, even if Location is currently toggled off
            // so enabling Location later starts fixes immediately.
            if (LocationManager.GPS_PROVIDER in lm.allProviders) {
                try {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        UPDATE_INTERVAL_MS,
                        MIN_DISTANCE_METERS,
                        locationListener,
                        thread.looper
                    )
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "GPS provider unavailable", e)
                }
            }

            // The network provider is left out on purpose: its Wi-Fi and cell positions can be
            // tens of metres off and would be written into the video as if they were GPS.

            // Track satellite counts and GNSS fix
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
                            satellitesTotal = total
                        )
                    }
                }
            }
            gnssCallback = cb
            lm.registerGnssStatusCallback(cb, handler)

            Log.i(TAG, "GPS tracking started at 1 Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start GPS tracking", e)
            isTracking = false
            handler.removeCallbacksAndMessages(null)
            locationHandler = null
            thread.quitSafely()
            locationThread = null
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
        locationHandler?.removeCallbacksAndMessages(null)
        locationHandler = null
        locationThread?.quitSafely()
        locationThread = null

        val lm = locationManager
        if (lm != null) {
            try {
                lm.removeUpdates(locationListener)
                gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
                gnssCallback = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping GPS tracking", e)
            }
        }
        _telemetry.update { it.copy(hasFix = false, satellitesInFix = 0, satellitesTotal = 0) }
        Log.i(TAG, "GPS tracking stopped")
    }
}
