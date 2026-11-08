package com.routinepilot

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.routinepilot.infrastructure.notifications.RunIntents
import com.routinepilot.presentation.navigation.RoutinePilotNavHost
import com.routinepilot.presentation.navigation.RunRequest
import com.routinepilot.presentation.theme.RoutinePilotTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var runRequest by mutableStateOf<RunRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) runRequest = intent.toRunRequest()
        setContent {
            RoutinePilotTheme {
                RoutinePilotNavHost(
                    runRequest = runRequest,
                    onRunRequestHandled = { runRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toRunRequest()?.let { runRequest = it }
    }

    private fun Intent.toRunRequest(): RunRequest? {
        val routineId = getStringExtra(RunIntents.EXTRA_ROUTINE_ID)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return null
        return RunRequest(routineId = routineId, resume = action == RunIntents.ACTION_RESUME_RUN)
    }
}
