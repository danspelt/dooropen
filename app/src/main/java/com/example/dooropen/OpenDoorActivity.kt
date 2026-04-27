package com.example.dooropen

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.domain.DoorCommand
import com.example.dooropen.domain.DoorFeedback
import kotlinx.coroutines.launch

/**
 * External entry (Tecla, Tasker, shortcuts). Requires trigger key; same safety rules as in-app.
 */
class OpenDoorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = extractKey(intent)
        val expected = try {
            DoorPrefs.getTriggerKey(this)
        } catch (_: Exception) {
            ""
        }
        if (expected.isEmpty()) {
            toast(getString(R.string.open_need_trigger_key))
            finish()
            return
        }
        if (expected != key) {
            toast(getString(R.string.open_bad_key))
            finish()
            return
        }

        lifecycleScope.launch {
            val blocked = DoorCommand.evaluate(this@OpenDoorActivity)
            if (blocked != null) {
                DoorFeedback.playBlockedWarning(this@OpenDoorActivity)
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
                    DoorFeedback.playFailure(this@OpenDoorActivity)
                    toast(getString(R.string.open_failed, r.message))
                }
            }
            finish()
        }
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
