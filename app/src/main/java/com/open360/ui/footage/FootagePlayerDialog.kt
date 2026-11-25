package com.open360.ui.footage

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.open360.core.gps.GpxTrackSummary
import com.open360.core.model.CameraMediaFile
import com.open360.ui.theme.EaseOutCubic
import com.open360.ui.theme.TabularFigures
import com.open360.ui.theme.middleEllipsize
import com.open360.ui.theme.rememberReducedMotion
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FootagePlayerDialog(
    file: CameraMediaFile,
    isSpeedMph: Boolean = false,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showTelemetry by remember { mutableStateOf(file.hasCompanionGpx) }
    var shareMenuExpanded by remember { mutableStateOf(false) }

    val canShareMedia = file.isImported
    val canShareGpx = file.companionGpxUri != null
    val canShareFit = file.companionFitUri != null
    val shareOptionsCount = listOf(canShareMedia, canShareGpx, canShareFit).count { it }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Media Content (Video Player or Photo View)
                if (file.isVideo) {
                    VideoPlayer(uriString = file.streamUri)
                } else {
                    PhotoViewer(file = file)
                }

                // Top Bar Controls
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = middleEllipsize(file.name, 28),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = mediaSummary(file),
                                style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
                                color = Color.LightGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                    },
                    actions = {
                        if (file.hasCompanionGpx && file.gpxSummary != null) {
                            IconButton(onClick = { showTelemetry = !showTelemetry }) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Toggle Telemetry",
                                    tint = if (showTelemetry) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }
                        if (shareOptionsCount > 0) {
                            Box {
                                IconButton(onClick = {
                                    when {
                                        shareOptionsCount == 1 && canShareMedia -> {
                                            val mimeType = if (file.isVideo) "video/*" else "image/*"
                                            shareUri(context, file.playableUri, mimeType, "Share Footage")
                                        }
                                        shareOptionsCount == 1 && canShareGpx -> {
                                            file.companionGpxUri?.let {
                                                shareUri(context, it, "application/gpx+xml", "Share GPX Track")
                                            }
                                        }
                                        shareOptionsCount == 1 && canShareFit -> {
                                            file.companionFitUri?.let {
                                                shareUri(context, it, "application/octet-stream", "Share FIT File")
                                            }
                                        }
                                        else -> shareMenuExpanded = true
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = shareMenuExpanded,
                                    onDismissRequest = { shareMenuExpanded = false }
                                ) {
                                    if (canShareMedia) {
                                        DropdownMenuItem(
                                            text = { Text(if (file.isVideo) "Share Video" else "Share Photo") },
                                            onClick = {
                                                shareMenuExpanded = false
                                                val mimeType = if (file.isVideo) "video/*" else "image/*"
                                                shareUri(context, file.playableUri, mimeType, "Share Footage")
                                            }
                                        )
                                    }
                                    file.companionGpxUri?.let { gpxUri ->
                                        DropdownMenuItem(
                                            text = { Text("Share GPX Track") },
                                            onClick = {
                                                shareMenuExpanded = false
                                                shareUri(context, gpxUri, "application/gpx+xml", "Share GPX Track")
                                            }
                                        )
                                    }
                                    file.companionFitUri?.let { fitUri ->
                                        DropdownMenuItem(
                                            text = { Text("Share FIT File") },
                                            onClick = {
                                                shareMenuExpanded = false
                                                shareUri(context, fitUri, "application/octet-stream", "Share FIT File")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                // Companion GPX Telemetry Overlay
                if (showTelemetry && file.gpxSummary != null) {
                    TelemetryOverlay(
                        summary = file.gpxSummary,
                        isSpeedMph = isSpeedMph,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                            .padding(bottom = 48.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoPlayer(uriString: String) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isBuffering by remember(uriString) { mutableStateOf(true) }
    var playerError by remember(uriString) { mutableStateOf<String?>(null) }

    val exoPlayer = remember(uriString) {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(uriString.toUri())
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playerError = null
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playerError = error.localizedMessage ?: "Unable to play this video"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering && playerError == null) {
            LoadingIndicator(
                modifier = Modifier.size(48.dp),
                color = Color.White
            )
        }

        playerError?.let { message ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.Black.copy(alpha = 0.85f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    TextButton(onClick = {
                        playerError = null
                        isBuffering = true
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }) {
                        Text("Retry")
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoViewer(file: CameraMediaFile) {
    val scope = rememberCoroutineScope()
    val reducedMotion = rememberReducedMotion()
    val scaleAnim = remember(file.fileUrl) { Animatable(1f) }
    val offsetAnim = remember(file.fileUrl) { Animatable(Offset.Zero, Offset.VectorConverter) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    val transformableState = rememberTransformableState { _, zoomChange, panChange, _ ->
        val newScale = (scaleAnim.value * zoomChange).coerceIn(1f, 5f)
        val newOffset = if (newScale <= 1.01f) {
            Offset.Zero
        } else {
            val maxX = viewportSize.width * (newScale - 1f) / 2f
            val maxY = viewportSize.height * (newScale - 1f) / 2f
            Offset(
                x = (offsetAnim.value.x + panChange.x).coerceIn(-maxX, maxX),
                y = (offsetAnim.value.y + panChange.y).coerceIn(-maxY, maxY)
            )
        }
        scope.launch {
            scaleAnim.snapTo(newScale)
            offsetAnim.snapTo(newOffset)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewportSize = it }
            .pointerInput(file.fileUrl, reducedMotion) {
                detectTapGestures(
                    onDoubleTap = {
                        val targetScale = if (scaleAnim.value > 1.05f) 1f else 2.5f
                        scope.launch {
                            if (reducedMotion) {
                                scaleAnim.snapTo(targetScale)
                                offsetAnim.snapTo(Offset.Zero)
                            } else {
                                launch {
                                    scaleAnim.animateTo(
                                        targetValue = targetScale,
                                        animationSpec = tween(220, easing = EaseOutCubic)
                                    )
                                }
                                launch {
                                    offsetAnim.animateTo(
                                        targetValue = Offset.Zero,
                                        animationSpec = tween(220, easing = EaseOutCubic)
                                    )
                                }
                            }
                        }
                    }
                )
            }
            .transformable(state = transformableState, canPan = { scaleAnim.value > 1f }),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = file.playableUri,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetAnim.value.x
                    translationY = offsetAnim.value.y
                }
        )
    }
}

@Composable
private fun TelemetryOverlay(
    summary: GpxTrackSummary,
    isSpeedMph: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Black.copy(alpha = 0.75f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Companion GPS Telemetry",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryMetricItem(
                    icon = Icons.Default.Straighten,
                    label = "Distance",
                    value = summary.formattedDistance(isSpeedMph)
                )
                TelemetryMetricItem(
                    icon = Icons.Default.Speed,
                    label = "Max Speed",
                    value = summary.formattedMaxSpeed(isSpeedMph)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryMetricItem(
                    icon = Icons.Default.Timer,
                    label = "Duration",
                    value = summary.formattedDuration
                )
                TelemetryMetricItem(
                    icon = Icons.Default.Terrain,
                    label = "Elevation",
                    value = String.format(Locale.US, "%.0f m", summary.elevationGainMeters)
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.LightGray,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium.merge(TabularFigures),
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
