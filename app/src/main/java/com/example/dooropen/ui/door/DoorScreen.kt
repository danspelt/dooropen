package com.example.dooropen.ui.door

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.example.dooropen.domain.DoorCommand
import com.example.dooropen.domain.DoorFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DoorPhase {
    Ready,
    Opening,
    Done,
    Failed,
}

@Composable
fun DoorScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(DoorPhase.Ready) }
    var failureDetail by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        focusRequester.requestFocus()
    }

    LaunchedEffect(phase) {
        if (phase == DoorPhase.Done || phase == DoorPhase.Failed) {
            delay(2500)
            phase = DoorPhase.Ready
            failureDetail = ""
        }
    }

    val statusText = when (phase) {
        DoorPhase.Ready -> context.getString(R.string.status_ready)
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
                    val blocked = DoorCommand.evaluate(context)
                    if (blocked != null) {
                        failureDetail = blocked.message
                        phase = DoorPhase.Failed
                        DoorFeedback.playBlockedWarning(context)
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
                            DoorFeedback.playFailure(context)
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
        Text(
            text = statusText,
            modifier = Modifier
                .padding(top = 28.dp)
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
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
