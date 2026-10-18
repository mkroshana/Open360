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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

        const val ACTION_START_TRACKING = "com.open360.action.START_GPS_TRACKING"
        const val ACTION_STOP_TRACKING = "com.open360.action.STOP_GPS_TRACKING"
        const val ACTION_SET_HOLD_AWAKE = "com.open360.action.SET_HOLD_AWAKE"
        const val EXTRA_HOLD_AWAKE = "hold_awake"

        fun start(context: Context, holdAwake: Boolean) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_HOLD_AWAKE, holdAwake)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Holds the processor awake, or lets it sleep, while the service is running. */
        fun setHoldAwake(context: Context, holdAwake: Boolean) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_SET_HOLD_AWAKE
                putExtra(EXTRA_HOLD_AWAKE, holdAwake)
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
                startForegroundTracking()
                holdAwake(intent.getBooleanExtra(EXTRA_HOLD_AWAKE, true))
            }
            ACTION_SET_HOLD_AWAKE -> {
                holdAwake(intent.getBooleanExtra(EXTRA_HOLD_AWAKE, true))
            }
            ACTION_STOP_TRACKING -> {
                stopForegroundTracking()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun startForegroundTracking() {
        val notification = buildNotification("Camera Connected", "GPS is sent to the camera while recording")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
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
        lock.acquire()
    }

    private fun stopForegroundTracking() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i(TAG, "GpsTrackerService stopped foreground tracking")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
