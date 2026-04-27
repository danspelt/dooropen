@file:Suppress("DEPRECATION")

package com.example.dooropen.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

object DoorPrefs {

    private const val FILE = "door_assist_secure_prefs"
    private const val K_TOKEN = "token"
    private const val K_SECRET = "secret"
    private const val K_DEVICE_ID = "device_id"
    private const val K_TRIGGER = "trigger_key"
    private const val K_HOME_SAFETY = "home_safety"
    private const val K_HOME_SSID = "home_ssid"
    private const val K_BT_SAFETY = "bt_safety"
    private const val K_SOUND = "sound_feedback"
    private const val K_VIBRATION = "vibration_feedback"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getToken(context: Context) = prefs(context).getString(K_TOKEN, "").orEmpty().trim()

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getSecret(context: Context) = prefs(context).getString(K_SECRET, "").orEmpty().trim()

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getDeviceId(context: Context) = prefs(context).getString(K_DEVICE_ID, "").orEmpty().trim()

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getTriggerKey(context: Context) = prefs(context).getString(K_TRIGGER, "").orEmpty().trim()

    fun isConfigured(context: Context): Boolean = try {
        getToken(context).isNotEmpty() && getSecret(context).isNotEmpty() && getDeviceId(context).isNotEmpty()
    } catch (_: Exception) {
        false
    }

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getHomeSafetyEnabled(context: Context) = prefs(context).getBoolean(K_HOME_SAFETY, false)

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getHomeSsid(context: Context) = prefs(context).getString(K_HOME_SSID, "").orEmpty().trim()

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getBtSafetyEnabled(context: Context) = prefs(context).getBoolean(K_BT_SAFETY, false)

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getSoundEnabled(context: Context) = prefs(context).getBoolean(K_SOUND, true)

    @Throws(GeneralSecurityException::class, IOException::class)
    fun getVibrationEnabled(context: Context) = prefs(context).getBoolean(K_VIBRATION, true)

    @Throws(GeneralSecurityException::class, IOException::class)
    fun save(
        context: Context,
        token: String,
        secret: String,
        deviceId: String,
        triggerKey: String,
        homeSafety: Boolean,
        homeSsid: String,
        btSafety: Boolean,
        sound: Boolean,
        vibration: Boolean,
    ) {
        prefs(context).edit()
            .putString(K_TOKEN, token.trim())
            .putString(K_SECRET, secret.trim())
            .putString(K_DEVICE_ID, deviceId.trim())
            .putString(K_TRIGGER, triggerKey.trim())
            .putBoolean(K_HOME_SAFETY, homeSafety)
            .putString(K_HOME_SSID, homeSsid.trim())
            .putBoolean(K_BT_SAFETY, btSafety)
            .putBoolean(K_SOUND, sound)
            .putBoolean(K_VIBRATION, vibration)
            .apply()
    }
}
