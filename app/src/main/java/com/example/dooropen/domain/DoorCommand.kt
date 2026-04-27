package com.example.dooropen.domain

import android.content.Context
import com.example.dooropen.R
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.data.SwitchBotApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DoorCommand {

    private const val PREFS = "door_assist_timing"
    private const val KEY_LAST_ELAPSED = "last_open_elapsed_realtime"
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

    private fun cooldownRemainingMs(context: Context): Long {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_LAST_ELAPSED, 0L)
        val elapsed = android.os.SystemClock.elapsedRealtime() - last
        return (COOLDOWN_MS - elapsed).coerceAtLeast(0L)
    }

    private fun markAttempt(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_ELAPSED, android.os.SystemClock.elapsedRealtime())
            .apply()
    }

    /** Safety, cooldown, and credentials — before "door opening" feedback. */
    suspend fun evaluate(context: Context): Outcome.Blocked? = withContext(Dispatchers.Default) {
        DoorSafety.blockReason(context)?.let { return@withContext Outcome.Blocked(it) }
        val remaining = cooldownRemainingMs(context)
        if (remaining > 0L) {
            val sec = ((remaining + 999) / 1000).toInt()
            return@withContext Outcome.Blocked(context.getString(R.string.blocked_cooldown, sec))
        }
        if (!DoorPrefs.isConfigured(context)) {
            return@withContext Outcome.Blocked(context.getString(R.string.blocked_not_configured))
        }
        try {
            DoorPrefs.getToken(context)
            DoorPrefs.getSecret(context)
            DoorPrefs.getDeviceId(context)
        } catch (_: Exception) {
            return@withContext Outcome.Blocked(context.getString(R.string.blocked_prefs))
        }
        null
    }

    /** Records cooldown and sends SwitchBot press. Call only after [evaluate] returned null. */
    suspend fun commitPress(context: Context): PressOutcome = withContext(Dispatchers.Default) {
        markAttempt(context)
        val token: String
        val secret: String
        val deviceId: String
        try {
            token = DoorPrefs.getToken(context)
            secret = DoorPrefs.getSecret(context)
            deviceId = DoorPrefs.getDeviceId(context)
        } catch (_: Exception) {
            return@withContext PressOutcome.Failed(context.getString(R.string.blocked_prefs))
        }
        val api = withContext(Dispatchers.IO) {
            SwitchBotApi.pressBot(token, secret, deviceId)
        }
        if (api.ok) PressOutcome.Success else PressOutcome.Failed(api.message)
    }
}
