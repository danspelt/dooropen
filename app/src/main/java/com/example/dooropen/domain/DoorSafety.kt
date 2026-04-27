package com.example.dooropen.domain

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.dooropen.R
import com.example.dooropen.data.DoorPrefs

object DoorSafety {

    /** @return null if allowed, otherwise a user-visible reason */
    fun blockReason(context: Context): String? {
        val app = context.applicationContext
        val km = app.getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            return context.getString(R.string.blocked_keyguard)
        }
        try {
            if (DoorPrefs.getHomeSafetyEnabled(context)) {
                if (ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return context.getString(R.string.blocked_location_permission)
                }
                val ssid = WifiSsidReader.currentSsid(context)
                    ?: return context.getString(R.string.blocked_ssid_unknown)
                val home = DoorPrefs.getHomeSsid(context)
                if (home.isEmpty()) {
                    return context.getString(R.string.blocked_home_ssid_not_configured)
                }
                if (!ssid.equals(home, ignoreCase = true)) {
                    return context.getString(R.string.blocked_not_home_wifi, ssid)
                }
            }
            if (DoorPrefs.getBtSafetyEnabled(context)) {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        return context.getString(R.string.blocked_bt_permission)
                    }
                }
                if (!BluetoothProbe.isBluetoothOn(context)) {
                    return context.getString(R.string.blocked_bt_off)
                }
            }
        } catch (_: Exception) {
            return context.getString(R.string.blocked_prefs)
        }
        return null
    }
}
