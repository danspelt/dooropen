package com.example.dooropen.util

import android.app.Activity
import android.content.Context
import android.content.Intent

object SwitchBotLauncher {

    private const val PKG = "com.theswitchbot.switchbot"

    fun openSwitchBotApp(context: Context): Boolean {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(PKG) ?: return false
        if (context !is Activity) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(launch)
        return true
    }
}
