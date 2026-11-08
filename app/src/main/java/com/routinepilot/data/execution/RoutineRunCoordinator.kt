package com.routinepilot.data.execution

import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.MonotonicClock
import com.routinepilot.domain.execution.PausedRunReminders
import com.routinepilot.domain.execution.RoutineTimer
import com.routinepilot.domain.execution.RunEvent
import com.routinepilot.domain.execution.RunForegroundLauncher
import com.routinepilot.domain.execution.RunSnapshot
import com.routinepilot.domain.execution.TimedStep
import com.routinepilot.domain.execution.TimerEvent
import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.SessionOutcome
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.repository.ExecutionRepository
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.infrastructure.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TICK_MILLIS = 250L
private const val CHECKPOINT_INTERVAL_MILLIS = 1_000L
private const val EVENT_BUFFER = 16

/**
 * Owns the one routine run the user can have at a time, for the whole process.
 *
 * The run is not tied to any screen: a ticker keeps it moving while the app is in the background, timing is
 * measured with the monotonic clock, and every change is checkpointed so a killed process can resume from the
 * last checkpoint. Only the foreground service, which the platform allows while a run is RUNNING, keeps the
 * process alive for background timing.
 *
 * A run ends as FINISHED (Finish, or the last step expiring) or CANCELLED (Cancel, or a saved plan that no longer
 * matches the routine). Either way its focused time is recorded.
 */
@Singleton
class RoutineRunCoordinator @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val executionRepository: ExecutionRepository,
    private val activeExecutionStore: ActiveExecutionStore,
    private val monotonicClock: MonotonicClock,
    private val wallClock: Clock,
    private val reminders: PausedRunReminders,
    private val foreground: RunForegroundLauncher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val timer = RoutineTimer(monotonicClock)
    private val meta = MutableStateFlow(RunSnapshot())
    private val gate = Mutex()
    private val eventFlow = MutableSharedFlow<RunEvent>(extraBufferCapacity = EVENT_BUFFER)
    private var activeRun: ActiveExecution? = null
    private var lastCheckpointMillis = 0L
    private var tickerJob: Job? = null

    val snapshot: StateFlow<RunSnapshot> = combine(timer.state, meta) { timerState, runMeta ->
        runMeta.copy(timer = timerState)
    }.stateIn(scope, SharingStarted.Eagerly, RunSnapshot())

    val events: SharedFlow<RunEvent> = eventFlow.asSharedFlow()

    /** Shows a routine on the run screen. Re-opening the routine that is already running keeps the run. */
    fun open(routineId: UUID) = serialized { load(routineId) }

    fun start() = serialized {
        val state = timer.state.value
        val routineId = meta.value.routineId
        if (state.status != ExecutionStatus.NOT_STARTED || state.steps.isEmpty() || routineId == null) {
            return@serialized
        }
        if (activeExecutionStore.observe().first() != null) {
            meta.update { it.copy(blockedByOtherRun = true) }
            return@serialized
        }
        meta.update { it.copy(blockedByOtherRun = false) }
        val startedAt = Instant.now(wallClock)
        val run = ActiveExecution(
            sessionId = UUID.randomUUID(),
            routineId = routineId,
            startedAt = startedAt,
            stepIds = state.steps.map { it.id },
            stepIndex = 0,
            elapsedMillis = 0L,
            status = ExecutionStatus.RUNNING,
        )
        activeRun = run
        handle(timer.start())
        executionRepository.startSession(
            ExecutionSession(
                id = run.sessionId,
                routineId = routineId,
                occurrenceId = null,
                startedAt = startedAt,
                endedAt = null,
                createdAt = startedAt,
                localDate = LocalDate.ofInstant(startedAt, wallClock.zone),
            ),
        )
        persist()
        onStatusChanged(ExecutionStatus.NOT_STARTED)
    }

    fun pause() = command(RoutineTimer::pause)

    fun resume() = command(RoutineTimer::resume)

    fun skip() = command(RoutineTimer::skip)

    fun previous() = command(RoutineTimer::previous)

    fun finish() = command(RoutineTimer::finish)

    fun cancel() = command(RoutineTimer::cancel)

    /** The app came back to the foreground: settle any step that ran out while the screen was away. */
    fun onForeground() = serialized {
        val before = timer.state.value.status
        handle(timer.tick())
        persist()
        onStatusChanged(before)
    }

    /** The app left the foreground: checkpoint now so a later process death loses as little as possible. */
    fun onBackground() = serialized { persist() }

    private fun command(action: RoutineTimer.() -> List<TimerEvent>) = serialized {
        adoptStoredRunIfAny()
        val before = timer.state.value.status
        handle(timer.action())
        persist()
        onStatusChanged(before)
    }

    /** Runs one operation at a time, then keeps the ticker alive while the timer is running. */
    private fun serialized(block: suspend () -> Unit) {
        scope.launch {
            gate.withLock {
                block()
                if (timer.state.value.status == ExecutionStatus.RUNNING) ensureTicker()
            }
        }
    }

    private suspend fun load(routineId: UUID) {
        if (activeRun?.routineId == routineId) {
            meta.update { it.copy(isLoading = false, isMissing = false, blockedByOtherRun = false, isClosed = false) }
            return
        }
        val other = activeRun ?: activeExecutionStore.observe().first()
        if (other != null && other.routineId != routineId) {
            meta.update { it.copy(isLoading = false, isMissing = false, blockedByOtherRun = true, isClosed = false) }
            return
        }
        val routine = routineRepository.observeRoutine(routineId).first()
        if (routine == null) {
            meta.update { RunSnapshot(routineId = routineId, isLoading = false, isMissing = true) }
            return
        }
        val plan = planOf(routineId)
        meta.update { RunSnapshot(routineId = routineId, routineTitle = routine.title, isLoading = false) }
        timer.reset()
        if (other != null) adopt(other, plan) else timer.load(plan)
    }

    /** Commands can arrive after a process restart, for example from a notification. Pick up the stored run first. */
    private suspend fun adoptStoredRunIfAny() {
        if (activeRun != null) return
        val stored = activeExecutionStore.observe().first() ?: return
        val routine = routineRepository.observeRoutine(stored.routineId).first()
        if (routine == null) {
            endRun(stored, monotonicClock.elapsedMillis(), SessionOutcome.CANCELLED, stored.focusedMillis)
            return
        }
        meta.update { RunSnapshot(routineId = stored.routineId, routineTitle = routine.title, isLoading = false) }
        timer.reset()
        adopt(stored, planOf(stored.routineId))
    }

    /**
     * Restores a stored run as PAUSED at its checkpoint. A process restart or reboot never resumes timing on its
     * own, so the user decides when time counts again. A run whose plan no longer matches the routine is ended.
     */
    private suspend fun adopt(stored: ActiveExecution, plan: List<TimedStep>) {
        val byId = plan.associateBy { it.id }
        val intact = stored.stepIds.isNotEmpty() &&
            stored.stepIndex in stored.stepIds.indices &&
            stored.stepIds.all { it in byId }
        if (!intact) {
            endRun(stored, monotonicClock.elapsedMillis(), SessionOutcome.CANCELLED, stored.focusedMillis)
            timer.load(plan)
            return
        }
        val before = timer.state.value.status
        timer.restore(
            plan = stored.stepIds.map { byId.getValue(it) },
            stepIndex = stored.stepIndex,
            elapsedMillis = stored.elapsedMillis,
            focusedMillis = stored.focusedMillis,
        )
        activeRun = stored
        persist()
        onStatusChanged(before)
    }

    private suspend fun planOf(routineId: UUID): List<TimedStep> =
        routineRepository.observeSteps(routineId).first()
            .map { TimedStep(it.id, it.title, it.durationSeconds) }

    private suspend fun handle(events: List<TimerEvent>) {
        val run = activeRun ?: return
        val routineTitle = meta.value.routineTitle
        val steps = timer.state.value.steps
        val finishing = events.any { it is TimerEvent.Finished }
        for (event in events) {
            when (event) {
                is TimerEvent.StepCompleted -> {
                    val completedAt = wallTime(event.atMillis)
                    executionRepository.recordStepCompletion(
                        StepCompletionEvent(
                            id = UUID.randomUUID(),
                            sessionId = run.sessionId,
                            stepId = event.stepId,
                            completedAt = completedAt,
                            createdAt = Instant.now(wallClock),
                            localDate = LocalDate.ofInstant(completedAt, wallClock.zone),
                        ),
                    )
                    val index = steps.indexOfFirst { it.id == event.stepId }
                    val next = steps.getOrNull(index + 1)
                    if (!finishing && index >= 0 && next != null) {
                        eventFlow.emit(
                            RunEvent.StepCompleted(run.routineId, routineTitle, steps[index].title, next.title),
                        )
                    }
                }
                is TimerEvent.StepSkipped -> Unit
                is TimerEvent.Finished -> {
                    endRun(run, event.atMillis, SessionOutcome.FINISHED, timer.snapshot().focusedMillis)
                    eventFlow.emit(RunEvent.RoutineFinished(run.routineId, routineTitle))
                }
                is TimerEvent.Cancelled -> {
                    endRun(run, event.atMillis, SessionOutcome.CANCELLED, timer.snapshot().focusedMillis)
                    meta.update { it.copy(isClosed = true) }
                }
            }
        }
    }

    private suspend fun endRun(run: ActiveExecution, atMillis: Long, outcome: SessionOutcome, focusedMillis: Long) {
        executionRepository.endSession(run.sessionId, outcome, wallTime(atMillis), focusedMillis)
        activeExecutionStore.clear()
        reminders.cancel()
        activeRun = null
    }

    /**
     * Reminders follow the pause state; ending a run cancels its reminder in [endRun]. Entering RUNNING also asks
     * the platform to keep the run alive.
     */
    private fun onStatusChanged(before: ExecutionStatus) {
        val after = timer.state.value.status
        if (after == before) return
        when (after) {
            ExecutionStatus.RUNNING -> {
                reminders.cancel()
                val running = foreground.ensureRunning()
                meta.update { it.copy(foregroundUnavailable = !running) }
            }
            ExecutionStatus.PAUSED -> activeRun?.let { reminders.schedule(it.sessionId) }
            ExecutionStatus.NOT_STARTED, ExecutionStatus.COMPLETED, ExecutionStatus.CANCELLED -> Unit
        }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (timer.state.value.status == ExecutionStatus.RUNNING) {
                delay(TICK_MILLIS)
                gate.withLock {
                    handle(timer.tick())
                    val checkpointDue = monotonicClock.elapsedMillis() - lastCheckpointMillis >=
                        CHECKPOINT_INTERVAL_MILLIS
                    if (activeRun != null && checkpointDue) persist()
                }
            }
        }
    }

    private suspend fun persist() {
        val run = activeRun ?: return
        val snapshot = timer.snapshot()
        lastCheckpointMillis = monotonicClock.elapsedMillis()
        if (snapshot.status.isActive) {
            activeExecutionStore.save(
                run.copy(
                    stepIndex = snapshot.stepIndex,
                    elapsedMillis = snapshot.elapsedMillis,
                    status = snapshot.status,
                    focusedMillis = snapshot.focusedMillis,
                ),
            )
        }
    }

    /** Monotonic readings are not wall-clock values, so convert relative to now. */
    private fun wallTime(atMillis: Long): Instant =
        Instant.now(wallClock).minusMillis(monotonicClock.elapsedMillis() - atMillis)
}
