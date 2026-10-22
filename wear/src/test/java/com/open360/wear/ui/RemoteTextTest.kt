package com.open360.wear.ui

import com.open360.core.ble.BleCameraState
import com.open360.core.ble.BleConnectionStatus
import com.open360.core.ble.CameraMode
import com.open360.core.model.CaptureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTextTest {

    private val cameraOn = BleCameraState(connectionStatus = BleConnectionStatus.CONNECTED, isCameraAwake = true)
    private val cameraOff = BleCameraState(connectionStatus = BleConnectionStatus.CONNECTED, isCameraAwake = false)

    @Test
    fun statusFollowsTheLinkAndTheCamera() {
        assertEquals("Disconnected", RemoteText.status(BleCameraState(), false, 0))
        assertEquals("Searching...", RemoteText.status(BleCameraState(connectionStatus = BleConnectionStatus.SCANNING), false, 0))
        assertEquals("Camera Is Off", RemoteText.status(cameraOff, false, 0))
        assertEquals("Waking Up...", RemoteText.status(cameraOff.copy(isWaking = true), false, 0))
        assertEquals("Ready", RemoteText.status(cameraOn, false, 0))
        assertEquals("Recording 02:45", RemoteText.status(cameraOn, true, 165))
    }

    @Test
    fun ambientStatusCountsWholeMinutes() {
        assertEquals("Recording, Under 1 min", RemoteText.ambientStatus(cameraOn, true, 59))
        assertEquals("Recording, 3 min", RemoteText.ambientStatus(cameraOn, true, 3 * 60 + 59))
        assertEquals("Recording, 1 h 2 min", RemoteText.ambientStatus(cameraOn, true, 3729))
        assertEquals("Ready", RemoteText.ambientStatus(cameraOn, false, 0))
    }

    @Test
    fun elapsedShowsHoursOnlyWhenThereAreSome() {
        assertEquals("00:00", RemoteText.elapsed(0))
        assertEquals("59:59", RemoteText.elapsed(3599))
        assertEquals("1:02:09", RemoteText.elapsed(3729))
    }

    @Test
    fun shutterNamesTheCamerasModeAsThePhoneDoes() {
        assertEquals("Start Recording", RemoteText.shutter(CaptureMode.VIDEO, null, false, false, false))
        assertEquals("Start Recording", RemoteText.shutter(CaptureMode.VIDEO, CameraMode.VIDEO, false, false, false))
        assertEquals("Start Recording", RemoteText.shutter(CaptureMode.VIDEO, CameraMode.TIMELAPSE, false, false, false))
        assertEquals("Take Photo", RemoteText.shutter(CaptureMode.PHOTO, CameraMode.PHOTO, false, false, false))
        assertEquals("Take HDR Photo", RemoteText.shutter(CaptureMode.PHOTO, CameraMode.HDR_PHOTO, false, false, false))
        assertEquals("Start Interval Photo", RemoteText.shutter(CaptureMode.PHOTO, CameraMode.INTERVAL_PHOTO, false, false, false))
        assertEquals("Stop Interval Photo", RemoteText.shutter(CaptureMode.PHOTO, CameraMode.INTERVAL_PHOTO, true, false, false))
        assertEquals("Stop Recording", RemoteText.shutter(CaptureMode.VIDEO, CameraMode.FREEFRAME_PRO, true, false, false))
        assertEquals("Starting...", RemoteText.shutter(CaptureMode.VIDEO, CameraMode.VIDEO, false, true, false))
        assertEquals("Stopping...", RemoteText.shutter(CaptureMode.VIDEO, CameraMode.VIDEO, true, false, true))
        assertEquals("Taking Photo...", RemoteText.shutter(CaptureMode.PHOTO, CameraMode.PHOTO, false, false, false, isTakingPhoto = true))
        assertEquals("Taking Photo...", RemoteText.shutter(CaptureMode.PHOTO, CameraMode.HDR_PHOTO, false, false, false, isTakingPhoto = true))
        // A camera not yet in the chosen group is not named
        assertEquals("Take Photo", RemoteText.shutter(CaptureMode.PHOTO, CameraMode.FREEFRAME_PRO, false, false, false))
    }

    @Test
    fun hintSaysWhatToDoUntilTheCameraIsOn() {
        assertNull(RemoteText.hint(cameraOn, hasWakeUpId = true, isLookingForCamera = false))
        assertEquals("Or switch it on with its button", RemoteText.hint(cameraOff, true, false))
        assertEquals("Takes a few seconds", RemoteText.hint(cameraOff.copy(isWaking = true), true, true))
        assertEquals("Keep the camera nearby", RemoteText.hint(BleCameraState(connectionStatus = BleConnectionStatus.SCANNING), true, true))
        assertEquals("Connect also switches it on", RemoteText.hint(BleCameraState(), true, false))
        assertEquals("Switch the camera on, then connect", RemoteText.hint(BleCameraState(), false, false))
    }

    @Test
    fun gpsIsReadyOnlyWithACurrentFix() {
        assertTrue(RemoteText.gpsReady(hasFix = true, fixAgeMs = 900))
        assertTrue(RemoteText.gpsReady(hasFix = true, fixAgeMs = 5000))
        assertFalse(RemoteText.gpsReady(hasFix = true, fixAgeMs = 5001))
        assertFalse(RemoteText.gpsReady(hasFix = false, fixAgeMs = 100))
        assertEquals("GPS Ready", RemoteText.gpsStatus(true))
        assertEquals("Waiting for GPS", RemoteText.gpsStatus(false))
    }

    @Test
    fun savedSaysWhatTheCameraReported() {
        assertEquals("Saved, 3 min 2 s", RemoteText.saved(182, named = true))
        assertEquals("Saved, 41 s", RemoteText.saved(41, named = true))
        assertEquals("Clip Saved", RemoteText.saved(null, named = true))
        assertEquals("Recording Stopped", RemoteText.saved(null, named = false))
    }
}
