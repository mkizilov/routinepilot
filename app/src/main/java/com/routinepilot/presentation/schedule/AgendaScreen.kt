package com.routinepilot.presentation.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.schedule.AgendaItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val TAG_AGENDA_EMPTY = "agenda_empty"

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun AgendaRoute(
    onNewSchedule: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenHelp: () -> Unit = {},
    viewModel: AgendaViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AgendaScreen(
        state = state,
        actions = viewModel,
        onNewSchedule = onNewSchedule,
        onOpenSchedules = onOpenSchedules,
        onOpenProgress = onOpenProgress,
        onOpenHelp = onOpenHelp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaScreen(
    state: AgendaUiState,
    actions: AgendaActions,
    onNewSchedule: () -> Unit,
    onOpenSchedules: () -> Unit,
    onOpenProgress: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agenda_title)) },
                actions = {
                    TextButton(onClick = onOpenHelp) { Text(stringResource(R.string.agenda_help)) }
                    TextButton(onClick = onOpenProgress) { Text(stringResource(R.string.agenda_progress)) }
                    TextButton(onClick = onOpenSchedules) { Text(stringResource(R.string.agenda_manage_schedules)) }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewSchedule) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.agenda_new_schedule))
            }
        },
    ) { innerPadding ->
        val agenda = state.agenda
        if (state.loaded && agenda.current.isEmpty() && agenda.upcoming.isEmpty() && agenda.earlier.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.agenda_empty),
                    modifier = Modifier.testTag(TAG_AGENDA_EMPTY),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                section(R.string.agenda_section_now, agenda.current, state.zone, actions, showActions = true)
                section(R.string.agenda_section_upcoming, agenda.upcoming, state.zone, actions, showActions = true)
                section(R.string.agenda_section_earlier, agenda.earlier, state.zone, actions, showActions = false)
            }
        }
    }
}

private fun LazyListScope.section(
    titleRes: Int,
    rows: List<AgendaItem>,
    zone: ZoneId,
    actions: AgendaActions,
    showActions: Boolean,
) {
    if (rows.isEmpty()) return
    item(key = "header-$titleRes") {
        Text(
            stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
    items(rows, key = { it.occurrenceId }) { item ->
        AgendaRow(item, zone, actions, showActions)
    }
}

@Composable
private fun AgendaRow(
    item: AgendaItem,
    zone: ZoneId,
    actions: AgendaActions,
    showActions: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                TIME_FORMAT.format(item.scheduledAt.atZone(zone)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    statusLabel(item),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (showActions && item.status.isPending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.complete(item.occurrenceId) }) {
                    Text(stringResource(R.string.agenda_action_complete))
                }
                TextButton(onClick = { actions.skip(item.occurrenceId) }) {
                    Text(stringResource(R.string.agenda_action_skip))
                }
                TextButton(onClick = { actions.postpone(item.occurrenceId, item.scheduledAt) }) {
                    Text(stringResource(R.string.agenda_action_postpone))
                }
            }
        }
    }
}

@Composable
private fun statusLabel(item: AgendaItem): String =
    when (item.status) {
        OccurrenceStatus.COMPLETED -> stringResource(R.string.agenda_status_completed)
        OccurrenceStatus.SKIPPED -> stringResource(R.string.agenda_status_skipped)
        OccurrenceStatus.PLANNED, OccurrenceStatus.RESCHEDULED ->
            if (item.isMoved) {
                stringResource(R.string.agenda_status_moved)
            } else {
                stringResource(R.string.agenda_status_planned)
            }
    }
