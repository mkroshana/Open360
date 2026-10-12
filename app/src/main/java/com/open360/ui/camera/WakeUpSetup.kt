package com.open360.ui.camera

import com.open360.core.ble.WakeUpRegistration

/** The steps of the Wake-Up set-up, in the order they are gone through. */
enum class WakeUpSetupStep { SWITCH_CAMERA_ON, ALLOW_ON_CAMERA, READY }

/**
 * Works out where the Wake-Up set-up stands from what the camera reports. Kept apart from the
 * screen so it can be tested.
 */
object WakeUpSetup {

    /**
     * The step the set-up is at. The camera has to be on to be asked, and it is ready once it
     * has accepted the ID, whether or not it is still on.
     */
    fun step(cameraOn: Boolean, registration: WakeUpRegistration): WakeUpSetupStep = when {
        registration == WakeUpRegistration.AUTHORIZED -> WakeUpSetupStep.READY
        !cameraOn -> WakeUpSetupStep.SWITCH_CAMERA_ON
        else -> WakeUpSetupStep.ALLOW_ON_CAMERA
    }

    /** Whether the camera is still to be asked: it is on and nothing has been asked of it. */
    fun shouldAsk(cameraOn: Boolean, registration: WakeUpRegistration): Boolean =
        cameraOn && registration == WakeUpRegistration.NONE

    /** Whether the set-up has stopped at the camera not allowing the app, to be tried again. */
    fun failed(cameraOn: Boolean, registration: WakeUpRegistration): Boolean =
        step(cameraOn, registration) == WakeUpSetupStep.ALLOW_ON_CAMERA &&
            CameraStatusText.wakeUpRegistrationIsProblem(registration)
}
