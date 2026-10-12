package com.open360.ui.camera

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.ble.InstaGpsRemoteGattServer
import com.open360.core.ble.InstaGpsRemoteServerState
import com.open360.core.gps.GpsTelemetry
import com.open360.core.gps.GpsTrackerEngine
import com.open360.core.gps.GpsTrackerService
import com.open360.core.gps.GpxTrackRecorder
import com.open360.core.gps.GpxTrackState
import com.open360.core.gps.runAtFixedRate
import com.open360.core.model.CaptureMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RemoteUiState(
    val bleState: BleCameraState = BleCameraState(),
    val serverState: InstaGpsRemoteServerState = InstaGpsRemoteServerState(),
    val gpsState: GpsTelemetry = GpsTelemetry(),
    val trackState: GpxTrackState = GpxTrackState(),
    val currentMode: CaptureMode = CaptureMode.VIDEO,
    val isSpeedMph: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val keepGpsActiveInBackground: Boolean = true,
    val isConnected: Boolean = false,
    val isRecording: Boolean = false,
    val cameraDisplayInfo: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // The camera reports "recording" about a second after the clip really starts: on three
        // GO 3S clips the time between its start and stop reports was 0.85 to 1.06 s shorter
        // than the clip. The timer starts from this value to make up for it.
        private const val CAPTURE_STATUS_LAG_SECONDS = 1L

        private const val GPS_SAMPLE_INTERVAL_MS = 100L
        private const val MAX_CATCH_UP_MS = 2000L

        // The phone delivers a fix about once a second. After three seconds without one the
        // last position no longer describes where the camera is.
        private const val STALE_FIX_MS = 3000L
    }

    val bleManager = GpsRemoteBleManager(application)
    val gattServer = InstaGpsRemoteGattServer(application)
    val gpsEngine = GpsTrackerEngine(application)
    private val trackRecorder = GpxTrackRecorder(application)

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var gpsStreamJob: Job? = null

    init {
        // The GO 3S has not connected to a phone advertising as the GPS Remote, so the GATT server
        // is not started here. The app connects out to the camera and uploads GPS over that link.
        // The two cannot run together: there is only one LE link between phone and camera.

        // Collect GATT server state updates (Architecture A: GPS Remote)
        viewModelScope.launch {
            gattServer.state.collect { srv ->
                _uiState.update { current ->
                    val connected = srv.isCameraConnected || current.bleState.connectionStatus == BleConnectionStatus.CONNECTED
                    val recording = srv.isRecording || current.bleState.isRecording
                    val duration = if (srv.isRecording && srv.recordingDurationSeconds > 0) {
                        srv.recordingDurationSeconds
                    } else {
                        current.recordingDurationSeconds
                    }
                    current.copy(
                        serverState = srv,
                        isConnected = connected,
                        isRecording = recording,
                        recordingDurationSeconds = duration,
                        cameraDisplayInfo = srv.cameraDisplayString
                    )
                }

                if (srv.isRecording && recordingTimerJob == null) {
                    startRecordingTimer()
                } else if (!srv.isRecording && recordingTimerJob != null && !_uiState.value.bleState.isRecording) {
                    stopRecordingTimer()
                }
            }
        }

        // Collect BLE state updates (Architecture B: direct connection)
        viewModelScope.launch {
            bleManager.state.collect { ble ->
                _uiState.update { current ->
                    val connected = current.serverState.isCameraConnected || ble.connectionStatus == BleConnectionStatus.CONNECTED
                    val recording = current.serverState.isRecording || ble.isRecording
                    current.copy(
                        bleState = ble,
                        isConnected = connected,
                        isRecording = recording
                    )
                }

                if (ble.isRecording && recordingTimerJob == null) {
                    startRecordingTimer()
                } else if (!ble.isRecording && recordingTimerJob != null && !_uiState.value.serverState.isRecording) {
                    stopRecordingTimer()
                }

                // GPS is sampled for as long as the camera is connected. The BLE manager passes the
                // samples on only while a recording is running, whoever started it.
                val linked = ble.connectionStatus == BleConnectionStatus.CONNECTED
                if (linked && gpsStreamJob == null) {
                    startGpsTelemetryInjection()
                } else if (!linked && gpsStreamJob != null) {
                    stopGpsTelemetryInjection()
                }
            }
        }

        // Collect GPS updates and feed directly into the 10 Hz GATT server stream
        viewModelScope.launch {
            gpsEngine.telemetry.collect { gps ->
                _uiState.update { it.copy(gpsState = gps) }
                gattServer.updateGpsTelemetry(gps)
                // hasFix can turn true from satellite counts before the first position arrives
                if (gps.hasFix && gps.timestampEpochMs > 0) {
                    trackRecorder.addPoint(gps.latitude, gps.longitude, gps.altitudeMeters, gps.timestampEpochMs)
                }
            }
        }

        // Collect the GPX track file status
        viewModelScope.launch {
            trackRecorder.state.collect { track ->
                _uiState.update { it.copy(trackState = track) }
            }
        }

        // Start GPS tracking immediately so speed and location are available
        gpsEngine.startTracking()

        // Look for the camera straight away
        bleManager.startScanAndConnect()
    }

    fun wakeUpCamera(serialSuffix: String? = null) {
        val suffix = serialSuffix ?: "8JY3HF"
        bleManager.startWakeUpBroadcast(suffix)
        viewModelScope.launch {
            delay(1500)
            bleManager.startScanAndConnect(suffix)
        }
    }

    fun startGpsRemoteServer() {
        gattServer.startServer()
    }

    fun stopGpsRemoteServer() {
        gattServer.stopServer()
    }

    fun connectBle() {
        bleManager.startScanAndConnect()
    }

    fun connect() {
        connectBle()
    }

    fun disconnectBle() {
        bleManager.disconnect()
        stopRecordingTimer()
        stopGpsTelemetryInjection()
    }

    fun disconnect() {
        disconnectBle()
    }

    fun setMode(mode: CaptureMode) {
        _uiState.update { it.copy(currentMode = mode) }
    }

    fun toggleSpeedUnit() {
        _uiState.update { it.copy(isSpeedMph = !it.isSpeedMph) }
    }

    fun toggleKeepGpsInBackground() {
        _uiState.update { it.copy(keepGpsActiveInBackground = !it.keepGpsActiveInBackground) }
    }

    fun onShutterClicked() {
        // Send remote button command over CE82 (Architecture A)
        gattServer.sendShutter()

        // Also trigger via Architecture B if connected
        val current = _uiState.value
        if (current.bleState.connectionStatus == BleConnectionStatus.CONNECTED) {
            when (current.currentMode) {
                CaptureMode.PHOTO -> bleManager.takePhoto()
                CaptureMode.VIDEO -> {
                    val ble = current.bleState
                    if (ble.isCaptureStarting || ble.isCaptureStopping) {
                        // Waiting for the camera to confirm the last tap
                    } else if (ble.isRecording) {
                        bleManager.stopRecording()
                    } else {
                        bleManager.startRecording()
                    }
                }
            }
        }
    }

    fun cycleMode() {
        gattServer.sendMode()
        bleManager.cycleMode()
        _uiState.update {
            val next = if (it.currentMode == CaptureMode.VIDEO) CaptureMode.PHOTO else CaptureMode.VIDEO
            it.copy(currentMode = next)
        }
    }

    fun wakeScreen() {
        gattServer.sendScreenToggle()
        bleManager.wakeScreen()
    }

    fun powerOff() {
        gattServer.sendPowerOff()
        bleManager.powerOff()
    }

    fun queryBattery() {
        bleManager.queryBattery()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(recordingDurationSeconds = CAPTURE_STATUS_LAG_SECONDS) }
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update {
                    if (it.serverState.isRecording && it.serverState.recordingDurationSeconds > 0) {
                        it.copy(recordingDurationSeconds = it.serverState.recordingDurationSeconds)
                    } else {
                        it.copy(recordingDurationSeconds = it.recordingDurationSeconds + 1)
                    }
                }
            }
        }

        if (_uiState.value.keepGpsActiveInBackground) {
            GpsTrackerService.start(getApplication())
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.update { it.copy(recordingDurationSeconds = 0L) }

        if (_uiState.value.keepGpsActiveInBackground) {
            GpsTrackerService.stop(getApplication())
        }
    }

    private fun startGpsTelemetryInjection() {
        gpsStreamJob?.cancel()
        gpsStreamJob = viewModelScope.launch {
            // The clip stores when the first GPS record arrived, not when each one did, so the
            // rest are placed by their position in the stream at 10 per second. That is inferred
            // from the clip metadata (first_gps_timestamp), not confirmed. It means the stream has
            // to be exactly 10 Hz with no gaps, and a sample goes out on every tick even without
            // a usable fix.
            runAtFixedRate(GPS_SAMPLE_INTERVAL_MS, MAX_CATCH_UP_MS, SystemClock::elapsedRealtime) {
                val gps = _uiState.value.gpsState
                val hasPosition = gps.hasFix && gps.timestampEpochMs > 0
                val fixAgeMs = SystemClock.elapsedRealtime() - gps.fixElapsedRealtimeMs
                bleManager.addGpsFix(
                    // A fresh fix keeps its own time. Otherwise the sample carries the current time.
                    timestampMs = when {
                        hasPosition && fixAgeMs <= STALE_FIX_MS -> gps.timestampEpochMs
                        hasPosition -> gps.timestampEpochMs + fixAgeMs
                        else -> System.currentTimeMillis()
                    },
                    latitude = gps.latitude,
                    longitude = gps.longitude,
                    speedMps = gps.speedKmh / 3.6,
                    bearingDegrees = gps.bearingDegrees.toDouble(),
                    altitudeMeters = gps.altitudeMeters,
                    // An old position is marked void instead of being passed off as current
                    hasFix = hasPosition && fixAgeMs <= STALE_FIX_MS
                )
            }
        }
    }

    private fun stopGpsTelemetryInjection() {
        gpsStreamJob?.cancel()
        gpsStreamJob = null
    }

    override fun onCleared() {
        super.onCleared()
        gpsEngine.stopTracking()
        trackRecorder.stop()
        gattServer.stopServer()
        bleManager.disconnect()
        recordingTimerJob?.cancel()
        gpsStreamJob?.cancel()
    }
}
