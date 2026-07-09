package com.example.dooropen.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.dooropen.buddy.BuddyVoiceService
import com.example.dooropen.data.DoorPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            if (DoorPrefs.getBleEnabled(context)) {
                ProximityService.start(context)
            }
        } catch (_: Exception) {}
        try {
            if (DoorPrefs.getBuddyEnabled(context)) {
                BuddyVoiceService.start(context)
            }
        } catch (_: Exception) {}
    }
}
