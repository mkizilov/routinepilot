package com.routinepilot.presentation.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.planning.FlexibleTask
import com.routinepilot.domain.planning.PlanResult
import com.routinepilot.domain.planning.PlanRequest
import com.routinepilot.domain.planning.SmartScheduler
import com.routinepilot.domain.repository.PlanningRepository
import com.routinepilot.domain.repository.PlanningSettings
import com.routinepilot.domain.repository.PlanningTaskSetting
import com.routinepilot.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** A planned task with its title. */
data class PlannedTaskRow(val title: String, val setting: PlanningTaskSetting)

/** A task that is not planned yet. */
data class CandidateTask(val taskId: UUID, val title: String)

data class PlanningUiState(
    val date: LocalDate,
    val settings: PlanningSettings? = null,
    val planned: List<PlannedTaskRow> = emptyList(),
    val candidates: List<CandidateTask> = emptyList(),
    val result: PlanResult? = null,
    val pins: Map<UUID, Int> = emptyMap(),
    val busy: Boolean = false,
    val message: PlanningMessage? = null,
)

sealed interface PlanningMessage {
    data object Applied : PlanningMessage

    data object CannotApply : PlanningMessage

    data object InvalidInput : PlanningMessage
}

/** The planner works in quarter hours, so working hours must start and end on that grid. */
private const val GRID_MINUTES = PlanRequest.DEFAULT_GRANULARITY_MINUTES

@HiltViewModel
class PlanningViewModel @Inject constructor(
    private val repository: PlanningRepository,
    private val tasks: TaskRepository,
    private val clock: Clock,
) : ViewModel() {
    private val zone: ZoneId = clock.zone
    private val date: LocalDate = LocalDate.now(clock)
    private val mutableState = MutableStateFlow(PlanningUiState(date = date))
    val uiState: StateFlow<PlanningUiState> = mutableState.asStateFlow()

    /** The newest replan. An older one is cancelled, so a slow result can never overwrite a newer one. */
    private var replanJob: Job? = null

    init {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(pins = repository.storedPins(date))
            combine(repository.observeSettings(), repository.observeTaskSettings(), tasks.observeTasks()) { s, t, a ->
                Triple(s, t, a)
            }.collect { (settings, taskSettings, active) ->
                val open = active.filterNot { it.isCompleted }
                val titles = open.associate { it.id to it.title }
                val planned = taskSettings.mapNotNull { setting ->
                    titles[setting.taskId]?.let { PlannedTaskRow(it, setting) }
                }
                val plannedIds = planned.map { it.setting.taskId }.toSet()
                val candidates = open.filterNot { it.id in plannedIds }.map { CandidateTask(it.id, it.title) }
                mutableState.value = mutableState.value.copy(
                    settings = settings,
                    planned = planned,
                    candidates = candidates,
                )
                scheduleReplan()
            }
        }
    }

    /** Re-plans from the current settings, planned tasks, and pins. Nothing is applied. */
    fun refresh() = scheduleReplan()

    fun pin(taskId: UUID, startMinute: Int) {
        mutableState.value = mutableState.value.copy(pins = mutableState.value.pins + (taskId to startMinute))
        refresh()
    }

    fun unpin(taskId: UUID) {
        mutableState.value = mutableState.value.copy(pins = mutableState.value.pins - taskId)
        refresh()
    }

    /** Moves a recommended block by [deltaMinutes] and pins it there. */
    fun nudge(taskId: UUID, deltaMinutes: Int) {
        val start = recommendedStart(taskId) ?: return
        pin(taskId, start + deltaMinutes)
    }

    fun togglePin(taskId: UUID) {
        if (taskId in mutableState.value.pins) {
            unpin(taskId)
        } else {
            recommendedStart(taskId)?.let { pin(taskId, it) }
        }
    }

    /**
     * Applies the plan for the current pins. It waits for a replan that is still running, so a pin changed a moment
     * ago is always checked before anything is written.
     */
    fun apply() {
        viewModelScope.launch {
            replanJob?.join()
            val result = mutableState.value.result ?: return@launch
            if (!result.canApply) {
                mutableState.value = mutableState.value.copy(message = PlanningMessage.CannotApply)
                return@launch
            }
            mutableState.value = mutableState.value.copy(busy = true)
            repository.applyPlan(date, result)
            mutableState.value = mutableState.value.copy(
                busy = false,
                message = PlanningMessage.Applied,
                pins = repository.storedPins(date),
            )
        }
    }

    fun saveSettings(settings: PlanningSettings) {
        if (settings.workday.start % GRID_MINUTES != 0) {
            mutableState.value = mutableState.value.copy(message = PlanningMessage.InvalidInput)
            return
        }
        viewModelScope.launch { repository.saveSettings(settings) }
    }

    fun savePlanned(setting: PlanningTaskSetting) {
        val priorities = FlexibleTask.MIN_PRIORITY..FlexibleTask.MAX_PRIORITY
        val valid = setting.durationMinutes > 0 && setting.priority in priorities
        if (!valid) {
            mutableState.value = mutableState.value.copy(message = PlanningMessage.InvalidInput)
            return
        }
        viewModelScope.launch { repository.saveTaskSetting(setting) }
    }

    fun removePlanned(taskId: UUID) {
        mutableState.value = mutableState.value.copy(pins = mutableState.value.pins - taskId)
        viewModelScope.launch { repository.removeTaskSetting(taskId) }
    }

    private fun recommendedStart(taskId: UUID): Int? =
        mutableState.value.result?.placements?.firstOrNull { it.taskId == taskId }?.range?.start

    private fun scheduleReplan() {
        replanJob?.cancel()
        replanJob = viewModelScope.launch { replan() }
    }

    private suspend fun replan() {
        val state = mutableState.value
        if (state.settings == null) return
        val request = repository.buildRequest(date, zone, state.pins)
        mutableState.value = mutableState.value.copy(result = SmartScheduler.plan(request))
    }
}
