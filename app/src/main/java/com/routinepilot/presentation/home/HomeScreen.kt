package com.routinepilot.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.model.Routine
import java.util.UUID

@Composable
fun HomeRoute(
    onOpenRoutine: (UUID) -> Unit,
    onNewRoutine: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val deletedMessage = stringResource(R.string.home_routine_deleted)
    val duplicatedMessage = stringResource(R.string.home_routine_duplicated)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.RoutineDeleted -> {
                    val result = snackbarHostState.showSnackbar(
                        message = deletedMessage,
                        actionLabel = undoLabel,
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.id)
                }
                HomeEvent.RoutineDuplicated -> snackbarHostState.showSnackbar(duplicatedMessage)
            }
        }
    }
    HomeScreen(
        uiState = uiState,
        onOpenRoutine = onOpenRoutine,
        onNewRoutine = onNewRoutine,
        actions = viewModel,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenRoutine: (UUID) -> Unit,
    onNewRoutine: () -> Unit,
    actions: HomeActions,
    snackbarHostState: SnackbarHostState,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewRoutine) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_new_routine))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (uiState.routines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.home_empty_state))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                items(uiState.routines, key = { it.id }) { routine ->
                    RoutineRow(
                        routine = routine,
                        onOpen = { onOpenRoutine(routine.id) },
                        onDuplicate = { actions.duplicateRoutine(routine.id) },
                        onDelete = { actions.deleteRoutine(routine.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    onOpen: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(routine.title, style = MaterialTheme.typography.titleMedium)
            routine.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RoutineActionsMenu(onDuplicate = onDuplicate, onDelete = onDelete)
    }
}

@Composable
private fun RoutineActionsMenu(onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.home_more_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_action_duplicate)) },
                onClick = {
                    expanded = false
                    onDuplicate()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_action_delete)) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}
