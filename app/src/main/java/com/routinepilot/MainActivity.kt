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

    /** Only the three run actions open a run. Any other action just opens the app. */
    private fun Intent.toRunRequest(): RunRequest? {
        val routineId =
            getStringExtra(RunIntents.EXTRA_ROUTINE_ID)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return null
        return when (action) {
            RunIntents.ACTION_RESUME_RUN -> RunRequest(routineId = routineId, resume = true)
            RunIntents.ACTION_START_RUN -> RunRequest(routineId = routineId, resume = false, start = true)
            RunIntents.ACTION_OPEN_RUN -> RunRequest(routineId = routineId, resume = false)
            else -> null
        }
    }
}
