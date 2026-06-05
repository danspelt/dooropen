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
    private const val MODEL = "gpt-4o-mini"
    private const val MAX_TOKENS = 80
    private const val TIMEOUT_MS = 8000

    private val SYSTEM_PROMPT = """
        You are Buddy, a friendly voice assistant built into a door-opening app for someone who uses a power wheelchair.
        You are helpful, warm, and concise — always reply in one or two short spoken sentences, no bullet points or lists.
        You can open the front door when asked. If the user asks you to open the door, confirm you are doing it.
        Never mention API keys, code, or technical details. Speak naturally as if you are talking, not texting.
    """.trimIndent()

    data class Reply(val text: String, val shouldOpenDoor: Boolean)

    private val DOOR_PHRASES = listOf(
        "open the door", "open door", "let me in", "unlock the door", "unlock door"
    )

    fun isDoorRequest(text: String): Boolean {
        val lower = text.lowercase()
        return DOOR_PHRASES.any { lower.contains(it) }
    }

    suspend fun chat(context: Context, userMessage: String): Reply = withContext(Dispatchers.IO) {
        val apiKey = try { DoorPrefs.getOpenAiKey(context) } catch (_: Exception) { "" }
        if (apiKey.isBlank()) {
            return@withContext Reply("I don't have an AI key set up yet. Go to Settings and add your OpenAI key.", false)
        }

        val doorRequest = isDoorRequest(userMessage)
        val messageForAI = if (doorRequest) {
            "The user said: \"$userMessage\" — they want the door opened. Reply briefly confirming you are opening it now."
        } else {
            userMessage
        }

        try {
            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", messageForAI)
                    })
                })
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
                return@withContext Reply("Sorry, I had trouble thinking of a reply.", doorRequest)
            }

            val text = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            Reply(text, doorRequest)
        } catch (_: Exception) {
            Reply("Sorry, I couldn't connect right now.", doorRequest)
        }
    }
}
