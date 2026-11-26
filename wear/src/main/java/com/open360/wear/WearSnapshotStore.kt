package com.open360.wear

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.open360.wear.complication.Open360ComplicationService
import com.open360.wear.tile.Open360TileService

data class WearCameraSnapshot(
    val cameraName: String = "Open360",
    val statusLabel: String = "Not Connected",
    val batteryPercent: Int? = null,
    val isRecording: Boolean = false,
    val isConnected: Boolean = false,
    val isAwake: Boolean = false,
    val hasWakeUpId: Boolean = false,
    val updatedAtMs: Long = 0L
)

/**
 * Lightweight persisted snapshot of camera status shared between the Wear app, Tile, and watch-face
 * complications. Only notifies Wear surfaces when user-visible fields change.
 */
object WearSnapshotStore {
    const val EXTRA_QUICK_ACTION = "open360_quick_action"
    const val ACTION_WAKE_UP = "wake_up"

    private const val PREFS_NAME = "open360_wear_snapshot"
    private const val KEY_CAMERA_NAME = "camera_name"
    private const val KEY_STATUS_LABEL = "status_label"
    private const val KEY_BATTERY_PERCENT = "battery_percent"
    private const val KEY_IS_RECORDING = "is_recording"
    private const val KEY_IS_CONNECTED = "is_connected"
    private const val KEY_IS_AWAKE = "is_awake"
    private const val KEY_HAS_WAKE_UP_ID = "has_wake_up_id"
    private const val KEY_UPDATED_AT_MS = "updated_at_ms"

    fun tileActionLabel(snapshot: WearCameraSnapshot): String = when {
        snapshot.isRecording || (snapshot.isConnected && snapshot.isAwake) -> "Open Remote"
        snapshot.hasWakeUpId -> "Wake Up"
        else -> "Connect"
    }

    fun complicationShortText(snapshot: WearCameraSnapshot): String = when {
        snapshot.isRecording -> "REC"
        snapshot.isConnected && snapshot.isAwake && snapshot.batteryPercent != null -> "${snapshot.batteryPercent}%"
        snapshot.isConnected && snapshot.isAwake -> "Ready"
        snapshot.isConnected -> "Off"
        else -> "Open"
    }

    fun shouldUseWakeUpAction(snapshot: WearCameraSnapshot): Boolean =
        !snapshot.isRecording && !(snapshot.isConnected && snapshot.isAwake) && snapshot.hasWakeUpId

    internal fun read(prefs: SharedPreferences): WearCameraSnapshot {
        val defaults = WearCameraSnapshot()
        return WearCameraSnapshot(
            cameraName = prefs.getString(KEY_CAMERA_NAME, defaults.cameraName) ?: defaults.cameraName,
            statusLabel = prefs.getString(KEY_STATUS_LABEL, defaults.statusLabel) ?: defaults.statusLabel,
            batteryPercent = if (prefs.contains(KEY_BATTERY_PERCENT)) prefs.getInt(KEY_BATTERY_PERCENT, 0) else null,
            isRecording = prefs.getBoolean(KEY_IS_RECORDING, defaults.isRecording),
            isConnected = prefs.getBoolean(KEY_IS_CONNECTED, defaults.isConnected),
            isAwake = prefs.getBoolean(KEY_IS_AWAKE, defaults.isAwake),
            hasWakeUpId = prefs.getBoolean(KEY_HAS_WAKE_UP_ID, defaults.hasWakeUpId),
            updatedAtMs = prefs.getLong(KEY_UPDATED_AT_MS, defaults.updatedAtMs)
        )
    }

    internal fun writeIfChanged(prefs: SharedPreferences, snapshot: WearCameraSnapshot): Boolean {
        if (prefs.contains(KEY_CAMERA_NAME) && hasSameVisibleState(read(prefs), snapshot)) {
            return false
        }
        prefs.edit {
            putString(KEY_CAMERA_NAME, snapshot.cameraName)
            putString(KEY_STATUS_LABEL, snapshot.statusLabel)
            if (snapshot.batteryPercent != null) {
                putInt(KEY_BATTERY_PERCENT, snapshot.batteryPercent)
            } else {
                remove(KEY_BATTERY_PERCENT)
            }
            putBoolean(KEY_IS_RECORDING, snapshot.isRecording)
            putBoolean(KEY_IS_CONNECTED, snapshot.isConnected)
            putBoolean(KEY_IS_AWAKE, snapshot.isAwake)
            putBoolean(KEY_HAS_WAKE_UP_ID, snapshot.hasWakeUpId)
            putLong(KEY_UPDATED_AT_MS, snapshot.updatedAtMs)
        }
        return true
    }

    fun read(context: Context): WearCameraSnapshot =
        read(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    fun writeAndNotify(context: Context, snapshot: WearCameraSnapshot) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!writeIfChanged(prefs, snapshot)) return
        runCatching {
            TileService.getUpdater(context).requestUpdate(Open360TileService::class.java)
        }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(context, ComponentName(context, Open360ComplicationService::class.java))
                .requestUpdateAll()
        }
    }

    private fun hasSameVisibleState(a: WearCameraSnapshot, b: WearCameraSnapshot): Boolean =
        a.cameraName == b.cameraName &&
            a.statusLabel == b.statusLabel &&
            a.batteryPercent == b.batteryPercent &&
            a.isRecording == b.isRecording &&
            a.isConnected == b.isConnected &&
            a.isAwake == b.isAwake &&
            a.hasWakeUpId == b.hasWakeUpId
}
