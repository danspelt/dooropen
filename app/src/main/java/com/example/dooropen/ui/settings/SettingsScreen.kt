package com.example.dooropen.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dooropen.DoorShortcut
import com.example.dooropen.R
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.data.SwitchBotApi
import com.example.dooropen.util.SwitchBotLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var token by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var triggerKey by remember { mutableStateOf("") }
    var homeSafety by remember { mutableStateOf(false) }
    var homeSsid by remember { mutableStateOf("") }
    var btSafety by remember { mutableStateOf(false) }
    var sound by remember { mutableStateOf(true) }
    var vibration by remember { mutableStateOf(true) }
    var showHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            token = DoorPrefs.getToken(context)
            secret = DoorPrefs.getSecret(context)
            deviceId = DoorPrefs.getDeviceId(context)
            triggerKey = DoorPrefs.getTriggerKey(context)
            homeSafety = DoorPrefs.getHomeSafetyEnabled(context)
            homeSsid = DoorPrefs.getHomeSsid(context)
            btSafety = DoorPrefs.getBtSafetyEnabled(context)
            sound = DoorPrefs.getSoundEnabled(context)
            vibration = DoorPrefs.getVibrationEnabled(context)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.open_error_prefs, Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.isEmpty()) return@rememberLauncherForActivityResult
        if (!granted.values.all { it }) {
            Toast.makeText(context, R.string.permissions_denied, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        persistAndBack(
            context = context,
            scope = scope,
            token = token,
            secret = secret,
            deviceId = deviceId,
            triggerKey = triggerKey,
            homeSafety = homeSafety,
            homeSsid = homeSsid,
            btSafety = btSafety,
            sound = sound,
            vibration = vibration,
            onBack = onBack,
        )
    }

    fun requiredPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (homeSafety) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (btSafety && android.os.Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return list
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(context.getString(R.string.help_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    listOf(
                        R.string.help_step1,
                        R.string.help_step2,
                        R.string.help_step3,
                        R.string.help_step4,
                        R.string.help_step5,
                        R.string.help_step6,
                    ).forEach { res ->
                        Text(
                            text = context.getString(res),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            showHelp = false
                            if (!SwitchBotLauncher.openSwitchBotApp(context)) {
                                Toast.makeText(context, R.string.switchbot_not_installed, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(context.getString(R.string.help_open_switchbot))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHelp = false }) {
                    Text(context.getString(R.string.help_dismiss))
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(context.getString(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(context.getString(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { showHelp = true }) {
                        Icon(
                            imageVector = Icons.Filled.Help,
                            contentDescription = context.getString(R.string.help_title),
                        )
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Text(context.getString(R.string.settings_intro_short), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(context.getString(R.string.hint_token)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(context.getString(R.string.hint_secret)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = deviceId,
                onValueChange = { deviceId = it },
                label = { Text(context.getString(R.string.hint_device_id)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = triggerKey,
                onValueChange = { triggerKey = it },
                label = { Text(context.getString(R.string.hint_trigger_key)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            RowSwitch(
                title = context.getString(R.string.home_safety_title),
                subtitle = context.getString(R.string.home_safety_subtitle),
                checked = homeSafety,
                onCheckedChange = { homeSafety = it },
            )
            OutlinedTextField(
                value = homeSsid,
                onValueChange = { homeSsid = it },
                label = { Text(context.getString(R.string.home_ssid_hint)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                enabled = homeSafety,
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            RowSwitch(
                title = context.getString(R.string.bt_safety_title),
                subtitle = context.getString(R.string.bt_safety_subtitle),
                checked = btSafety,
                onCheckedChange = { btSafety = it },
            )
            Spacer(Modifier.height(12.dp))
            RowSwitch(
                title = context.getString(R.string.sound_title),
                subtitle = context.getString(R.string.sound_subtitle),
                checked = sound,
                onCheckedChange = { sound = it },
            )
            Spacer(Modifier.height(8.dp))
            RowSwitch(
                title = context.getString(R.string.vibration_title),
                subtitle = context.getString(R.string.vibration_subtitle),
                checked = vibration,
                onCheckedChange = { vibration = it },
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            SwitchBotApi.testConnection(token.trim(), secret.trim())
                        }
                        Toast.makeText(
                            context,
                            if (r.ok) context.getString(R.string.test_ok) else context.getString(
                                R.string.test_failed,
                                r.message,
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(context.getString(R.string.test_connection))
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (!SwitchBotLauncher.openSwitchBotApp(context)) {
                        Toast.makeText(context, R.string.switchbot_not_installed, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(context.getString(R.string.open_switchbot_app))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (token.isBlank() || secret.isBlank() || deviceId.isBlank()) {
                        Toast.makeText(context, R.string.open_missing_credentials, Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    if (homeSafety && homeSsid.isBlank()) {
                        Toast.makeText(context, R.string.home_ssid_required, Toast.LENGTH_LONG).show()
                        return@Button
                    }
                    val need = requiredPermissions().filter { perm ->
                        ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
                    }
                    if (need.isNotEmpty()) {
                        permissionLauncher.launch(need.toTypedArray())
                    } else {
                        persistAndBack(
                            context = context,
                            scope = scope,
                            token = token,
                            secret = secret,
                            deviceId = deviceId,
                            triggerKey = triggerKey,
                            homeSafety = homeSafety,
                            homeSsid = homeSsid,
                            btSafety = btSafety,
                            sound = sound,
                            vibration = vibration,
                            onBack = onBack,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(context.getString(R.string.button_save))
            }
        }
    }
}

private fun persistAndBack(
    context: Context,
    scope: CoroutineScope,
    token: String,
    secret: String,
    deviceId: String,
    triggerKey: String,
    homeSafety: Boolean,
    homeSsid: String,
    btSafety: Boolean,
    sound: Boolean,
    vibration: Boolean,
    onBack: () -> Unit,
) {
    scope.launch {
        try {
            DoorPrefs.save(
                context,
                token,
                secret,
                deviceId,
                triggerKey,
                homeSafety,
                homeSsid,
                btSafety,
                sound,
                vibration,
            )
            DoorShortcut.refresh(context)
            Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            onBack()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.save_failed, e.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

@Composable
private fun RowSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.padding(top = 4.dp))
    }
}
