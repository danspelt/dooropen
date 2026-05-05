package com.example.dooropen.domain

import android.content.Context
import com.example.dooropen.R
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.data.SeamApi
import com.example.dooropen.data.SwitchBotBle
import com.example.dooropen.data.SwitchBotApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DoorCommand {

    private const val PREFS = "door_assist_timing"
    /**
     * Current key (wall clock time in millis since epoch).
     * We keep legacy keys below to tolerate old installs / corrupted values.
     */
    private const val KEY_LAST_WALL_TIME = "last_open_wall_time_ms"

    /** Legacy key: previously stored a monotonic timestamp (e.g., elapsedRealtime). */
    private const val KEY_LAST_ELAPSED_LEGACY = "last_open_elapsed_realtime"
    private const val COOLDOWN_MS = 10_000L

    sealed class Outcome {
        data object Success : Outcome()
        data class Blocked(val message: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    sealed class PressOutcome {
        data object Success : PressOutcome()
        data class Failed(val message: String) : PressOutcome()
    }

    sealed class SeamOutcome {
        data object Success : SeamOutcome()
        data class Failed(val message: String) : SeamOutcome()
        data object NotConfigured : SeamOutcome()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun cooldownRemainingMs(context: Context): Long {
        val sp = prefs(context)
        // Clear legacy monotonic timestamp (incompatible with wall clock based cooldown).
        if (sp.contains(KEY_LAST_ELAPSED_LEGACY)) sp.edit().remove(KEY_LAST_ELAPSED_LEGACY).apply()

        val last = sp.getLong(KEY_LAST_WALL_TIME, 0L)
        if (last == 0L) return 0L
        val now = System.currentTimeMillis()
        // Protect against clock changes, corrupted timestamps, and overflow.
        if (last > now) {
            sp.edit().remove(KEY_LAST_WALL_TIME).apply()
            return 0L
        }

        val elapsed = now - last
        if (elapsed < 0L) {
            sp.edit().remove(KEY_LAST_WALL_TIME).apply()
            return 0L
        }

        val remaining = COOLDOWN_MS - elapsed
        if (remaining <= 0L) return 0L

        // If remaining is ever > cooldown, the stored value is bad (overflow/corruption). Reset.
        if (remaining > COOLDOWN_MS) {
            sp.edit().remove(KEY_LAST_WALL_TIME).apply()
            return 0L
        }

        return remaining
    }

    private fun markAttempt(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_WALL_TIME, System.currentTimeMillis())
            .apply()
    }

    /** Safety, cooldown, and credentials — before "door opening" feedback.
     *  Pass skipCooldown=true for manual button presses so they always work immediately. */
    suspend fun evaluate(context: Context, skipCooldown: Boolean = false): Outcome.Blocked? = withContext(Dispatchers.Default) {
        DoorSafety.blockReason(context)?.let { return@withContext Outcome.Blocked(it) }
        if (!skipCooldown) {
            val remaining = cooldownRemainingMs(context)
            if (remaining > 0L) {
                val sec = ((remaining + 999) / 1000).toInt()
                return@withContext Outcome.Blocked(context.getString(R.string.blocked_cooldown, sec))
            }
        }
        try {
            val bleEnabled = DoorPrefs.getBleEnabled(context)
            if (bleEnabled) {
                val mac = DoorPrefs.getBleMac(context)
                if (mac.isBlank()) {
                    return@withContext Outcome.Blocked(context.getString(R.string.blocked_ble_missing_mac))
                }
                // BLE mode does not require SwitchBot cloud credentials.
                return@withContext null
            }

            if (!DoorPrefs.isConfigured(context)) {
                return@withContext Outcome.Blocked(context.getString(R.string.blocked_not_configured))
            }

            DoorPrefs.getToken(context)
            DoorPrefs.getSecret(context)
            DoorPrefs.getDeviceId(context)
        } catch (_: Exception) {
            return@withContext Outcome.Blocked(context.getString(R.string.blocked_prefs))
        }
        null
    }

    /**
     * Full door open flow with Seam August integration:
     * 1. Check if Seam enabled -> check lock status
     * 2. If offline -> fail
     * 3. If locked -> send unlock, poll for success
     * 4. Verify unlocked status
     * 5. Only then trigger SwitchBot
     */
    suspend fun commitPress(context: Context): PressOutcome = withContext(Dispatchers.Default) {
        markAttempt(context)

        // Step 1: Handle Seam August lock if enabled
        val seamEnabled = DoorPrefs.getSeamEnabled(context)
        if (seamEnabled) {
            val apiKey = DoorPrefs.getSeamApiKey(context)
            val deviceId = DoorPrefs.getSeamDeviceId(context)

            if (apiKey.isEmpty() || deviceId.isEmpty()) {
                return@withContext PressOutcome.Failed("Seam: Missing API key or device ID")
            }

            // 1a. Check current lock state
            val deviceResult = withContext(Dispatchers.IO) {
                SeamApi.getDevice(apiKey, deviceId)
            }
            if (!deviceResult.ok) {
                return@withContext PressOutcome.Failed("Seam: ${deviceResult.message}")
            }
            if (deviceResult.online != true) {
                return@withContext PressOutcome.Failed("August lock is offline")
            }

            // 1b. Unlock if needed
            if (deviceResult.locked == true) {
                val unlockResult = withContext(Dispatchers.IO) {
                    SeamApi.unlockDoor(apiKey, deviceId)
                }
                if (!unlockResult.success) {
                    return@withContext PressOutcome.Failed("Seam unlock: ${unlockResult.message}")
                }

                // 1c. Poll for unlock confirmation (max 10 seconds)
                var unlockedConfirmed = false
                val actionId = unlockResult.actionAttemptId
                if (actionId != null) {
                    repeat(10) { attempt ->
                        kotlinx.coroutines.delay(1000)
                        val pollResult = withContext(Dispatchers.IO) {
                            SeamApi.getActionAttempt(apiKey, actionId)
                        }
                        if (pollResult.success) {
                            unlockedConfirmed = true
                            return@repeat
                        }
                        // Continue polling unless explicitly failed
                    }
                }

                // 1d. Final verification by checking device state
                if (!unlockedConfirmed) {
                    val verifyResult = withContext(Dispatchers.IO) {
                        SeamApi.getDevice(apiKey, deviceId)
                    }
                    if (!verifyResult.ok || verifyResult.locked != false) {
                        return@withContext PressOutcome.Failed("Lock did not confirm unlock. Door opener was not triggered.")
                    }
                }
            }
        }

        // Step 2: Press SwitchBot to open door
        try {
            val bleEnabled = DoorPrefs.getBleEnabled(context)
            if (bleEnabled) {
                val mac = DoorPrefs.getBleMac(context)
                val password = DoorPrefs.getBlePassword(context)
                val r = withContext(Dispatchers.IO) { SwitchBotBle.press(context, mac, password) }
                return@withContext if (r.ok) PressOutcome.Success else PressOutcome.Failed(r.message)
            }

            val token = DoorPrefs.getToken(context)
            val secret = DoorPrefs.getSecret(context)
            val deviceId = DoorPrefs.getDeviceId(context)
            val api = withContext(Dispatchers.IO) {
                SwitchBotApi.pressBot(token, secret, deviceId)
            }
            return@withContext if (api.ok) PressOutcome.Success else PressOutcome.Failed(api.message)
        } catch (_: Exception) {
            return@withContext PressOutcome.Failed(context.getString(R.string.blocked_prefs))
        }
    }

    /** Test Seam connection */
    suspend fun testSeam(context: Context): SeamOutcome = withContext(Dispatchers.IO) {
        try {
            if (!DoorPrefs.getSeamEnabled(context)) {
                return@withContext SeamOutcome.NotConfigured
            }
            val apiKey = DoorPrefs.getSeamApiKey(context)
            if (apiKey.isEmpty()) {
                return@withContext SeamOutcome.Failed("Missing Seam API key")
            }
            val result = SeamApi.testConnection(apiKey)
            if (result.ok) SeamOutcome.Success else SeamOutcome.Failed(result.message)
        } catch (e: Exception) {
            SeamOutcome.Failed(e.message ?: "Seam test failed")
        }
    }
}
