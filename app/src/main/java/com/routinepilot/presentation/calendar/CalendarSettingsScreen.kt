package com.routinepilot.presentation.calendar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.calendar.CalendarInfo
import com.routinepilot.domain.calendar.CalendarLinkStatus
import com.routinepilot.domain.calendar.CalendarState
import com.routinepilot.domain.calendar.LinkedEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

private val calendarPermissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
private val lastSyncFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

@Composable
fun CalendarSettingsRoute(
    onBack: () -> Unit,
    viewModel: CalendarSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    CalendarSettingsScreen(state = state, actions = viewModel, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(
    state: CalendarSettingsUiState,
    actions: CalendarSettingsActions,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pendingCalendar by remember { mutableStateOf<CalendarInfo?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            if (results.values.all { it }) actions.setEnabled(true) else actions.refresh()
        }
    val requestAccess = { permissionLauncher.launch(calendarPermissions) }
    val linkedCount = state.events.count { it.eventId != null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calendar_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.editor_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text(stringResource(R.string.calendar_intro), style = MaterialTheme.typography.bodyMedium) }
            item { Text(stringResource(R.string.calendar_permission_why), style = MaterialTheme.typography.bodySmall) }
            item {
                ExportRow(
                    enabled = state.settings.enabled,
                    onChange = { checked ->
                        when {
                            !checked -> actions.setEnabled(false)
                            state.hasAccess -> actions.setEnabled(true)
                            else -> requestAccess()
                        }
                    },
                )
            }
            if (state.loaded) {
                item { Text(syncStatus(state), style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.loaded && !state.hasAccess) {
                item {
                    OutlinedButton(onClick = requestAccess) { Text(stringResource(R.string.calendar_grant_access)) }
                }
            }
            if (state.hasAccess) {
                destinationItems(state) { calendar ->
                    val moving =
                        state.settings.calendarId != null &&
                            calendar.id != state.settings.calendarId &&
                            linkedCount > 0
                    if (moving) {
                        pendingCalendar = calendar
                    } else {
                        actions.selectCalendar(calendar)
                    }
                }
                item { AllDayRow(allDay = state.settings.allDay, onChange = { actions.setAllDay(it) }) }
            }
            eventItems(
                events = state.events,
                onOpen = { openEvent(context, it) },
                onExportAgain = { actions.exportAgain(it) },
            )
            if (state.settings.enabled || state.events.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = { confirmRemove = true }) {
                        Text(stringResource(R.string.calendar_remove_action))
                    }
                }
            }
        }
    }

    pendingCalendar?.let { calendar ->
        MoveCalendarDialog(
            linkedCount = linkedCount,
            onConfirm = {
                pendingCalendar = null
                actions.selectCalendar(calendar)
            },
            onDismiss = { pendingCalendar = null },
        )
    }
    if (confirmRemove) {
        RemoveEventsDialog(
            onConfirm = {
                confirmRemove = false
                actions.removeAllEvents()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

private fun LazyListScope.destinationItems(
    state: CalendarSettingsUiState,
    onSelect: (CalendarInfo) -> Unit,
) {
    item { Text(stringResource(R.string.calendar_destination_title), style = MaterialTheme.typography.titleMedium) }
    if (state.calendars.isEmpty()) {
        item { Text(stringResource(R.string.calendar_destination_none), style = MaterialTheme.typography.bodyMedium) }
    }
    items(state.calendars, key = { it.id }) { calendar ->
        CalendarRow(calendar, selected = calendar.id == state.settings.calendarId) { onSelect(calendar) }
    }
}

private fun LazyListScope.eventItems(
    events: List<LinkedEvent>,
    onOpen: (Long) -> Unit,
    onExportAgain: (UUID) -> Unit,
) {
    if (events.isEmpty()) return
    item { Text(stringResource(R.string.calendar_events_title), style = MaterialTheme.typography.titleMedium) }
    items(events, key = { it.scheduleId }) { event ->
        LinkedEventRow(event = event, onOpen = onOpen, onExportAgain = onExportAgain)
    }
}

@Composable
private fun ExportRow(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.calendar_enable),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun AllDayRow(
    allDay: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.calendar_all_day), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.calendar_all_day_note), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = allDay, onCheckedChange = onChange)
    }
}

@Composable
private fun syncStatus(state: CalendarSettingsUiState): String =
    when {
        !state.settings.enabled -> stringResource(R.string.calendar_status_off)
        state.syncState == CalendarState.PERMISSION_MISSING ->
            stringResource(
                R.string.calendar_status_permission_missing,
            )
        state.syncState == CalendarState.NO_DESTINATION -> stringResource(R.string.calendar_status_no_destination)
        state.syncState == CalendarState.DESTINATION_MISSING ->
            stringResource(
                R.string.calendar_status_destination_missing,
            )
        else -> {
            val lastSync =
                state.settings.lastSyncMillis?.let {
                    lastSyncFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                }
            if (lastSync == null) {
                stringResource(R.string.calendar_status_pending)
            } else {
                stringResource(R.string.calendar_status_synced, lastSync)
            }
        }
    }

@Composable
private fun CalendarRow(
    calendar: CalendarInfo,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(calendar.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(calendar.accountName, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LinkedEventRow(
    event: LinkedEvent,
    onOpen: (Long) -> Unit,
    onExportAgain: (UUID) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                event.title ?: stringResource(R.string.calendar_event_stopped_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(eventStatus(event.status), style = MaterialTheme.typography.bodySmall)
        }
        val eventId = event.eventId
        if (event.status == CalendarLinkStatus.REMOVED_IN_CALENDAR) {
            TextButton(onClick = { onExportAgain(event.scheduleId) }) {
                Text(stringResource(R.string.calendar_event_export_again))
            }
        } else if (eventId != null) {
            TextButton(onClick = { onOpen(eventId) }) { Text(stringResource(R.string.calendar_event_open)) }
        }
    }
}

@Composable
private fun eventStatus(status: CalendarLinkStatus): String =
    stringResource(
        when (status) {
            CalendarLinkStatus.LINKED -> R.string.calendar_event_status_linked
            CalendarLinkStatus.ENDED -> R.string.calendar_event_status_ended
            CalendarLinkStatus.REMOVED_IN_CALENDAR -> R.string.calendar_event_status_removed
        },
    )

@Composable
private fun MoveCalendarDialog(
    linkedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_move_title)) },
        text = { Text(stringResource(R.string.calendar_move_message, linkedCount)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.calendar_move_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RemoveEventsDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calendar_remove_title)) },
        text = { Text(stringResource(R.string.calendar_remove_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.calendar_remove_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun openEvent(
    context: Context,
    eventId: Long,
) {
    val intent =
        Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.calendar_open_failed, Toast.LENGTH_SHORT).show()
    }
}
