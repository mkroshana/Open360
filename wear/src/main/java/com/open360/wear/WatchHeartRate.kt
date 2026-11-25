package com.open360.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.open360.core.sensors.SensorKind
import com.open360.core.sensors.SportsSensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/** Whether the watch's heart rate is being read, and its last reading. */
data class WatchHeartRateState(
    // The watch has a heart rate sensor
    val available: Boolean = false,
    // Open360 may use body sensors
    val permitted: Boolean = false,
    // The setting: read it while the camera is on
    val enabled: Boolean = true,
    val reading: Boolean = false,
    val bpm: Int? = null
)

/**
 * The watch's own heart rate, read from its sensor while the camera is on and logged with the
 * Bluetooth sensors' readings (SportsSensorManager.recordOwn), so each recording's FIT file holds
 * it as it would a chest strap's. It is not read the rest of the time, to spare the battery.
 */
class WatchHeartRate(private val context: Context, private val sensors: SportsSensorManager) : SensorEventListener {

    companion object {
        private const val TAG = "WatchHeartRate"
        private const val PREFS = "open360_watch_settings"
        private const val PREF_ENABLED = "read_watch_heart_rate"

        /** Whether the setting is on, read where no instance is at hand. */
        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, true)

        fun isPermitted(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private var wanted = false

    private val _state = MutableStateFlow(
        WatchHeartRateState(available = sensor != null, permitted = isPermitted(context), enabled = isEnabled(context))
    )
    val state: StateFlow<WatchHeartRateState> = _state.asStateFlow()

    /** Called as the camera comes on and goes off: the heart rate is read only while it is on. */
    fun setWanted(wanted: Boolean) {
        this.wanted = wanted
        update()
    }

    fun setEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(PREF_ENABLED, enabled) }
        _state.update { it.copy(enabled = enabled) }
        update()
    }

    /** Called once body sensors may have been allowed. */
    fun onPermissionChanged() {
        _state.update { it.copy(permitted = isPermitted(context)) }
        update()
    }

    fun stop() {
        wanted = false
        update()
    }

    private fun update() {
        val s = _state.value
        val read = wanted && s.enabled && s.permitted && sensor != null
        if (read == s.reading) return
        if (read) {
            val registered = sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL) == true
            Log.i(TAG, if (registered) "Reading the watch's heart rate" else "Could not read the watch's heart rate")
            _state.update { it.copy(reading = registered) }
        } else {
            sensorManager?.unregisterListener(this)
            Log.i(TAG, "No longer reading the watch's heart rate")
            _state.update { it.copy(reading = false, bpm = null) }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Nothing is logged while the watch is off the wrist or still settling
        if (event.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT || event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            _state.update { it.copy(bpm = null) }
            return
        }
        val bpm = event.values.firstOrNull()?.roundToInt()?.takeIf { it > 0 } ?: return
        sensors.recordOwn(SensorKind.HEART_RATE, bpm)
        _state.update { it.copy(bpm = bpm) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
