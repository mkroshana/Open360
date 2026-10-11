package com.open360.core.model

data class CameraInfo(
    val manufacturer: String = "Insta360",
    val model: String = "Insta360 GO 3S",
    val serialNumber: String = "",
    val firmwareVersion: String = "",
    val storageUri: String = ""
)

data class BatteryInfo(
    val percentage: Int = 0,
    val isCharging: Boolean = false
)

enum class CaptureMode(val displayName: String, val oscName: String) {
    VIDEO("Video", "_video"),
    PHOTO("Photo", "_photo"),
    FREE_FRAME("FreeFrame 4K", "_freeframe"),
    TIMELAPSE("Timelapse", "_timelapse"),
    SLOW_MOTION("Slow Motion", "_slowmotion")
}

data class CameraState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val connectionError: String? = null,
    val info: CameraInfo? = null,
    val battery: BatteryInfo? = null,
    val currentMode: CaptureMode = CaptureMode.VIDEO,
    val isRecording: Boolean = false,
    val recordingDurationSeconds: Int = 0,
    val storageUri: String? = null
)
