package com.open360.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import kotlin.math.abs

/**
 * Tracks the camera's live status for Wear OS's [OngoingActivity] notification and watch-face
 * chip, keeping the recording stopwatch anchor stable across second-to-second duration ticks.
 */
object WearOngoingStatus {

    private const val STOPWATCH_DRIFT_THRESHOLD_MS = 3_000L

    data class Snapshot(
        val isConnected: Boolean = false,
        val isAwake: Boolean = false,
        val isRecording: Boolean = false,
        val linkedThroughPeer: Boolean = false,
        val recordingStartedElapsedMs: Long? = null
    )

    @Volatile
    private var current: Snapshot = Snapshot()

    fun templateFor(snapshot: Snapshot): String = when {
        snapshot.isRecording && snapshot.recordingStartedElapsedMs != null -> "REC #time#"
        snapshot.isRecording -> "Recording"
        snapshot.isConnected && snapshot.isAwake && snapshot.linkedThroughPeer -> "Through Phone"
        snapshot.isConnected && snapshot.isAwake -> "Camera Ready"
        snapshot.isConnected && !snapshot.isAwake -> "Camera Is Off"
        else -> "Camera Connected"
    }

    fun buildStatus(snapshot: Snapshot): Status {
        val builder = Status.Builder().addTemplate(templateFor(snapshot))
        val startedElapsedMs = snapshot.recordingStartedElapsedMs
        if (snapshot.isRecording && startedElapsedMs != null) {
            builder.addPart("time", Status.StopwatchPart(startedElapsedMs))
        }
        return builder.build()
    }

    fun currentStatus(): Status = buildStatus(current)

    internal fun computeNextSnapshot(
        previous: Snapshot,
        ble: BleCameraState,
        isRecording: Boolean,
        recordingDurationSeconds: Long,
        linkedThroughPeer: Boolean,
        nowElapsedMs: Long
    ): Snapshot {
        val isConnected = ble.connectionStatus == BleConnectionStatus.CONNECTED || linkedThroughPeer
        val isAwake = ble.isCameraAwake
        val recordingStartedElapsedMs = if (!isRecording) {
            null
        } else {
            val expectedStartMs = nowElapsedMs - recordingDurationSeconds.coerceAtLeast(0L) * 1000L
            val previousStartMs = previous.recordingStartedElapsedMs
            if (!previous.isRecording || previousStartMs == null || abs(previousStartMs - expectedStartMs) > STOPWATCH_DRIFT_THRESHOLD_MS) {
                expectedStartMs
            } else {
                previousStartMs
            }
        }
        return Snapshot(
            isConnected = isConnected,
            isAwake = isAwake,
            isRecording = isRecording,
            linkedThroughPeer = linkedThroughPeer,
            recordingStartedElapsedMs = recordingStartedElapsedMs
        )
    }

    fun update(
        context: Context,
        ble: BleCameraState,
        isRecording: Boolean,
        recordingDurationSeconds: Long,
        linkedThroughPeer: Boolean,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ) {
        val next = synchronized(this) {
            val candidate = computeNextSnapshot(
                previous = current,
                ble = ble,
                isRecording = isRecording,
                recordingDurationSeconds = recordingDurationSeconds,
                linkedThroughPeer = linkedThroughPeer,
                nowElapsedMs = nowElapsedMs
            )
            if (candidate == current) return
            current = candidate
            candidate
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            OngoingActivity.recoverOngoingActivity(context)?.update(context, buildStatus(next))
        } catch (_: SecurityException) {
            // Notification permission was revoked before the ongoing activity could update.
        } catch (_: Exception) {
            // Ignore when no ongoing notification is active or outside a real Android runtime.
        }
    }
}
