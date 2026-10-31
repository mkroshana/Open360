@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.open360.ui.footage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.core.model.CameraMediaFile
import com.open360.ui.theme.SurfaceCard

import com.open360.core.network.CameraWifiStatus

/**
 * Camera, Downloaded and USB as a connected button group, as Video and Photo are on the Remote
 * screen.
 */
@Composable
fun FootageTabSegmentedControl(
    currentTab: FootageTab,
    onTabSelected: (FootageTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = FootageTab.entries

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = currentTab == tab
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { if (!isSelected) onTabSelected(tab) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    tabs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { role = Role.Tab }
            ) {
                Text(
                    text = when (tab) {
                        FootageTab.CAMERA -> "Camera"
                        FootageTab.DOWNLOADED -> "Downloaded"
                        FootageTab.USB -> "USB"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

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

    Column(modifier = modifier.fillMaxSize()) {
        FootageTabSegmentedControl(
            currentTab = uiState.currentTab,
            onTabSelected = { viewModel.selectTab(it) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                    onDownload = { viewModel.downloadCameraFile(it) },
                    loadThumbnail = viewModel::cameraThumbnail
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

// LoadingIndicator, LinearWavyProgressIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    onDownload: (CameraMediaFile) -> Unit,
    loadThumbnail: suspend (CameraMediaFile) -> Bitmap?
) {
    if (wifiStatus == CameraWifiStatus.CONNECTING && files.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            SurfaceCard {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LoadingIndicator(modifier = Modifier.size(48.dp))
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
                LoadingIndicator()
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
            SurfaceCard {
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
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                MaterialTheme.shapes.medium
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "1. Switch the camera on and connect to it on the Remote tab. It gives Open360 its Wi-Fi password over Bluetooth.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "2. Tap Connect Wi-Fi. The camera turns its Wi-Fi on by itself a few seconds after it comes on.",
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
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Connect Wi-Fi")
                        }
                        OutlinedButton(
                            onClick = onOpenWifiSettings,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = "Wi-Fi Settings")
                        }
                    }

                    if (downloadedCount > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = onSelectDownloadedTab,
                            shapes = ButtonDefaults.shapes(),
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No Media Files Found",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Record videos or photos with the camera to view them here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(files, key = { it.name }) { file ->
            val progress = downloadProgressMap[file.name]
            MediaFileCard(
                file = file,
                downloadProgress = progress,
                // A clip not on the phone yet plays its low-resolution copy
                primaryActionLabel = when {
                    file.isImported -> "Play"
                    file.previewUrl != null -> "Preview"
                    else -> "Stream"
                },
                secondaryActionLabel = if (file.isImported) "Downloaded" else "Download",
                secondaryActionEnabled = !file.isImported && progress == null,
                onPrimaryAction = { onPlay(file) },
                onSecondaryAction = { onDownload(file) },
                loadThumbnail = loadThumbnail
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
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No Downloaded Footage",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Download clips over Wi-Fi from the Camera tab or import footage directly from USB storage.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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

// LoadingIndicator, LinearWavyProgressIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

                Button(onClick = onPickFolder, shapes = ButtonDefaults.shapes()) {
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
                LoadingIndicator()
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = "USB",
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connect Camera Over USB-C",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Plug the camera into your phone with a USB-C cable and tap Select to grant access to DCIM/Camera01 for high-speed importing without Wi-Fi battery drain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return
        }

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Media Files Found in Folder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Make sure the camera's DCIM or Camera01 directory is selected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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

// LoadingIndicator, LinearWavyProgressIndicator
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    onDelete: (() -> Unit)? = null,
    loadThumbnail: (suspend (CameraMediaFile) -> Bitmap?)? = null
) {
    SurfaceCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail with subtle play overlay for video playback
                ThumbnailBox(
                    file = file,
                    onClick = if (file.isVideo) onPrimaryAction else null,
                    loadThumbnail = loadThumbnail
                )

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
                        text = mediaSummary(file),
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
                LinearWavyProgressIndicator(
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

            Spacer(modifier = Modifier.height(10.dp))

            // Compact Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onShare != null) {
                    IconButton(
                        onClick = onShare,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }

                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(
                        onClick = onSecondaryAction,
                        enabled = secondaryActionEnabled,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp),
                        shapes = ButtonDefaults.shapes()
                    ) {
                        if (!secondaryActionEnabled && file.isImported) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Imported",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = secondaryActionLabel,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = secondaryActionLabel,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                FilledTonalButton(
                    onClick = onPrimaryAction,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = primaryActionLabel,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = primaryActionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * The line under a file's name: the date the camera gave it, its size when known (the camera's
 * file list has no sizes) and whether it is a video or a photo.
 */
internal fun mediaSummary(file: CameraMediaFile): String =
    listOfNotNull(
        file.displayDateTime,
        file.formattedSize.takeIf { file.sizeBytes > 0 },
        if (file.isVideo) "Video" else "Photo"
    ).joinToString(" • ")

@Composable
private fun ThumbnailBox(
    file: CameraMediaFile,
    onClick: (() -> Unit)? = null,
    loadThumbnail: (suspend (CameraMediaFile) -> Bitmap?)? = null
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file.fileUrl, loadThumbnail) {
        value = loadThumbnail?.invoke(file)?.asImageBitmap()
    }

    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Subtle play overlay for video playback
            if (file.isVideo) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                    }
                }
            }
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
