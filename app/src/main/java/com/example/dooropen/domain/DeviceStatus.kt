package com.example.dooropen.domain

import android.content.Context
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.data.SwitchBotApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DeviceStatus {

    sealed class State {
        data object Unknown : State()
        data object Checking : State()
        data class Connected(val deviceName: String) : State()
        data class Disconnected(val reason: String) : State()
        data class Error(val message: String) : State()
    }

    private var lastState: State = State.Unknown
    private var lastCheckTime: Long = 0
    private const val CACHE_MS = 5_000L // 5 second cache

    suspend fun check(context: Context): State = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CACHE_MS && lastState != State.Unknown) {
            return@withContext lastState
        }

        lastState = doCheck(context)
        lastCheckTime = now
        lastState
    }

    private suspend fun doCheck(context: Context): State {
        return try {
            val bleEnabled = DoorPrefs.getBleEnabled(context)

            if (bleEnabled) {
                val mac = DoorPrefs.getBleMac(context)
                if (mac.isBlank()) {
                    return State.Disconnected("Bluetooth MAC not set")
                }
                // For BLE, we can't easily check without scanning - assume OK if configured
                return State.Connected("Bluetooth device")
            }

            // Cloud/API mode
            if (!DoorPrefs.isConfigured(context)) {
                return State.Disconnected("Not configured")
            }

            val token = DoorPrefs.getToken(context)
            val secret = DoorPrefs.getSecret(context)
            val deviceId = DoorPrefs.getDeviceId(context)

            // Test the connection by fetching device list
            val result = SwitchBotApi.verifyDevice(token, secret, deviceId)

            if (result.ok) {
                State.Connected(result.message)
            } else {
                State.Disconnected(result.message)
            }
        } catch (e: Exception) {
            State.Error(e.message ?: "Unknown error")
        }
    }

    fun invalidateCache() {
        lastCheckTime = 0
        lastState = State.Unknown
    }
}
