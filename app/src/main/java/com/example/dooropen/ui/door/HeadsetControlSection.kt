package com.example.dooropen.ui.door

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dooropen.R
import com.example.dooropen.buddy.BuddyBridge
import com.example.dooropen.domain.DoorFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val PhoneBlue = Color(0xFF1D4ED8)
private val ComputerGreen = Color(0xFF047857)
private val DisabledBg = Color(0xFF374151)
private val ButtonText = Color(0xFFFFFFFF)
private val StatusText = Color(0xFFF3F4F6)
private val TitleGold = Color(0xFFFFE066)

@Composable
fun HeadsetControlSection(
    buddyEnabled: Boolean,
    onRequestBuddyOn: () -> Unit,
    phoneFocusRequester: FocusRequester = FocusRequester(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(BuddyBridge.isConnected) }
    var mode by remember { mutableStateOf(BuddyBridge.getCurrentMode()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(buddyEnabled) {
        while (true) {
            connected = BuddyBridge.isConnected
            mode = BuddyBridge.getCurrentMode()
            delay(1_500)
        }
    }

    LaunchedEffect(buddyEnabled) {
        if (buddyEnabled) {
            delay(300)
            try { phoneFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    val statusLine = when {
        !buddyEnabled -> context.getString(R.string.headset_turn_buddy_on)
        !connected -> context.getString(R.string.headset_not_connected)
        mode == "phone" -> context.getString(R.string.headset_on_phone)
        else -> context.getString(R.string.headset_on_computer)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = context.getString(R.string.headset_control_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TitleGold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = statusLine,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyLarge,
            color = StatusText,
            textAlign = TextAlign.Center,
        )
        if (status.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = status,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF93C5FD),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeadsetScanButton(
                title = context.getString(R.string.headset_phone_button),
                subtitle = context.getString(R.string.headset_phone_subtitle),
                contentDesc = context.getString(R.string.cd_headset_phone),
                stateDesc = when {
                    !buddyEnabled -> context.getString(R.string.headset_turn_buddy_on)
                    !connected -> context.getString(R.string.headset_not_connected)
                    busy -> context.getString(R.string.headset_busy)
                    else -> ""
                },
                containerColor = PhoneBlue,
                modifier = Modifier.focusRequester(phoneFocusRequester),
                onActivate = {
                    if (busy) return@HeadsetScanButton
                    if (!buddyEnabled) {
                        onRequestBuddyOn()
                        DoorFeedback.speak(context, context.getString(R.string.headset_turn_buddy_on))
                        return@HeadsetScanButton
                    }
                    if (!connected) {
                        DoorFeedback.speak(context, context.getString(R.string.headset_not_connected))
                        status = context.getString(R.string.headset_not_connected)
                        return@HeadsetScanButton
                    }
                    busy = true
                    status = context.getString(R.string.headset_moving_phone)
                    BuddyBridge.requestHeadsetSwitch("phone")
                    scope.launch {
                        DoorFeedback.speak(context, "Phone")
                        delay(2_000)
                        mode = BuddyBridge.getCurrentMode()
                        status = context.getString(R.string.headset_moved_phone)
                        busy = false
                    }
                },
            )
            HeadsetScanButton(
                title = context.getString(R.string.headset_computer_button),
                subtitle = context.getString(R.string.headset_computer_subtitle),
                contentDesc = context.getString(R.string.cd_headset_computer),
                stateDesc = when {
                    !buddyEnabled -> context.getString(R.string.headset_turn_buddy_on)
                    !connected -> context.getString(R.string.headset_not_connected)
                    busy -> context.getString(R.string.headset_busy)
                    else -> ""
                },
                containerColor = ComputerGreen,
                onActivate = {
                    if (busy) return@HeadsetScanButton
                    if (!buddyEnabled) {
                        onRequestBuddyOn()
                        DoorFeedback.speak(context, context.getString(R.string.headset_turn_buddy_on))
                        return@HeadsetScanButton
                    }
                    if (!connected) {
                        DoorFeedback.speak(context, context.getString(R.string.headset_not_connected))
                        status = context.getString(R.string.headset_not_connected)
                        return@HeadsetScanButton
                    }
                    busy = true
                    status = context.getString(R.string.headset_moving_computer)
                    BuddyBridge.requestHeadsetSwitch("computer")
                    scope.launch {
                        DoorFeedback.speak(context, "Computer")
                        delay(3_000)
                        mode = BuddyBridge.getCurrentMode()
                        status = context.getString(R.string.headset_moved_computer)
                        busy = false
                    }
                },
            )
        }
    }
}

/** Always focusable for Switch Access — never use enabled=false (that hides from scan). */
@Composable
private fun HeadsetScanButton(
    title: String,
    subtitle: String,
    contentDesc: String,
    stateDesc: String,
    containerColor: Color,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimmed = stateDesc.isNotBlank()
    val bg = if (dimmed) DisabledBg else containerColor
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 104.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .focusable()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = contentDesc
                if (stateDesc.isNotBlank()) stateDescription = stateDesc
            }
            .clickable(interactionSource = interaction, indication = null) { onActivate() }
            .padding(vertical = 14.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = ButtonText,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics { },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = ButtonText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}
