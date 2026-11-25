package com.open360.core.peer

import android.content.Context
import com.open360.core.gps.SavedTrack

/**
 * No-op FOSS build of [TrackHandover]: Wear OS Data Layer handover is omitted when building
 * without Google Play services.
 */
object TrackHandover {
    const val PATH_PREFIX = "/open360/track"
    const val KEY_RECORDING_ID = "recording_id"
    const val KEY_GPX_NAME = "gpx_name"
    const val KEY_GPX = "gpx"
    const val KEY_FIT_NAME = "fit_name"
    const val KEY_FIT = "fit"

    @Suppress("UNUSED_PARAMETER")
    fun send(context: Context, track: SavedTrack) = Unit
}
