package com.routinepilot.infrastructure.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.infrastructure.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val MINUTE_MILLIS = 60_000L
private const val TODAY_REFRESH_MILLIS = 15 * MINUTE_MILLIS

/**
 * Pushes widget updates while the app process is alive: when data changes, when a run changes step or status, once a
 * minute while a routine is running (so the minutes-left figure stays right), and every fifteen minutes for today's
 * list (so items move from upcoming to due). When the process is not alive, [WidgetRefreshWorker] catches up.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scheduleRepository: ScheduleRepository,
    private val routineRepository: RoutineRepository,
    private val activeExecutionStore: ActiveExecutionStore,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { refreshAll() }
        scope.launch { watchToday() }
        scope.launch {
            routineRepository.observeRoutines().distinctUntilChanged().collect { refreshQuickStart() }
        }
        scope.launch {
            activeExecutionStore.observe()
                .map { run -> run?.let { RunKey.of(it) } }
                .distinctUntilChanged()
                .collect { refreshRun() }
        }
        scope.launch { tickRunningRoutine() }
    }

    suspend fun refreshAll() {
        refreshToday()
        refreshRun()
        refreshQuickStart()
    }

    suspend fun refreshToday() = TodayWidget().updateAll(context)

    suspend fun refreshRun() = RunWidget().updateAll(context)

    suspend fun refreshQuickStart() = QuickStartWidget().updateAll(context)

    /** Re-subscribes at each day boundary and at least every fifteen minutes, so "today" never goes stale. */
    private suspend fun watchToday() {
        while (true) {
            val zone = clock.zone
            val day = LocalDate.now(clock)
            withTimeoutOrNull(TODAY_REFRESH_MILLIS) {
                scheduleRepository.observeAgenda(
                    from = day.atStartOfDay(zone).toInstant(),
                    to = day.plusDays(1).atStartOfDay(zone).toInstant(),
                ).collect { refreshToday() }
            }
        }
    }

    private suspend fun tickRunningRoutine() {
        while (true) {
            delay(MINUTE_MILLIS)
            if (activeExecutionStore.observe().first()?.status == ExecutionStatus.RUNNING) refreshRun()
        }
    }

    /** What a run widget must redraw for. Checkpoints that only move the clock do not change it. */
    private data class RunKey(
        val sessionId: UUID,
        val status: ExecutionStatus,
        val stepIndex: Int,
    ) {
        companion object {
            fun of(run: ActiveExecution) = RunKey(run.sessionId, run.status, run.stepIndex)
        }
    }
}
