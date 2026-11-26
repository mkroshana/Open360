package com.open360.wear

import android.app.Application
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.wear.ongoing.OngoingActivity
import com.open360.core.gps.GpsTrackerService

class WearApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // While the camera is linked, the location service's notification is an ongoing activity:
        // the watch shows it on its face, and tapping it leads back to the remote
        GpsTrackerService.decorateNotification = { context, builder, notificationId, openApp ->
            OngoingActivity.Builder(context, notificationId, builder)
                .setStaticIcon(com.open360.core.R.drawable.ic_open360_mark)
                .setTouchIntent(openApp)
                .setStatus(WearOngoingStatus.currentStatus())
                .build()
                .apply(context)
        }
        // Reading the watch's heart rate with the screen off needs the service to be a health one
        GpsTrackerService.extraServiceTypes = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                WatchHeartRate.isEnabled(this) && WatchHeartRate.isPermitted(this)
            ) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        }
    }
}
