@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.open360.ui.footage

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.core.model.CameraMediaFile

import com.open360.core.network.CameraWifiStatus

@Composable
fun FootageScreen(
    viewModel: FootageViewModel,
    bleState: BleCameraState? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val safFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.setUsbTree(uri)
        }
    }

    LaunchedEffect(uiState.currentTab) {
        when (uiState.currentTab) {
            FootageTab.CAMERA -> {
                if (uiState.cameraFiles.isEmpty()) {
                    if (viewModel.wifiConnector.checkAndBindExistingCameraNetwork()) {
                        viewModel.loadCameraFiles()
                    } else {
                        viewModel.connectCameraWifi()
                    }
                }
            }
            FootageTab.DOWNLOADED -> viewModel.loadDownloadedFiles()
            FootageTab.USB -> uiState.usbTreeUri?.let { viewModel.loadUsbFiles(it) }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val title = if (bleState?.connectionStatus == BleConnectionStatus.CONNECTED && !bleState.deviceName.isNullOrBlank()) {
                        bleState.deviceName
                    } else {
                        "Footage"
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    IconButton(
                        onClick = {
                            when (uiState.currentTab) {
                                FootageTab.CAMERA -> {
                                    if (viewModel.wifiConnector.boundNetwork != null) {
                                        viewModel.loadCameraFiles()
                                    } else {
                                        viewModel.connectCameraWifi()
                                    }
                                }
                                FootageTab.DOWNLOADED -> viewModel.loadDownloadedFiles()
                                FootageTab.USB -> uiState.usbTreeUri?.let { viewModel.loadUsbFiles(it) }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                }

                androidx.compose.material3.TabRow(
                    selectedTabIndex = uiState.currentTab.ordinal,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FootageTab.entries.forEach { tab ->
                        Tab(
                            selected = uiState.currentTab == tab,
                            onClick = { viewModel.selectTab(tab) },
                            text = { Text(text = tab.title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (uiState.currentTab) {
                FootageTab.CAMERA -> CameraWifiTabContent(
                    files = uiState.cameraFiles,
                    isLoading = uiState.isLoading,
                    wifiStatus = uiState.wifiStatus,
                    wifiSsid = uiState.wifiSsid,
                    wifiStatusMessage = uiState.wifiStatusMessage,
                    downloadedCount = uiState.downloadedFiles.size,
                    errorMessage = uiState.errorMessage,
                    downloadProgressMap = uiState.downloadProgressMap,
                    onConnectWifi = { viewModel.connectCameraWifi() },
                    onOpenWifiSettings = { viewModel.openWifiSettings(context) },
                    onSelectDownloadedTab = { viewModel.selectTab(FootageTab.DOWNLOADED) },
                    onRetry = { viewModel.loadCameraFiles() },
                    onPlay = { viewModel.playFile(it) },
                    onDownload = { viewModel.downloadCameraFile(it) }
                )

                FootageTab.DOWNLOADED -> DownloadedTabContent(
                    files = uiState.downloadedFiles,
                    onPlay = { viewModel.playFile(it) },
                    onShare = { shareMedia(context, it) },
                    onDelete = { viewModel.deleteDownloadedFile(it) }
                )

                FootageTab.USB -> UsbTabContent(
                    files = uiState.usbFiles,
                    selectedUri = uiState.usbTreeUri,
                    isLoading = uiState.isLoading,
                    downloadProgressMap = uiState.downloadProgressMap,
                    onPickFolder = { safFolderLauncher.launch(null) },
                    onPlay = { viewModel.playFile(it) },
                    onImport = { viewModel.importUsbFile(it) }
                )
            }

            uiState.activePlayerFile?.let { mediaFile ->
                FootagePlayerDialog(
                    file = mediaFile,
                    onDismiss = { viewModel.closePlayer() }
                )
            }
        }
    }
}

@Composable
private fun CameraWifiTabContent(
    files: List<CameraMediaFile>,
    isLoading: Boolean,
    wifiStatus: CameraWifiStatus,
    wifiSsid: String?,
    wifiStatusMessage: String?,
    downloadedCount: Int,
    errorMessage: String?,
    downloadProgressMap: Map<String, Float>,
    onConnectWifi: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onSelectDownloadedTab: () -> Unit,
    onRetry: () -> Unit,
    onPlay: (CameraMediaFile) -> Unit,
    onDownload: (CameraMediaFile) -> Unit
) {
    if (wifiStatus == CameraWifiStatus.CONNECTING && files.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connecting to Camera Wi-Fi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = wifiStatusMessage ?: "Requesting connection to ${wifiSsid ?: "camera AP"}...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If Android asks to connect, tap Connect on the system prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onOpenWifiSettings) {
                        Text(text = "Open Wi-Fi Settings")
                    }
                }
            }
        }
        return
    }

    if (isLoading && files.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Loading Camera Footage...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    if ((errorMessage != null || wifiStatus == CameraWifiStatus.FAILED || wifiStatus == CameraWifiStatus.IDLE) && files.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = "Wi-Fi",
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Camera Wi-Fi Connection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Bluetooth handles shutter and GPS metadata. High-speed Wi-Fi is needed to stream and download video clips.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "1. Swipe down on the camera or Action Pod screen and ensure Wi-Fi is enabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "2. Tap Connect Wi-Fi below to automatically bind to ${wifiSsid ?: "GO 3S ...OSC"}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onConnectWifi,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Connect Wi-Fi")
                        }
                        OutlinedButton(
                            onClick = onOpenWifiSettings,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Wi-Fi Settings")
                        }
                    }

                    if (downloadedCount > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = onSelectDownloadedTab,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = "View Downloaded Clips ($downloadedCount Available)")
                        }
                    }
                }
            }
        }
        return
    }

    if (files.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "No Media Files Found on Camera",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(files, key = { it.name }) { file ->
            val progress = downloadProgressMap[file.name]
            MediaFileCard(
                file = file,
                downloadProgress = progress,
                primaryActionLabel = "Stream",
                secondaryActionLabel = if (file.isImported) "Downloaded" else "Download",
                secondaryActionEnabled = !file.isImported && progress == null,
                onPrimaryAction = { onPlay(file) },
                onSecondaryAction = { onDownload(file) }
            )
        }
    }
}

@Composable
private fun DownloadedTabContent(
    files: List<CameraMediaFile>,
    onPlay: (CameraMediaFile) -> Unit,
    onShare: (CameraMediaFile) -> Unit,
    onDelete: (CameraMediaFile) -> Unit
) {
    if (files.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No Downloaded Footage. Download clips over Wi-Fi or import from USB.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(files, key = { it.name }) { file ->
            MediaFileCard(
                file = file,
                primaryActionLabel = "Play",
                onPrimaryAction = { onPlay(file) },
                onShare = { onShare(file) },
                onDelete = { onDelete(file) }
            )
        }
    }
}

@Composable
private fun UsbTabContent(
    files: List<CameraMediaFile>,
    selectedUri: Uri?,
    isLoading: Boolean,
    downloadProgressMap: Map<String, Float>,
    onPickFolder: () -> Unit,
    onPlay: (CameraMediaFile) -> Unit,
    onImport: (CameraMediaFile) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Wired USB-C / OTG Access",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedUri != null) "Folder Selected" else "Select Camera Storage (DCIM/Camera01)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Button(onClick = onPickFolder) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = "Open Folder",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (selectedUri != null) "Change" else "Select")
                }
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        if (selectedUri == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = "USB",
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connect Camera Over USB-C",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Plug the camera into your phone with a USB-C cable and tap Select to grant access to DCIM/Camera01 for high-speed importing without Wi-Fi battery drain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return
        }

        if (files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No Media Files Found in Selected Folder",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            items(files, key = { it.name }) { file ->
                val progress = downloadProgressMap[file.name]
                MediaFileCard(
                    file = file,
                    downloadProgress = progress,
                    primaryActionLabel = "Play",
                    secondaryActionLabel = "Import",
                    secondaryActionEnabled = progress == null,
                    onPrimaryAction = { onPlay(file) },
                    onSecondaryAction = { onImport(file) }
                )
            }
        }
    }
}

@Composable
private fun MediaFileCard(
    file: CameraMediaFile,
    downloadProgress: Float? = null,
    primaryActionLabel: String,
    secondaryActionLabel: String? = null,
    secondaryActionEnabled: Boolean = true,
    onPrimaryAction: () -> Unit,
    onSecondaryAction: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail or Type Icon
                ThumbnailBox(file = file)

                Spacer(modifier = Modifier.width(12.dp))

                // File metadata
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${file.formattedSize} • ${if (file.isVideo) "Video" else "Photo"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (file.hasCompanionGpx) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Straighten,
                                contentDescription = "Route Tracked",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            val summary = file.gpxSummary
                            val label = if (summary != null) {
                                "${summary.formattedDistance} (${summary.formattedDuration})"
                            } else {
                                "GPS Route Tracked"
                            }
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                // Delete action if available
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Progress bar if downloading/importing
            if (downloadProgress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${(downloadProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.End)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onShare != null) {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        enabled = secondaryActionEnabled
                    ) {
                        if (!secondaryActionEnabled && file.isImported) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Imported",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = secondaryActionLabel,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(text = secondaryActionLabel)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                FilledTonalButton(onClick = onPrimaryAction) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = primaryActionLabel,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = primaryActionLabel)
                }
            }
        }
    }
}

@Composable
private fun ThumbnailBox(file: CameraMediaFile) {
    val bitmap = remember(file.thumbnailUrl) {
        val raw = file.thumbnailUrl ?: return@remember null
        val base64Data = if (raw.contains(",")) raw.substringAfter(",") else raw
        try {
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = if (file.isVideo) Icons.Default.Movie else Icons.Default.Photo,
                contentDescription = if (file.isVideo) "Video" else "Photo",
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun shareMedia(context: Context, file: CameraMediaFile) {
    try {
        val uri = Uri.parse(file.playableUri)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Footage"))
    } catch (_: Exception) {}
}
