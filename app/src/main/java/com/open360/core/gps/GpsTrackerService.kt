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
import com.open360.MainActivity
import com.open360.core.ble.GpsRemoteBleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps GPS fixes arriving and the camera link alive when the phone
 * display is off or the app is in the background. It runs for as long as the camera is
 * connected and has to be started while the app is on screen.
 */
class GpsTrackerService : Service() {

    companion object {
        private const val TAG = "GpsTrackerService"
        private const val CHANNEL_ID = "open360_gps_tracking_channel"
        private const val NOTIFICATION_ID = 2001

        const val ACTION_START_TRACKING = "com.open360.action.START_GPS_TRACKING"
        const val ACTION_STOP_TRACKING = "com.open360.action.STOP_GPS_TRACKING"

        fun start(context: Context) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_START_TRACKING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GpsTrackerService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var gpsEngine: GpsTrackerEngine
    private var bleManager: GpsRemoteBleManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        val service: GpsTrackerService get() = this@GpsTrackerService
    }

    private val binder = LocalBinder()

    val telemetry: StateFlow<GpsTelemetry> get() = gpsEngine.telemetry

    fun attachBleManager(manager: GpsRemoteBleManager) {
        this.bleManager = manager
    }

    override fun onCreate() {
        super.onCreate()
        gpsEngine = GpsTrackerEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> {
                startForegroundTracking()
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

        // Keeps location updates and the process alive in the background. The fixes themselves
        // are sent to the camera by CameraViewModel.
        gpsEngine.startTracking()

        // With the display off the phone lets the processor sleep between fixes. That stalls the
        // keep-alive the camera needs every 2 s and the GPS sent to it, so it is held awake.
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Open360:CameraLink")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        lock.acquire()
        Log.i(TAG, "GpsTrackerService started foreground tracking")
    }

    private fun stopForegroundTracking() {
        gpsEngine.stopTracking()
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
                "GPS Telemetry Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors and streams GPS location to Insta360 camera"
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
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        gpsEngine.stopTracking()
        releaseWakeLock()
        serviceScope.cancel()
    }
}
