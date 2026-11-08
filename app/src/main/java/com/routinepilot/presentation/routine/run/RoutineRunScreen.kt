package com.routinepilot.presentation.routine.run

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.TimerState
import com.routinepilot.infrastructure.notifications.NotificationAccess
import com.routinepilot.presentation.routine.formatCountdown

internal const val TAG_RUN_COUNTDOWN = "run_countdown"
internal const val TAG_RUN_STEP_TITLE = "run_step_title"
internal const val TAG_RUN_TOTAL = "run_total"
internal const val TAG_RUN_START = "run_start"

@Composable
fun RoutineRunRoute(
    onClose: () -> Unit,
    autoResume: Boolean = false,
    autoStart: Boolean = false,
    viewModel: RoutineRunViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.isClosed) {
        if (state.isClosed) onClose()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.onForeground()
                    Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var resumeHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoResume) {
        if (autoResume && !resumeHandled) {
            resumeHandled = true
            viewModel.resume()
        }
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // The run starts whether or not the user allows notifications. Only the alerts depend on the answer.
            viewModel.start()
        }
    val actions =
        remember(viewModel, permissionLauncher) {
            object : RoutineRunActions by viewModel {
                override fun start() {
                    if (NotificationAccess.isPermissionGranted(context)) {
                        viewModel.start()
                    } else {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    var startHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStart, state.isLoading, state.timer.status) {
        // A widget's Start opens the run and begins it once, through the same permission path as the Start button.
        val ready = !state.isLoading && state.timer.status == ExecutionStatus.NOT_STARTED
        if (autoStart && !startHandled && ready) {
            startHandled = true
            actions.start()
        }
    }
    RoutineRunScreen(
        state = state,
        actions = actions,
        onClose = onClose,
        notificationsAllowed = NotificationAccess.canPost(context),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineRunScreen(
    state: RoutineRunUiState,
    actions: RoutineRunActions,
    onClose: () -> Unit,
    notificationsAllowed: Boolean = true,
) {
    val view = LocalView.current
    val keepScreenOn = state.timer.status == ExecutionStatus.RUNNING
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.routineTitle) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                state.isMissing -> Text(stringResource(R.string.editor_missing))
                state.timer.status == ExecutionStatus.COMPLETED -> CompletedContent(onClose)
                state.timer.status == ExecutionStatus.CANCELLED -> Unit
                else -> ActiveContent(state, actions, notificationsAllowed)
            }
        }
    }
}

@Composable
private fun ActiveContent(
    state: RoutineRunUiState,
    actions: RoutineRunActions,
    notificationsAllowed: Boolean,
) {
    val timer = state.timer
    val current = timer.currentStep
    Text(stepHeading(timer), style = MaterialTheme.typography.titleMedium)
    Text(
        text = current?.title ?: stringResource(R.string.run_no_steps),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 16.dp).testTag(TAG_RUN_STEP_TITLE),
    )
    Text(
        text = countdownText(timer),
        style = MaterialTheme.typography.displayLarge,
        modifier = Modifier.testTag(TAG_RUN_COUNTDOWN),
    )
    val duration = current?.durationMillis
    if (duration != null && timer.status.isActive) {
        val fraction = (timer.currentElapsedMillis.toFloat() / duration).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
    } else if (current != null && duration == null && timer.status.isActive) {
        Text(stringResource(R.string.run_untimed), style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(24.dp))
    Text(
        stringResource(R.string.run_total_left, formatCountdown(timer.totalRemainingMillis)),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.testTag(TAG_RUN_TOTAL),
    )
    Text(nextText(timer), style = MaterialTheme.typography.bodyMedium)
    if (state.blockedByOtherRun) {
        Text(
            stringResource(R.string.run_blocked),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
    if (!notificationsAllowed) {
        Text(
            stringResource(R.string.run_notifications_off),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
    if (state.foregroundUnavailable && timer.status == ExecutionStatus.RUNNING) {
        Text(
            stringResource(R.string.run_keep_open),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Spacer(Modifier.height(32.dp))
    Controls(state, actions)
}

@Composable
private fun Controls(
    state: RoutineRunUiState,
    actions: RoutineRunActions,
) {
    var confirmingCancel by rememberSaveable { mutableStateOf(false) }
    val status = state.timer.status
    if (status == ExecutionStatus.NOT_STARTED) {
        val canStart = state.timer.steps.isNotEmpty() && !state.isLoading && !state.blockedByOtherRun
        Button(onClick = actions::start, enabled = canStart, modifier = Modifier.testTag(TAG_RUN_START)) {
            Text(stringResource(R.string.run_start))
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = actions::previous) { Text(stringResource(R.string.run_previous)) }
            if (status == ExecutionStatus.RUNNING) {
                Button(onClick = actions::pause) { Text(stringResource(R.string.run_pause)) }
            } else {
                Button(onClick = actions::resume) { Text(stringResource(R.string.run_resume)) }
            }
            OutlinedButton(onClick = actions::skip) { Text(stringResource(R.string.run_skip)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions::finish) { Text(stringResource(R.string.run_finish)) }
            TextButton(onClick = { confirmingCancel = true }) {
                Text(stringResource(R.string.run_cancel), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text(stringResource(R.string.run_cancel_title)) },
            text = { Text(stringResource(R.string.run_cancel_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingCancel = false
                    actions.cancel()
                }) {
                    Text(stringResource(R.string.run_cancel_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCancel = false }) {
                    Text(stringResource(R.string.run_keep_going))
                }
            },
        )
    }
}

@Composable
private fun CompletedContent(onClose: () -> Unit) {
    Text(stringResource(R.string.run_complete), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(24.dp))
    Button(onClick = onClose) { Text(stringResource(R.string.run_done)) }
}

@Composable
private fun stepHeading(timer: TimerState): String =
    when {
        timer.steps.isEmpty() -> ""
        timer.status == ExecutionStatus.NOT_STARTED -> stringResource(R.string.run_ready)
        else -> stringResource(R.string.run_step_progress, timer.currentIndex + 1, timer.steps.size)
    }

@Composable
private fun nextText(timer: TimerState): String {
    if (!timer.status.isActive) return ""
    val next = timer.nextStep
    return if (next == null) {
        stringResource(R.string.run_last_step)
    } else {
        stringResource(R.string.run_next, next.title)
    }
}

private fun countdownText(timer: TimerState): String {
    val current = timer.currentStep ?: return "--:--"
    return if (current.durationSeconds == null) {
        "--:--"
    } else {
        formatCountdown(timer.currentRemainingMillis ?: 0L)
    }
}
