package com.open360.ui.footage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.open360.core.model.CameraMediaFile
import com.open360.core.repository.FootageRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

import android.content.Context
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.network.CameraWifiConnector
import com.open360.core.network.CameraWifiStatus

enum class FootageTab(val title: String) {
    CAMERA("Camera Wi-Fi"),
    DOWNLOADED("Downloaded"),
    USB("USB / Folder")
}

data class FootageUiState(
    val currentTab: FootageTab = FootageTab.CAMERA,
    val cameraFiles: List<CameraMediaFile> = emptyList(),
    val downloadedFiles: List<CameraMediaFile> = emptyList(),
    val usbFiles: List<CameraMediaFile> = emptyList(),
    val usbTreeUri: Uri? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val downloadProgressMap: Map<String, Float> = emptyMap(),
    val activePlayerFile: CameraMediaFile? = null,
    val wifiStatus: CameraWifiStatus = CameraWifiStatus.IDLE,
    val wifiSsid: String? = null,
    val wifiStatusMessage: String? = null
)

class FootageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = FootageRepository(application)
    val wifiConnector = CameraWifiConnector(application)

    // The Bluetooth link to the camera, set by the activity. The camera gives its Wi-Fi password over it.
    var cameraLink: GpsRemoteBleManager? = null

    private val _uiState = MutableStateFlow(FootageUiState())
    val uiState: StateFlow<FootageUiState> = _uiState.asStateFlow()

    private var downloadJobs = mutableMapOf<String, Job>()

    init {
        loadDownloadedFiles()

        // Sync Wi-Fi connector state into FootageUiState
        viewModelScope.launch {
            wifiConnector.status.collect { status ->
                _uiState.update { it.copy(wifiStatus = status) }
                if (status == CameraWifiStatus.CONNECTED && _uiState.value.cameraFiles.isEmpty()) {
                    loadCameraFiles()
                }
            }
        }
        viewModelScope.launch {
            wifiConnector.activeSsid.collect { ssid ->
                _uiState.update { it.copy(wifiSsid = ssid) }
            }
        }
        viewModelScope.launch {
            wifiConnector.statusMessage.collect { msg ->
                _uiState.update { it.copy(wifiStatusMessage = msg) }
            }
        }
    }

    fun selectTab(tab: FootageTab) {
        _uiState.update { it.copy(currentTab = tab) }
        when (tab) {
            FootageTab.CAMERA -> {
                if (_uiState.value.cameraFiles.isEmpty()) {
                    if (wifiConnector.checkAndBindExistingCameraNetwork()) {
                        loadCameraFiles()
                    } else {
                        connectCameraWifi()
                    }
                }
            }
            FootageTab.DOWNLOADED -> loadDownloadedFiles()
            FootageTab.USB -> {
                val tree = _uiState.value.usbTreeUri
                if (tree != null) {
                    loadUsbFiles(tree)
                }
            }
        }
    }

    /**
     * Joins the camera's Wi-Fi with the name and password the camera gives over Bluetooth, or
     * the ones it gave last time when it is not connected.
     */
    fun connectCameraWifi() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val link = cameraLink
            val info = link?.requestWifiInfo() ?: link?.getSavedWifiInfo()
            if (info == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "The camera gives Open360 its Wi-Fi password over Bluetooth. " +
                            "Switch the camera on, connect to it on the Remote tab, then try again."
                    )
                }
                return@launch
            }

            wifiConnector.connect(
                targetSsid = info.ssid,
                passphrase = info.password,
                onConnected = {
                    loadCameraFiles()
                },
                onFailed = { _ ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Camera Wi-Fi (${info.ssid}) not reached. Make sure the camera is on."
                        )
                    }
                }
            )
        }
    }

    fun openWifiSettings(context: Context) {
        wifiConnector.openWifiSettings(context)
    }

    fun loadCameraFiles() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.fetchCameraFiles()
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        cameraFiles = result.getOrDefault(emptyList()),
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Could not reach camera. Connect Wi-Fi to camera AP (192.168.42.1)."
                    )
                }
            }
        }
    }

    fun loadDownloadedFiles() {
        viewModelScope.launch {
            val list = repository.getLocalFiles()
            _uiState.update { it.copy(downloadedFiles = list) }
        }
    }

    fun setUsbTree(uri: Uri) {
        _uiState.update { it.copy(usbTreeUri = uri) }
        loadUsbFiles(uri)
    }

    fun loadUsbFiles(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val list = repository.getUsbFiles(uri)
            _uiState.update {
                it.copy(
                    usbFiles = list,
                    isLoading = false
                )
            }
        }
    }

    fun downloadCameraFile(file: CameraMediaFile) {
        if (_uiState.value.downloadProgressMap.containsKey(file.name)) return

        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(downloadProgressMap = it.downloadProgressMap + (file.name to 0.0f))
            }

            val result = repository.downloadCameraFile(file) { bytesRead, totalBytes ->
                if (totalBytes > 0) {
                    val progress = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    _uiState.update {
                        it.copy(downloadProgressMap = it.downloadProgressMap + (file.name to progress))
                    }
                }
            }

            _uiState.update {
                it.copy(downloadProgressMap = it.downloadProgressMap - file.name)
            }

            if (result.isSuccess) {
                loadDownloadedFiles()
                loadCameraFiles()
            } else {
                _uiState.update {
                    it.copy(errorMessage = "Download failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }
        downloadJobs[file.name] = job
    }

    fun importUsbFile(file: CameraMediaFile) {
        val uriStr = file.contentUri ?: return
        if (_uiState.value.downloadProgressMap.containsKey(file.name)) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(downloadProgressMap = it.downloadProgressMap + (file.name to 0.0f))
            }

            val result = repository.importFromUsb(
                sourceUri = Uri.parse(uriStr),
                name = file.name,
                sizeBytes = file.sizeBytes
            ) { bytesRead, totalBytes ->
                if (totalBytes > 0) {
                    val progress = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    _uiState.update {
                        it.copy(downloadProgressMap = it.downloadProgressMap + (file.name to progress))
                    }
                }
            }

            _uiState.update {
                it.copy(downloadProgressMap = it.downloadProgressMap - file.name)
            }

            if (result.isSuccess) {
                loadDownloadedFiles()
                val tree = _uiState.value.usbTreeUri
                if (tree != null) loadUsbFiles(tree)
            } else {
                _uiState.update {
                    it.copy(errorMessage = "Import failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun deleteDownloadedFile(file: CameraMediaFile) {
        viewModelScope.launch {
            repository.deleteLocalFile(file)
            loadDownloadedFiles()
            loadCameraFiles()
        }
    }

    /** A thumbnail for a file on the camera, or null when it has none. */
    suspend fun cameraThumbnail(file: CameraMediaFile): Bitmap? = repository.loadCameraThumbnail(file)

    fun playFile(file: CameraMediaFile) {
        _uiState.update { it.copy(activePlayerFile = file) }
    }

    fun closePlayer() {
        _uiState.update { it.copy(activePlayerFile = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        super.onCleared()
        wifiConnector.disconnect()
    }
}
