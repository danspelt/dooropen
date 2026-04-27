package com.example.dooropen

import android.os.Bundle
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
import com.example.dooropen.ui.door.DoorScreen
import com.example.dooropen.ui.settings.SettingsScreen
import com.example.dooropen.ui.theme.DoorAssistTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
    }

    companion object {
        const val ROUTE_DOOR = "door"
        const val ROUTE_SETTINGS = "settings"
    }
}
