package com.open360.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertBytes(expected: String, actual: ByteArray) =
        assertEquals(expected, actual.joinToString("") { "%02x".format(it) })

    @Test
    fun testRecordingCommandsMatchWhatTheCameraTook() {
        // Sent to a GO 3S in each mode: it recorded in that mode and saved the clip
        assertEquals(Go2BleProtocol.CMD_START_CAPTURE, CameraMode.FREEFRAME_PRO.startCommand)
        assertBytes("0805", CameraMode.FREEFRAME_PRO.startPayload())
        assertEquals(Go2BleProtocol.CMD_STOP_CAPTURE, CameraMode.FREEFRAME_PRO.stopCommand)
        assertBytes("1005", CameraMode.FREEFRAME_PRO.stopPayload())

        assertBytes("0804", CameraMode.TIMESHIFT.startPayload())
        assertBytes("1004", CameraMode.TIMESHIFT.stopPayload())
        assertBytes("0802", CameraMode.SLOW_MOTION.startPayload())
        assertBytes("1002", CameraMode.SLOW_MOTION.stopPayload())
        assertBytes("0806", CameraMode.LOOP_RECORDING.startPayload())
        assertBytes("1006", CameraMode.LOOP_RECORDING.stopPayload())

        // Normal video keeps the empty commands the app has always sent
        assertEquals(Go2BleProtocol.CMD_START_CAPTURE, CameraMode.VIDEO.startCommand)
        assertBytes("", CameraMode.VIDEO.startPayload())
        assertBytes("", CameraMode.VIDEO.stopPayload())
    }

    @Test
    fun testTimelapseCommandsMatchWhatTheCameraTook() {
        assertEquals(0x16, CameraMode.TIMELAPSE.startCommand)
        assertEquals(0x17, CameraMode.TIMELAPSE.stopCommand)
        assertBytes("0801", CameraMode.TIMELAPSE.startPayload())
        assertBytes("0801", CameraMode.TIMELAPSE.stopPayload())
        assertBytes("0802", CameraMode.INTERVAL_PHOTO.startPayload())
        assertBytes("0802", CameraMode.INTERVAL_PHOTO.stopPayload())
        assertBytes("0805", CameraMode.STARLAPSE.startPayload())
        assertBytes("0805", CameraMode.STARLAPSE.stopPayload())
        assertTrue(CameraMode.INTERVAL_PHOTO.isContinuous)
        assertTrue(CameraMode.STARLAPSE.isContinuous)
    }

    @Test
    fun testPhotoCommands() {
        assertEquals(Go2BleProtocol.CMD_TAKE_PICTURE, CameraMode.PHOTO.startCommand)
        assertBytes("", CameraMode.PHOTO.startPayload())
        assertBytes("0801", CameraMode.HDR_PHOTO.startPayload())
        assertNull(CameraMode.HDR_PHOTO.stopCommand)
        assertFalse(CameraMode.PHOTO.isContinuous)
        assertFalse(CameraMode.HDR_PHOTO.isContinuous)
    }

    @Test
    fun testModeFromWhatTheCameraReports() {
        assertEquals(CameraMode.FREEFRAME_PRO, CameraMode.of(Go2BleProtocol.SubModes(100, 5)))
        assertEquals(CameraMode.SLOW_MOTION, CameraMode.of(Go2BleProtocol.SubModes(100, 1)))
        assertEquals(CameraMode.STARLAPSE, CameraMode.of(Go2BleProtocol.SubModes(7, 100)))
        assertEquals(CameraMode.PHOTO, CameraMode.of(Go2BleProtocol.SubModes(0, 100)))
        // A sub-mode the GO 3S does not offer
        assertNull(CameraMode.of(Go2BleProtocol.SubModes(100, 9)))
    }

    @Test
    fun testGroupsInTheOrderOffered() {
        assertEquals(
            listOf("Video", "FreeFrame Video (PRO)", "TimeShift", "Timelapse", "Slow Motion", "Loop Recording"),
            CameraMode.group(photo = false).map { it.label }
        )
        assertEquals(
            listOf("Photo", "HDR Photo", "Interval Photo", "Starlapse"),
            CameraMode.group(photo = true).map { it.label }
        )
        // Each mode is found again from the sub-modes the camera reports once it is in it
        CameraMode.entries.forEach { mode ->
            val reported = if (mode.isPhoto) Go2BleProtocol.SubModes(mode.subMode, 100) else Go2BleProtocol.SubModes(100, mode.subMode)
            assertEquals(mode, CameraMode.of(reported))
        }
        // and selected with the SET_OPTIONS the camera took for it
        assertTrue(hex("08281203c00207").contentEquals(Go2BleProtocol.buildSetSubModePayload(true, CameraMode.STARLAPSE.subMode)))
    }
}
