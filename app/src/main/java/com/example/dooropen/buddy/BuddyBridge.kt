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
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BuddyBridge: phone-side WebSocket client that connects to Windows Buddy server.
 *
 * Uses a raw TCP + HTTP Upgrade WebSocket handshake so we need no external library.
 * Reconnects automatically every 5 seconds if disconnected.
 *
 * Messages from Windows → Phone:
 *   { "type": "OPEN_DOOR" }
 *   { "type": "MODE_ACK", "mode": "phone" }
 *   { "type": "PING" }
 *
 * Messages Phone → Windows:
 *   { "type": "STATUS", "mode": "phone"|"computer", "doorReady": true, "version": "1" }
 *   { "type": "MODE_SWITCH", "target": "computer"|"phone" }
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

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: OutputStreamWriter? = null
    @Volatile private var currentMode = "phone"
    @Volatile var isConnected = false
        private set

    var onModeSwitch: ((String) -> Unit)? = null
    var onDoorCommand: (() -> Unit)? = null

    fun start(context: Context) {
        if (running.getAndSet(true)) return
        scope.launch { connectLoop(context) }
    }

    fun stop() {
        running.set(false)
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
        sendJson(JSONObject().apply {
            put("type", "MODE_SWITCH")
            put("target", target)
        })
        currentMode = target
    }

    private fun sendJson(json: JSONObject) {
        scope.launch {
            try {
                val w = writer ?: return@launch
                val frame = encodeWebSocketFrame(json.toString())
                w.write(frame)
                w.flush()
            } catch (_: Exception) {}
        }
    }

    private fun sendStatusLoop(context: Context) {
        if (!running.get()) return
        sendJson(JSONObject().apply {
            put("type", "STATUS")
            put("mode", currentMode)
            put("doorReady", try { DoorPrefs.getBuddyEnabled(context) } catch (_: Exception) { false })
            put("version", "1")
        })
        mainHandler.postDelayed({ sendStatusLoop(context) }, STATUS_INTERVAL_MS)
    }

    private suspend fun connectLoop(context: Context) {
        while (running.get()) {
            val host = try { DoorPrefs.getBridgeHost(context) } catch (_: Exception) { "" }
            if (host.isBlank()) {
                kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
                continue
            }
            try {
                connect(context, host)
            } catch (_: Exception) {}
            isConnected = false
            if (running.get()) {
                kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private fun connect(context: Context, host: String) {
        val sock = Socket()
        sock.connect(InetSocketAddress(host, PORT), 4000)
        sock.soTimeout = 60_000
        socket = sock

        val out = OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8)
        writer = out

        // WebSocket HTTP upgrade handshake
        val handshake = buildString {
            append("GET / HTTP/1.1\r\n")
            append("Host: $host:$PORT\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        out.write(handshake)
        out.flush()

        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        // Read HTTP response headers
        var line = reader.readLine()
        while (!line.isNullOrBlank()) {
            line = reader.readLine()
        }

        isConnected = true
        mainHandler.post {
            DoorFeedback.speak(context, "Computer connected.")
            sendStatusLoop(context)
        }

        // Send initial status
        sendJson(JSONObject().apply {
            put("type", "STATUS")
            put("mode", currentMode)
            put("doorReady", true)
            put("version", "1")
        })

        // Read loop — raw WebSocket frame parsing
        val rawIn = sock.getInputStream()
        while (running.get() && !sock.isClosed) {
            val msgText = readWebSocketFrame(rawIn) ?: break
            handleMessage(context, msgText)
        }
        closeSocket()
    }

    private fun handleMessage(context: Context, text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "OPEN_DOOR" -> {
                    mainHandler.post {
                        DoorFeedback.buddyHeard(context)
                        scope.launch {
                            BuddyCommandProcessor.handle(context)
                        }
                    }
                    onDoorCommand?.invoke()
                }
                "MODE_ACK" -> {
                    val mode = json.optString("mode", currentMode)
                    mainHandler.post {
                        DoorFeedback.speak(context, if (mode == "computer") "Computer mode." else "Phone mode.")
                    }
                }
                "PING" -> {
                    sendJson(JSONObject().apply { put("type", "PONG") })
                }
                "SPEAK" -> {
                    val msg = json.optString("message", "")
                    if (msg.isNotBlank()) mainHandler.post { DoorFeedback.speak(context, msg) }
                }
            }
        } catch (_: Exception) {}
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
        isConnected = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    // --- Minimal WebSocket frame encoder (client→server, masked) ---
    private fun encodeWebSocketFrame(text: String): String {
        val payload = text.toByteArray(Charsets.UTF_8)
        val len = payload.size
        val mask = byteArrayOf(0x37, 0x7A, 0x12, 0x4C)
        val frame = mutableListOf<Byte>()
        frame.add(0x81.toByte()) // FIN + text opcode
        when {
            len <= 125 -> frame.add((0x80 or len).toByte())
            len <= 65535 -> {
                frame.add((0x80 or 126).toByte())
                frame.add((len shr 8).toByte())
                frame.add((len and 0xFF).toByte())
            }
            else -> {
                frame.add((0x80 or 127).toByte())
                for (i in 7 downTo 0) frame.add(((len shr (i * 8)) and 0xFF).toByte())
            }
        }
        frame.addAll(mask.toList())
        payload.forEachIndexed { i, b -> frame.add((b.toInt() xor mask[i % 4].toInt()).toByte()) }
        return String(frame.toByteArray(), Charsets.ISO_8859_1)
    }

    // --- Minimal WebSocket frame decoder (server→client, unmasked) ---
    private fun readWebSocketFrame(stream: java.io.InputStream): String? {
        fun readByte() = stream.read().also { if (it == -1) throw java.io.EOFException() }.toByte()
        val b0 = readByte().toInt() and 0xFF
        val b1 = readByte().toInt() and 0xFF
        val opcode = b0 and 0x0F
        if (opcode == 0x8) return null // close frame
        if (opcode == 0x9) { // ping — read payload and ignore
            val pLen = b1 and 0x7F
            repeat(pLen) { readByte() }
            return readWebSocketFrame(stream)
        }
        var payloadLen = (b1 and 0x7F).toLong()
        if (payloadLen == 126L) {
            payloadLen = ((readByte().toInt() and 0xFF shl 8) or (readByte().toInt() and 0xFF)).toLong()
        } else if (payloadLen == 127L) {
            payloadLen = 0L
            repeat(8) { payloadLen = (payloadLen shl 8) or (readByte().toInt() and 0xFF).toLong() }
        }
        val payload = ByteArray(payloadLen.toInt())
        var offset = 0
        while (offset < payload.size) {
            val n = stream.read(payload, offset, payload.size - offset)
            if (n == -1) throw java.io.EOFException()
            offset += n
        }
        return String(payload, Charsets.UTF_8)
    }
}
