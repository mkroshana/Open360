package com.open360.core.ble

/**
 * Where the phone app hands its connection settings to the watch app on the Wear OS Data Layer:
 * the wake-up ID the camera already allows, the camera's serial suffix, and the speed unit. With
 * them the watch links to the same camera and wakes it without a set-up of its own. The Data Layer
 * only joins the two apps when they have the same package name and are signed with the same key.
 */
object SharedConnectionSettings {
    const val PATH = "/open360/connection"
    const val KEY_WAKE_UP_ID = "wake_up_id"
    const val KEY_SERIAL_SUFFIX = "serial_suffix"
    const val KEY_SPEED_IN_MPH = "speed_in_mph"
}
