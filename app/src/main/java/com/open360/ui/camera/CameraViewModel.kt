package com.open360.ui.camera

import android.app.Application
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
    val currentMode: CaptureMode = CaptureMode.VIDEO,
    val isSpeedMph: Boolean = false,
    val recordingDurationSeconds: Long = 0L,
    val keepGpsActiveInBackground: Boolean = true,
    val isConnected: Boolean = false,
    val isRecording: Boolean = false,
    val cameraDisplayInfo: String? = null
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager = GpsRemoteBleManager(application)
    val gattServer = InstaGpsRemoteGattServer(application)
    val gpsEngine = GpsTrackerEngine(application)

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var gpsStreamJob: Job? = null

    init {
        // Start the GPS Remote GATT Server immediately so the camera can discover it under Bluetooth Remote
        gattServer.startServer()

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

        // Collect BLE state updates (Architecture B fallback)
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
                    startGpsTelemetryInjection()
                } else if (!ble.isRecording && recordingTimerJob != null && !_uiState.value.serverState.isRecording) {
                    stopRecordingTimer()
                    stopGpsTelemetryInjection()
                }
            }
        }

        // Collect GPS updates and feed directly into the 10 Hz GATT server stream
        viewModelScope.launch {
            gpsEngine.telemetry.collect { gps ->
                _uiState.update { it.copy(gpsState = gps) }
                gattServer.updateGpsTelemetry(gps)
            }
        }

        // Start GPS tracking immediately so speed and location are available
        gpsEngine.startTracking()
    }

    fun wakeUpCamera(serialSuffix: String? = null) {
        val suffix = serialSuffix ?: "8JY3HF"
        gattServer.burstWakeUpBeacon(suffix)
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
        gattServer.startAdvertising()
        bleManager.startScanAndConnect()
    }

    fun connect() {
        connectBle()
    }

    fun disconnectBle() {
        gattServer.stopAdvertising()
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
                    if (current.bleState.isRecording) {
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
        _uiState.update { it.copy(recordingDurationSeconds = 0L) }
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
            while (isActive) {
                val gps = _uiState.value.gpsState
                if (gps.hasFix) {
                    bleManager.injectGps(gps.latitude, gps.longitude, gps.altitudeMeters)
                }
                delay(1000)
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
        gattServer.stopServer()
        bleManager.disconnect()
        recordingTimerJob?.cancel()
        gpsStreamJob?.cancel()
    }
}
