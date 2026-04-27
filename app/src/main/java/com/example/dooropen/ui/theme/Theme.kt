package com.example.dooropen.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = DoorGreen,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = DoorGreenLight,
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = DoorGreenDark,
    onSecondary = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = DoorGreenLight,
    onPrimary = Color(0xFF000000),
    primaryContainer = DoorGreenDark,
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = DoorGreenLight,
    onSecondary = Color(0xFF000000),
)

@Composable
fun DoorAssistTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography, content = content)
}
