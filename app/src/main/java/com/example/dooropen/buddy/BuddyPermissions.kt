package com.example.dooropen.buddy

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object BuddyPermissions {

    val MIC = Manifest.permission.RECORD_AUDIO
    val CALL = Manifest.permission.CALL_PHONE
    val CONTACTS = Manifest.permission.READ_CONTACTS

    val ALL = arrayOf(MIC, CALL, CONTACTS)
    val PHONE_ACTIONS = arrayOf(CALL, CONTACTS)

    fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, MIC) == PackageManager.PERMISSION_GRANTED

    fun hasCall(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, CALL) == PackageManager.PERMISSION_GRANTED

    fun hasContacts(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canPlaceCalls(context: Context): Boolean = hasCall(context) && hasContacts(context)

    fun missing(context: Context, permissions: Array<String>): Array<String> =
        permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
}
