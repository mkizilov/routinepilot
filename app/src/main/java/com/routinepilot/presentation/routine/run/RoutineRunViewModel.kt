package com.routinepilot.presentation.routine.run

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.routinepilot.domain.execution.ActiveExecution
import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.execution.MonotonicClock
import com.routinepilot.domain.execution.RoutineTimer
import com.routinepilot.domain.execution.TimedStep
import com.routinepilot.domain.execution.TimerEvent
import com.routinepilot.domain.execution.TimerState
import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.repository.ExecutionRepository
import com.routinepilot.domain.repository.RoutineRepository
import com.routinepilot.presentation.routine.editor.ROUTINE_ID_ARG
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

private const val TICK_MILLIS = 250L
private const val PERSIST_INTERVAL_MILLIS = 5_000L

data class RoutineRunUiState(
    val routineTitle: String = "",
    val timer: TimerState = TimerState(),
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val blockedByOtherRun: Boolean = false,
    val isClosed: Boolean = false,
)

interface RoutineRunActions {
    fun start()

    fun pause()

    fun resume()

    fun skip()

    fun previous()

    fun finish()

    fun cancel()

    /** Pauses a running timer when the screen leaves the foreground. */
    fun onStopped()
}

private data class RunMeta(
    val title: String = "",
    val loading: Boolean = true,
    val missing: Boolean = false,
    val blocked: Boolean = false,
    val closed: Boolean = false,
)

@HiltViewModel
class RoutineRunViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val routineRepository: RoutineRepository,
    private val executionRepository: ExecutionRepository,
    private val activeExecutionStore: ActiveExecutionStore,
    private val monotonicClock: MonotonicClock,
    private val wallClock: Clock,
) : ViewModel(), RoutineRunActions {
    private val routineId = UUID.fromString(checkNotNull(savedStateHandle.get<String>(ROUTINE_ID_ARG)))
    private val timer = RoutineTimer(monotonicClock)
    private val meta = MutableStateFlow(RunMeta())
    private val gate = Mutex()
    private var activeRun: ActiveExecution? = null
    private var lastPersistMillis = 0L
    private var tickerJob: Job? = null

    val uiState: StateFlow<RoutineRunUiState> = combine(timer.state, meta) { timerState, runMeta ->
        RoutineRunUiState(
            routineTitle = runMeta.title,
            timer = timerState,
            isLoading = runMeta.loading,
            isMissing = runMeta.missing,
            blockedByOtherRun = runMeta.blocked,
            isClosed = runMeta.closed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RoutineRunUiState())

    init {
        viewModelScope.launch { gate.withLock { load() } }
    }

    override fun start() = serialized {
        val state = timer.state.value
        if (state.status != ExecutionStatus.NOT_STARTED || state.steps.isEmpty()) return@serialized
        if (activeExecutionStore.observe().first() != null) {
            meta.update { it.copy(blocked = true) }
            return@serialized
        }
        meta.update { it.copy(blocked = false) }
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
        executionRepository.saveSession(
            ExecutionSession(
                id = run.sessionId,
                routineId = routineId,
                occurrenceId = null,
                startedAt = startedAt,
                endedAt = null,
                createdAt = startedAt,
            ),
        )
        persist()
    }

    override fun pause() = serialized {
        handle(timer.pause())
        persist()
    }

    override fun resume() = serialized {
        handle(timer.resume())
        persist()
    }

    override fun skip() = serialized {
        handle(timer.skip())
        persist()
    }

    override fun previous() = serialized {
        handle(timer.previous())
        persist()
    }

    override fun finish() = serialized {
        handle(timer.finish())
        persist()
    }

    override fun cancel() = serialized {
        handle(timer.cancel())
        persist()
    }

    override fun onStopped() = serialized {
        if (timer.state.value.status == ExecutionStatus.RUNNING) {
            handle(timer.pause())
            persist()
        }
    }

    private suspend fun load() {
        val routine = routineRepository.observeRoutine(routineId).first()
        if (routine == null) {
            meta.update { it.copy(loading = false, missing = true) }
            return
        }
        val plan = routineRepository.observeSteps(routineId).first()
            .map { TimedStep(it.id, it.title, it.durationSeconds) }
        val byId = plan.associateBy { it.id }
        val active = activeExecutionStore.observe().first()
        when {
            active == null || active.routineId != routineId -> timer.load(plan)
            active.stepIds.isNotEmpty() && active.stepIds.all { it in byId } -> {
                activeRun = active
                timer.restore(active.stepIds.map { byId.getValue(it) }, active.stepIndex, active.elapsedMillis)
            }
            else -> {
                // The routine changed while the run was interrupted, so the saved step plan can't be trusted.
                endRun(active, monotonicClock.elapsedMillis())
                timer.load(plan)
            }
        }
        meta.update { it.copy(title = routine.title, loading = false) }
    }

    /** Runs a command under the gate, then keeps the ticker alive if the timer is now running. */
    private fun serialized(block: suspend () -> Unit) {
        viewModelScope.launch {
            gate.withLock {
                block()
                if (timer.state.value.status == ExecutionStatus.RUNNING) ensureTicker()
            }
        }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (timer.state.value.status == ExecutionStatus.RUNNING) {
                delay(TICK_MILLIS)
                gate.withLock {
                    handle(timer.tick())
                    val due = monotonicClock.elapsedMillis() - lastPersistMillis >= PERSIST_INTERVAL_MILLIS
                    if (due) persist()
                }
            }
        }
    }

    private suspend fun handle(events: List<TimerEvent>) {
        val run = activeRun ?: return
        events.forEach { event ->
            when (event) {
                is TimerEvent.StepCompleted -> executionRepository.recordStepCompletion(
                    StepCompletionEvent(
                        id = UUID.randomUUID(),
                        sessionId = run.sessionId,
                        stepId = event.stepId,
                        completedAt = wallTime(event.atMillis),
                        createdAt = Instant.now(wallClock),
                    ),
                )
                is TimerEvent.StepSkipped -> Unit
                is TimerEvent.Finished -> endRun(run, event.atMillis)
                is TimerEvent.Cancelled -> {
                    endRun(run, event.atMillis)
                    meta.update { it.copy(closed = true) }
                }
            }
        }
    }

    private suspend fun endRun(run: ActiveExecution, atMillis: Long) {
        executionRepository.saveSession(
            ExecutionSession(
                id = run.sessionId,
                routineId = run.routineId,
                occurrenceId = null,
                startedAt = run.startedAt,
                endedAt = wallTime(atMillis),
                createdAt = run.startedAt,
            ),
        )
        activeExecutionStore.clear()
        activeRun = null
    }

    private suspend fun persist() {
        val run = activeRun ?: return
        val snapshot = timer.snapshot()
        lastPersistMillis = monotonicClock.elapsedMillis()
        if (snapshot.status.isActive) {
            activeExecutionStore.save(
                run.copy(
                    stepIndex = snapshot.stepIndex,
                    elapsedMillis = snapshot.elapsedMillis,
                    status = snapshot.status,
                ),
            )
        }
    }

    /** Converts a monotonic reading to wall time. Monotonic readings are not wall-clock values, so we anchor to now. */
    private fun wallTime(atMillis: Long): Instant =
        Instant.now(wallClock).minusMillis(monotonicClock.elapsedMillis() - atMillis)
}
