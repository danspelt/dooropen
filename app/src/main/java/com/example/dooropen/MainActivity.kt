package com.example.dooropen

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.dooropen.data.DoorPrefs
import com.example.dooropen.service.ProximityService
import com.example.dooropen.ui.door.DoorScreen
import com.example.dooropen.ui.settings.SettingsScreen
import com.example.dooropen.ui.theme.DoorAssistTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Allow app to show and play audio over the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        setContent {
            DoorAssistTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_DOOR,
                    ) {
                        composable(ROUTE_DOOR) {
                            DoorScreen(onOpenSettings = { navController.navigate(ROUTE_SETTINGS) })
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        DoorShortcut.refresh(this)
        // Start the background proximity service if BLE is configured
        try {
            if (DoorPrefs.getBleEnabled(this)) {
                ProximityService.start(this)
            }
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        // Service keeps running in background - don't stop it here
    }

    companion object {
        const val ROUTE_DOOR = "door"
        const val ROUTE_SETTINGS = "settings"
    }
}
