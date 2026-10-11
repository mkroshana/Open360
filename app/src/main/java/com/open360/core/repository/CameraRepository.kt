package com.open360.core.repository

import com.open360.core.model.BatteryInfo
import com.open360.core.model.CameraInfo
import com.open360.core.model.CameraState
import com.open360.core.model.CaptureMode
import com.open360.core.osc.OscClient
import com.open360.core.protocol.CameraNotification
import com.open360.core.socket.CameraSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CameraRepository(
    private val scope: CoroutineScope,
    private val oscClient: OscClient = OscClient(),
    private val socketClient: CameraSocketClient = CameraSocketClient()
) {
    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState.asStateFlow()

    private var timerJob: Job? = null
    private var notificationJob: Job? = null

    fun connect() {
        scope.launch(Dispatchers.IO) {
            _cameraState.update { it.copy(isConnecting = true, connectionError = null) }

            // 1. Initial OSC handshake
            val infoResult = oscClient.getInfo()
            if (infoResult.isFailure) {
                _cameraState.update {
                    it.copy(
                        isConnecting = false,
                        isConnected = false,
                        connectionError = "Could not reach camera at 192.168.42.1. Check Wi-Fi connection."
                    )
                }
                return@launch
            }

            val cameraInfo = infoResult.getOrNull()
            val stateResult = oscClient.getState()
            val (initialBattery, storageUri) = stateResult.getOrDefault(Pair(null, null))

            _cameraState.update {
                it.copy(
                    isConnected = true,
                    isConnecting = false,
                    info = cameraInfo?.copy(storageUri = storageUri ?: ""),
                    battery = initialBattery,
                    storageUri = storageUri
                )
            }

            // 2. Connect binary command socket for real-time telemetry
            try {
                socketClient.connect(scope)

                notificationJob?.cancel()
                notificationJob = scope.launch(Dispatchers.Default) {
                    socketClient.notifications.collect { notif ->
                        when (notif) {
                            is CameraNotification.BatteryUpdate -> {
                                _cameraState.update {
                                    it.copy(
                                        battery = BatteryInfo(
                                            percentage = notif.percentage,
                                            isCharging = notif.isCharging
                                        )
                                    )
                                }
                            }
                            is CameraNotification.ShutterStateUpdate -> {
                                _cameraState.update {
                                    it.copy(
                                        isRecording = notif.isRecording,
                                        recordingDurationSeconds = notif.elapsedSeconds
                                    )
                                }
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                // Socket failure is non-fatal if OSC succeeded
            }
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        _cameraState.update { it.copy(currentMode = mode) }
    }

    fun takePicture() {
        scope.launch(Dispatchers.IO) {
            oscClient.takePicture()
        }
    }

    fun toggleRecording() {
        val currentState = _cameraState.value
        if (currentState.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        scope.launch(Dispatchers.IO) {
            val result = oscClient.startCapture()
            if (result.isSuccess) {
                _cameraState.update { it.copy(isRecording = true, recordingDurationSeconds = 0) }
                startRecordingTimer()
            }
        }
    }

    private fun stopRecording() {
        scope.launch(Dispatchers.IO) {
            oscClient.stopCapture()
            stopRecordingTimer()
            _cameraState.update { it.copy(isRecording = false, recordingDurationSeconds = 0) }
        }
    }

    private fun startRecordingTimer() {
        timerJob?.cancel()
        timerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                _cameraState.update {
                    if (it.isRecording) it.copy(recordingDurationSeconds = it.recordingDurationSeconds + 1)
                    else it
                }
            }
        }
    }

    private fun stopRecordingTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    fun disconnect() {
        stopRecordingTimer()
        notificationJob?.cancel()
        socketClient.disconnect()
        _cameraState.update { CameraState() }
    }
}
