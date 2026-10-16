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
    fun testTimeLeftLabel() {
        assertEquals("1 h 19 min left", CameraStatusText.timeLeftLabel(4796))
        assertEquals("42 min left", CameraStatusText.timeLeftLabel(42 * 60 + 59))
        assertEquals("Under 1 min left", CameraStatusText.timeLeftLabel(30))
    }

    @Test
    fun testPhotosLeft() {
        // A GO 3S in Photo mode gave 1105 after one photo
        assertEquals("1,105", CameraStatusText.photosLeft(1105))
        assertEquals("42", CameraStatusText.photosLeft(42))
        assertEquals("0", CameraStatusText.photosLeft(0))
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
        // Docked but switched off: not a battery reading
        val switchedOff = ChargeBoxStatus(connected = true, battery = null, isOn = false)
        assertEquals("Switched Off", CameraStatusText.pod(switchedOff))
        assertFalse(CameraStatusText.podIsLow(switchedOff))

        assertFalse(CameraStatusText.podIsLow(onBattery))
        assertTrue(CameraStatusText.podIsLow(low))
        assertFalse(CameraStatusText.podIsLow(lowButCharging))
        assertFalse(CameraStatusText.podIsLow(gone))
    }

    @Test
    fun testPodBatteryLabel() {
        val onBattery = ChargeBoxStatus(connected = true, battery = BatteryStatus(69, onExternalPower = false))
        val gone = ChargeBoxStatus(connected = false, battery = null)
        val connectedNoBattery = ChargeBoxStatus(connected = true, battery = null)

        assertEquals("Pod 69%", CameraStatusText.podBatteryLabel(onBattery))
        assertNull(CameraStatusText.podBatteryLabel(gone))
        assertNull(CameraStatusText.podBatteryLabel(connectedNoBattery))
        assertNull(CameraStatusText.podBatteryLabel(null))
    }

    @Test
    fun testHasTopStatus() {
        val pod = ChargeBoxStatus(connected = true, battery = BatteryStatus(80, onExternalPower = false))
        val disconnectedPod = ChargeBoxStatus(connected = false, battery = null)

        // All null / default -> false
        assertFalse(CameraStatusText.hasTopStatus(null, null, null, null))
        assertFalse(CameraStatusText.hasTopStatus(null, disconnectedPod, null, CardState.OK))

        // When any relevant status is available -> true
        assertTrue(CameraStatusText.hasTopStatus(90, null, null, null))
        assertTrue(CameraStatusText.hasTopStatus(null, pod, null, null))
        assertTrue(CameraStatusText.hasTopStatus(null, null, 1200, null))
        assertTrue(CameraStatusText.hasTopStatus(null, null, null, CardState.FULL))
        assertTrue(CameraStatusText.hasTopStatus(null, null, null, CardState.NO_CARD))
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

    @Test
    fun testWakeUpSetupStatus() {
        // Without an ID it is not set up, whatever the camera last said
        assertEquals("Not Set Up", CameraStatusText.wakeUpSetupStatus(hasId = false, WakeUpRegistration.NONE))
        assertEquals("Not Set Up", CameraStatusText.wakeUpSetupStatus(hasId = false, WakeUpRegistration.AUTHORIZED))

        assertEquals("Ready", CameraStatusText.wakeUpSetupStatus(hasId = true, WakeUpRegistration.AUTHORIZED))
        // An ID the camera has not been asked about: nothing is claimed
        assertNull(CameraStatusText.wakeUpSetupStatus(hasId = true, WakeUpRegistration.NONE))

        // Otherwise what the camera is doing about it, or how the last try ended
        assertEquals(
            "Allow Open360 on the Camera",
            CameraStatusText.wakeUpSetupStatus(hasId = true, WakeUpRegistration.CONFIRM_ON_CAMERA)
        )
        assertEquals("Declined on the Camera", CameraStatusText.wakeUpSetupStatus(hasId = true, WakeUpRegistration.REJECTED))
    }
}
