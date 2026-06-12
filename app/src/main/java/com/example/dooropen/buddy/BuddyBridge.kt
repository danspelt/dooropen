package com.example.dooropen.buddy

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.domain.DoorFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BuddyBridge: phone-side WebSocket client that connects to Windows Buddy server.
 *
 * Messages from Windows → Phone:
 *   { "type": "OPEN_DOOR" }
 *   { "type": "MODE_ACK", "mode": "phone"|"computer" }
 *   { "type": "PHONE_CALL", "action": "call", "contactName": "Dad" }
 *   { "type": "PHONE_CALL", "action": "hangup" }
 *   { "type": "SPEAK", "message": "..." }
 *   { "type": "PING" }
 *
 * Messages Phone → Windows:
 *   { "type": "STATUS", "mode": "phone"|"computer", "doorReady": true, "version": "1" }
 *   { "type": "HEADSET_SWITCH", "target": "computer"|"phone" }
 *   { "type": "MODE_SWITCH", "target": "computer"|"phone" }  // alias
 *   { "type": "ACK", "message": "..." }
 *   { "type": "PONG" }
 */
object BuddyBridge {

    private const val PORT = 8765
    private const val RECONNECT_DELAY_MS = 5_000L
    private const val STATUS_INTERVAL_MS = 30_000L

    private val running = AtomicBoolean(false)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var currentMode = "phone"
    @Volatile private var hasAnnouncedConnect = false
    @Volatile var isConnected = false
        private set

    var onModeSwitch: ((String) -> Unit)? = null
    var onDoorCommand: (() -> Unit)? = null

    fun start(context: Context) {
        if (running.getAndSet(true)) return
        scope.launch { connectLoop(context.applicationContext) }
    }

    fun stop() {
        running.set(false)
        hasAnnouncedConnect = false
        closeSocket()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun setMode(mode: String) {
        currentMode = mode
        sendJson(JSONObject().apply {
            put("type", "STATUS")
            put("mode", mode)
        })
    }

    fun sendModeSwitch(target: String, context: Context) {
        sendHeadsetSwitch(target, context)
    }

    fun sendHeadsetSwitch(target: String, context: Context) {
        sendJson(JSONObject().apply {
            put("type", "HEADSET_SWITCH")
            put("target", target)
        })
        currentMode = target
    }

    fun getCurrentMode(): String = currentMode

    /** Phone UI buttons — tells Windows to release or grab the headset. */
    fun requestHeadsetSwitch(target: String) {
        if (!isConnected) return
        sendJson(JSONObject().apply {
            put("type", "HEADSET_SWITCH")
            put("target", target)
        })
        currentMode = target
    }

    private fun sendJson(json: JSONObject) {
        val ws = webSocket ?: return
        if (!isConnected) return
        ws.send(json.toString())
    }

    private fun sendStatus(context: Context) {
        sendJson(JSONObject().apply {
            put("type", "STATUS")
            put("mode", currentMode)
            put("doorReady", try { DoorPrefs.getBuddyEnabled(context) } catch (_: Exception) { false })
            put("version", "1")
        })
    }

    private suspend fun connectLoop(context: Context) {
        while (running.get()) {
            val host = try { DoorPrefs.getBridgeHost(context) } catch (_: Exception) { "" }
            if (host.isBlank()) {
                delay(RECONNECT_DELAY_MS)
                continue
            }
            try {
                connect(context, host)
            } catch (_: Exception) {}
            isConnected = false
            if (running.get()) {
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun connect(context: Context, host: String) {
        val request = Request.Builder()
            .url("ws://$host:$PORT/")
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                BuddyBridge.webSocket = webSocket
                isConnected = true
                if (!hasAnnouncedConnect) {
                    hasAnnouncedConnect = true
                    mainHandler.post { DoorFeedback.speak(context, "Computer connected.") }
                }
                sendStatus(context)
                scheduleStatusLoop(context)
                latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(context, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (BuddyBridge.webSocket === webSocket) {
                    isConnected = false
                    BuddyBridge.webSocket = null
                }
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (BuddyBridge.webSocket === webSocket) {
                    isConnected = false
                    BuddyBridge.webSocket = null
                }
                latch.countDown()
            }
        })

        latch.await()
        while (running.get() && isConnected) {
            delay(1_000)
        }

        try { ws.cancel() } catch (_: Exception) {}
        if (webSocket === ws) {
            webSocket = null
            isConnected = false
        }
    }

    private fun scheduleStatusLoop(context: Context) {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!running.get() || !isConnected) return
                sendStatus(context)
                mainHandler.postDelayed(this, STATUS_INTERVAL_MS)
            }
        }, STATUS_INTERVAL_MS)
    }

    private fun handleMessage(context: Context, text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "OPEN_DOOR" -> {
                    mainHandler.post {
                        DoorFeedback.buddyHeard(context)
                        scope.launch { BuddyCommandProcessor.handle(context) }
                    }
                    onDoorCommand?.invoke()
                }
                "MODE_ACK" -> {
                    val mode = json.optString("mode", currentMode)
                    currentMode = mode
                    mainHandler.post {
                        DoorFeedback.speak(
                            context,
                            if (mode == "computer") "Computer mode." else "Phone mode."
                        )
                    }
                }
                "PING" -> sendJson(JSONObject().apply { put("type", "PONG") })
                "SPEAK" -> {
                    val msg = json.optString("message", "")
                    if (msg.isNotBlank()) mainHandler.post { DoorFeedback.speak(context, msg) }
                }
                "PHONE_CALL" -> {
                    val action = json.optString("action", "")
                    mainHandler.post {
                        when (action) {
                            "call" -> scope.launch {
                                BuddyPhoneActions.callContact(context, json.optString("contactName", ""))
                            }
                            "hangup" -> scope.launch { BuddyPhoneActions.hangUp(context) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun closeSocket() {
        try { webSocket?.close(1000, "bye") } catch (_: Exception) {}
        try { webSocket?.cancel() } catch (_: Exception) {}
        webSocket = null
        isConnected = false
    }
}
