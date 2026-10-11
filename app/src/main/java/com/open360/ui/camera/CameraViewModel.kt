package com.open360.ui.camera

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.model.CameraState
import com.open360.core.model.CaptureMode
import com.open360.core.repository.CameraRepository
import kotlinx.coroutines.flow.StateFlow

class CameraViewModel(
    private val repository: CameraRepository = CameraRepository(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main))
) : ViewModel() {

    val uiState: StateFlow<CameraState> = repository.cameraState

    init {
        // Attempt initial connection on viewmodel start
        connect()
    }

    fun connect() {
        repository.connect()
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun setMode(mode: CaptureMode) {
        repository.setCaptureMode(mode)
    }

    fun onShutterClicked() {
        val state = uiState.value
        if (!state.isConnected) return

        when (state.currentMode) {
            CaptureMode.PHOTO -> repository.takePicture()
            CaptureMode.VIDEO,
            CaptureMode.FREE_FRAME,
            CaptureMode.TIMELAPSE,
            CaptureMode.SLOW_MOTION -> repository.toggleRecording()
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.disconnect()
    }
}
