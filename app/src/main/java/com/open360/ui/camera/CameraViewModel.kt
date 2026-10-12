package com.open360.ui.camera

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.ble.InstaGpsRemoteGattServer
import com.open360.core.ble.InstaGpsRemoteServerState
import com.open360.core.gps.GpsFix
import com.open360.core.gps.GpsStreamSchedule
import com.open360.core.gps.GpsTelemetry
import com.open360.core.gps.GpsTimeline
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
        private const val TAG = "CameraViewModel"

        // The camera reports "recording" about a second after the clip really starts: on three
        // GO 3S clips the time between its start and stop reports was 0.85 to 1.06 s shorter
        // than the clip. The timer starts from this value to make up for it.
        private const val CAPTURE_STATUS_LAG_SECONDS = 1L

        private const val GPS_SAMPLE_INTERVAL_MS = 100L
        private const val MAX_CATCH_UP_MS = 2000L

        // A sample is sent once the fix after its moment has arrived, normally within two
        // seconds. If no fix comes in this long the sample goes out as void instead.
        private const val MAX_WAIT_FOR_NEXT_FIX_MS = 4000L
    }

    val bleManager = GpsRemoteBleManager(application)
    val gattServer = InstaGpsRemoteGattServer(application)
    val gpsEngine = GpsTrackerEngine(application)
    private val trackRecorder = GpxTrackRecorder(application)
    private val gpsTimeline = GpsTimeline()
    private val gpsSchedule = GpsStreamSchedule(GPS_SAMPLE_INTERVAL_MS, MAX_WAIT_FOR_NEXT_FIX_MS)

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var gpsStreamJob: Job? = null
    private var trackedFirstFrameAtMs: Long? = null
    private var backgroundServiceRunning = false

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
                updateBackgroundService()

                // One GPX track per recording, from its first frame to its stop
                val firstFrame = ble.recordingFirstFrameAtMs
                if (firstFrame != trackedFirstFrameAtMs) {
                    trackedFirstFrameAtMs = firstFrame
                    if (firstFrame != null) {
                        trackRecorder.start(firstFrame)
                    } else {
                        trackRecorder.stop(SystemClock.elapsedRealtime())
                    }
                }
                ble.lastVideoFile?.let { trackRecorder.setVideoFileName(it) }
            }
        }

        // Collect GPS updates and feed directly into the 10 Hz GATT server stream
        viewModelScope.launch {
            gpsEngine.telemetry.collect { gps ->
                _uiState.update { it.copy(gpsState = gps) }
                gattServer.updateGpsTelemetry(gps)
                // The track keeps only the fixes taken while a recording is running
                gps.toFix()?.let { trackRecorder.addFix(it) }
            }
        }

        // Collect the GPX track file status
        viewModelScope.launch {
            trackRecorder.state.collect { track ->
                _uiState.update { it.copy(trackState = track) }
            }
        }

        // Keep the recent fixes for interpolating the samples sent to the camera
        viewModelScope.launch {
            gpsEngine.telemetry.collect { gps ->
                gps.toFix()?.let { gpsTimeline.add(it) }
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
        updateBackgroundService()
    }

    /**
     * Runs the foreground service for as long as the camera is connected, so that with the
     * display off GPS fixes keep arriving and the link stays up. That covers recordings started
     * on the camera too. Android only lets it start while the app is on screen.
     */
    private fun updateBackgroundService() {
        val state = _uiState.value
        val wanted = state.keepGpsActiveInBackground &&
            state.bleState.connectionStatus == BleConnectionStatus.CONNECTED
        if (wanted == backgroundServiceRunning) return

        try {
            if (wanted) {
                // Android refuses a location service to an app without the permission
                if (!gpsEngine.hasLocationPermission()) return
                GpsTrackerService.start(getApplication())
            } else {
                GpsTrackerService.stop(getApplication())
            }
            backgroundServiceRunning = wanted
        } catch (e: Exception) {
            Log.w(TAG, "Could not ${if (wanted) "start" else "stop"} the background service", e)
        }
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
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _uiState.update { it.copy(recordingDurationSeconds = 0L) }
    }

    private fun startGpsTelemetryInjection() {
        gpsStreamJob?.cancel()
        gpsStreamJob = viewModelScope.launch {
            // The clip stores when the first GPS record arrived, not when each one did, and
            // Insta360 Studio shows that first record on the first frame and the rest 100 ms
            // apart by counting. So the samples sent are the positions at the first frame, 100 ms
            // later, 200 ms later and so on, each interpolated between the fixes around it.
            // They go out in a batch each time a new fix makes the next ones known.
            runAtFixedRate(GPS_SAMPLE_INTERVAL_MS, MAX_CATCH_UP_MS, SystemClock::elapsedRealtime) { scheduledMs ->
                gpsSchedule.setFirstFrame(bleManager.state.value.recordingFirstFrameAtMs)
                for (moment in gpsSchedule.due(scheduledMs, gpsTimeline.newestFixMs())) {
                    val sample = gpsTimeline.sampleAt(moment)
                    bleManager.addGpsFix(
                        // With no fix at all the sample still carries the time of its moment
                        timestampMs = sample?.utcMs
                            ?: (System.currentTimeMillis() - (SystemClock.elapsedRealtime() - moment)),
                        latitude = sample?.latitude ?: 0.0,
                        longitude = sample?.longitude ?: 0.0,
                        speedMps = sample?.speedMps ?: 0.0,
                        bearingDegrees = sample?.bearingDegrees ?: 0.0,
                        altitudeMeters = sample?.altitudeMeters ?: 0.0,
                        // A moment with no fix close on both sides is sent as void
                        hasFix = sample?.valid == true
                    )
                }
            }
        }
    }

    private fun stopGpsTelemetryInjection() {
        gpsStreamJob?.cancel()
        gpsStreamJob = null
    }

    /** The fix this telemetry holds, or null before the first position has arrived. */
    private fun GpsTelemetry.toFix(): GpsFix? {
        if (timestampEpochMs <= 0 || fixElapsedRealtimeMs <= 0) return null
        return GpsFix(
            elapsedMs = fixElapsedRealtimeMs,
            utcMs = timestampEpochMs,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            speedMps = speedKmh / 3.6,
            bearingDegrees = bearingDegrees.toDouble()
        )
    }

    override fun onCleared() {
        super.onCleared()
        gpsEngine.stopTracking()
        trackRecorder.stop(SystemClock.elapsedRealtime())
        gattServer.stopServer()
        bleManager.disconnect()
        recordingTimerJob?.cancel()
        gpsStreamJob?.cancel()
        if (backgroundServiceRunning) {
            try {
                GpsTrackerService.stop(getApplication())
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop the background service", e)
            }
            backgroundServiceRunning = false
        }
    }
}
