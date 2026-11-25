package com.open360.core.peer

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraDetails
import com.open360.core.ble.CameraMode
import com.open360.core.ble.Go2BleProtocol
import com.open360.core.ble.WakeUpRegistration
import com.open360.core.gps.GpsFix
import com.open360.core.gps.GpsTelemetry
import com.open360.core.model.CaptureMode
import com.open360.ui.camera.CameraButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerMessagesTest {

    // The camera on, in FreeFrame Video (PRO) in its Action Pod, as a GO 3S reports it
    private val camera = BleCameraState(
        connectionStatus = BleConnectionStatus.CONNECTED,
        isCameraAwake = true,
        isRecording = true,
        photosTaken = 3,
        photosFailed = 1,
        lastVideoFile = "PRO_VID_20261025_101500_00_171.mp4",
        lastVideoSeconds = 42,
        joinedRecordingSeconds = 12,
        batteryPercent = 87,
        isCharging = true,
        details = CameraDetails(
            captureSecondsLeft = 5652,
            cardState = Go2BleProtocol.CardState.OK,
            pod = Go2BleProtocol.ChargeBoxStatus(connected = true, battery = Go2BleProtocol.BatteryStatus(100, true), isOn = true),
            recordingState = 17,
            subModes = Go2BleProtocol.SubModes(photo = 100, video = 5),
            batteryLow = false,
            temperatureHigh = true
        ),
        deviceName = "GO 3S 8JY3HF",
        deviceAddress = "EB:0B:A2:1A:9D:6F",
        lastGpsSentTimeMs = 1_761_380_000_000L,
        lastError = null
    )

    private val gps = GpsTelemetry(
        latitude = 6.9271,
        longitude = 79.8612,
        altitudeMeters = 12.5,
        speedKmh = 36f,
        speedMph = 22.4f,
        bearingDegrees = 271.5f,
        accuracyMeters = 3.8f,
        satellitesInFix = 14,
        satellitesTotal = 31,
        hasFix = true,
        timestampEpochMs = 1_761_379_999_000L,
        fixElapsedRealtimeMs = 500_000L,
        isStreamingToCamera = true
    )

    private val snapshot = PeerSnapshot(
        from = PeerDevice.WATCH,
        camera = camera,
        currentMode = CaptureMode.VIDEO,
        recordingSeconds = 75,
        gps = gps,
        canTakeLink = true
    )

    @Test
    fun testASnapshotReadsBackAsSent() {
        // Read on the same clocks it was written on
        val bytes = PeerMessages.encodeSnapshot(snapshot, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)
        assertEquals(snapshot, PeerMessages.decodeSnapshot(bytes, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L))
    }

    @Test
    fun testTimesOnTheSendersClockAreMovedOntoTheReceivers() {
        // The watch booted later than the phone: its elapsedRealtime is far behind
        val bytes = PeerMessages.encodeSnapshot(snapshot, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)
        val read = PeerMessages.decodeSnapshot(bytes, nowElapsedMs = 9_000_000L, nowWallMs = 1_761_380_000_350L)!!
        // The fix was 400 ms old when sent, so it is taken as that old on arrival
        assertEquals(9_000_000L - 400L, read.gps.fixElapsedRealtimeMs)
        assertEquals(1_761_380_000_050L, read.camera.lastGpsSentTimeMs)
    }

    @Test
    fun testTimesThatWereNeverSetStayUnset() {
        val idle = snapshot.copy(camera = BleCameraState(), gps = GpsTelemetry())
        val bytes = PeerMessages.encodeSnapshot(idle, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)
        val read = PeerMessages.decodeSnapshot(bytes, nowElapsedMs = 9_000_000L, nowWallMs = 1_761_380_000_350L)!!
        assertEquals(0L, read.gps.fixElapsedRealtimeMs)
        assertEquals(0L, read.camera.lastGpsSentTimeMs)
        assertEquals(BleCameraState(), read.camera)
    }

    @Test
    fun testWhatBelongsToTheReceiversOwnLinkIsNotSent() {
        val withOwn = snapshot.copy(
            camera = camera.copy(
                wakeUpRegistration = WakeUpRegistration.AUTHORIZED,
                gpsStreamStartAtMs = 480_000L,
                cameraClockOffsetMs = 3_150L
            )
        )
        val bytes = PeerMessages.encodeSnapshot(withOwn, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)
        val read = PeerMessages.decodeSnapshot(bytes, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)!!
        assertEquals(WakeUpRegistration.NONE, read.camera.wakeUpRegistration)
        assertNull(read.camera.gpsStreamStartAtMs)
        assertNull(read.camera.cameraClockOffsetMs)
    }

    @Test
    fun testBytesThatAreNotASnapshotAreRefused() {
        assertNull(PeerMessages.decodeSnapshot(byteArrayOf(), 0L, 0L))
        assertNull(PeerMessages.decodeSnapshot(byteArrayOf(9, 1, 2), 0L, 0L))
        // Cut short
        val bytes = PeerMessages.encodeSnapshot(snapshot, 500_400L, 1_761_380_000_300L)
        assertNull(PeerMessages.decodeSnapshot(bytes.copyOf(bytes.size - 4), 500_400L, 1_761_380_000_300L))
    }

    @Test
    fun testEveryCommandReadsBackAsSent() {
        val commands = listOf(
            PeerCommand.Shutter,
            PeerCommand.SetCaptureMode(CaptureMode.PHOTO),
            PeerCommand.SetCameraMode(CameraMode.HDR_PHOTO),
            PeerCommand.PressCameraButton(CameraButton.WAKE_UP),
            PeerCommand.GiveLink,
            PeerCommand.TakeLink(),
            PeerCommand.TakeLinkFailed
        )
        commands.forEach { assertEquals(it, PeerMessages.decodeCommand(PeerMessages.encodeCommand(it))) }
    }

    @Test
    fun testARecordingsStreamGoesWithTheLink() {
        // As a phone hands it over: the next record due 1.4 s before the move, two fixes around it
        val stream = StreamHandover(
            nextMomentUtcMs = 1_761_380_000_100L,
            stampOffsetMs = -174L,
            behindPictureMs = 550L,
            fixes = listOf(
                GpsFix(1_761_379_999_000L, 1_761_379_999_000L, 6.9271, 79.8612, 12.5, 10.0, 271.5),
                GpsFix(1_761_380_000_000L, 1_761_380_000_000L, 6.92711, 79.86111, 12.4, 10.2, 271.75)
            )
        )
        assertEquals(PeerCommand.TakeLink(stream), PeerMessages.decodeCommand(PeerMessages.encodeCommand(PeerCommand.TakeLink(stream))))
        // A handover with no fixes to give
        val bare = stream.copy(fixes = emptyList())
        assertEquals(PeerCommand.TakeLink(bare), PeerMessages.decodeCommand(PeerMessages.encodeCommand(PeerCommand.TakeLink(bare))))
    }

    @Test
    fun testABrokenStreamIsRefused() {
        assertNull(PeerMessages.decodeCommand("take_link 1761380000100 -174".toByteArray()))
        assertNull(PeerMessages.decodeCommand("take_link 1761380000100 -174 550 1761379999000,6.9271,79.8612".toByteArray()))
    }

    @Test
    fun testUnknownCommandsAreRefused() {
        assertNull(PeerMessages.decodeCommand("power_off".toByteArray()))
        assertNull(PeerMessages.decodeCommand("camera_mode SUPER_ZOOM".toByteArray()))
        assertNull(PeerMessages.decodeCommand("button".toByteArray()))
    }

    @Test
    fun testLookingForTheCameraCountsAsHoldingTheLink() {
        assertFalse(BleCameraState().holdsLink)
        assertTrue(BleCameraState(connectionStatus = BleConnectionStatus.SCANNING).holdsLink)
        assertTrue(BleCameraState(connectionStatus = BleConnectionStatus.CONNECTED).holdsLink)
        // Between a drop and the link opened again by itself
        assertTrue(BleCameraState(isReconnecting = true).holdsLink)
    }

    @Test
    fun testStorageSpaceRoundTripsInSnapshot() {
        val withStorage = snapshot.copy(
            camera = camera.copy(
                details = camera.details.copy(
                    freeSpaceBytes = 66_679_341_056L,
                    totalSpaceBytes = 128_000_000_000L
                )
            )
        )
        val encoded = PeerMessages.encodeSnapshot(withStorage, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)
        val decoded = PeerMessages.decodeSnapshot(encoded, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)!!
        assertEquals(66_679_341_056L, decoded.camera.details.freeSpaceBytes)
        assertEquals(128_000_000_000L, decoded.camera.details.totalSpaceBytes)

        val withoutStorageBytes = PeerMessages.encodeSnapshot(snapshot, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)
        val withoutStorage = PeerMessages.decodeSnapshot(withoutStorageBytes, nowElapsedMs = 500_400L, nowWallMs = 1_761_380_000_300L)!!
        assertNull(withoutStorage.camera.details.freeSpaceBytes)
        assertNull(withoutStorage.camera.details.totalSpaceBytes)
    }
}
