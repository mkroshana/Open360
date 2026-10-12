package com.open360.ui.camera

import com.open360.core.ble.Go2BleProtocol.BatteryStatus
import com.open360.core.ble.Go2BleProtocol.CardState
import com.open360.core.ble.Go2BleProtocol.ChargeBoxStatus
import com.open360.core.ble.WakeUpRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStatusTextTest {

    @Test
    fun testTimeLeft() {
        assertEquals("1 h 19 min", CameraStatusText.timeLeft(4796))
        assertEquals("1 h 0 min", CameraStatusText.timeLeft(3600))
        assertEquals("42 min", CameraStatusText.timeLeft(42 * 60 + 59))
        assertEquals("1 min", CameraStatusText.timeLeft(60))
        assertEquals("Under 1 min", CameraStatusText.timeLeft(59))
        assertEquals("Under 1 min", CameraStatusText.timeLeft(0))
    }

    @Test
    fun testStorageProblemOnlyWhenSomethingIsWrong() {
        assertNull(CameraStatusText.storageProblem(null))
        assertNull(CameraStatusText.storageProblem(CardState.OK))
        assertEquals("Full", CameraStatusText.storageProblem(CardState.FULL))
        assertEquals("Not Found", CameraStatusText.storageProblem(CardState.NO_CARD))
        assertEquals("Needs Formatting", CameraStatusText.storageProblem(CardState.INVALID_FORMAT))
        assertEquals("Write-Protected", CameraStatusText.storageProblem(CardState.WRITE_PROTECTED))
        assertEquals("Error", CameraStatusText.storageProblem(CardState.ERROR))
    }

    @Test
    fun testRecordingModeNames() {
        assertEquals("Video", CameraStatusText.recordingMode(1))
        assertEquals("PRO Video", CameraStatusText.recordingMode(17))
        assertNull(CameraStatusText.recordingMode(null))
        assertNull(CameraStatusText.recordingMode(0))
        assertNull(CameraStatusText.recordingMode(99))
    }

    @Test
    fun testClipLength() {
        assertEquals("41 s", CameraStatusText.clipLength(41))
        assertEquals("3 min 30 s", CameraStatusText.clipLength(210))
        assertEquals("1 min 0 s", CameraStatusText.clipLength(60))
        assertEquals("1 h 2 min", CameraStatusText.clipLength(3725))
    }

    @Test
    fun testPodLine() {
        val onBattery = ChargeBoxStatus(connected = true, battery = BatteryStatus(69, onExternalPower = false))
        val pluggedIn = ChargeBoxStatus(connected = true, battery = BatteryStatus(65, onExternalPower = true))
        val low = ChargeBoxStatus(connected = true, battery = BatteryStatus(15, onExternalPower = false))
        val lowButCharging = ChargeBoxStatus(connected = true, battery = BatteryStatus(15, onExternalPower = true))
        val gone = ChargeBoxStatus(connected = false, battery = null)

        assertEquals("69%", CameraStatusText.pod(onBattery))
        assertEquals("65%, Plugged In", CameraStatusText.pod(pluggedIn))
        assertEquals("Not Connected", CameraStatusText.pod(gone))
        assertEquals("Connected", CameraStatusText.pod(ChargeBoxStatus(connected = true, battery = null)))

        assertFalse(CameraStatusText.podIsLow(onBattery))
        assertTrue(CameraStatusText.podIsLow(low))
        assertFalse(CameraStatusText.podIsLow(lowButCharging))
        assertFalse(CameraStatusText.podIsLow(gone))
    }

    @Test
    fun testWakeUpRegistrationLine() {
        // Nothing is said before the camera has been asked
        assertNull(CameraStatusText.wakeUpRegistration(WakeUpRegistration.NONE))
        assertEquals("Accepted by the Camera", CameraStatusText.wakeUpRegistration(WakeUpRegistration.AUTHORIZED))
        assertEquals("Allow Open360 on the Camera", CameraStatusText.wakeUpRegistration(WakeUpRegistration.CONFIRM_ON_CAMERA))

        // Every state but the unasked one has wording
        WakeUpRegistration.entries.filter { it != WakeUpRegistration.NONE }.forEach {
            assertTrue(it.name, !CameraStatusText.wakeUpRegistration(it).isNullOrBlank())
        }

        // Not asked, being asked and accepted are not problems; every way of failing is
        listOf(
            WakeUpRegistration.NONE,
            WakeUpRegistration.CHECKING,
            WakeUpRegistration.CONFIRM_ON_CAMERA,
            WakeUpRegistration.AUTHORIZED
        ).forEach {
            assertFalse(it.name, CameraStatusText.wakeUpRegistrationIsProblem(it))
        }
        listOf(
            WakeUpRegistration.REJECTED,
            WakeUpRegistration.TIMED_OUT,
            WakeUpRegistration.CAMERA_BUSY,
            WakeUpRegistration.NO_ANSWER
        ).forEach {
            assertTrue(it.name, CameraStatusText.wakeUpRegistrationIsProblem(it))
        }
    }
}
