package com.open360.wear

import android.app.Application
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.open360.core.gps.GpsTrackerService

class WearApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // While the camera is linked, the location service's notification is an ongoing activity:
        // the watch shows it on its face, and tapping it leads back to the remote
        GpsTrackerService.decorateNotification = { context, builder, notificationId, openApp ->
            OngoingActivity.Builder(context, notificationId, builder)
                .setStaticIcon(android.R.drawable.ic_menu_camera)
                .setTouchIntent(openApp)
                .setStatus(Status.Builder().addTemplate("Camera Connected").build())
                .build()
                .apply(context)
        }
    }
}
