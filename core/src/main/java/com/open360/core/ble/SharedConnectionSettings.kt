package com.open360.core.ble

/**
 * Where the phone app hands its connection settings to the watch app on the Wear OS Data Layer:
 * the wake-up ID the camera already allows and the camera's serial suffix. With them the watch
 * links to the same camera and wakes it without a set-up of its own. The Data Layer only joins
 * the two apps when they have the same package name and are signed with the same key.
 */
object SharedConnectionSettings {
    const val PATH = "/open360/connection"
    const val KEY_WAKE_UP_ID = "wake_up_id"
    const val KEY_SERIAL_SUFFIX = "serial_suffix"
}
