package com.example.dooropen.domain

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.dooropen.data.DoorPrefs

object DoorFeedback {

    fun playOpeningCue(context: Context) {
        val ctx = context.applicationContext
        try {
            if (DoorPrefs.getSoundEnabled(ctx)) {
                val tone = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 70)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { tone.release() },
                    200
                )
            }
        } catch (_: Exception) {
        }
        vibrateShort(ctx, 40L)
    }

    fun playSuccess(context: Context) {
        val ctx = context.applicationContext
        try {
            if (DoorPrefs.getSoundEnabled(ctx)) {
                val tone = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 80)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 160)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { tone.release() },
                    220
                )
            }
        } catch (_: Exception) {
        }
        vibrateShort(ctx, 80L)
    }

    fun playFailure(context: Context) {
        val ctx = context.applicationContext
        try {
            if (DoorPrefs.getSoundEnabled(ctx)) {
                val tone = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, 80)
                tone.startTone(ToneGenerator.TONE_PROP_NACK, 220)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    { tone.release() },
                    280
                )
            }
        } catch (_: Exception) {
        }
        vibratePattern(ctx, longArrayOf(0, 60, 80, 60))
    }

    fun playBlockedWarning(context: Context) {
        vibrateShort(context.applicationContext, 55L)
    }

    private fun vibrateShort(context: Context, durationMs: Long) {
        if (!shouldVibrate(context)) return
        val v = vibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(durationMs)
            }
        } catch (_: Exception) {
        }
    }

    private fun vibratePattern(context: Context, pattern: LongArray) {
        if (!shouldVibrate(context)) return
        val v = vibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    private fun shouldVibrate(context: Context): Boolean = try {
        DoorPrefs.getVibrationEnabled(context.applicationContext)
    } catch (_: Exception) {
        true
    }

    private fun vibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= 31) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }
}
