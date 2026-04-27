package com.example.dooropen.domain

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager

object WifiSsidReader {

    @SuppressLint("MissingPermission")
    fun currentSsid(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        val raw = info.ssid ?: return null
        val s = raw.trim('"')
        if (s.equals("<unknown ssid>", ignoreCase = true) || s.equals("unknown ssid", ignoreCase = true)) {
            return null
        }
        return s
    }
}
