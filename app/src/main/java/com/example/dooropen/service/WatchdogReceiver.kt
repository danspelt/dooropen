package com.example.dooropen.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.dooropen.data.DoorPrefs

/**
 * Fired by AlarmManager every ~30 seconds when the screen is off.
 * Restarts the BLE scan inside ProximityService if it has died,
 * and re-schedules itself for the next interval.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (!DoorPrefs.getBleEnabled(context) || !DoorPrefs.getAutoOpenEnabled(context)) return
            // Kick the service — if already running onStartCommand handles it gracefully;
            // if dead, START_STICKY should have restarted it, but this is the safety net.
            ProximityService.start(context)
            // Re-schedule next watchdog tick
            schedule(context)
        } catch (_: Exception) {}
    }

    companion object {
        private const val INTERVAL_MS = 30_000L
        private const val REQUEST_CODE = 0xD00C

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pendingIntent(context) ?: return
            val triggerAt = SystemClock.elapsedRealtime() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (_: SecurityException) {
                // SCHEDULE_EXACT_ALARM not granted yet; fall back to inexact
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }

        fun cancel(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            pendingIntent(context)?.let { am.cancel(it) }
        }

        private fun pendingIntent(context: Context): PendingIntent? {
            val intent = Intent(context, WatchdogReceiver::class.java)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
