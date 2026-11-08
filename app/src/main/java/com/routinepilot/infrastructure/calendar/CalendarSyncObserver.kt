package com.routinepilot.infrastructure.calendar

import com.routinepilot.domain.repository.CalendarRepository
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.domain.repository.TaskRepository
import com.routinepilot.infrastructure.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

private const val DEBOUNCE_MILLIS = 500L

/**
 * Keeps linked events current while the app process is alive. It syncs when export is switched, when the destination or
 * the all-day choice changes, and when any schedule, routine, or task changes. A burst of edits syncs once. Settings
 * writes that only record the last sync are left out of the key, so a sync never triggers another sync.
 */
@Singleton
class CalendarSyncObserver @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val scheduleRepository: ScheduleRepository,
    private val routineRepository: RoutineRepository,
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val exportKey = calendarRepository.observeSettings()
                .map { Triple(it.enabled, it.calendarId, it.allDay) }
                .distinctUntilChanged()
            combine(
                exportKey,
                scheduleRepository.observeSchedules(),
                routineRepository.observeRoutines(),
                taskRepository.observeTasks(),
            ) { _, _, _, _ -> }
                .debounce(DEBOUNCE_MILLIS)
                .collect { calendarRepository.sync(Instant.now(clock)) }
        }
    }
}
