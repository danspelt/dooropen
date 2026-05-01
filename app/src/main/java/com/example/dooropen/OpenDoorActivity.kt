package com.example.dooropen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.domain.DeviceStatus
import com.example.dooropen.domain.DoorCommand
import com.example.dooropen.domain.DoorFeedback
import kotlinx.coroutines.launch

/**
 * External entry (Tecla, Tasker, shortcuts). Requires trigger key; same safety rules as in-app.
 */
class OpenDoorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize TTS
        DoorFeedback.initTts(this)

        val key = extractKey(intent)
        val expected = try {
            DoorPrefs.getTriggerKey(this)
        } catch (_: Exception) {
            ""
        }
        if (expected.isEmpty()) {
            val msg = getString(R.string.open_need_trigger_key)
            DoorFeedback.playFailure(this, msg)
            toast(msg)
            finish()
            return
        }
        if (expected != key) {
            val msg = getString(R.string.open_bad_key)
            DoorFeedback.playFailure(this, msg)
            toast(msg)
            finish()
            return
        }

        lifecycleScope.launch {
            // Check device connectivity first
            val status = DeviceStatus.check(this@OpenDoorActivity)
            if (status is DeviceStatus.State.Disconnected || status is DeviceStatus.State.Error) {
                val reason = when (status) {
                    is DeviceStatus.State.Disconnected -> status.reason
                    is DeviceStatus.State.Error -> status.message
                    else -> "Device not connected"
                }
                DoorFeedback.playFailure(this@OpenDoorActivity, reason)
                toast("Failed: $reason")
                finish()
                return@launch
            }

            val blocked = DoorCommand.evaluate(this@OpenDoorActivity)
            if (blocked != null) {
                DoorFeedback.playBlockedWarning(this@OpenDoorActivity, blocked.message)
                toast(blocked.message)
                finish()
                return@launch
            }
            DoorFeedback.playOpeningCue(this@OpenDoorActivity)
            when (val r = DoorCommand.commitPress(this@OpenDoorActivity)) {
                is DoorCommand.PressOutcome.Success -> {
                    DoorFeedback.playSuccess(this@OpenDoorActivity)
                    toast(getString(R.string.open_ok))
                }
                is DoorCommand.PressOutcome.Failed -> {
                    DoorFeedback.playFailure(this@OpenDoorActivity, r.message)
                    toast(getString(R.string.open_failed, r.message))
                }
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DoorFeedback.shutdown()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ACTION_OPEN_DOOR = "com.example.dooropen.ACTION_OPEN_DOOR"
        const val EXTRA_KEY = "key"
    }
}

private fun extractKey(intent: Intent?): String {
    if (intent == null) return ""
    val extra = intent.getStringExtra(OpenDoorActivity.EXTRA_KEY)
    if (!extra.isNullOrBlank()) return extra.trim()
    val data: Uri? = intent.data
    if (data != null) {
        val q = data.getQueryParameter("key")
        if (!q.isNullOrBlank()) return q.trim()
    }
    return ""
}
