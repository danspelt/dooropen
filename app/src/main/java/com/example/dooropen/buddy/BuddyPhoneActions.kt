package com.example.dooropen.buddy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.TelecomManager
import com.example.dooropen.domain.DoorFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Phone-only actions: place calls and hang up.
 * Phase 2 — requires CALL_PHONE and READ_CONTACTS at runtime.
 */
object BuddyPhoneActions {

    suspend fun callContact(context: Context, contactName: String) {
        if (!BuddyPermissions.canPlaceCalls(context)) {
            val missing = when {
                !BuddyPermissions.hasContacts(context) && !BuddyPermissions.hasCall(context) ->
                    "Open Buddy and allow phone and contacts permissions."
                !BuddyPermissions.hasContacts(context) ->
                    "Open Buddy and allow contacts permission so I can find people to call."
                else ->
                    "Open Buddy and allow phone permission so I can place calls."
            }
            DoorFeedback.speak(context, missing)
            return
        }

        val name = contactName.trim()
        if (name.isBlank()) {
            DoorFeedback.speak(context, "Who should I call?")
            return
        }

        val number = withContext(Dispatchers.IO) { findContactNumber(context, name) }
        if (number.isNullOrBlank()) {
            DoorFeedback.speak(context, "I couldn't find $name in your contacts.")
            return
        }

        BuddyBridge.sendHeadsetSwitch("phone", context)
        DoorFeedback.speak(context, "Calling $name.")
        placeCall(context, number)
    }

    suspend fun hangUp(context: Context) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        if (telecom?.endCall() == true) {
            DoorFeedback.speak(context, "Hanging up.")
            delay(1000)
            BuddyBridge.sendHeadsetSwitch("computer", context)
            return
        }
        DoorFeedback.speak(context, "Couldn't hang up. Buddy may need to be the default phone app.")
    }

    private fun placeCall(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.parse("tel:$number")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(intent)
        } catch (_: SecurityException) {
            val dial = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dial)
            DoorFeedback.speak(context, "Tap call to confirm.")
        }
    }

    private fun findContactNumber(context: Context, name: String): String? {
        if (!BuddyPermissions.hasContacts(context)) return null

        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$name%")

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }
}
