package com.open360.ui.camera

import com.open360.core.network.CameraWifiStatus
import com.open360.ui.footage.FootageTab
import java.util.Locale

/**
 * Contextual subtitle displayed in the top bar. Kept in the phone app, apart from the rest of
 * CameraStatusText, as it names the footage tabs.
 */
fun CameraStatusText.topBarSubtitle(
    isRecording: Boolean,
    recordingDurationSeconds: Long,
    isFootageTab: Boolean,
    footageTab: FootageTab?,
    wifiStatus: CameraWifiStatus?,
    isAsleep: Boolean,
    isConnected: Boolean,
    isAdvertising: Boolean,
    bleScanning: Boolean,
    bleConnecting: Boolean
): String = when {
    isRecording -> {
        val mins = recordingDurationSeconds / 60
        val secs = recordingDurationSeconds % 60
        String.format(Locale.US, "Recording %02d:%02d", mins, secs)
    }
    isFootageTab && footageTab != null -> {
        when (footageTab) {
            FootageTab.CAMERA -> if (wifiStatus == CameraWifiStatus.CONNECTED) "Wi-Fi Connected" else "Camera Wi-Fi"
            FootageTab.DOWNLOADED -> "Downloaded Clips"
            FootageTab.USB -> "Local Storage"
        }
    }
    isAsleep -> "Standby"
    isConnected -> "Connected"
    isAdvertising -> "Broadcasting"
    bleScanning -> "Scanning..."
    bleConnecting -> "Connecting..."
    else -> "Disconnected"
}
