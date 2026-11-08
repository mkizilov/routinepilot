package com.routinepilot.presentation.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.domain.repository.TaskRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

data class ScheduleListItem(val schedule: Schedule, val title: String)

data class ScheduleListUiState(
    val loaded: Boolean = false,
    val items: List<ScheduleListItem> = emptyList(),
)

interface ScheduleListActions {
    fun delete(scheduleId: UUID)
}

@HiltViewModel
class ScheduleListViewModel @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    routineRepository: RoutineRepository,
    taskRepository: TaskRepository,
    private val clock: Clock,
) : ViewModel(), ScheduleListActions {
    val uiState: StateFlow<ScheduleListUiState> = combine(
        scheduleRepository.observeSchedules(),
        routineRepository.observeRoutines(),
        taskRepository.observeTasks(),
    ) { schedules, routines, tasks ->
        val routineTitles = routines.associate { it.id to it.title }
        val taskTitles = tasks.associate { it.id to it.title }
        val items = schedules.mapNotNull { schedule ->
            val title = when (val target = schedule.target) {
                is ScheduleTarget.RoutineTarget -> routineTitles[target.routineId]
                is ScheduleTarget.TaskTarget -> taskTitles[target.taskId]
            }
            title?.let { ScheduleListItem(schedule, it) }
        }
        ScheduleListUiState(loaded = true, items = items)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleListUiState())

    override fun delete(scheduleId: UUID) {
        viewModelScope.launch { scheduleRepository.deleteSchedule(scheduleId, Instant.now(clock)) }
    }
}
