package com.example.dooropen.buddy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.example.dooropen.MainActivity
import com.example.dooropen.R
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.domain.DoorFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BuddyVoiceService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var shouldRestart = true

    /**
     * True only when the user explicitly turned Buddy off (ACTION_STOP).
     * On transient service recreation (Android killing/restarting the foreground
     * service) we must NOT tear down the WebSocket bridge, or the phone keeps
     * dropping and reconnecting in a loop.
     */
    private var userStopped = false

    /** true while Buddy is in an active chat conversation */
    private var inConversation = false
    /** timeout to exit conversation if user goes quiet */
    private var conversationTimeoutRunnable: Runnable? = null
    private val CONVERSATION_TIMEOUT_MS = 30_000L

    private val GREETING_WORDS = listOf(
        "hi buddy", "hey buddy", "hello buddy", "ok buddy", "okay buddy",
        "hi body", "hey body", "hello body", "hi bud", "hey bud"
    )
    // Computer mode — broad matching for CP speech variants
    private val MODE_COMPUTER = listOf(
        "buddy computer", "buddy, computer",
        "body computer", "but he computer",
        "headset computer", "headset to computer",
        "switch computer", "switch to computer",
        "move computer", "move to computer",
        "computer mode", "use computer",
        "buddy headset computer", "buddy switch computer"
    )
    // Phone mode — broad matching for CP speech variants
    private val MODE_PHONE = listOf(
        "buddy phone", "buddy, phone",
        "body phone", "but he phone",
        "headset phone", "headset to phone",
        "switch phone", "switch to phone",
        "move phone", "move to phone",
        "phone mode", "use phone",
        "buddy headset phone", "buddy switch phone"
    )
    private val CALL_PREFIX = listOf("buddy call", "buddy, call", "body call", "call")
    private val HANG_UP = listOf(
        "buddy hang up", "buddy, hang up",
        "hang up", "hangup", "end call", "buddy end call",
        "body hang up", "buddy hang"
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Buddy is listening\u2026"))
        DoorFeedback.initTts(this)
        BuddyBridge.start(this)
        startListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                userStopped = true
                shouldRestart = false
                BuddyBridge.stop()
                DoorFeedback.shutdown()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shouldRestart = false
        stopRecognizer()
        cancelConversationTimeout()
        // Only tear down the bridge/TTS when the user explicitly stopped Buddy.
        // For transient restarts, keep the WebSocket connection alive so the
        // phone does not enter a connect/disconnect loop. BuddyBridge is a
        // process-scoped singleton and survives service recreation.
        if (userStopped) {
            BuddyBridge.stop()
            DoorFeedback.shutdown()
        }
        scope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                updateNotification("Speech recognition not available")
                return@post
            }
            stopRecognizer()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(buddyListener)
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            recognizer?.startListening(intent)
            isListening = true
        }
    }

    private fun stopRecognizer() {
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        isListening = false
    }

    private fun scheduleRestart(delayMs: Long = 800L) {
        if (!shouldRestart) return
        mainHandler.postDelayed({ startListening() }, delayMs)
    }

    private fun isGreeting(text: String): Boolean {
        val lower = text.lowercase().trim()
        return GREETING_WORDS.any { lower.contains(it) }
    }

    private fun isModeSwitch(text: String, target: String): Boolean {
        val lower = text.lowercase().trim()
        val list = if (target == "computer") MODE_COMPUTER else MODE_PHONE
        return list.any { lower.contains(it) }
    }

    private fun isHangUp(text: String): Boolean {
        val lower = text.lowercase().trim()
        return HANG_UP.any { lower.contains(it) }
    }

    private fun parseCallContact(text: String): String? {
        val lower = text.lowercase().trim()
        for (prefix in CALL_PREFIX) {
            if (!lower.contains(prefix)) continue
            val name = lower.substringAfter(prefix).trim().trim(',', '.')
            if (name.isNotBlank()) return name.replaceFirstChar { it.titlecase() }
        }
        return null
    }

    private fun handleModeSwitch(target: String) {
        stopRecognizer()
        exitConversation()
        BuddyBridge.sendHeadsetSwitch(target, applicationContext)
        scope.launch {
            val msg = if (target == "computer") "Switching to computer." else "Phone mode."
            DoorFeedback.speak(applicationContext, msg)
            updateNotification(if (target == "computer") "Computer mode" else "Phone mode")
            mainHandler.postDelayed({ scheduleRestart(1200L) }, 2500L)
        }
    }

    private fun handleCallContact(contactName: String) {
        stopRecognizer()
        exitConversation()
        scope.launch {
            BuddyPhoneActions.callContact(applicationContext, contactName)
            mainHandler.postDelayed({ scheduleRestart(1200L) }, 4000L)
        }
    }

    private fun handleHangUp() {
        stopRecognizer()
        exitConversation()
        scope.launch {
            BuddyPhoneActions.hangUp(applicationContext)
            mainHandler.postDelayed({ scheduleRestart(1200L) }, 3000L)
        }
    }

    private fun enterConversation() {
        inConversation = true
        resetConversationTimeout()
        updateNotification("Buddy is chatting with you\u2026")
    }

    private fun exitConversation() {
        inConversation = false
        cancelConversationTimeout()
        updateNotification("Buddy is listening\u2026")
    }

    private fun resetConversationTimeout() {
        cancelConversationTimeout()
        val r = Runnable { exitConversation() }
        conversationTimeoutRunnable = r
        mainHandler.postDelayed(r, CONVERSATION_TIMEOUT_MS)
    }

    private fun cancelConversationTimeout() {
        conversationTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        conversationTimeoutRunnable = null
    }

    private val buddyListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            val status = if (inConversation) "Buddy is chatting\u2026" else "Buddy is listening\u2026"
            updateNotification(status)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            // Quick door command match on partials only in non-conversation mode
            if (!inConversation && BuddyCommandProcessor.isCommandMatch(partial)) {
                handleDoorCommand(partial)
            }
        }

        override fun onResults(results: Bundle?) {
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?: emptyList<String>()
            val best = texts.firstOrNull() ?: run { scheduleRestart(); return }

            when {
                // Mode switching — highest priority
                isModeSwitch(best, "computer") -> handleModeSwitch("computer")
                isModeSwitch(best, "phone") -> handleModeSwitch("phone")
                isHangUp(best) -> handleHangUp()
                parseCallContact(best) != null -> handleCallContact(parseCallContact(best)!!)

                // Always handle "Buddy, open door" regardless of conversation state
                BuddyCommandProcessor.isCommandMatch(best) -> handleDoorCommand(best)

                // Greeting — enter conversation mode
                isGreeting(best) -> handleGreeting()

                // In conversation — pass to AI
                inConversation -> handleChat(best)

                // Idle, no match — keep listening silently
                else -> scheduleRestart()
            }
        }

        override fun onError(error: Int) {
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_AUDIO,
                SpeechRecognizer.ERROR_SERVER -> 2000L
                else -> 800L
            }
            scheduleRestart(delay)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleDoorCommand(text: String) {
        stopRecognizer()
        updateNotification("Opening door\u2026")
        scope.launch {
            BuddyCommandProcessor.handle(applicationContext)
            val resumeDelay = if (inConversation) 5000L else 4000L
            mainHandler.postDelayed({ scheduleRestart(800L) }, resumeDelay)
            if (inConversation) resetConversationTimeout()
        }
    }

    private fun handleGreeting() {
        stopRecognizer()
        enterConversation()
        scope.launch {
            DoorFeedback.speak(applicationContext, "Hello! What can I do for you?")
            mainHandler.postDelayed({ scheduleRestart(1500L) }, 2500L)
        }
    }

    private fun handleChat(userText: String) {
        stopRecognizer()
        resetConversationTimeout()
        updateNotification("Thinking\u2026")
        scope.launch {
            // Check if it's a door request first
            if (BuddyAI.isDoorRequest(userText)) {
                DoorFeedback.buddyHeard(applicationContext)
                DoorFeedback.buddySpeakOpening(applicationContext)
                BuddyCommandProcessor.handle(applicationContext)
                mainHandler.postDelayed({ scheduleRestart(800L) }, 5000L)
                return@launch
            }

            val reply = BuddyAI.chat(applicationContext, userText)
            DoorFeedback.speak(applicationContext, reply.text)

            // Resume listening after TTS finishes (estimate ~200ms per word + buffer)
            val wordCount = reply.text.split(" ").size
            val ttsMs = (wordCount * 220L).coerceIn(1500L, 8000L)
            mainHandler.postDelayed({ scheduleRestart(600L) }, ttsMs + 800L)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Buddy Voice Assist",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Listens for \"Buddy, open door\" to open the door hands-free"
            setShowBadge(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Buddy")
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        const val CHANNEL_ID = "buddy_voice"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.example.dooropen.BUDDY_STOP"

        fun start(context: Context) {
            if (!DoorPrefs.getBuddyEnabled(context)) return
            val intent = Intent(context, BuddyVoiceService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BuddyVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            try { context.startService(intent) } catch (_: Exception) {}
        }
    }
}
