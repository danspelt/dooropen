package com.example.dooropen.data

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Seam API client for August Smart Lock integration.
 * Official API: https://docs.seam.co/latest/device-and-system-integration-guides/august-locks
 *
 * Flow:
 * 1. Check lock online status
 * 2. If locked, call unlockDoor -> returns action_attempt_id
 * 3. Poll action_attempt until success/failure
 * 4. Verify lock.properties.locked == false
 * 5. Only then trigger physical door opener
 */
object SeamApi {

    private const val API_BASE = "https://connect.seam.co"

    data class SeamResult(val ok: Boolean, val message: String, val locked: Boolean? = null, val online: Boolean? = null)
    data class ActionAttemptResult(val success: Boolean, val message: String, val actionAttemptId: String? = null)

    private fun apiHeaders(apiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $apiKey",
        "Content-Type" to "application/json",
        "Accept" to "application/json",
    )

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        } catch (_: Exception) {
            null
        } ?: return ""
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun parseSeamError(body: String): String {
        if (body.isBlank()) return "Unknown error"
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            error?.optString("message", "")
                ?: json.optString("message", "")
                ?: json.optString("error", "")
                ?: "Unknown error"
        } catch (_: Exception) {
            body.take(200)
        }
    }

    /** Get device details including lock state and online status */
    fun getDevice(apiKey: String, deviceId: String): SeamResult {
        if (apiKey.isEmpty() || deviceId.isEmpty()) {
            return SeamResult(false, "Missing Seam API key or device ID")
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$API_BASE/devices/get")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                apiHeaders(apiKey).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val payload = JSONObject().put("device_id", deviceId).toString()
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload) }

            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return SeamResult(false, "Seam error: ${parseSeamError(body)}")
            }
            val json = JSONObject(body)
            val device = json.optJSONObject("device") ?: return SeamResult(false, "No device in response")
            val props = device.optJSONObject("properties") ?: return SeamResult(false, "No properties in device")

            val online = props.optBoolean("online", false)
            val locked = props.optBoolean("locked", false)
            val name = device.optString("name", "Unknown")

            SeamResult(true, name, locked = locked, online = online)
        } catch (e: Exception) {
            SeamResult(false, e.message ?: "Seam API error")
        } finally {
            conn?.disconnect()
        }
    }

    /** Unlock the door - returns action_attempt_id for polling */
    fun unlockDoor(apiKey: String, deviceId: String): ActionAttemptResult {
        if (apiKey.isEmpty() || deviceId.isEmpty()) {
            return ActionAttemptResult(false, "Missing Seam API key or device ID")
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$API_BASE/locks/unlock_door")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                apiHeaders(apiKey).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val payload = JSONObject().put("device_id", deviceId).toString()
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload) }

            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return ActionAttemptResult(false, "Seam unlock error: ${parseSeamError(body)}")
            }
            val json = JSONObject(body)
            val actionAttempt = json.optJSONObject("action_attempt")
                ?: return ActionAttemptResult(false, "No action_attempt in response")
            val actionAttemptId = actionAttempt.optString("action_attempt_id", "")
            val status = actionAttempt.optString("status", "")

            when (status) {
                "success" -> ActionAttemptResult(true, "Unlocked immediately", actionAttemptId)
                "pending", "executing" -> ActionAttemptResult(true, "Unlock in progress", actionAttemptId)
                "failed" -> {
                    val error = actionAttempt.optString("error_message", "Unlock failed")
                    ActionAttemptResult(false, error, actionAttemptId)
                }
                else -> ActionAttemptResult(true, "Unlock status: $status", actionAttemptId)
            }
        } catch (e: Exception) {
            ActionAttemptResult(false, e.message ?: "Unlock request failed")
        } finally {
            conn?.disconnect()
        }
    }

    /** Get action attempt status - poll this until success/failure */
    fun getActionAttempt(apiKey: String, actionAttemptId: String): ActionAttemptResult {
        if (apiKey.isEmpty() || actionAttemptId.isEmpty()) {
            return ActionAttemptResult(false, "Missing API key or action attempt ID")
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$API_BASE/action_attempts/get")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                apiHeaders(apiKey).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val payload = JSONObject().put("action_attempt_id", actionAttemptId).toString()
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload) }

            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return ActionAttemptResult(false, "Seam error: ${parseSeamError(body)}")
            }
            val json = JSONObject(body)
            val actionAttempt = json.optJSONObject("action_attempt")
                ?: return ActionAttemptResult(false, "No action_attempt in response")
            val status = actionAttempt.optString("status", "")

            when (status) {
                "success" -> ActionAttemptResult(true, "Unlock confirmed", actionAttemptId)
                "pending", "executing" -> ActionAttemptResult(false, "Still processing", actionAttemptId)
                "failed" -> {
                    val error = actionAttempt.optString("error_message", "Action failed")
                    ActionAttemptResult(false, error, actionAttemptId)
                }
                else -> ActionAttemptResult(false, "Status: $status", actionAttemptId)
            }
        } catch (e: Exception) {
            ActionAttemptResult(false, e.message ?: "Failed to check action status")
        } finally {
            conn?.disconnect()
        }
    }

    /** Test Seam connection and API key */
    fun testConnection(apiKey: String): SeamResult {
        if (apiKey.isEmpty()) {
            return SeamResult(false, "Missing Seam API key")
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$API_BASE/workspaces/list")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                apiHeaders(apiKey).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { }

            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return SeamResult(false, "Seam auth failed: ${parseSeamError(body)}")
            }
            SeamResult(true, "Seam connected")
        } catch (e: Exception) {
            SeamResult(false, e.message ?: "Connection failed")
        } finally {
            conn?.disconnect()
        }
    }

    /** Get list of locks for user to choose from */
    fun listDevices(apiKey: String): Pair<List<Pair<String, String>>, String?> {
        if (apiKey.isEmpty()) {
            return emptyList<Pair<String, String>>() to "Missing API key"
        }
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$API_BASE/devices/list")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                apiHeaders(apiKey).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { }

            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return emptyList<Pair<String, String>>() to "Seam error: ${parseSeamError(body)}"
            }
            val json = JSONObject(body)
            val devices = json.optJSONArray("devices") ?: return emptyList<Pair<String, String>>() to "No devices"
            val locks = mutableListOf<Pair<String, String>>()
            for (i in 0 until devices.length()) {
                val device = devices.getJSONObject(i)
                val id = device.optString("device_id", "")
                val name = device.optString("name", "Unknown")
                val isLock = device.optString("device_type", "").contains("lock", ignoreCase = true)
                if (id.isNotEmpty() && isLock) {
                    locks.add(id to name)
                }
            }
            locks to null
        } catch (e: Exception) {
            emptyList<Pair<String, String>>() to (e.message ?: "Failed to list devices")
        } finally {
            conn?.disconnect()
        }
    }
}
