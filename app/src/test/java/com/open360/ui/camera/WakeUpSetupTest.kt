package com.open360.ui.camera

import com.open360.core.ble.WakeUpRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeUpSetupTest {

    @Test
    fun theCameraHasToBeOnBeforeItCanBeAsked() {
        assertEquals(WakeUpSetupStep.SWITCH_CAMERA_ON, WakeUpSetup.step(cameraOn = false, WakeUpRegistration.NONE))
        assertFalse(WakeUpSetup.shouldAsk(cameraOn = false, WakeUpRegistration.NONE))
        // What went wrong last time does not matter while the camera is off
        assertEquals(WakeUpSetupStep.SWITCH_CAMERA_ON, WakeUpSetup.step(cameraOn = false, WakeUpRegistration.REJECTED))
        assertFalse(WakeUpSetup.failed(cameraOn = false, WakeUpRegistration.REJECTED))
    }

    @Test
    fun aCameraThatIsOnIsAskedOnce() {
        assertEquals(WakeUpSetupStep.ALLOW_ON_CAMERA, WakeUpSetup.step(cameraOn = true, WakeUpRegistration.NONE))
        assertTrue(WakeUpSetup.shouldAsk(cameraOn = true, WakeUpRegistration.NONE))

        // While it is being asked, and while it asks on its own screen, it is not asked again
        listOf(WakeUpRegistration.CHECKING, WakeUpRegistration.CONFIRM_ON_CAMERA).forEach {
            assertEquals(it.name, WakeUpSetupStep.ALLOW_ON_CAMERA, WakeUpSetup.step(cameraOn = true, it))
            assertFalse(it.name, WakeUpSetup.shouldAsk(cameraOn = true, it))
            assertFalse(it.name, WakeUpSetup.failed(cameraOn = true, it))
        }
    }

    @Test
    fun anAcceptedIdIsReadyEvenWithTheCameraOff() {
        assertEquals(WakeUpSetupStep.READY, WakeUpSetup.step(cameraOn = true, WakeUpRegistration.AUTHORIZED))
        assertEquals(WakeUpSetupStep.READY, WakeUpSetup.step(cameraOn = false, WakeUpRegistration.AUTHORIZED))
        assertFalse(WakeUpSetup.failed(cameraOn = true, WakeUpRegistration.AUTHORIZED))
        assertFalse(WakeUpSetup.shouldAsk(cameraOn = true, WakeUpRegistration.AUTHORIZED))
    }

    @Test
    fun aCameraThatDidNotAllowTheAppStopsAtThatStep() {
        listOf(
            WakeUpRegistration.REJECTED,
            WakeUpRegistration.TIMED_OUT,
            WakeUpRegistration.CAMERA_BUSY,
            WakeUpRegistration.NO_ANSWER
        ).forEach {
            assertEquals(it.name, WakeUpSetupStep.ALLOW_ON_CAMERA, WakeUpSetup.step(cameraOn = true, it))
            assertTrue(it.name, WakeUpSetup.failed(cameraOn = true, it))
            // Asking again is left to Try Again
            assertFalse(it.name, WakeUpSetup.shouldAsk(cameraOn = true, it))
        }
    }
}
