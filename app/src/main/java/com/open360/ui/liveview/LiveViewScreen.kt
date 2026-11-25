package com.open360.ui.liveview

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.view.Surface
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.connection.DirectConnectionState
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.rememberPressScale
import com.open360.ui.theme.rememberReducedMotion

/**
 * Dedicated Live View tab hosting the camera Wi-Fi video viewfinder and a compact stream toolbar;
 * shutter and capture mode controls live in the sticky recording bar below.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveViewScreen(
    cameraViewModel: CameraViewModel,
    liveViewViewModel: LiveViewViewModel,
    modifier: Modifier = Modifier,
    directState: DirectConnectionState = DirectConnectionState()
) {
    val cameraState by cameraViewModel.uiState.collectAsState()
    val liveState by liveViewViewModel.uiState.collectAsState()
    val ble = cameraState.bleState
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val isAsleep = ble.connectionStatus == BleConnectionStatus.CONNECTED && !ble.isCameraAwake
    val isCameraReady = (cameraState.isConnected && !isAsleep) || directState.isConnected
    val isStreamingOrConnecting = liveState.status == LiveViewStatus.CONNECTING_WIFI ||
        liveState.status == LiveViewStatus.CONNECTING_SOCKET ||
        liveState.status == LiveViewStatus.STARTING_STREAM ||
        liveState.status == LiveViewStatus.STREAMING
    val resolvedModel = ble.resolvedModel ?: directState.cameraModel
    val canSelectResolution = (resolvedModel?.supportsVariableLivePreviewResolution
        ?: liveState.supportsResolutionSelection) && !liveState.resolutionLockedByCamera
    val streamSummary = if (liveState.hasFirstFrame) {
        formatStreamMetrics(liveState)
    } else {
        "${resolvedModel?.shortName ?: "Camera"} • 640p Preview"
    }

    if (isLandscape && isStreamingOrConnecting) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LiveViewViewport(
                liveState = liveState,
                isCameraReady = isCameraReady,
                reducedMotion = reducedMotion,
                onStart = { liveViewViewModel.startLiveView() },
                onOpenWifiSettings = { liveViewViewModel.openWifiSettings(context) },
                onOpenFullscreen = { liveViewViewModel.openFullscreen() },
                onSurfaceAvailable = { liveViewViewModel.attachSurface(it) },
                onSurfaceDestroyed = { liveViewViewModel.detachSurface(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.widthIn(min = 160.dp, max = 216.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (canSelectResolution) {
                        PreviewResolutionSelector(
                            selected = liveState.resolution,
                            onSelect = { liveViewViewModel.selectResolution(it) }
                        )
                    } else {
                        Text(
                            text = streamSummary,
                            style = MaterialTheme.typography.labelMedium.merge(TabularFigures),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    LiveViewStreamActions(
                        onOpenFullscreen = { liveViewViewModel.openFullscreen() },
                        onStop = { liveViewViewModel.stopLiveView() }
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveViewViewport(
                liveState = liveState,
                isCameraReady = isCameraReady,
                reducedMotion = reducedMotion,
                onStart = { liveViewViewModel.startLiveView() },
                onOpenWifiSettings = { liveViewViewModel.openWifiSettings(context) },
                onOpenFullscreen = { liveViewViewModel.openFullscreen() },
                onSurfaceAvailable = { liveViewViewModel.attachSurface(it) },
                onSurfaceDestroyed = { liveViewViewModel.detachSurface(it) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Slim stream toolbar (resolution/stream spec + Fullscreen/Stop or Wi-Fi); shutter & mode live in the sticky recording bar below
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (canSelectResolution) {
                            PreviewResolutionSelector(
                                selected = liveState.resolution,
                                onSelect = { liveViewViewModel.selectResolution(it) }
                            )
                        } else {
                            Text(
                                text = streamSummary,
                                style = MaterialTheme.typography.labelMedium.merge(TabularFigures),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }

                    if (isStreamingOrConnecting) {
                        LiveViewStreamActions(
                            onOpenFullscreen = { liveViewViewModel.openFullscreen() },
                            onStop = { liveViewViewModel.stopLiveView() }
                        )
                    } else {
                        OutlinedButton(
                            onClick = { liveViewViewModel.openWifiSettings(context) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.heightIn(min = 36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Wi-Fi",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LiveViewStreamActions(
    onOpenFullscreen: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedIconButton(
            onClick = onOpenFullscreen,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Fullscreen,
                contentDescription = "Fullscreen Live View",
                modifier = Modifier.size(20.dp)
            )
        }
        OutlinedButton(
            onClick = onStop,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.heightIn(min = 40.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Stop",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LiveViewViewport(
    liveState: LiveViewUiState,
    isCameraReady: Boolean,
    reducedMotion: Boolean,
    onStart: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenFullscreen: () -> Unit,
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val viewMode = when (liveState.status) {
            LiveViewStatus.IDLE -> 0
            LiveViewStatus.ERROR -> 2
            else -> 1
        }

        AnimatedContent(
            targetState = viewMode,
            transitionSpec = {
                if (reducedMotion) {
                    fadeIn(tween(150, easing = EaseOutCubic)) togetherWith
                        fadeOut(tween(120, easing = EaseOutCubic))
                } else {
                    (fadeIn(tween(160, easing = EaseOutCubic)) +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(160, easing = EaseOutCubic))) togetherWith
                        fadeOut(tween(120, easing = EaseOutCubic))
                }
            },
            label = "liveViewportTransition",
            modifier = Modifier.fillMaxSize()
        ) { mode ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                when (mode) {
                    0 -> {
                        val startInteraction = remember { MutableInteractionSource() }
                        val startScale by rememberPressScale(startInteraction, targetScale = 0.97f)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                    shape = MaterialTheme.shapes.extraLarge
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(28.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Text(
                                    text = "Camera Live View",
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (isCameraReady) {
                                        "Stream a real-time viewfinder over camera Wi-Fi."
                                    } else {
                                        "Connect via Bluetooth or Connection Methods for automatic Wi-Fi login, or join the camera AP manually."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = onStart,
                                    interactionSource = startInteraction,
                                    shapes = ButtonDefaults.shapes(),
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = startScale
                                        scaleY = startScale
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Start Live View",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    1 -> {
                        val isQuarterTurn = liveState.rotationDegrees == 90 || liveState.rotationDegrees == 270
                        val effWidth = if (isQuarterTurn) liveState.videoHeight else liveState.videoWidth
                        val effHeight = if (isQuarterTurn) liveState.videoWidth else liveState.videoHeight
                        val aspect = (effWidth.toFloat() / effHeight.coerceAtLeast(1).toFloat())
                            .coerceIn(0.4f, 2.5f)
                        Box(
                            modifier = Modifier
                                .aspectRatio(aspect, matchHeightConstraintsFirst = effHeight > effWidth)
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(Color.Black)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                    shape = MaterialTheme.shapes.extraLarge
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!liveState.isFullscreen) {
                                LiveVideoSurface(
                                    videoWidth = liveState.videoWidth,
                                    videoHeight = liveState.videoHeight,
                                    onSurfaceAvailable = onSurfaceAvailable,
                                    onSurfaceDestroyed = onSurfaceDestroyed,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable(onClick = onOpenFullscreen),
                                    rotationDegrees = liveState.rotationDegrees
                                )
                            }

                            if (!liveState.hasFirstFrame) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(24.dp)
                                ) {
                                    LoadingIndicator(
                                        modifier = Modifier.size(44.dp),
                                        color = Color.White
                                    )
                                    liveState.statusMessage?.let { msg ->
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color.White.copy(alpha = 0.9f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(MaterialTheme.shapes.extraLarge)
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                    shape = MaterialTheme.shapes.extraLarge
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.padding(28.dp)
                            ) {
                                Text(
                                    text = liveState.errorMessage ?: "Unable to start Live View.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = onStart,
                                        shapes = ButtonDefaults.shapes()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(text = "Retry", maxLines = 1, softWrap = false)
                                    }
                                    OutlinedButton(
                                        onClick = onOpenWifiSettings,
                                        shapes = ButtonDefaults.shapes()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Wifi,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Wi-Fi Settings",
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
