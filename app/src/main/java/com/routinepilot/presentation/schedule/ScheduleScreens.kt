package com.routinepilot.presentation.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.schedule.RecurrenceKind
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.domain.validation.ScheduleValidationError
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

internal const val TAG_SCHEDULE_START_DATE = "schedule_start_date"
internal const val TAG_SCHEDULE_HOUR = "schedule_hour"
internal const val TAG_SCHEDULE_MINUTE = "schedule_minute"
internal const val TAG_SCHEDULE_INTERVAL = "schedule_interval"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleListRoute(
    onBack: () -> Unit,
    onEdit: (UUID) -> Unit,
    onNew: () -> Unit,
    viewModel: ScheduleListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingStop by rememberSaveable { mutableStateOf<UUID?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.schedules_new))
            }
        },
    ) { innerPadding ->
        if (state.loaded && state.items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.schedules_empty))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                items(state.items, key = { it.schedule.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onEdit(item.schedule.id) }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                describe(item.schedule.recurrence.pattern, item.schedule.recurrence.time.toString()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        TextButton(onClick = { pendingStop = item.schedule.id }) {
                            Text(stringResource(R.string.schedules_stop))
                        }
                    }
                }
            }
        }
    }
    pendingStop?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingStop = null },
            title = { Text(stringResource(R.string.schedule_stop_title)) },
            text = { Text(stringResource(R.string.schedule_stop_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingStop = null
                    viewModel.delete(id)
                }) {
                    Text(stringResource(R.string.schedule_stop_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { pendingStop = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorRoute(
    onClose: () -> Unit,
    viewModel: ScheduleEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.isClosed) {
        if (state.isClosed) onClose()
    }
    ScheduleEditorScreen(state = state, actions = viewModel, onBack = onClose)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorScreen(
    state: ScheduleEditorUiState,
    actions: ScheduleEditorActions,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (state.isNew) R.string.schedule_title_new else R.string.schedule_title_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = actions::save,
                enabled = !state.isLoading && !state.isMissing,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("schedule_save"),
            ) {
                Text(stringResource(R.string.editor_save))
            }
        },
    ) { innerPadding ->
        if (state.isMissing) {
            Text(stringResource(R.string.schedule_missing), modifier = Modifier.padding(innerPadding).padding(16.dp))
        } else {
            ScheduleForm(state, actions, Modifier.fillMaxSize().padding(innerPadding))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleForm(state: ScheduleEditorUiState, actions: ScheduleEditorActions, modifier: Modifier) {
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.schedule_target_heading), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.targetKind == ScheduleTargetKind.ROUTINE,
                    onClick = { actions.setTargetKind(ScheduleTargetKind.ROUTINE) },
                    enabled = !state.targetLocked,
                    label = { Text(stringResource(R.string.schedule_target_routine)) },
                )
                FilterChip(
                    selected = state.targetKind == ScheduleTargetKind.TASK,
                    onClick = { actions.setTargetKind(ScheduleTargetKind.TASK) },
                    enabled = !state.targetLocked,
                    label = { Text(stringResource(R.string.schedule_target_task)) },
                )
            }
        }
        if (state.targetKind == ScheduleTargetKind.ROUTINE) {
            item {
                Column {
                    state.routines.forEach { routine ->
                        FilterChip(
                            selected = state.selectedRoutineId == routine.id,
                            onClick = { actions.selectRoutine(routine.id) },
                            enabled = !state.targetLocked,
                            label = { Text(routine.title) },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = state.taskTitle,
                    onValueChange = actions::setTaskTitle,
                    label = { Text(stringResource(R.string.schedule_task_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Text(stringResource(R.string.schedule_repeat_heading), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.pattern == RecurrenceKind.DAILY,
                    onClick = { actions.setPattern(RecurrenceKind.DAILY) },
                    label = { Text(stringResource(R.string.schedule_repeat_daily)) },
                )
                FilterChip(
                    selected = state.pattern == RecurrenceKind.WEEKDAYS,
                    onClick = { actions.setPattern(RecurrenceKind.WEEKDAYS) },
                    label = { Text(stringResource(R.string.schedule_repeat_weekdays)) },
                )
                FilterChip(
                    selected = state.pattern == RecurrenceKind.EVERY_N_DAYS,
                    onClick = { actions.setPattern(RecurrenceKind.EVERY_N_DAYS) },
                    label = { Text(stringResource(R.string.schedule_repeat_interval)) },
                )
            }
        }
        if (state.pattern == RecurrenceKind.WEEKDAYS) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day in state.weekdays,
                            onClick = { actions.toggleWeekday(day) },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
            }
        }
        if (state.pattern == RecurrenceKind.EVERY_N_DAYS) {
            item {
                OutlinedTextField(
                    value = state.intervalText,
                    onValueChange = actions::setInterval,
                    label = { Text(stringResource(R.string.schedule_interval_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(TAG_SCHEDULE_INTERVAL),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.hourText,
                    onValueChange = actions::setHour,
                    label = { Text(stringResource(R.string.schedule_hour)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag(TAG_SCHEDULE_HOUR),
                )
                OutlinedTextField(
                    value = state.minuteText,
                    onValueChange = actions::setMinute,
                    label = { Text(stringResource(R.string.schedule_minute)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag(TAG_SCHEDULE_MINUTE),
                )
            }
        }
        item {
            OutlinedTextField(
                value = state.startDateText,
                onValueChange = actions::setStartDate,
                label = { Text(stringResource(R.string.schedule_start_date)) },
                supportingText = { Text(stringResource(R.string.schedule_start_date_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TAG_SCHEDULE_START_DATE),
            )
        }
        if (state.showErrors) {
            state.errors.forEach { error ->
                item(key = error) {
                    Text(
                        stringResource(errorText(error)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (!state.isNew) {
            item {
                TextButton(onClick = { confirmingDelete = true }) {
                    Text(stringResource(R.string.schedule_stop), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.schedule_stop_title)) },
            text = { Text(stringResource(R.string.schedule_stop_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    actions.delete()
                }) {
                    Text(stringResource(R.string.schedule_stop_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun errorText(error: ScheduleValidationError): Int = when (error) {
    ScheduleValidationError.MISSING_ROUTINE -> R.string.schedule_error_routine
    ScheduleValidationError.BLANK_TASK_TITLE -> R.string.error_blank_title
    ScheduleValidationError.NO_WEEKDAYS -> R.string.schedule_error_weekdays
    ScheduleValidationError.INTERVAL_OUT_OF_RANGE -> R.string.schedule_error_interval
    ScheduleValidationError.INVALID_TIME -> R.string.schedule_error_time
    ScheduleValidationError.INVALID_START_DATE -> R.string.schedule_error_start_date
}

@Composable
private fun describe(pattern: RecurrencePattern, time: String): String = when (pattern) {
    is RecurrencePattern.Daily -> stringResource(R.string.schedule_describe_daily, time)
    is RecurrencePattern.Weekdays ->
        stringResource(
            R.string.schedule_describe_weekdays,
            pattern.days.sorted().joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) },
            time,
        )
    is RecurrencePattern.EveryNDays -> stringResource(R.string.schedule_describe_interval, pattern.intervalDays, time)
}
