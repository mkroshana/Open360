package com.open360.wear.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.ui.camera.CameraViewModel
import com.open360.ui.camera.RemoteUiState
import com.open360.wear.ui.theme.WearTabularFigures
import java.util.Calendar
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val AmbientIvory = Color(0xFFF0EBE1)
private val AmbientStone = Color(0xFFA39E94)

private data class AmbientDisplayState(
    val status: String,
    val modeLabel: String?,
    val batteryText: String?,
    val isRecording: Boolean
)

private data class AmbientClockState(
    val clockText: String,
    val minuteOfDay: Int
)

/**
 * Returns a symmetric `(xDp, yDp)` pixel-shift offset cycling across 8 positions on a ~3dp ring
 * each minute when [burnInProtection] is enabled, or `(0f, 0f)` otherwise.
 */
internal fun ambientBurnInOffsetDp(
    minuteOfDay: Int,
    burnInProtection: Boolean
): Pair<Float, Float> {
    if (!burnInProtection) return 0f to 0f
    return when (Math.floorMod(minuteOfDay, 8)) {
        0 -> 0f to -3f
        1 -> 2.2f to -2.2f
        2 -> 3f to 0f
        3 -> 2.2f to 2.2f
        4 -> 0f to 3f
        5 -> -2.2f to 2.2f
        6 -> -3f to 0f
        7 -> -2.2f to -2.2f
        else -> 0f to 0f
    }
}

private fun currentAmbientClock(context: Context): AmbientClockState {
    val now = Calendar.getInstance()
    val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val clockText = DateFormat.getTimeFormat(context).format(now.time)
    return AmbientClockState(clockText = clockText, minuteOfDay = minuteOfDay)
}

private fun toAmbientDisplayState(uiState: RemoteUiState): AmbientDisplayState {
    val ble = uiState.bleState
    val cameraMode = ble.details.subModes?.let { CameraMode.of(it) }
    val showMode = uiState.isRecording ||
        (ble.connectionStatus == BleConnectionStatus.CONNECTED && ble.isCameraAwake)
    return AmbientDisplayState(
        status = if (uiState.linkMove != null) {
            RemoteText.MOVING_LINK
        } else {
            RemoteText.ambientStatus(ble, uiState.isRecording, uiState.recordingDurationSeconds)
        },
        modeLabel = if (showMode) {
            RemoteText.shownMode(uiState.currentMode, cameraMode)?.label ?: uiState.currentMode.displayName
        } else {
            null
        },
        batteryText = ble.batteryPercent?.let { "Camera $it%" },
        isRecording = uiState.isRecording
    )
}

/**
 * The remote while the watch's screen is dimmed: pure black OLED burn-in-safe layout with
 * tabular figures and warm editorial ivory / stone tones that update at most once a minute.
 */
@Composable
fun AmbientRemote(
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
    burnInProtection: Boolean = false
) {
    val initialState = remember(viewModel) { toAmbientDisplayState(viewModel.uiState.value) }
    val displayFlow = remember(viewModel) {
        viewModel.uiState
            .map(::toAmbientDisplayState)
            .distinctUntilChanged()
    }
    val display by displayFlow.collectAsStateWithLifecycle(initialValue = initialState)

    val context = LocalContext.current
    var clockState by remember(context) { mutableStateOf(currentAmbientClock(context)) }
    DisposableEffect(context) {
        clockState = currentAmbientClock(context)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                clockState = currentAmbientClock(context)
            }
        }
        // RECEIVER_NOT_EXPORTED blocks untrusted apps while still delivering protected system time broadcasts.
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    val (offsetX, offsetY) = ambientBurnInOffsetDp(clockState.minuteOfDay, burnInProtection)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .drawBehind {
                if (display.isRecording) {
                    val strokeWidthPx = 1.dp.toPx()
                    val inset = 4.dp.toPx() + strokeWidthPx / 2f
                    val radius = size.minDimension / 2f - inset
                    if (radius > 0f) {
                        drawCircle(
                            color = AmbientStone.copy(alpha = 0.45f),
                            radius = radius,
                            center = center,
                            style = Stroke(width = strokeWidthPx)
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offsetX.dp.toPx()
                    translationY = offsetY.dp.toPx()
                }
                .padding(if (burnInProtection) 20.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = clockState.clockText,
                style = MaterialTheme.typography.labelMedium.merge(WearTabularFigures),
                color = AmbientStone,
                textAlign = TextAlign.Center
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                if (display.isRecording) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .border(width = 1.5.dp, color = AmbientIvory, shape = CircleShape)
                    )
                }
                Text(
                    text = display.status,
                    style = MaterialTheme.typography.titleMedium.merge(WearTabularFigures),
                    color = AmbientIvory,
                    textAlign = TextAlign.Center
                )
            }
            display.modeLabel?.let { modeLabel ->
                Text(
                    text = modeLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmbientStone,
                    textAlign = TextAlign.Center
                )
            }
            display.batteryText?.let { batteryText ->
                Text(
                    text = batteryText,
                    style = MaterialTheme.typography.bodySmall.merge(WearTabularFigures),
                    color = AmbientStone,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
