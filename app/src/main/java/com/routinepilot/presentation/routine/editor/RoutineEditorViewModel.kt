package com.routinepilot.presentation.routine.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.RoutineStep
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.validation.RoutineValidationError
import com.routinepilot.domain.validation.RoutineValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val ROUTINE_ID_ARG = "routineId"

data class StepDraft(
    val id: UUID,
    val createdAt: Instant,
    val title: String = "",
    val description: String = "",
    val durationMinutes: String = "",
    val titleError: RoutineValidationError? = null,
    val descriptionError: RoutineValidationError? = null,
    val durationError: RoutineValidationError? = null,
) {
    val hasErrors: Boolean
        get() = titleError != null || descriptionError != null || durationError != null
}

data class RemovedStep(val step: StepDraft, val index: Int)

data class RoutineEditorUiState(
    val isNewRoutine: Boolean = true,
    val isLoading: Boolean = !isNewRoutine,
    val isMissing: Boolean = false,
    val title: String = "",
    val description: String = "",
    val steps: List<StepDraft> = emptyList(),
    val titleError: RoutineValidationError? = null,
    val descriptionError: RoutineValidationError? = null,
    val showErrors: Boolean = false,
    val removedStep: RemovedStep? = null,
    val isClosed: Boolean = false,
) {
    val canSave: Boolean
        get() = titleError == null && descriptionError == null && steps.none { it.hasErrors }

    val totalDurationSeconds: Int
        get() = steps.sumOf { RoutineValidator.durationSecondsOf(it.durationMinutes) ?: 0 }
}

interface RoutineEditorActions {
    fun onTitleChange(value: String)

    fun onDescriptionChange(value: String)

    fun onAddStep()

    fun onStepTitleChange(stepId: UUID, value: String)

    fun onStepDescriptionChange(stepId: UUID, value: String)

    fun onStepDurationChange(stepId: UUID, value: String)

    /** Moves the step by [offset] positions; out-of-range moves are ignored. */
    fun onMoveStep(stepId: UUID, offset: Int)

    fun onRemoveStep(stepId: UUID)

    fun onUndoRemoveStep()

    fun onRemovedStepShown()

    fun onSave()

    fun onDelete()
}

@HiltViewModel
class RoutineEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
) : ViewModel(), RoutineEditorActions {
    private val routineId: UUID? = savedStateHandle.get<String>(ROUTINE_ID_ARG)?.let(UUID::fromString)
    private val _uiState = MutableStateFlow(RoutineEditorUiState(isNewRoutine = routineId == null))
    val uiState: StateFlow<RoutineEditorUiState> = _uiState.asStateFlow()

    private var existing: Routine? = null

    init {
        routineId?.let { id -> viewModelScope.launch { load(id) } }
    }

    private suspend fun load(id: UUID) {
        val routine = routineRepository.observeRoutine(id).first()
        if (routine == null) {
            _uiState.update { it.copy(isLoading = false, isMissing = true) }
            return
        }
        existing = routine
        val steps = routineRepository.observeSteps(id).first()
        _uiState.update {
            it.copy(
                isLoading = false,
                title = routine.title,
                description = routine.description.orEmpty(),
                steps = steps.map { step -> step.toDraft() },
            ).validated()
        }
    }

    override fun onTitleChange(value: String) = updateState { copy(title = value) }

    override fun onDescriptionChange(value: String) = updateState { copy(description = value) }

    override fun onAddStep() = updateState {
        copy(steps = steps + StepDraft(id = UUID.randomUUID(), createdAt = Instant.now()))
    }

    override fun onStepTitleChange(stepId: UUID, value: String) = editStep(stepId) { copy(title = value) }

    override fun onStepDescriptionChange(stepId: UUID, value: String) = editStep(stepId) { copy(description = value) }

    override fun onStepDurationChange(stepId: UUID, value: String) = editStep(stepId) { copy(durationMinutes = value) }

    override fun onMoveStep(stepId: UUID, offset: Int) = updateState {
        val from = steps.indexOfFirst { it.id == stepId }
        val to = from + offset
        if (from < 0 || to !in steps.indices) {
            this
        } else {
            copy(steps = steps.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    override fun onRemoveStep(stepId: UUID) = updateState {
        val index = steps.indexOfFirst { it.id == stepId }
        if (index < 0) {
            this
        } else {
            copy(steps = steps.filterNot { it.id == stepId }, removedStep = RemovedStep(steps[index], index))
        }
    }

    override fun onUndoRemoveStep() = updateState {
        removedStep?.let { removed ->
            copy(
                steps = steps.toMutableList().apply { add(minOf(removed.index, size), removed.step) },
                removedStep = null,
            )
        } ?: this
    }

    override fun onRemovedStepShown() = updateState { copy(removedStep = null) }

    override fun onSave() {
        val state = _uiState.value
        if (state.isLoading || state.isMissing) return
        if (!state.canSave) {
            _uiState.update { it.copy(showErrors = true) }
            return
        }
        viewModelScope.launch {
            val routine = Routine(
                id = routineId ?: UUID.randomUUID(),
                title = state.title.trim(),
                position = existing?.position ?: routineRepository.observeRoutines().first().size,
                createdAt = existing?.createdAt ?: Instant.now(),
                description = state.description.trim().ifBlank { null },
            )
            val steps = state.steps.mapIndexed { index, draft ->
                RoutineStep(
                    id = draft.id,
                    routineId = routine.id,
                    title = draft.title.trim(),
                    position = index,
                    createdAt = draft.createdAt,
                    description = draft.description.trim().ifBlank { null },
                    durationSeconds = RoutineValidator.durationSecondsOf(draft.durationMinutes),
                )
            }
            routineRepository.saveRoutine(routine, steps)
            _uiState.update { it.copy(isClosed = true) }
        }
    }

    override fun onDelete() {
        val id = routineId ?: return
        viewModelScope.launch {
            routineRepository.deleteRoutine(id)
            _uiState.update { it.copy(isClosed = true) }
        }
    }

    private fun updateState(change: RoutineEditorUiState.() -> RoutineEditorUiState) {
        _uiState.update { it.change().validated() }
    }

    private fun editStep(stepId: UUID, change: StepDraft.() -> StepDraft) = updateState {
        copy(steps = steps.map { if (it.id == stepId) it.change() else it })
    }

    private fun RoutineEditorUiState.validated() = copy(
        titleError = RoutineValidator.validateTitle(title),
        descriptionError = RoutineValidator.validateDescription(description),
        steps = steps.map { it.validated() },
    )

    private fun StepDraft.validated() = copy(
        titleError = RoutineValidator.validateTitle(title),
        descriptionError = RoutineValidator.validateDescription(description),
        durationError = RoutineValidator.validateDurationMinutes(durationMinutes),
    )

    private fun RoutineStep.toDraft() = StepDraft(
        id = id,
        createdAt = createdAt,
        title = title,
        description = description.orEmpty(),
        durationMinutes = durationSeconds?.div(SECONDS_PER_MINUTE)?.toString().orEmpty(),
    )

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}
