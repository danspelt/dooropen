package com.example.dooropen.domain

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.dooropen.data.DoorPrefs
import java.util.Locale
import java.util.UUID

object DoorFeedback {

    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var pendingTts = mutableListOf<Pair<String, () -> Unit>>()

    fun initTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsInitialized = true
                // Play any pending utterances
                pendingTts.forEach { (text, onDone) ->
                    speakInternal(text, onDone)
                }
                pendingTts.clear()
            }
        }
    }

    private fun speak(context: Context, text: String, onDone: () -> Unit = {}) {
        initTts(context)
        if (!ttsInitialized) {
            pendingTts.add(text to onDone)
            return
        }
        speakInternal(text, onDone)
    }

    private fun speakInternal(text: String, onDone: () -> Unit) {
        val utteranceId = UUID.randomUUID().toString()

        // Set up listener for completion before speaking
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(doneId: String?) {
                if (doneId == utteranceId) {
                    onDone()
                }
            }
            override fun onError(errorId: String?) {
                onDone()
            }
        })

        if (Build.VERSION.SDK_INT >= 21) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val params = hashMapOf(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID to utteranceId)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
    }

    fun playOpeningCue(context: Context) {
        val ctx = context.applicationContext
        vibrateShort(ctx, 40L)
        if (DoorPrefs.getSoundEnabled(ctx)) {
            speak(ctx, "Opening door")
        }
        playTone(ctx, ToneGenerator.TONE_PROP_BEEP, 120, 70)
    }

    fun playSuccess(context: Context) {
        val ctx = context.applicationContext
        vibrateShort(ctx, 80L)
        if (DoorPrefs.getSoundEnabled(ctx)) {
            speak(ctx, "Door opened")
        }
        playTone(ctx, ToneGenerator.TONE_PROP_ACK, 160, 80)
    }

    fun playFailure(context: Context, reason: String? = null) {
        val ctx = context.applicationContext
        vibratePattern(ctx, longArrayOf(0, 60, 80, 60))
        if (DoorPrefs.getSoundEnabled(ctx)) {
            val message = if (reason.isNullOrBlank()) "Failed to open door" else "Failed: $reason"
            speak(ctx, message)
        }
        playTone(ctx, ToneGenerator.TONE_PROP_NACK, 220, 80)
    }

    fun playBlockedWarning(context: Context, reason: String? = null) {
        val ctx = context.applicationContext
        vibrateShort(ctx, 55L)
        if (DoorPrefs.getSoundEnabled(ctx)) {
            val message = reason ?: "Cannot open door"
            speak(ctx, message)
        }
    }

    fun speakStatus(context: Context, status: String) {
        if (DoorPrefs.getSoundEnabled(context.applicationContext)) {
            speak(context.applicationContext, status)
        }
    }

    private fun playTone(ctx: Context, toneType: Int, durationMs: Int, volume: Int) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ACCESSIBILITY, volume)
            tone.startTone(toneType, durationMs)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { tone.release() },
                (durationMs + 50).toLong()
            )
        } catch (_: Exception) {
        }
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
