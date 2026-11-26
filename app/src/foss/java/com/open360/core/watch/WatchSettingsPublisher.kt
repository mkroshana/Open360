package com.open360.core.watch

import android.content.Context

/**
 * No-op FOSS build of [WatchSettingsPublisher]: Wear OS Data Layer sync is omitted when building
 * without Google Play services.
 */
@Suppress("UNUSED_PARAMETER")
class WatchSettingsPublisher(context: Context) {
    fun publish(wakeUpId: String?, serialSuffix: String, speedInMph: Boolean? = null) = Unit
}
