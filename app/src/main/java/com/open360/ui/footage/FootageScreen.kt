@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.open360.ui.footage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.open360.core.model.CameraMediaFile
import com.open360.core.network.CameraWifiStatus
import com.open360.ui.theme.BlurBehindDialog
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.PillSegmentOption
import com.open360.ui.theme.PillSegmentedControl
import com.open360.ui.theme.SurfaceCard
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.middleEllipsize
import com.open360.ui.theme.pluralize
import com.open360.ui.theme.rememberPressScale
import com.open360.ui.theme.rememberReducedMotion

/**
 * Camera, Downloaded and USB as a recessed capsule segmented control, matching Video/Photo on the
 * Remote screen.
 */
@Composable
fun FootageTabSegmentedControl(
    currentTab: FootageTab,
    onTabSelected: (FootageTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = FootageTab.entries
    PillSegmentedControl(
        options = tabs.map { tab ->
            PillSegmentOption(
                when (tab) {
                    FootageTab.CAMERA -> "Camera"
                    FootageTab.DOWNLOADED -> "Downloaded"
                    FootageTab.USB -> "USB"
                }
            )
        },
        selectedIndex = tabs.indexOf(currentTab).coerceAtLeast(0),
        onSelect = { index -> onTabSelected(tabs[index]) },
        role = Role.Tab,
        minHeight = 46.dp,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun FootageScreen(
    viewModel: FootageViewModel,
    modifier: Modifier = Modifier,
    isSpeedMph: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var pendingDeleteFile by remember { mutableStateOf<CameraMediaFile?>(null) }

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

    LaunchedEffect(Unit) {
        viewModel.onScreenEntered()
    }

    Column(modifier = modifier.fillMaxSize()) {
        FootageTabSegmentedControl(
            currentTab = uiState.currentTab,
            onTabSelected = { viewModel.selectTab(it) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        val bannerError = uiState.errorMessage?.takeIf {
            uiState.currentTab != FootageTab.CAMERA || uiState.cameraFiles.isNotEmpty()
        }
        if (bannerError != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(start = 12.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = bannerError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss error",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

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
                    isSpeedMph = isSpeedMph,
                    onConnectWifi = { viewModel.connectCameraWifi() },
                    onOpenWifiSettings = { viewModel.openWifiSettings(context) },
                    onSelectDownloadedTab = { viewModel.selectTab(FootageTab.DOWNLOADED) },
                    onPlay = { viewModel.playFile(it) },
                    onDownload = { viewModel.downloadCameraFile(it) },
                    onCancelTransfer = { viewModel.cancelTransfer(it.name) },
                    loadThumbnail = viewModel::cameraThumbnail
                )

                FootageTab.DOWNLOADED -> DownloadedTabContent(
                    files = uiState.downloadedFiles,
                    isSpeedMph = isSpeedMph,
                    onPlay = { viewModel.playFile(it) },
                    onShare = { shareMedia(context, it) },
                    onDelete = { pendingDeleteFile = it },
                    loadThumbnail = viewModel::localThumbnail
                )

                FootageTab.USB -> UsbTabContent(
                    files = uiState.usbFiles,
                    selectedUri = uiState.usbTreeUri,
                    isLoading = uiState.isLoading,
                    downloadProgressMap = uiState.downloadProgressMap,
                    isSpeedMph = isSpeedMph,
                    onPickFolder = { safFolderLauncher.launch(null) },
                    onPlay = { viewModel.playFile(it) },
                    onImport = { viewModel.importUsbFile(it) },
                    onCancelTransfer = { viewModel.cancelTransfer(it.name) },
                    loadThumbnail = viewModel::localThumbnail
                )
            }

            uiState.activePlayerFile?.let { mediaFile ->
                FootagePlayerDialog(
                    file = mediaFile,
                    isSpeedMph = isSpeedMph,
                    onDismiss = { viewModel.closePlayer() }
                )
            }
        }
    }

    pendingDeleteFile?.let { fileToDelete ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            title = { Text("Delete File?") },
            text = {
                BlurBehindDialog()
                Text("Remove ${fileToDelete.name} from downloaded footage?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteDownloadedFile(fileToDelete)
                        pendingDeleteFile = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFile = null }) {
                    Text("Cancel")
                }
            }
        )
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
    isSpeedMph: Boolean,
    onConnectWifi: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onSelectDownloadedTab: () -> Unit,
    onPlay: (CameraMediaFile) -> Unit,
    onDownload: (CameraMediaFile) -> Unit,
    onCancelTransfer: (CameraMediaFile) -> Unit,
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
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = wifiStatusMessage ?: "Requesting connection to ${wifiSsid ?: "camera AP"}...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If Android asks to connect, tap Connect on the system prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
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
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "High-speed Wi-Fi streams and downloads clips directly from the camera. You can also import recordings over USB-C in the Local tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
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
                            text = "1. Connect over Bluetooth for automatic Wi-Fi credentials, or set a manual Wi-Fi SSID/password or Station IP in Connection Methods.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "2. Tap Connect Wi-Fi (or join the camera AP in Wi-Fi Settings) once camera Wi-Fi is active.",
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
                            Text(
                                text = "Connect Wi-Fi",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onOpenWifiSettings,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Wi-Fi Settings",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (downloadedCount > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        FilledTonalButton(
                            onClick = onSelectDownloadedTab,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "View ${pluralize(downloadedCount, "Downloaded Clip", "Downloaded Clips")}",
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
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
        items(files, key = { it.fileUrl }) { file ->
            val progress = downloadProgressMap[file.name]
            MediaFileCard(
                file = file,
                downloadProgress = progress,
                isSpeedMph = isSpeedMph,
                // A clip not on the phone yet plays its low-resolution copy
                primaryActionLabel = when {
                    !file.isVideo -> "View"
                    file.isImported -> "Play"
                    file.previewUrl != null -> "Preview"
                    else -> "Stream"
                },
                secondaryActionLabel = when {
                    progress != null -> "Cancel"
                    file.isImported -> "Downloaded"
                    else -> "Download"
                },
                secondaryActionEnabled = !file.isImported || progress != null,
                onPrimaryAction = { onPlay(file) },
                onSecondaryAction = {
                    if (progress != null) onCancelTransfer(file) else onDownload(file)
                },
                loadThumbnail = loadThumbnail
            )
        }
    }
}

@Composable
private fun DownloadedTabContent(
    files: List<CameraMediaFile>,
    isSpeedMph: Boolean,
    onPlay: (CameraMediaFile) -> Unit,
    onShare: (CameraMediaFile) -> Unit,
    onDelete: (CameraMediaFile) -> Unit,
    loadThumbnail: suspend (CameraMediaFile) -> Bitmap?
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
        items(files, key = { it.fileUrl }) { file ->
            MediaFileCard(
                file = file,
                isSpeedMph = isSpeedMph,
                primaryActionLabel = if (file.isVideo) "Play" else "View",
                onPrimaryAction = { onPlay(file) },
                onShare = { onShare(file) },
                onDelete = { onDelete(file) },
                loadThumbnail = loadThumbnail
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
    isSpeedMph: Boolean,
    onPickFolder: () -> Unit,
    onPlay: (CameraMediaFile) -> Unit,
    onImport: (CameraMediaFile) -> Unit,
    onCancelTransfer: (CameraMediaFile) -> Unit,
    loadThumbnail: suspend (CameraMediaFile) -> Bitmap?
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SurfaceCard(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                ) {
                    Text(
                        text = "USB-C Drive or Local Folder",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedUri != null) {
                            "Folder Selected"
                        } else {
                            "Pick USB-C camera storage (DCIM/Camera01) or any local folder (e.g. DCIM/Insta360Download)"
                        },
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
                    Text(
                        text = if (selectedUri != null) "Change" else "Select",
                        maxLines = 1,
                        softWrap = false
                    )
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
                        text = "Import From USB-C or Local Folder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Connect a USB-C camera drive (DCIM/Camera01) or choose any local folder (such as DCIM/Insta360Download) via Android's folder picker — no broad gallery permissions required.",
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
                        text = "Select the camera's DCIM/Camera01 directory or a local folder containing Insta360 clips (such as DCIM/Insta360Download).",
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
            items(files, key = { it.fileUrl }) { file ->
                val progress = downloadProgressMap[file.name]
                MediaFileCard(
                    file = file,
                    downloadProgress = progress,
                    isSpeedMph = isSpeedMph,
                    primaryActionLabel = if (file.isVideo) "Play" else "View",
                    secondaryActionLabel = when {
                        progress != null -> "Cancel"
                        file.isImported -> "Imported"
                        else -> "Import"
                    },
                    secondaryActionEnabled = !file.isImported || progress != null,
                    onPrimaryAction = { onPlay(file) },
                    onSecondaryAction = {
                        if (progress != null) onCancelTransfer(file) else onImport(file)
                    },
                    loadThumbnail = loadThumbnail
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
    isSpeedMph: Boolean = false,
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
                // Thumbnail with play overlay for video, tappable for videos and photos alike
                ThumbnailBox(
                    file = file,
                    onClick = onPrimaryAction,
                    loadThumbnail = loadThumbnail
                )

                Spacer(modifier = Modifier.width(12.dp))

                // File metadata
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = middleEllipsize(file.name, 30),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = mediaSummary(file),
                        style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (file.hasCompanionGpx || file.hasCompanionFit) {
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
                            val gpxLabel = when {
                                summary != null -> "${summary.formattedDistance(isSpeedMph)} (${summary.formattedDuration})"
                                file.hasCompanionGpx -> "GPX Route Tracked"
                                else -> null
                            }
                            val fitLabel = if (file.hasCompanionFit) "FIT" else null
                            val label = listOfNotNull(gpxLabel, fitLabel).joinToString(" • ")
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                    style = MaterialTheme.typography.labelSmall.merge(TabularFigures),
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
                        modifier = Modifier.size(40.dp)
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
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 40.dp),
                        shapes = ButtonDefaults.shapes()
                    ) {
                        val secondaryIcon = when {
                            downloadProgress != null -> Icons.Default.Close
                            !secondaryActionEnabled && file.isImported -> Icons.Default.Check
                            else -> Icons.Default.Download
                        }
                        Icon(
                            imageVector = secondaryIcon,
                            contentDescription = secondaryActionLabel,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = secondaryActionLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                FilledTonalButton(
                    onClick = onPrimaryAction,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.heightIn(min = 40.dp),
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(
                        imageVector = if (file.isVideo) Icons.Default.PlayArrow else Icons.Default.Visibility,
                        contentDescription = primaryActionLabel,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = primaryActionLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
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
    val reducedMotion = rememberReducedMotion()
    val thumbAlpha by animateFloatAsState(
        targetValue = if (bitmap != null) 1f else 0f,
        animationSpec = tween(durationMillis = if (reducedMotion) 120 else 180, easing = EaseOutCubic),
        label = "thumbFadeIn"
    )
    val thumbInteraction = remember { MutableInteractionSource() }
    val thumbScale by rememberPressScale(thumbInteraction, targetScale = 0.96f)

    Box(
        modifier = Modifier
            .size(68.dp)
            .graphicsLayer {
                scaleX = thumbScale
                scaleY = thumbScale
            }
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = thumbInteraction,
                        indication = null,
                        onClick = onClick
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = thumbAlpha },
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
    val mimeType = if (file.isVideo) "video/*" else "image/*"
    shareUri(context, file.playableUri, mimeType, "Share Footage")
}

internal fun shareUri(context: Context, uriString: String, mimeType: String, title: String) {
    try {
        val rawUri = uriString.toUri()
        val shareableUri = if (rawUri.scheme == "file") {
            val filePath = rawUri.path ?: return
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(filePath))
        } else {
            rawUri
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, shareableUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, title))
    } catch (_: Exception) {
        Toast.makeText(context, "Unable to share this file", Toast.LENGTH_SHORT).show()
    }
}
