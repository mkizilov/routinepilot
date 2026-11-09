package com.routinepilot.presentation.routine.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.routinepilot.R
import com.routinepilot.domain.validation.RoutineValidationError
import com.routinepilot.presentation.common.CenteredContent
import com.routinepilot.presentation.routine.formatDuration

internal const val TAG_TITLE = "editor_title"
internal const val TAG_DESCRIPTION = "editor_description"
internal const val TAG_TOTAL = "editor_total"
internal const val TAG_ADD_STEP = "editor_add_step"
internal const val TAG_DELETE = "editor_delete"
internal const val TAG_SAVE = "editor_save"
internal const val TAG_LOADING = "editor_loading"
internal const val TAG_EDITOR_LIST = "editor_list"
internal const val TAG_STEP_PREFIX = "editor_step_"
internal const val TAG_STEP_TITLE_PREFIX = "editor_step_title_"
internal const val TAG_STEP_DURATION_PREFIX = "editor_step_duration_"

@Composable
fun RoutineEditorRoute(
    onClose: () -> Unit,
    viewModel: RoutineEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.isClosed) {
        if (uiState.isClosed) onClose()
    }
    RoutineEditorScreen(
        state = uiState,
        actions = viewModel,
        onBack = onClose,
        snackbarHostState = snackbarHostState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(
    state: RoutineEditorUiState,
    actions: RoutineEditorActions,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    LaunchedEffect(state.removedStep) {
        val removed = state.removedStep ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = context.getString(R.string.editor_step_removed, removed.step.title),
                actionLabel = context.getString(R.string.undo),
                withDismissAction = true,
            )
        if (result == SnackbarResult.ActionPerformed) actions.onUndoRemoveStep() else actions.onRemovedStepShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (state.isNewRoutine) R.string.editor_title_new else R.string.editor_title_edit,
                        ),
                    )
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
                onClick = actions::onSave,
                enabled = !state.isLoading && !state.isMissing,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag(TAG_SAVE),
            ) {
                Text(stringResource(R.string.editor_save))
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (state.isMissing) {
            Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.editor_missing))
            }
        } else {
            EditorContent(state, actions, innerPadding)
        }
    }
}

@Composable
private fun EditorContent(
    state: RoutineEditorUiState,
    actions: RoutineEditorActions,
    padding: PaddingValues,
) {
    CenteredContent(padding) {
        if (state.isLoading) LoadingRoutine() else EditorForm(state, actions)
    }
}

@Composable
private fun LoadingRoutine() {
    val label = stringResource(R.string.editor_loading)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.testTag(TAG_LOADING).semantics { contentDescription = label })
    }
}

@Composable
private fun EditorForm(
    state: RoutineEditorUiState,
    actions: RoutineEditorActions,
) {
    val focus = LocalFocusManager.current
    val next = remember(focus) { KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(TAG_EDITOR_LIST),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = actions::onTitleChange,
                label = { Text(stringResource(R.string.editor_name_label)) },
                singleLine = true,
                isError = state.showErrors && state.titleError != null,
                supportingText = { ErrorText(state.showErrors, state.titleError) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = next,
                modifier = Modifier.fillMaxWidth().testTag(TAG_TITLE),
            )
        }
        item {
            OutlinedTextField(
                value = state.description,
                onValueChange = actions::onDescriptionChange,
                label = { Text(stringResource(R.string.editor_description_label)) },
                isError = state.showErrors && state.descriptionError != null,
                supportingText = { ErrorText(state.showErrors, state.descriptionError) },
                modifier = Modifier.fillMaxWidth().testTag(TAG_DESCRIPTION),
            )
        }
        item {
            Text(
                stringResource(R.string.editor_total_duration, formatDuration(state.totalDurationSeconds)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag(TAG_TOTAL),
            )
        }
        item {
            Text(
                stringResource(R.string.editor_steps_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (state.steps.isEmpty()) {
            item {
                Text(stringResource(R.string.editor_no_steps), style = MaterialTheme.typography.bodyMedium)
            }
        }
        itemsIndexed(state.steps, key = { _, step -> step.id }) { index, step ->
            StepEditor(
                index = index,
                count = state.steps.size,
                step = step,
                showErrors = state.showErrors,
                actions = actions,
                modifier = Modifier.animateItem(),
            )
        }
        item {
            OutlinedButton(onClick = actions::onAddStep, modifier = Modifier.testTag(TAG_ADD_STEP)) {
                Text(stringResource(R.string.editor_add_step))
            }
        }
        if (!state.isNewRoutine) {
            item { DeleteRoutineButton(onConfirm = actions::onDelete) }
        }
    }
}

@Composable
private fun StepEditor(
    index: Int,
    count: Int,
    step: StepDraft,
    showErrors: Boolean,
    actions: RoutineEditorActions,
    modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    val next = remember(focus) { KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }) }
    val done = remember(focus) { KeyboardActions(onDone = { focus.clearFocus() }) }
    val stepNumber = index + 1
    Column(modifier.fillMaxWidth().testTag("$TAG_STEP_PREFIX$index")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.editor_step_number, stepNumber),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.onMoveStep(step.id, -1) }, enabled = index > 0) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.editor_move_up, stepNumber),
                )
            }
            IconButton(onClick = { actions.onMoveStep(step.id, 1) }, enabled = index < count - 1) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.editor_move_down, stepNumber),
                )
            }
            IconButton(onClick = { actions.onRemoveStep(step.id) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.editor_remove_step, stepNumber),
                )
            }
        }
        OutlinedTextField(
            value = step.title,
            onValueChange = { actions.onStepTitleChange(step.id, it) },
            label = { Text(stringResource(R.string.editor_step_name_label)) },
            singleLine = true,
            isError = showErrors && step.titleError != null,
            supportingText = { ErrorText(showErrors, step.titleError) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = next,
            modifier = Modifier.fillMaxWidth().testTag("$TAG_STEP_TITLE_PREFIX$index"),
        )
        OutlinedTextField(
            value = step.description,
            onValueChange = { actions.onStepDescriptionChange(step.id, it) },
            label = { Text(stringResource(R.string.editor_step_description_label)) },
            isError = showErrors && step.descriptionError != null,
            supportingText = { ErrorText(showErrors, step.descriptionError) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = next,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = step.durationMinutes,
            onValueChange = { actions.onStepDurationChange(step.id, it) },
            label = { Text(stringResource(R.string.editor_step_minutes_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = done,
            isError = showErrors && step.durationError != null,
            supportingText = { ErrorText(showErrors, step.durationError) },
            modifier = Modifier.fillMaxWidth().testTag("$TAG_STEP_DURATION_PREFIX$index"),
        )
    }
}

@Composable
private fun DeleteRoutineButton(onConfirm: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }, modifier = Modifier.testTag(TAG_DELETE)) {
        Text(stringResource(R.string.editor_delete_routine), color = MaterialTheme.colorScheme.error)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.editor_delete_title)) },
            text = { Text(stringResource(R.string.editor_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onConfirm()
                }) {
                    Text(stringResource(R.string.editor_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ErrorText(
    showErrors: Boolean,
    error: RoutineValidationError?,
) {
    if (showErrors && error != null) {
        Text(
            when (error) {
                RoutineValidationError.BLANK_TITLE -> stringResource(R.string.error_blank_title)
                RoutineValidationError.TITLE_TOO_LONG -> stringResource(R.string.error_title_too_long)
                RoutineValidationError.DESCRIPTION_TOO_LONG -> stringResource(R.string.error_description_too_long)
                RoutineValidationError.DURATION_INVALID -> stringResource(R.string.error_duration_invalid)
            },
        )
    }
}
