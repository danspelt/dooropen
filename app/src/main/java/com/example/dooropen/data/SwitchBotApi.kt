package com.example.dooropen.data

import android.util.Base64
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SwitchBotApi {

    private const val API_BASE = "https://api.switch-bot.com/v1.1"

    data class ApiResult(val ok: Boolean, val message: String)

    data class DeviceInfo(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
        val hubDeviceId: String?,
        val enableCloudService: Boolean?,
    )

    private fun signHeaders(token: String, secret: String): Map<String, String> {
        val nonce = UUID.randomUUID().toString()
        val t = System.currentTimeMillis().toString()
        val data = token + t + nonce
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val sign = Base64.encodeToString(
            mac.doFinal(data.toByteArray(StandardCharsets.UTF_8)),
            Base64.NO_WRAP
        )
        return mapOf(
            "Authorization" to token,
            "sign" to sign,
            "nonce" to nonce,
            "t" to t,
            "Content-Type" to "application/json; charset=utf-8",
        )
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try {
            if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        } catch (_: Exception) {
            null
        } ?: return ""
        return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun parseApiError(code: Int, body: String): String {
        if (body.isBlank()) return "HTTP $code"
        return try {
            val json = JSONObject(body)
            val status = json.optInt("statusCode", -1)
            val msg = json.optString("message", "").ifBlank { "API $status" }
            "HTTP $code • $msg"
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    fun testConnection(token: String, secret: String): ApiResult {
        if (token.isEmpty() || secret.isEmpty()) {
            return ApiResult(false, "Missing token or secret")
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$API_BASE/devices").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 20_000
                signHeaders(token, secret).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) {
                return ApiResult(false, parseApiError(code, body))
            }
            val json = JSONObject(body)
            val status = json.optInt("statusCode", -1)
            if (status == 100) ApiResult(true, json.optString("message", "OK"))
            else ApiResult(false, json.optString("message", "API $status"))
        } catch (e: Exception) {
            ApiResult(false, e.message ?: "Error")
        } finally {
            conn?.disconnect()
        }
    }

    fun verifyDevice(token: String, secret: String, deviceId: String): ApiResult {
        if (token.isEmpty() || secret.isEmpty() || deviceId.isEmpty()) {
            return ApiResult(false, "Missing credentials")
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$API_BASE/devices").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 20_000
                signHeaders(token, secret).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) return ApiResult(false, parseApiError(code, body))

            val json = JSONObject(body)
            val status = json.optInt("statusCode", -1)
            if (status != 100) {
                val msg = json.optString("message", "").ifBlank { "API $status" }
                return ApiResult(false, msg)
            }

            val deviceList = json.optJSONObject("body")?.optJSONArray("deviceList")
            val found = (0 until (deviceList?.length() ?: 0))
                .mapNotNull { idx -> deviceList?.optJSONObject(idx) }
                .firstOrNull { it.optString("deviceId") == deviceId }
                ?: return ApiResult(false, "DeviceId not found in /devices list")

            val info = DeviceInfo(
                deviceId = found.optString("deviceId", deviceId),
                deviceName = found.optString("deviceName", ""),
                deviceType = found.optString("deviceType", ""),
                hubDeviceId = found.optString("hubDeviceId", "").ifBlank { null },
                enableCloudService = if (found.has("enableCloudService")) found.optBoolean("enableCloudService") else null,
            )

            val cloud = when (info.enableCloudService) {
                true -> "cloud=on"
                false -> "cloud=off"
                null -> "cloud=?"
            }
            val hub = info.hubDeviceId ?: "-"
            ApiResult(true, "Found: ${info.deviceName} (${info.deviceType}), $cloud, hub=$hub")
        } catch (e: Exception) {
            ApiResult(false, e.message ?: "Error")
        } finally {
            conn?.disconnect()
        }
    }

    fun pressBot(token: String, secret: String, deviceId: String): ApiResult {
        if (token.isEmpty() || secret.isEmpty() || deviceId.isEmpty()) {
            return ApiResult(false, "Missing credentials")
        }
        val body =
            """{"commandType":"command","command":"press","parameter":"default"}"""
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$API_BASE/devices/$deviceId/commands").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 20_000
                doOutput = true
                signHeaders(token, secret).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            val raw = readBody(conn)
            if (code !in 200..299) return ApiResult(false, parseApiError(code, raw))

            val json = JSONObject(raw)
            val status = json.optInt("statusCode", -1)
            val msg = json.optString("message", "")
            if (status == 100) ApiResult(true, if (msg.isEmpty()) "OK" else msg)
            else ApiResult(false, if (msg.isEmpty()) "API $status" else msg)
        } catch (e: Exception) {
            ApiResult(false, e.message ?: "Error")
        } finally {
            conn?.disconnect()
        }
    }
}
