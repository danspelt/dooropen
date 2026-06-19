package com.example.dooropen.buddy

import android.content.Context
import com.example.dooropen.data.DoorPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object BuddyAI {

    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4o"
    private const val MAX_TOKENS = 120
    private const val TIMEOUT_MS = 10_000
    private const val MAX_HISTORY = 10  // keep last 10 turns (5 exchanges)

    private val SYSTEM_PROMPT = """
        You are Buddy — Dan's personal AI assistant, voice-activated and always with him.
        
        About Dan:
        - Dan uses a power wheelchair full-time due to a physical disability.
        - He lives independently and values being in control of his own life.
        - He is sharp, direct, and has a good sense of humor.
        - He uses voice commands for almost everything — typing is difficult for him.
        - His home has a smart front door that you can open for him.
        - His Windows computer is your main brain; his Android phone is your mobile helper.
        - You travel with him on his phone. When he's at his desk, you run on his computer.
        
        Your personality:
        - You are warm, efficient, and a little bit of a friend — not just a tool.
        - You speak in short, natural sentences — like a real person talking, not a robot.
        - Never use bullet points, lists, markdown, or say "Certainly!" or "Of course!".
        - Get to the point fast. Dan doesn't want long explanations.
        - If Dan seems frustrated, acknowledge it briefly and help him fix it.
        - You remember what was said earlier in this conversation.
        
        Your capabilities:
        - Open the front door for Dan.
        - Make phone calls and hang up.
        - Answer any question using your knowledge.
        - Switch between computer mode and phone mode.
        - Keep Dan company and have real conversations with him.
        
        Never mention API keys, code, technical details, or that you are an AI unless asked.
    """.trimIndent()

    data class Reply(val text: String, val shouldOpenDoor: Boolean)

    private val DOOR_PHRASES = listOf(
        "open the door", "open door", "let me in", "unlock the door", "unlock door"
    )

    /** Rolling conversation history — survives across turns within a session */
    private val history = ArrayDeque<Pair<String, String>>()  // role → content

    fun isDoorRequest(text: String): Boolean {
        val lower = text.lowercase()
        return DOOR_PHRASES.any { lower.contains(it) }
    }

    fun clearHistory() { history.clear() }

    suspend fun chat(context: Context, userMessage: String): Reply = withContext(Dispatchers.IO) {
        val apiKey = try { DoorPrefs.getOpenAiKey(context) } catch (_: Exception) { "" }
        if (apiKey.isBlank()) {
            return@withContext Reply("I don't have an AI key yet. Go to Settings and add your OpenAI key.", false)
        }

        val doorRequest = isDoorRequest(userMessage)
        val messageForAI = if (doorRequest) {
            "The user said: \"$userMessage\" — they want the door opened. Reply briefly confirming you are opening it now."
        } else {
            userMessage
        }

        // Add user turn to history
        history.addLast("user" to messageForAI)
        // Trim to max window
        while (history.size > MAX_HISTORY) history.removeFirst()

        try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                for ((role, content) in history) {
                    put(JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    })
                }
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                put("messages", messages)
            }.toString()

            val url = URL(ENDPOINT)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val responseCode = conn.responseCode
            val stream = if (responseCode == 200) conn.inputStream else conn.errorStream
            val raw = stream.bufferedReader().readText()

            if (responseCode != 200) {
                history.removeLastOrNull()
                return@withContext Reply("Sorry, I had trouble thinking of a reply.", doorRequest)
            }

            val text = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            // Add assistant reply to history
            history.addLast("assistant" to text)
            while (history.size > MAX_HISTORY) history.removeFirst()

            Reply(text, doorRequest)
        } catch (_: Exception) {
            history.removeLastOrNull()
            Reply("Sorry, I couldn't connect right now.", doorRequest)
        }
    }
}
