package com.example.dooropen

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.dooropen.data.DoorPrefs

object DoorShortcut {

    const val SHORTCUT_ID = "open_door"

    fun refresh(context: Context) {
        try {
            if (!DoorPrefs.isConfigured(context)) {
                ShortcutManagerCompat.removeDynamicShortcuts(
                    context,
                    listOf(SHORTCUT_ID)
                )
                return
            }
            val trigger = DoorPrefs.getTriggerKey(context)
            if (trigger.isEmpty()) {
                ShortcutManagerCompat.removeDynamicShortcuts(
                    context,
                    listOf(SHORTCUT_ID)
                )
                return
            }
            val intent = Intent(context, OpenDoorActivity::class.java).apply {
                action = OpenDoorActivity.ACTION_OPEN_DOOR
                putExtra(OpenDoorActivity.EXTRA_KEY, trigger)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val shortcut = ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                .setShortLabel(context.getString(R.string.shortcut_open_door_short))
                .setLongLabel(context.getString(R.string.shortcut_open_door_long))
                .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        } catch (_: Exception) {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(SHORTCUT_ID))
        }
    }
}
