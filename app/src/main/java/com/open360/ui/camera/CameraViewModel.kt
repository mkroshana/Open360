package com.open360.ui.camera

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.gps.GpsTelemetry
import com.open360.core.gps.GpsTrackerEngine
import com.open360.core.gps.GpsTrackerService
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
    val gpsState: GpsTelemetry = GpsTelemetry(),
    val currentMode: CaptureMode = CaptureMode.VIDEO,
    val isSpeedMph: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val keepGpsActiveInBackground: Boolean = true
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = GpsRemoteBleManager(application)
    val gpsEngine = GpsTrackerEngine(application)

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var gpsStreamJob: Job? = null

    init {
        // Collect BLE state updates
        viewModelScope.launch {
            bleManager.state.collect { ble ->
                _uiState.update { it.copy(bleState = ble) }

                // Automatically manage timer and GPS stream on recording state change
                if (ble.isRecording && recordingTimerJob == null) {
                    startRecordingTimer()
                    startGpsTelemetryInjection()
                } else if (!ble.isRecording && recordingTimerJob != null) {
                    stopRecordingTimer()
                    stopGpsTelemetryInjection()
                }
            }
        }

        // Collect GPS updates
        viewModelScope.launch {
            gpsEngine.telemetry.collect { gps ->
                _uiState.update { it.copy(gpsState = gps) }
            }
        }

        // Start GPS tracking immediately so speed and location are available on screen
        gpsEngine.startTracking()
    }

    fun wakeUpCamera(serialSuffix: String? = null) {
        bleManager.startWakeUpBroadcast(serialSuffix)
        // Also initiate scan after broadcasting wake beacon
        viewModelScope.launch {
            delay(1500)
            bleManager.startScanAndConnect(serialSuffix)
        }
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
        val current = _uiState.value
        when (current.currentMode) {
            CaptureMode.PHOTO -> {
                bleManager.takePhoto()
            }
            CaptureMode.VIDEO -> {
                if (current.bleState.isRecording) {
                    bleManager.stopRecording()
                } else {
                    bleManager.startRecording()
                }
            }
        }
    }

    fun cycleMode() {
        bleManager.cycleMode()
        // Toggle UI mode to reflect cycle
        _uiState.update {
            val next = if (it.currentMode == CaptureMode.VIDEO) CaptureMode.PHOTO else CaptureMode.VIDEO
            it.copy(currentMode = next)
        }
    }

    fun wakeScreen() {
        bleManager.wakeScreen()
    }

    fun powerOff() {
        bleManager.powerOff()
    }

    fun queryBattery() {
        bleManager.queryBattery()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        _uiState.update { it.copy(recordingDurationSeconds = 0L) }
        recordingTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _uiState.update { it.copy(recordingDurationSeconds = it.recordingDurationSeconds + 1) }
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
            while (isActive) {
                val gps = _uiState.value.gpsState
                if (gps.hasFix) {
                    bleManager.injectGps(gps.latitude, gps.longitude, gps.altitudeMeters)
                }
                delay(1000) // 1 Hz injection
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
        bleManager.disconnect()
        recordingTimerJob?.cancel()
        gpsStreamJob?.cancel()
    }
}
