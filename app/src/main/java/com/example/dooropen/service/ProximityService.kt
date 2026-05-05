package com.example.dooropen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.dooropen.MainActivity
import com.example.dooropen.R
import com.example.dooropen.domain.DoorCommand
import com.example.dooropen.domain.DoorFeedback
import com.example.dooropen.domain.ProximityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProximityService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Partial wake lock keeps CPU alive so BLE scanning continues with screen off
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DoorAssist::ProximityWakeLock"
        ).also { it.acquire(12 * 60 * 60 * 1000L) } // max 12 hours

        startForeground(NOTIFICATION_ID, buildNotification("Watching for door..."))

        DoorFeedback.initTts(this)

        ProximityMonitor.setAutoOpenCallback(object : ProximityMonitor.AutoOpenCallback {
            override fun onAutoOpenTrigger() {
                scope.launch {
                    val blocked = DoorCommand.evaluate(applicationContext)
                    if (blocked == null) {
                        DoorFeedback.playSuccess(applicationContext)
                        DoorCommand.commitPress(applicationContext)
                    }
                }
            }
        })

        ProximityMonitor.startMonitoring(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_AUTO_OPEN) {
            val enabled = intent.getBooleanExtra(EXTRA_AUTO_OPEN, false)
            ProximityMonitor.setAutoOpenEnabled(enabled)
        }
        return START_STICKY // restart automatically if killed
    }

    override fun onDestroy() {
        ProximityMonitor.stopMonitoring(this)
        ProximityMonitor.setAutoOpenCallback(null)
        DoorFeedback.shutdown()
        scope.cancel()
        wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Door Proximity",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors Bluetooth proximity to auto-open the door"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Door Assist")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "door_proximity"
        const val NOTIFICATION_ID = 1001
        const val ACTION_UPDATE_AUTO_OPEN = "com.example.dooropen.UPDATE_AUTO_OPEN"
        const val EXTRA_AUTO_OPEN = "auto_open"

        fun start(context: Context) {
            val intent = Intent(context, ProximityService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ProximityService::class.java))
        }

        fun updateAutoOpen(context: Context, enabled: Boolean) {
            val intent = Intent(context, ProximityService::class.java).apply {
                action = ACTION_UPDATE_AUTO_OPEN
                putExtra(EXTRA_AUTO_OPEN, enabled)
            }
            context.startForegroundService(intent)
        }
    }
}
