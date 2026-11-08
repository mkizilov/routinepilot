package com.routinepilot.presentation.schedule

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.model.Routine
import com.routinepilot.domain.model.Schedule
import com.routinepilot.domain.model.ScheduleTarget
import com.routinepilot.domain.model.Task
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.domain.repository.TaskRepository
import com.routinepilot.domain.schedule.Recurrence
import com.routinepilot.domain.schedule.RecurrenceKind
import com.routinepilot.domain.schedule.RecurrencePattern
import com.routinepilot.domain.validation.RoutineValidator
import com.routinepilot.domain.validation.ScheduleValidationError
import com.routinepilot.domain.validation.ScheduleValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

const val SCHEDULE_ID_ARG = "scheduleId"
private const val DEFAULT_INTERVAL = "2"

enum class ScheduleTargetKind { ROUTINE, TASK }

data class ScheduleEditorUiState(
    val isNew: Boolean = true,
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val targetKind: ScheduleTargetKind = ScheduleTargetKind.ROUTINE,
    val targetLocked: Boolean = false,
    val routines: List<Routine> = emptyList(),
    val selectedRoutineId: UUID? = null,
    val taskTitle: String = "",
    val pattern: RecurrenceKind = RecurrenceKind.DAILY,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val intervalText: String = DEFAULT_INTERVAL,
    val hourText: String = "07",
    val minuteText: String = "00",
    val startDateText: String = "",
    val errors: Set<ScheduleValidationError> = emptySet(),
    val showErrors: Boolean = false,
    val isClosed: Boolean = false,
)

interface ScheduleEditorActions {
    fun selectRoutine(routineId: UUID)

    fun setTargetKind(kind: ScheduleTargetKind)

    fun setTaskTitle(value: String)

    fun setPattern(kind: RecurrenceKind)

    fun toggleWeekday(day: DayOfWeek)

    fun setInterval(value: String)

    fun setHour(value: String)

    fun setMinute(value: String)

    fun setStartDate(value: String)

    fun save()

    fun delete()
}

/**
 * Creates or edits one schedule. Field text is kept as typed and validated by the domain after every change, so
 * the error list always matches what Save would reject.
 */
@HiltViewModel
class ScheduleEditorViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val scheduleRepository: ScheduleRepository,
        private val routineRepository: RoutineRepository,
        private val taskRepository: TaskRepository,
        private val clock: Clock,
    ) : ViewModel(),
        ScheduleEditorActions {
        private val scheduleId: UUID? = savedStateHandle.get<String>(SCHEDULE_ID_ARG)?.let(UUID::fromString)
        private val _uiState =
            MutableStateFlow(
                ScheduleEditorUiState(isNew = scheduleId == null, startDateText = LocalDate.now(clock).toString()),
            )
        val uiState: StateFlow<ScheduleEditorUiState> = _uiState.asStateFlow()

        private var existing: Schedule? = null

        init {
            viewModelScope.launch {
                val loadedRoutines = routineRepository.observeRoutines().first()
                edit { copy(routines = loadedRoutines, isLoading = isLoading && scheduleId != null) }
                scheduleId?.let { load(it) }
            }
        }

        override fun selectRoutine(routineId: UUID) = edit { copy(selectedRoutineId = routineId) }

        override fun setTargetKind(kind: ScheduleTargetKind) =
            edit {
                if (targetLocked) this else copy(targetKind = kind)
            }

        override fun setTaskTitle(value: String) = edit { copy(taskTitle = value) }

        override fun setPattern(kind: RecurrenceKind) = edit { copy(pattern = kind) }

        override fun toggleWeekday(day: DayOfWeek) =
            edit {
                copy(weekdays = if (day in weekdays) weekdays - day else weekdays + day)
            }

        override fun setInterval(value: String) = edit { copy(intervalText = value) }

        override fun setHour(value: String) = edit { copy(hourText = value) }

        override fun setMinute(value: String) = edit { copy(minuteText = value) }

        override fun setStartDate(value: String) = edit { copy(startDateText = value) }

        override fun save() {
            val state = _uiState.value
            if (state.isLoading || state.isMissing) return
            if (state.errors.isNotEmpty()) {
                _uiState.update { it.copy(showErrors = true) }
                return
            }
            viewModelScope.launch {
                val now = Instant.now(clock)
                val time = ScheduleValidator.parseTime(state.hourText, state.minuteText) ?: return@launch
                val startDate = ScheduleValidator.parseStartDate(state.startDateText) ?: return@launch
                val target =
                    when (state.targetKind) {
                        ScheduleTargetKind.ROUTINE ->
                            ScheduleTarget.RoutineTarget(
                                checkNotNull(state.selectedRoutineId),
                            )
                        ScheduleTargetKind.TASK -> ScheduleTarget.TaskTarget(saveTask(state.taskTitle.trim(), now))
                    }
                val schedule =
                    Schedule(
                        id = scheduleId ?: UUID.randomUUID(),
                        target = target,
                        recurrence = Recurrence(state.toPattern(), time, startDate),
                        zone = clock.zone,
                        createdAt = existing?.createdAt ?: now,
                        modifiedAt = now,
                    )
                scheduleRepository.saveSchedule(schedule, now)
                _uiState.update { it.copy(isClosed = true) }
            }
        }

        override fun delete() {
            val id = scheduleId ?: return
            viewModelScope.launch {
                scheduleRepository.deleteSchedule(id, Instant.now(clock))
                _uiState.update { it.copy(isClosed = true) }
            }
        }

        private suspend fun load(id: UUID) {
            val schedule = scheduleRepository.observeSchedule(id).first()
            if (schedule == null) {
                edit { copy(isLoading = false, isMissing = true) }
                return
            }
            existing = schedule
            val target = schedule.target
            val tasks = if (target is ScheduleTarget.TaskTarget) taskRepository.observeTasks().first() else emptyList()
            val recurrence = schedule.recurrence
            val pattern = recurrence.pattern
            edit {
                copy(
                    isLoading = false,
                    targetLocked = true,
                    targetKind =
                        when (target) {
                            is ScheduleTarget.RoutineTarget -> ScheduleTargetKind.ROUTINE
                            is ScheduleTarget.TaskTarget -> ScheduleTargetKind.TASK
                        },
                    selectedRoutineId = (target as? ScheduleTarget.RoutineTarget)?.routineId,
                    taskTitle =
                        (target as? ScheduleTarget.TaskTarget)
                            ?.let { task -> tasks.firstOrNull { it.id == task.taskId }?.title }
                            .orEmpty(),
                    pattern = pattern.kind,
                    weekdays = (pattern as? RecurrencePattern.Weekdays)?.days.orEmpty(),
                    intervalText =
                        (pattern as? RecurrencePattern.EveryNDays)?.intervalDays?.toString() ?: DEFAULT_INTERVAL,
                    hourText = "%02d".format(recurrence.time.hour),
                    minuteText = "%02d".format(recurrence.time.minute),
                    startDateText = recurrence.startDate.toString(),
                )
            }
        }

        /** Creates the task on first save, or renames the existing task when its schedule is edited. */
        private suspend fun saveTask(
            title: String,
            now: Instant,
        ): UUID {
            val existingTaskId = (existing?.target as? ScheduleTarget.TaskTarget)?.taskId
            val tasks = taskRepository.observeTasks().first()
            val current = tasks.firstOrNull { it.id == existingTaskId }
            if (current != null) {
                taskRepository.saveTask(current.copy(title = title))
                return current.id
            }
            val id = UUID.randomUUID()
            taskRepository.saveTask(
                Task(
                    id = id,
                    title = title,
                    isCompleted = false,
                    routineId = null,
                    position = tasks.size,
                    createdAt = now,
                ),
            )
            return id
        }

        private fun ScheduleEditorUiState.toPattern(): RecurrencePattern =
            when (pattern) {
                RecurrenceKind.DAILY -> RecurrencePattern.Daily
                RecurrenceKind.WEEKDAYS -> RecurrencePattern.Weekdays(weekdays)
                RecurrenceKind.EVERY_N_DAYS ->
                    RecurrencePattern.EveryNDays(checkNotNull(ScheduleValidator.parseInterval(intervalText)))
            }

        /** Applies a change and recomputes the validation errors from the resulting state. */
        private fun edit(change: ScheduleEditorUiState.() -> ScheduleEditorUiState) {
            _uiState.update { state ->
                val changed = state.change()
                changed.copy(errors = validate(changed))
            }
        }

        private fun validate(state: ScheduleEditorUiState): Set<ScheduleValidationError> =
            buildSet {
                if (state.targetKind == ScheduleTargetKind.ROUTINE && state.selectedRoutineId == null) {
                    add(ScheduleValidationError.MISSING_ROUTINE)
                }
                if (state.targetKind == ScheduleTargetKind.TASK &&
                    RoutineValidator.validateTitle(state.taskTitle) != null
                ) {
                    add(ScheduleValidationError.BLANK_TASK_TITLE)
                }
                if (state.pattern == RecurrenceKind.WEEKDAYS && state.weekdays.isEmpty()) {
                    add(ScheduleValidationError.NO_WEEKDAYS)
                }
                val intervalInvalid = ScheduleValidator.parseInterval(state.intervalText) == null
                if (state.pattern == RecurrenceKind.EVERY_N_DAYS && intervalInvalid) {
                    add(ScheduleValidationError.INTERVAL_OUT_OF_RANGE)
                }
                if (ScheduleValidator.parseTime(state.hourText, state.minuteText) == null) {
                    add(ScheduleValidationError.INVALID_TIME)
                }
                if (ScheduleValidator.parseStartDate(state.startDateText) == null) {
                    add(ScheduleValidationError.INVALID_START_DATE)
                }
            }
    }
