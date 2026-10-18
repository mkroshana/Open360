package com.open360.core.ble

/**
 * The GO 3S's capture modes, named as its Action Pod shows them and in the order the Insta360
 * app lists them. Each is selected by a photo or video sub-mode (SET_OPTIONS option 40 or 41)
 * and captured with its own command: the plain start and take-picture commands put a GO 3S back
 * in normal Video or single Photo, so every other mode passes its kind in the command. Each
 * command here was checked on a GO 3S (2026-10-16) by the capture state it reported and the
 * file it saved.
 */
enum class CameraMode(
    val label: String,
    val isPhoto: Boolean,
    // PhotoSubMode or VideoSubMode
    val subMode: Int,
    private val kind: Kind,
    // The mode field of the capture command: CaptureMode, TimelapseMode or TakePicture.Mode in
    // the published definitions. Null sends the command empty.
    private val captureMode: Int?
) {
    VIDEO("Video", false, 0, Kind.RECORDING, null),
    FREEFRAME_PRO("FreeFrame Video (PRO)", false, 5, Kind.RECORDING, 5),
    TIMESHIFT("TimeShift", false, 4, Kind.RECORDING, 4),
    // MOBILE_TIMELAPSE_VIDEO; STATIC_TIMELAPSE_VIDEO (3) was refused with status 500
    TIMELAPSE("Timelapse", false, 2, Kind.TIMELAPSE, 1),
    // The GO 3S names its slow motion sub-mode and capture after bullet time (VIDEO_BULLETTIME,
    // Capture_MODE_BULLETTIME)
    SLOW_MOTION("Slow Motion", false, 1, Kind.RECORDING, 2),
    LOOP_RECORDING("Loop Recording", false, 6, Kind.RECORDING, 6),
    PHOTO("Photo", true, 0, Kind.PICTURE, null),
    // TakePicture.Mode AEB: a bracketed set
    HDR_PHOTO("HDR Photo", true, 1, Kind.PICTURE, 1),
    // TIMELAPSE_INTERVAL_SHOOTING: a photo every few seconds until stopped
    INTERVAL_PHOTO("Interval Photo", true, 2, Kind.TIMELAPSE, 2),
    // TIMELAPSE_STARLAPSE_SHOOTING: long exposures until stopped
    STARLAPSE("Starlapse", true, 7, Kind.TIMELAPSE, 5);

    private enum class Kind { RECORDING, TIMELAPSE, PICTURE }

    /** Whether it runs from a start to a stop, as a recording does, rather than taking one shot. */
    val isContinuous: Boolean get() = kind != Kind.PICTURE

    /** The command that starts it, or takes the shot. */
    val startCommand: Int
        get() = when (kind) {
            Kind.RECORDING -> Go2BleProtocol.CMD_START_CAPTURE
            Kind.TIMELAPSE -> Go2BleProtocol.CMD_START_TIMELAPSE
            Kind.PICTURE -> Go2BleProtocol.CMD_TAKE_PICTURE
        }

    /** StartCapture, StartTimelapse or TakePicture: the mode is field 1 of each. */
    fun startPayload(): ByteArray = captureMode?.let { byteArrayOf(0x08, it.toByte()) } ?: ByteArray(0)

    /** The command that stops it, null for a single shot. */
    val stopCommand: Int?
        get() = when (kind) {
            Kind.RECORDING -> Go2BleProtocol.CMD_STOP_CAPTURE
            Kind.TIMELAPSE -> Go2BleProtocol.CMD_STOP_TIMELAPSE
            Kind.PICTURE -> null
        }

    /** StopCapture carries the mode in field 2, StopTimelapse in field 1. */
    fun stopPayload(): ByteArray {
        val mode = captureMode ?: return ByteArray(0)
        return when (kind) {
            Kind.RECORDING -> byteArrayOf(0x10, mode.toByte())
            Kind.TIMELAPSE -> byteArrayOf(0x08, mode.toByte())
            Kind.PICTURE -> ByteArray(0)
        }
    }

    companion object {
        /** The modes of Photo ([photo] true) or Video, in the order they are offered. */
        fun group(photo: Boolean): List<CameraMode> = entries.filter { it.isPhoto == photo }

        /** The mode the camera reports itself in, or null for a sub-mode not listed here. */
        fun of(subModes: Go2BleProtocol.SubModes): CameraMode? =
            if (subModes.isPhoto) entries.firstOrNull { it.isPhoto && it.subMode == subModes.photo }
            else entries.firstOrNull { !it.isPhoto && it.subMode == subModes.video }

        /**
         * The camera's mode when it is one of Photo ([photo] true) or Video, the group the app
         * has chosen; null when it is not known or the camera has not yet followed the choice.
         */
        fun inGroup(subModes: Go2BleProtocol.SubModes?, photo: Boolean): CameraMode? =
            subModes?.let { of(it) }?.takeIf { it.isPhoto == photo }
    }
}
