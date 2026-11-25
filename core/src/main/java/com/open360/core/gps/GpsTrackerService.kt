package com.open360.core.gps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.open360.core.ble.GpsRemoteBleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps GPS fixes arriving and the camera link alive when the phone
 * display is off or the app is in the background. It runs for as long as the camera is
 * connected and has to be started while the app is on screen.
 *
 * The fixes come from CameraViewModel's GpsTrackerEngine: a running location service is what
 * lets the app receive them, and start them again, while it is not on screen. The processor is
 * held awake only while the camera is answering; while it is off nothing needs to run on time.
 */
class GpsTrackerService : Service() {

    companion object {
        private const val TAG = "GpsTrackerService"
        private const val CHANNEL_ID = "open360_gps_tracking_channel"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_TITLE = "Camera Connected"

        const val ACTION_START_TRACKING = "com.mkroshana.open360.action.START_GPS_TRACKING"
        const val ACTION_STOP_TRACKING = "com.mkroshana.open360.action.STOP_GPS_TRACKING"
        const val ACTION_SET_HOLD_AWAKE = "com.mkroshana.open360.action.SET_HOLD_AWAKE"
        const val ACTION_SET_TEXT = "com.mkroshana.open360.action.SET_TEXT"
        const val ACTION_REFRESH_FOREGROUND = "com.mkroshana.open360.action.REFRESH_FOREGROUND"
        const val EXTRA_HOLD_AWAKE = "hold_awake"
        const val EXTRA_TEXT = "text"

        private const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MS = 3 * 60 * 60 * 1000L

        /** Starts the service, its notification reading [text] under "Camera Connected". */
        fun start(context: Context, holdAwake: Boolean, text: String) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_HOLD_AWAKE, holdAwake)
                putExtra(EXTRA_TEXT, text)
            }
            context.startForegroundService(intent)
        }

        /** Holds the processor awake, or lets it sleep, while the service is running. */
        fun setHoldAwake(context: Context, holdAwake: Boolean) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_SET_HOLD_AWAKE
                putExtra(EXTRA_HOLD_AWAKE, holdAwake)
            }
            context.startService(intent)
        }

        /** Changes the running service's notification to read [text]. */
        fun setText(context: Context, text: String) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_SET_TEXT
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        /** Re-applies the foreground service types (e.g. adding health when watch HR turns on). */
        fun refreshServiceTypes(context: Context, text: String) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_REFRESH_FOREGROUND
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        /**
         * Adds to the service's notification before it is shown, given the notification's ID
         * and the intent that opens the app. The watch app makes it an ongoing activity, which
         * the watch shows on its face and leads back to the app from.
         */
        @Volatile
        var decorateNotification: ((Context, NotificationCompat.Builder, Int, PendingIntent) -> Unit)? = null

        /**
         * Foreground service types added to location, read each time the service starts. The
         * watch app adds health while it reads the watch's heart rate, which Android only lets
         * it do with the screen off from a service of that type.
         */
        @Volatile
        var extraServiceTypes: () -> Int = { 0 }

        fun stop(context: Context) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bleManager: GpsRemoteBleManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewJob: Job? = null

    inner class LocalBinder : Binder() {
        val service: GpsTrackerService get() = this@GpsTrackerService
    }

    private val binder = LocalBinder()

    fun attachBleManager(manager: GpsRemoteBleManager) {
        this.bleManager = manager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                startForegroundTracking(intent.getStringExtra(EXTRA_TEXT).orEmpty())
                holdAwake(intent.getBooleanExtra(EXTRA_HOLD_AWAKE, true))
            }
            ACTION_SET_HOLD_AWAKE -> {
                holdAwake(intent.getBooleanExtra(EXTRA_HOLD_AWAKE, true))
            }
            ACTION_SET_TEXT -> {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(NOTIFICATION_TITLE, intent.getStringExtra(EXTRA_TEXT).orEmpty()))
            }
            ACTION_REFRESH_FOREGROUND -> {
                startForegroundTracking(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            }
            ACTION_STOP_TRACKING -> {
                stopForegroundTracking()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundTracking(text: String) {
        val notification = buildNotification(NOTIFICATION_TITLE, text)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or extraServiceTypes()
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.i(TAG, "GpsTrackerService started foreground tracking")
    }

    /**
     * With the display off the phone lets the processor sleep between fixes. That stalls the
     * keep-alive the camera needs every 2 s and the GPS sent to it, so while the camera is
     * answering it is held awake.
     */
    private fun holdAwake(hold: Boolean) {
        if (!hold) {
            if (wakeLock?.isHeld == true) Log.i(TAG, "Letting the processor sleep")
            releaseWakeLock()
            return
        }
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Open360:CameraLink")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (!lock.isHeld) Log.i(TAG, "Holding the processor awake")
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = serviceScope.launch {
            while (isActive) {
                delay(WAKE_LOCK_RENEW_MS)
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun stopForegroundTracking() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "GpsTrackerService stopped foreground tracking")
    }

    private fun releaseWakeLock() {
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Camera Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Open360 is connected to the camera and sending it GPS"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            // The app's own launcher screen, so the phone and watch apps each open themselves
            packageManager.getLaunchIntentForPackage(packageName) ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(com.open360.core.R.drawable.ic_open360_mark)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        decorateNotification?.invoke(this, builder, NOTIFICATION_ID, pendingIntent)
        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        serviceScope.cancel()
    }
}
