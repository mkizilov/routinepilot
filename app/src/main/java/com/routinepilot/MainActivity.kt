package com.routinepilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.routinepilot.presentation.navigation.RoutinePilotNavHost
import com.routinepilot.presentation.theme.RoutinePilotTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoutinePilotTheme {
                RoutinePilotNavHost()
            }
        }
    }
}
