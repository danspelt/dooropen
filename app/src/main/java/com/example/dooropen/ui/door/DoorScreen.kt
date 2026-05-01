package com.example.dooropen.ui.door

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dooropen.R
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.domain.DeviceStatus
import com.example.dooropen.domain.DoorCommand
import com.example.dooropen.domain.DoorFeedback
import com.example.dooropen.domain.ProximityMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DoorPhase {
    Ready,
    Opening,
    Done,
    Failed,
}

private fun DeviceStatus.State.toDisplayText(): String = when (this) {
    is DeviceStatus.State.Unknown -> "Checking connection..."
    is DeviceStatus.State.Checking -> "Checking..."
    is DeviceStatus.State.Connected -> "Device connected"
    is DeviceStatus.State.Disconnected -> "Device not connected"
    is DeviceStatus.State.Error -> "Connection error"
}

private fun DeviceStatus.State.toColor(): Color = when (this) {
    is DeviceStatus.State.Unknown -> Color.Gray
    is DeviceStatus.State.Checking -> Color(0xFFFFA000) // Amber
    is DeviceStatus.State.Connected -> Color(0xFF4CAF50) // Green
    is DeviceStatus.State.Disconnected -> Color(0xFFF44336) // Red
    is DeviceStatus.State.Error -> Color(0xFFF44336) // Red
}

private fun ProximityMonitor.ProximityState.toDisplayText(): String = when (this) {
    is ProximityMonitor.ProximityState.Unknown -> ""
    is ProximityMonitor.ProximityState.Scanning -> "Scanning for door..."
    is ProximityMonitor.ProximityState.VeryNear -> "Very close! Auto-opening..."
    is ProximityMonitor.ProximityState.Near -> "You are near the door"
    is ProximityMonitor.ProximityState.Far -> "Walk closer to the door"
    is ProximityMonitor.ProximityState.NotDetected -> "Door not in range"
    is ProximityMonitor.ProximityState.Error -> ""
}

private fun ProximityMonitor.ProximityState.toColor(): Color = when (this) {
    is ProximityMonitor.ProximityState.VeryNear -> Color(0xFF00E5FF) // Bright cyan - auto-open zone
    is ProximityMonitor.ProximityState.Near -> Color(0xFF00BCD4) // Cyan - nearby
    is ProximityMonitor.ProximityState.Far -> Color(0xFF78909C) // Blue gray - far
    is ProximityMonitor.ProximityState.NotDetected -> Color(0xFF616161) // Dark gray
    is ProximityMonitor.ProximityState.Scanning -> Color(0xFFFFA000) // Amber while scanning
    is ProximityMonitor.ProximityState.Error -> Color(0xFFF44336) // Red for error
    else -> Color(0xFFBDBDBD) // Light gray for unknown
}

@Composable
fun DoorScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(DoorPhase.Ready) }
    var failureDetail by remember { mutableStateOf("") }
    var deviceStatus by remember { mutableStateOf<DeviceStatus.State>(DeviceStatus.State.Unknown) }
    var proximityState by remember { mutableStateOf<ProximityMonitor.ProximityState>(ProximityMonitor.ProximityState.Unknown) }
    var autoOpenEnabled by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Load auto-open preference
    LaunchedEffect(Unit) {
        autoOpenEnabled = DoorPrefs.getAutoOpenEnabled(context)
    }

    // Initialize TTS
    DisposableEffect(Unit) {
        DoorFeedback.initTts(context)
        onDispose {
            DoorFeedback.shutdown()
            ProximityMonitor.stopMonitoring(context)
        }
    }

    // Check device status on launch and periodically
    LaunchedEffect(Unit) {
        // Initial check
        deviceStatus = DeviceStatus.check(context)
        delay(150)
        focusRequester.requestFocus()

        // Periodic status checks
        while (true) {
            delay(10_000) // Check every 10 seconds
            deviceStatus = DeviceStatus.check(context)
        }
    }

    // Start proximity monitoring when BLE is enabled
    LaunchedEffect(deviceStatus) {
        if (deviceStatus is DeviceStatus.State.Connected && DoorPrefs.getBleEnabled(context)) {
            // Set up auto-open callback
            ProximityMonitor.setAutoOpenCallback(object : ProximityMonitor.AutoOpenCallback {
                override fun onAutoOpenTrigger() {
                    scope.launch {
                        // Safety check before auto-open
                        val blocked = DoorCommand.evaluate(context)
                        if (blocked != null) {
                            DoorFeedback.playBlockedWarning(context, blocked.message)
                            return@launch
                        }

                        DoorFeedback.speakStatus(context, "Opening door automatically")
                        DoorFeedback.playOpeningCue(context)
                        phase = DoorPhase.Opening

                        when (val r = DoorCommand.commitPress(context)) {
                            is DoorCommand.PressOutcome.Success -> {
                                phase = DoorPhase.Done
                                DoorFeedback.playSuccess(context)
                            }
                            is DoorCommand.PressOutcome.Failed -> {
                                failureDetail = r.message
                                phase = DoorPhase.Failed
                                DoorFeedback.playFailure(context, r.message)
                                DeviceStatus.invalidateCache()
                                deviceStatus = DeviceStatus.check(context)
                            }
                        }
                    }
                }
            })
            ProximityMonitor.setAutoOpenEnabled(autoOpenEnabled) // Respect user preference

            ProximityMonitor.startMonitoring(context)
            // Collect proximity state changes
            ProximityMonitor.state.collect { state ->
                proximityState = state

                // Announce when near (but not too often)
                if (ProximityMonitor.shouldAnnounce()) {
                    val message = ProximityMonitor.getAnnounceMessage()
                    if (message != null) {
                        DoorFeedback.speakStatus(context, message)
                        ProximityMonitor.markAnnounced()
                    }
                }
            }
        } else {
            ProximityMonitor.stopMonitoring(context)
            ProximityMonitor.setAutoOpenCallback(null)
            ProximityMonitor.setAutoOpenEnabled(false)
            proximityState = ProximityMonitor.ProximityState.Unknown
        }
    }

    LaunchedEffect(phase) {
        if (phase == DoorPhase.Done || phase == DoorPhase.Failed) {
            delay(2500)
            phase = DoorPhase.Ready
            failureDetail = ""
        }
    }

    val statusText = when (phase) {
        DoorPhase.Ready -> {
            // Always show proximity status when BLE is enabled
            if (DoorPrefs.getBleEnabled(context) && proximityState !is ProximityMonitor.ProximityState.Unknown) {
                proximityState.toDisplayText()
            } else {
                deviceStatus.toDisplayText()
            }
        }
        DoorPhase.Opening -> context.getString(R.string.status_opening)
        DoorPhase.Done -> context.getString(R.string.status_done)
        DoorPhase.Failed -> if (failureDetail.isEmpty()) {
            context.getString(R.string.status_failed)
        } else {
            context.getString(R.string.status_failed_detail, failureDetail)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(
            onClick = {
                if (phase == DoorPhase.Opening) return@Button
                scope.launch {
                    // Check device status first
                    val status = DeviceStatus.check(context)
                    if (status is DeviceStatus.State.Disconnected || status is DeviceStatus.State.Error) {
                        val reason = when (status) {
                            is DeviceStatus.State.Disconnected -> status.reason
                            is DeviceStatus.State.Error -> status.message
                            else -> "Device not connected"
                        }
                        failureDetail = reason
                        phase = DoorPhase.Failed
                        DoorFeedback.playFailure(context, reason)
                        // Refresh status
                        deviceStatus = status
                        return@launch
                    }

                    val blocked = DoorCommand.evaluate(context)
                    if (blocked != null) {
                        failureDetail = blocked.message
                        phase = DoorPhase.Failed
                        DoorFeedback.playBlockedWarning(context, blocked.message)
                        return@launch
                    }
                    DoorFeedback.playOpeningCue(context)
                    view.announceForAccessibility(context.getString(R.string.a11y_opening))
                    phase = DoorPhase.Opening
                    when (val r = DoorCommand.commitPress(context)) {
                        is DoorCommand.PressOutcome.Success -> {
                            phase = DoorPhase.Done
                            DoorFeedback.playSuccess(context)
                        }
                        is DoorCommand.PressOutcome.Failed -> {
                            failureDetail = r.message
                            phase = DoorPhase.Failed
                            DoorFeedback.playFailure(context, r.message)
                            // Invalidate cache so we recheck connection
                            DeviceStatus.invalidateCache()
                            deviceStatus = DeviceStatus.check(context)
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 220.dp)
                .focusRequester(focusRequester)
                .focusable()
                .semantics {
                    role = Role.Button
                    contentDescription = context.getString(R.string.cd_open_door_button)
                },
            enabled = phase != DoorPhase.Opening,
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(vertical = 28.dp, horizontal = 20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                text = context.getString(R.string.open_door_label),
                style = MaterialTheme.typography.displayLarge,
                textAlign = TextAlign.Center,
            )
        }
        // Connection status indicator
        Row(
            modifier = Modifier
                .padding(top = 28.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(deviceStatus.toColor())
            )
            Spacer(modifier = Modifier.padding(horizontal = 8.dp))
            Text(
                text = statusText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                proximityState is ProximityMonitor.ProximityState.VeryNear -> Color(0xFF00BCD4)
                proximityState is ProximityMonitor.ProximityState.Near -> Color(0xFF03A9F4)
                deviceStatus is DeviceStatus.State.Connected -> Color(0xFF4CAF50)
                deviceStatus is DeviceStatus.State.Disconnected || deviceStatus is DeviceStatus.State.Error -> Color(0xFFF44336)
                else -> MaterialTheme.colorScheme.onSurface
            },
                textAlign = TextAlign.Center,
            )
            // Refresh button for status
            IconButton(
                onClick = {
                    scope.launch {
                        deviceStatus = DeviceStatus.State.Checking
                        deviceStatus = DeviceStatus.check(context)
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh connection",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Auto-open toggle (only when BLE is enabled)
        if (DoorPrefs.getBleEnabled(context)) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoMode,
                    contentDescription = "Auto-open",
                    tint = if (autoOpenEnabled) Color(0xFF00E5FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Text(
                    text = if (autoOpenEnabled) "Auto-open ON" else "Auto-open OFF",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (autoOpenEnabled) Color(0xFF00E5FF) else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Switch(
                    checked = autoOpenEnabled,
                    onCheckedChange = {
                        autoOpenEnabled = it
                        DoorPrefs.setAutoOpenEnabled(context, it)
                        ProximityMonitor.setAutoOpenEnabled(it)
                        if (it) {
                            DoorFeedback.speakStatus(context, "Auto-open enabled")
                        } else {
                            DoorFeedback.speakStatus(context, "Auto-open disabled")
                        }
                    },
                )
            }
        }

        // Bot location note
        if (DoorPrefs.getBleEnabled(context)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Bot is on top of the door",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Proximity indicator - always show when BLE is enabled
        if (DoorPrefs.getBleEnabled(context)) {
            Spacer(modifier = Modifier.height(12.dp))

            // Show distance indicator even when unknown/scanning
            val currentProximity = proximityState
            val displayRssi = when (currentProximity) {
                is ProximityMonitor.ProximityState.VeryNear -> currentProximity.rssi
                is ProximityMonitor.ProximityState.Near -> currentProximity.rssi
                is ProximityMonitor.ProximityState.Far -> currentProximity.rssi
                else -> null
            }

            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(proximityState.toColor()),
                contentAlignment = Alignment.Center
            ) {
                when (currentProximity) {
                    is ProximityMonitor.ProximityState.VeryNear -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.NearMe,
                                contentDescription = "Auto-opening",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp),
                            )
                            Text(
                                text = "${currentProximity.rssi}dBm",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is ProximityMonitor.ProximityState.Near -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.NearMe,
                                contentDescription = "Near door",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp),
                            )
                            Text(
                                text = "${currentProximity.rssi}dBm",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is ProximityMonitor.ProximityState.Far -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${currentProximity.rssi}",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "dBm",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is ProximityMonitor.ProximityState.Scanning -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Scanning",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "Scanning...",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    is ProximityMonitor.ProximityState.NotDetected -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = "No signal",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "No signal",
                                color = Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    else -> {
                        // Unknown or Error - show default
                        Text(
                            text = "?",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Distance text
            Text(
                text = when (currentProximity) {
                    is ProximityMonitor.ProximityState.VeryNear -> if (autoOpenEnabled) "Auto-opening..." else "Very close! Tap to open"
                    is ProximityMonitor.ProximityState.Near -> "Keep walking..."
                    is ProximityMonitor.ProximityState.Far -> "Getting closer..."
                    is ProximityMonitor.ProximityState.NotDetected -> "Too far - walk closer"
                    is ProximityMonitor.ProximityState.Scanning -> "Looking for bot..."
                    is ProximityMonitor.ProximityState.Error -> "Scan error"
                    else -> "Waiting for signal..."
                },
                style = MaterialTheme.typography.titleMedium,
                color = proximityState.toColor(),
                textAlign = TextAlign.Center,
            )

            // Estimated distance in feet (rough approximation)
            if (displayRssi != null) {
                val estimatedFeet = when {
                    displayRssi >= -45 -> "1-2 ft"
                    displayRssi >= -55 -> "3-5 ft"
                    displayRssi >= -65 -> "6-10 ft"
                    displayRssi >= -75 -> "11-15 ft"
                    else -> "15+ ft"
                }
                Text(
                    text = "~$estimatedFeet away",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Warning icon if disconnected
        if (deviceStatus is DeviceStatus.State.Disconnected || deviceStatus is DeviceStatus.State.Error) {
            Spacer(modifier = Modifier.height(16.dp))
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = "Connection problem",
                tint = Color(0xFFF44336),
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = when (val s = deviceStatus) {
                    is DeviceStatus.State.Disconnected -> s.reason
                    is DeviceStatus.State.Error -> s.message
                    else -> ""
                },
                modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF44336),
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.semantics {
                    contentDescription = context.getString(R.string.cd_settings)
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                )
            }
        }
    }
}
