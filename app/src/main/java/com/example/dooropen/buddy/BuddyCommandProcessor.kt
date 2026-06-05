package com.example.dooropen.buddy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.domain.DoorCommand
import com.example.dooropen.domain.DoorFeedback

object BuddyCommandProcessor {

    private const val COOLDOWN_MS = 10_000L
    private var lastCommandTime = 0L

    private val WAKE_WORDS = listOf("buddy")
    private val DOOR_WORDS = listOf("door")
    private val OPEN_WORDS = listOf("open", "unlock", "let me in")

    fun isCommandMatch(rawText: String): Boolean {
        val text = rawText.lowercase().trim()
        val hasWake = WAKE_WORDS.any { text.contains(it) }
        val hasDoor = DOOR_WORDS.any { text.contains(it) }
        val hasOpen = OPEN_WORDS.any { text.contains(it) }
        return hasWake && hasDoor && hasOpen
    }

    fun isOnCooldown(): Boolean {
        val elapsed = System.currentTimeMillis() - lastCommandTime
        return lastCommandTime > 0L && elapsed < COOLDOWN_MS
    }

    private fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun handle(context: Context) {
        val ctx = context.applicationContext

        if (!DoorPrefs.getBuddyEnabled(ctx)) {
            DoorFeedback.buddySpeakFailure(ctx, DoorFeedback.BuddyFailReason.ASSIST_OFF)
            return
        }

        if (isOnCooldown()) {
            DoorFeedback.buddySpeakCooldown(ctx)
            return
        }

        // Heard tone + immediate "Opening door." — do NOT wait for command
        DoorFeedback.buddyHeard(ctx)
        DoorFeedback.buddySpeakOpening(ctx)

        // Check safety/config before sending
        val blockedOutcome = DoorCommand.evaluate(ctx, skipCooldown = true)
        if (blockedOutcome != null) {
            val reason = when {
                blockedOutcome.message.contains("offline", ignoreCase = true) ||
                blockedOutcome.message.contains("internet", ignoreCase = true) ->
                    DoorFeedback.BuddyFailReason.OFFLINE
                else -> DoorFeedback.BuddyFailReason.NOT_READY
            }
            DoorFeedback.buddySpeakFailure(ctx, reason)
            return
        }

        if (!isOnline(ctx) && !DoorPrefs.getBleEnabled(ctx)) {
            DoorFeedback.buddySpeakFailure(ctx, DoorFeedback.BuddyFailReason.OFFLINE)
            return
        }

        lastCommandTime = System.currentTimeMillis()

        val pressResult = DoorCommand.commitPress(ctx)
        if (pressResult is DoorCommand.PressOutcome.Success) {
            DoorFeedback.buddySpeakCommandSent(ctx)
        } else if (pressResult is DoorCommand.PressOutcome.Failed) {
            lastCommandTime = 0L
            val reason = when {
                pressResult.message.contains("timeout", ignoreCase = true) ->
                    DoorFeedback.BuddyFailReason.TIMEOUT
                pressResult.message.contains("offline", ignoreCase = true) ||
                pressResult.message.contains("internet", ignoreCase = true) ->
                    DoorFeedback.BuddyFailReason.OFFLINE
                else -> DoorFeedback.BuddyFailReason.UNREACHABLE
            }
            DoorFeedback.buddySpeakFailure(ctx, reason)
        }
    }
}
