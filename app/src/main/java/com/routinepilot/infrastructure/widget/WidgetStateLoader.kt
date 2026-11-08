package com.routinepilot.infrastructure.widget

import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.execution.TimedStep
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.domain.widget.QuickStartState
import com.routinepilot.domain.widget.QuickStartStateBuilder
import com.routinepilot.domain.widget.RunWidgetState
import com.routinepilot.domain.widget.RunWidgetStateBuilder
import com.routinepilot.domain.widget.TodayWidgetState
import com.routinepilot.domain.widget.TodayWidgetStateBuilder
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads everything the widgets show from the same sources the app uses: Room for routines and the agenda, and the
 * DataStore checkpoint for the run. The widgets keep no copy of their own, so they cannot disagree with the app.
 */
@Singleton
class WidgetStateLoader @Inject constructor(
    private val scheduleRepository: ScheduleRepository,
    private val routineRepository: RoutineRepository,
    private val activeExecutionStore: ActiveExecutionStore,
    private val clock: Clock,
) {
    suspend fun today(): TodayWidgetState {
        val zone = clock.zone
        val day = LocalDate.now(clock)
        val agenda = scheduleRepository.observeAgenda(
            from = day.atStartOfDay(zone).toInstant(),
            to = day.plusDays(1).atStartOfDay(zone).toInstant(),
        ).first()
        return TodayWidgetStateBuilder.build(agenda, Instant.now(clock), zone)
    }

    suspend fun run(): RunWidgetState {
        val active = activeExecutionStore.observe().first() ?: return RunWidgetState.NoActiveRun
        val title = routineRepository.observeRoutine(active.routineId).first()?.title
        val plan = routineRepository.observeSteps(active.routineId).first()
            .map { TimedStep(it.id, it.title, it.durationSeconds) }
        return RunWidgetStateBuilder.build(active, title, plan, Instant.now(clock).toEpochMilli())
    }

    suspend fun quickStart(): QuickStartState =
        QuickStartStateBuilder.build(routineRepository.observeRoutines().first())
}
