package com.open360.core.peer

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraDetails
import com.open360.core.ble.CameraMode
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.gps.GpsTelemetry
import com.open360.core.model.CaptureMode
import com.open360.ui.camera.CameraButton
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Which of the two Open360 apps a message comes from. */
enum class PeerDevice(val label: String) {
    PHONE("Phone"),
    WATCH("Watch")
}

/**
 * What one app tells the other about its own link to the camera: the camera as that link reports
 * it, the Video and Photo choice, the recording's time, that device's GPS, and whether the link
 * could be moved to it. It is always the sender's own link, never one it controls through the
 * other app.
 */
data class PeerSnapshot(
    val from: PeerDevice,
    val camera: BleCameraState,
    val currentMode: CaptureMode,
    val recordingSeconds: Long,
    val gps: GpsTelemetry,
    // On screen, or with its foreground service running, so it can keep a link moved to it
    val canTakeLink: Boolean
) {
    /** Whether the sender holds the camera link, or is looking for the camera or opening one. */
    val holdsLink: Boolean get() = camera.holdsLink
}

/** Whether this link is held or being opened: looking for the camera counts, as a link follows. */
val BleCameraState.holdsLink: Boolean
    get() = connectionStatus != BleConnectionStatus.DISCONNECTED || isReconnecting || isWaking

/** What one app asks the other to do. */
sealed interface PeerCommand {
    /** The shutter, pressed on the device that does not hold the link. */
    data object Shutter : PeerCommand

    /** The Video and Photo choice. */
    data class SetCaptureMode(val mode: CaptureMode) : PeerCommand

    /** One of the camera's modes, from the row or list of modes. */
    data class SetCameraMode(val mode: CameraMode) : PeerCommand

    /** The camera card's one button: Stop, Wake Up or Disconnect, as shown for the sender's link. */
    data class PressCameraButton(val button: CameraButton) : PeerCommand

    /** Asks the app holding the link to move it to the sender. */
    data object GiveLink : PeerCommand

    /** The sender has let go of the link to hand it over: the receiver links to the camera now. */
    data object TakeLink : PeerCommand

    /** The receiver of TakeLink could not link in time, so the sender links again. */
    data object TakeLinkFailed : PeerCommand
}

/**
 * The messages' bytes. Times on the sender's monotonic clock are sent as ages and put back on
 * the receiver's, as each device's elapsedRealtime counts from its own boot.
 */
object PeerMessages {

    private const val SNAPSHOT_VERSION = 1

    fun encodeSnapshot(snapshot: PeerSnapshot, nowElapsedMs: Long, nowWallMs: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(SNAPSHOT_VERSION)
            out.writeUTF(snapshot.from.name)
            out.writeCamera(snapshot.camera, nowElapsedMs, nowWallMs)
            out.writeUTF(snapshot.currentMode.name)
            out.writeLong(snapshot.recordingSeconds)
            out.writeGps(snapshot.gps, nowElapsedMs)
            out.writeBoolean(snapshot.canTakeLink)
        }
        return bytes.toByteArray()
    }

    /** The snapshot in [bytes], or null when they are not one this version reads. */
    fun decodeSnapshot(bytes: ByteArray, nowElapsedMs: Long, nowWallMs: Long): PeerSnapshot? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readByte().toInt() != SNAPSHOT_VERSION) return null
            PeerSnapshot(
                from = PeerDevice.valueOf(input.readUTF()),
                camera = input.readCamera(nowElapsedMs, nowWallMs),
                currentMode = CaptureMode.valueOf(input.readUTF()),
                recordingSeconds = input.readLong(),
                gps = input.readGps(nowElapsedMs),
                canTakeLink = input.readBoolean()
            )
        }
    } catch (_: Exception) {
        null
    }

    fun encodeCommand(command: PeerCommand): ByteArray = when (command) {
        PeerCommand.Shutter -> "shutter"
        is PeerCommand.SetCaptureMode -> "capture_mode ${command.mode.name}"
        is PeerCommand.SetCameraMode -> "camera_mode ${command.mode.name}"
        is PeerCommand.PressCameraButton -> "button ${command.button.name}"
        PeerCommand.GiveLink -> "give_link"
        PeerCommand.TakeLink -> "take_link"
        PeerCommand.TakeLinkFailed -> "take_link_failed"
    }.toByteArray(Charsets.UTF_8)

    /** The command in [bytes], or null when it is not one this version knows. */
    fun decodeCommand(bytes: ByteArray): PeerCommand? {
        val parts = bytes.toString(Charsets.UTF_8).split(' ', limit = 2)
        val argument = parts.getOrNull(1)
        return try {
            when (parts[0]) {
                "shutter" -> PeerCommand.Shutter
                "capture_mode" -> PeerCommand.SetCaptureMode(CaptureMode.valueOf(argument ?: return null))
                "camera_mode" -> PeerCommand.SetCameraMode(CameraMode.valueOf(argument ?: return null))
                "button" -> PeerCommand.PressCameraButton(CameraButton.valueOf(argument ?: return null))
                "give_link" -> PeerCommand.GiveLink
                "take_link" -> PeerCommand.TakeLink
                "take_link_failed" -> PeerCommand.TakeLinkFailed
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // The receiver keeps its own wake-up registration, and the clock offset and the GPS stream's
    // start belong to the link that reads them, so those are not sent
    private fun DataOutputStream.writeCamera(camera: BleCameraState, nowElapsedMs: Long, nowWallMs: Long) {
        writeUTF(camera.connectionStatus.name)
        writeBoolean(camera.isCameraAwake)
        writeBoolean(camera.isRecording)
        writeBoolean(camera.isCaptureStarting)
        writeBoolean(camera.isCaptureStopping)
        writeInt(camera.photosTaken)
        writeInt(camera.photosFailed)
        writeBoolean(camera.isTakingPhoto)
        writeNullable(camera.lastVideoFile) { writeUTF(it) }
        writeNullable(camera.lastVideoSeconds) { writeInt(it) }
        writeNullable(camera.joinedRecordingSeconds) { writeInt(it) }
        writeBoolean(camera.isReconnecting)
        writeBoolean(camera.isWaking)
        writeBoolean(camera.isLinkIdle)
        writeNullable(camera.batteryPercent) { writeInt(it) }
        writeBoolean(camera.isCharging)
        writeDetails(camera.details)
        writeNullable(camera.deviceName) { writeUTF(it) }
        writeNullable(camera.deviceAddress) { writeUTF(it) }
        // Wall-clock time, which the two devices share closely; 0 for none
        writeLong(if (camera.lastGpsSentTimeMs > 0) nowWallMs - camera.lastGpsSentTimeMs else -1)
        writeNullable(camera.lastError) { writeUTF(it) }
    }

    private fun DataInputStream.readCamera(nowElapsedMs: Long, nowWallMs: Long): BleCameraState {
        val connectionStatus = BleConnectionStatus.valueOf(readUTF())
        val isCameraAwake = readBoolean()
        val isRecording = readBoolean()
        val isCaptureStarting = readBoolean()
        val isCaptureStopping = readBoolean()
        val photosTaken = readInt()
        val photosFailed = readInt()
        val isTakingPhoto = readBoolean()
        val lastVideoFile = readNullable { readUTF() }
        val lastVideoSeconds = readNullable { readInt() }
        val joinedRecordingSeconds = readNullable { readInt() }
        val isReconnecting = readBoolean()
        val isWaking = readBoolean()
        val isLinkIdle = readBoolean()
        val batteryPercent = readNullable { readInt() }
        val isCharging = readBoolean()
        val details = readDetails()
        val deviceName = readNullable { readUTF() }
        val deviceAddress = readNullable { readUTF() }
        val gpsSentAgeMs = readLong()
        val lastError = readNullable { readUTF() }
        return BleCameraState(
            connectionStatus = connectionStatus,
            isCameraAwake = isCameraAwake,
            isRecording = isRecording,
            isCaptureStarting = isCaptureStarting,
            isCaptureStopping = isCaptureStopping,
            photosTaken = photosTaken,
            photosFailed = photosFailed,
            isTakingPhoto = isTakingPhoto,
            lastVideoFile = lastVideoFile,
            lastVideoSeconds = lastVideoSeconds,
            joinedRecordingSeconds = joinedRecordingSeconds,
            isReconnecting = isReconnecting,
            isWaking = isWaking,
            isLinkIdle = isLinkIdle,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            details = details,
            deviceName = deviceName,
            deviceAddress = deviceAddress,
            lastGpsSentTimeMs = if (gpsSentAgeMs >= 0) nowWallMs - gpsSentAgeMs else 0L,
            lastError = lastError
        )
    }

    private fun DataOutputStream.writeDetails(details: CameraDetails) {
        writeNullable(details.captureSecondsLeft) { writeInt(it) }
        writeNullable(details.cardState) { writeUTF(it.name) }
        writeNullable(details.pod) { pod ->
            writeBoolean(pod.connected)
            writeNullable(pod.battery) { battery ->
                writeInt(battery.levelPercent)
                writeBoolean(battery.onExternalPower)
            }
            writeBoolean(pod.isOn)
        }
        writeNullable(details.recordingState) { writeInt(it) }
        writeNullable(details.subModes) { subModes ->
            writeInt(subModes.photo)
            writeInt(subModes.video)
        }
        writeBoolean(details.batteryLow)
        writeBoolean(details.temperatureHigh)
    }

    private fun DataInputStream.readDetails(): CameraDetails {
        val captureSecondsLeft = readNullable { readInt() }
        val cardState = readNullable { Go2BleProtocol.CardState.valueOf(readUTF()) }
        val pod = readNullable {
            val connected = readBoolean()
            val battery = readNullable { Go2BleProtocol.BatteryStatus(readInt(), readBoolean()) }
            Go2BleProtocol.ChargeBoxStatus(connected, battery, readBoolean())
        }
        val recordingState = readNullable { readInt() }
        val subModes = readNullable { Go2BleProtocol.SubModes(readInt(), readInt()) }
        return CameraDetails(
            captureSecondsLeft = captureSecondsLeft,
            cardState = cardState,
            pod = pod,
            recordingState = recordingState,
            subModes = subModes,
            batteryLow = readBoolean(),
            temperatureHigh = readBoolean()
        )
    }

    private fun DataOutputStream.writeGps(gps: GpsTelemetry, nowElapsedMs: Long) {
        writeDouble(gps.latitude)
        writeDouble(gps.longitude)
        writeDouble(gps.altitudeMeters)
        writeFloat(gps.speedKmh)
        writeFloat(gps.speedMph)
        writeFloat(gps.bearingDegrees)
        writeFloat(gps.accuracyMeters)
        writeInt(gps.satellitesInFix)
        writeInt(gps.satellitesTotal)
        writeBoolean(gps.hasFix)
        writeLong(gps.timestampEpochMs)
        // How long ago the fix was taken; -1 before the first
        writeLong(if (gps.fixElapsedRealtimeMs > 0) nowElapsedMs - gps.fixElapsedRealtimeMs else -1)
        writeBoolean(gps.isStreamingToCamera)
    }

    private fun DataInputStream.readGps(nowElapsedMs: Long): GpsTelemetry {
        val latitude = readDouble()
        val longitude = readDouble()
        val altitudeMeters = readDouble()
        val speedKmh = readFloat()
        val speedMph = readFloat()
        val bearingDegrees = readFloat()
        val accuracyMeters = readFloat()
        val satellitesInFix = readInt()
        val satellitesTotal = readInt()
        val hasFix = readBoolean()
        val timestampEpochMs = readLong()
        val fixAgeMs = readLong()
        return GpsTelemetry(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            speedKmh = speedKmh,
            speedMph = speedMph,
            bearingDegrees = bearingDegrees,
            accuracyMeters = accuracyMeters,
            satellitesInFix = satellitesInFix,
            satellitesTotal = satellitesTotal,
            hasFix = hasFix,
            timestampEpochMs = timestampEpochMs,
            fixElapsedRealtimeMs = if (fixAgeMs >= 0) nowElapsedMs - fixAgeMs else 0L,
            isStreamingToCamera = readBoolean()
        )
    }

    // The lambdas are not named read and write, which DataInputStream and DataOutputStream already have
    private inline fun <T> DataOutputStream.writeNullable(value: T?, writeValue: DataOutputStream.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) writeValue(value)
    }

    private inline fun <T> DataInputStream.readNullable(readValue: DataInputStream.() -> T): T? =
        if (readBoolean()) readValue() else null
}
