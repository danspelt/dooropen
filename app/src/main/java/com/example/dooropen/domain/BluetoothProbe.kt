package com.example.dooropen.domain

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothProbe {

    fun isBluetoothOn(context: Context): Boolean {
        val adapter = if (Build.VERSION.SDK_INT >= 31) {
            val bm = context.applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        } ?: return false

        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return adapter.isEnabled
    }
}
