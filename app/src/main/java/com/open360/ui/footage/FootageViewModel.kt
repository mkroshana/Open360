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
import androidx.core.net.toUri
import com.open360.core.ble.GpsRemoteBleManager
import com.open360.core.connection.ConnectionPreferences
import com.open360.core.model.CameraTransportMode
import com.open360.core.network.CameraWifiConnector
import com.open360.core.network.CameraWifiResolver
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
    var connectionPreferences: ConnectionPreferences? = null

    private val _uiState = MutableStateFlow(FootageUiState())
    val uiState: StateFlow<FootageUiState> = _uiState.asStateFlow()

    private var downloadJobs = mutableMapOf<String, Job>()
    private var loadCameraJob: Job? = null

    init {
        loadDownloadedFiles()

        // Sync Wi-Fi connector state into FootageUiState
        viewModelScope.launch {
            wifiConnector.status.collect { status ->
                _uiState.update { state ->
                    val stillLoading = if (status == CameraWifiStatus.FAILED ||
                        status == CameraWifiStatus.DISCONNECTED ||
                        status == CameraWifiStatus.IDLE) false else state.isLoading
                    state.copy(wifiStatus = status, isLoading = stillLoading)
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

    /** Called once when the Footage screen enters composition. */
    fun onScreenEntered() {
        val state = _uiState.value
        when (state.currentTab) {
            FootageTab.CAMERA -> {
                if (state.cameraFiles.isEmpty() && !state.isLoading && state.wifiStatus != CameraWifiStatus.CONNECTING) {
                    if (wifiConnector.checkAndBindExistingCameraNetwork()) {
                        loadCameraFiles()
                    } else {
                        connectCameraWifi()
                    }
                }
            }
            FootageTab.DOWNLOADED -> loadDownloadedFiles()
            FootageTab.USB -> state.usbTreeUri?.let { loadUsbFiles(it) }
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
     * Refreshes the media list for whichever tab is currently active.
     */
    fun refreshCurrentTab() {
        val settings = connectionPreferences?.settings?.value
        val isStation = settings?.preferredTransport == CameraTransportMode.WIFI_STATION
        when (_uiState.value.currentTab) {
            FootageTab.CAMERA -> {
                if (isStation || wifiConnector.boundNetwork != null || wifiConnector.checkAndBindExistingCameraNetwork(allowStationMode = isStation)) {
                    loadCameraFiles()
                } else {
                    connectCameraWifi()
                }
            }
            FootageTab.DOWNLOADED -> loadDownloadedFiles()
            FootageTab.USB -> _uiState.value.usbTreeUri?.let { loadUsbFiles(it) }
        }
    }

    /**
     * Joins the camera's Wi-Fi using Connection Methods settings (manual SSID/password or Station IP),
     * BLE-supplied credentials, or legacy `"88888888"` default fallback.
     */
    fun connectCameraWifi() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val settings = connectionPreferences?.settings?.value
            val transport = settings?.preferredTransport ?: CameraTransportMode.WIFI_SOFTAP
            if (transport == CameraTransportMode.WIFI_STATION) {
                wifiConnector.checkAndBindExistingCameraNetwork(allowStationMode = true)
                loadCameraFiles()
                return@launch
            }

            val link = cameraLink
            val info = link?.requestWifiInfo() ?: link?.getSavedWifiInfo()
            val creds = CameraWifiResolver.resolveCredentials(
                manualSsid = settings?.manualWifiSsid,
                manualPassword = settings?.manualWifiPassword,
                bleSsid = info?.ssid,
                blePassword = info?.password,
                model = link?.state?.value?.resolvedModel ?: settings?.selectedModelOverride
            )
            if (creds == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Camera Wi-Fi SSID unknown. Connect over Bluetooth once, or enter SSID/password in Connection Methods."
                    )
                }
                return@launch
            }

            wifiConnector.connect(
                targetSsid = creds.ssid,
                passphrase = creds.password,
                onConnected = {
                    loadCameraFiles()
                },
                onFailed = { _ ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Camera Wi-Fi (${creds.ssid}) not reached. Make sure the camera is on."
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
        if (loadCameraJob?.isActive == true) return
        val settings = connectionPreferences?.settings?.value
        val endpoint = CameraWifiResolver.resolveEndpoint(
            transportMode = settings?.preferredTransport ?: CameraTransportMode.WIFI_SOFTAP,
            customHostIp = settings?.stationHostIp,
            discoveredGatewayIp = wifiConnector.getBoundGatewayIp()
        )
        val boundNetwork = wifiConnector.boundNetwork
        loadCameraJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.fetchCameraFiles(endpoint.host, boundNetwork)
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
                        errorMessage = "Could not reach camera on ${endpoint.host}. Connect Wi-Fi or adjust Connection Methods."
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
            try {
                val result = repository.downloadCameraFile(file) { bytesRead, totalBytes ->
                    if (totalBytes > 0) {
                        val progress = (bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        _uiState.update {
                            it.copy(downloadProgressMap = it.downloadProgressMap + (file.name to progress))
                        }
                    }
                }
                if (result.isSuccess) {
                    loadDownloadedFiles()
                    loadCameraFiles()
                } else {
                    _uiState.update {
                        it.copy(errorMessage = "Download failed: ${result.exceptionOrNull()?.message}")
                    }
                }
            } finally {
                downloadJobs.remove(file.name)
                _uiState.update {
                    it.copy(downloadProgressMap = it.downloadProgressMap - file.name)
                }
            }
        }
        downloadJobs[file.name] = job
    }

    fun importUsbFile(file: CameraMediaFile) {
        val uriStr = file.contentUri ?: return
        if (_uiState.value.downloadProgressMap.containsKey(file.name)) return

        val job = viewModelScope.launch {
            _uiState.update {
                it.copy(downloadProgressMap = it.downloadProgressMap + (file.name to 0.0f))
            }
            try {
                val result = repository.importFromUsb(
                    sourceUri = uriStr.toUri(),
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

                if (result.isSuccess) {
                    loadDownloadedFiles()
                    val tree = _uiState.value.usbTreeUri
                    if (tree != null) loadUsbFiles(tree)
                } else {
                    _uiState.update {
                        it.copy(errorMessage = "Import failed: ${result.exceptionOrNull()?.message}")
                    }
                }
            } finally {
                downloadJobs.remove(file.name)
                _uiState.update {
                    it.copy(downloadProgressMap = it.downloadProgressMap - file.name)
                }
            }
        }
        downloadJobs[file.name] = job
    }

    fun cancelTransfer(fileName: String) {
        downloadJobs.remove(fileName)?.cancel()
        _uiState.update {
            it.copy(downloadProgressMap = it.downloadProgressMap - fileName)
        }
    }

    fun deleteDownloadedFile(file: CameraMediaFile) {
        viewModelScope.launch {
            repository.deleteLocalFile(file)
            loadDownloadedFiles()
            if (wifiConnector.boundNetwork != null || _uiState.value.cameraFiles.isNotEmpty()) {
                loadCameraFiles()
            }
            val tree = _uiState.value.usbTreeUri
            if (tree != null && _uiState.value.usbFiles.isNotEmpty()) {
                loadUsbFiles(tree)
            }
        }
    }

    /** A thumbnail for a file on the camera, or null when it has none. */
    suspend fun cameraThumbnail(file: CameraMediaFile): Bitmap? = repository.loadCameraThumbnail(file)

    /** A thumbnail for a downloaded or USB file, or null when none could be read. */
    suspend fun localThumbnail(file: CameraMediaFile): Bitmap? = repository.loadLocalThumbnail(file)

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
