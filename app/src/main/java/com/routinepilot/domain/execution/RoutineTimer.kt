package com.routinepilot.domain.execution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State machine for running a routine. Every public command first settles any step whose time has run out,
 * so commands always act on the timeline as it is at the moment they are issued.
 *
 * Elapsed time is measured against [clock] (monotonic) and never by counting ticks. Time left over after a step
 * expires carries into the next step, so a late tick still lands on the correct step.
 *
 * Commands that do not apply to the current status are ignored and return no events.
 */
class RoutineTimer(private val clock: MonotonicClock) {
    private var steps: List<TimedStep> = emptyList()
    private var status = ExecutionStatus.NOT_STARTED
    private var index = 0
    private var accumulatedMillis = 0L
    private var runningSinceMillis: Long? = null

    private val _state = MutableStateFlow(TimerState())
    val state: StateFlow<TimerState> = _state.asStateFlow()

    fun load(plan: List<TimedStep>) {
        if (status != ExecutionStatus.NOT_STARTED) return
        steps = plan
        publish(clock.elapsedMillis())
    }

    /** Restores an interrupted run. It comes back paused so the user decides when time starts counting again. */
    fun restore(plan: List<TimedStep>, stepIndex: Int, elapsedMillis: Long) {
        if (status != ExecutionStatus.NOT_STARTED || stepIndex !in plan.indices) return
        steps = plan
        index = stepIndex
        accumulatedMillis = elapsedMillis.coerceAtLeast(0L)
        status = ExecutionStatus.PAUSED
        publish(clock.elapsedMillis())
    }

    fun start(): List<TimerEvent> = mutate { now, _ ->
        if (status == ExecutionStatus.NOT_STARTED && steps.isNotEmpty()) {
            status = ExecutionStatus.RUNNING
            index = 0
            accumulatedMillis = 0L
            runningSinceMillis = now
        }
    }

    fun pause(): List<TimerEvent> = mutate { now, _ ->
        if (status == ExecutionStatus.RUNNING) {
            accumulatedMillis = elapsedAt(now)
            runningSinceMillis = null
            status = ExecutionStatus.PAUSED
        }
    }

    fun resume(): List<TimerEvent> = mutate { now, _ ->
        if (status == ExecutionStatus.PAUSED) {
            runningSinceMillis = now
            status = ExecutionStatus.RUNNING
        }
    }

    fun skip(): List<TimerEvent> = mutate { now, events ->
        if (status.isActive) {
            events += TimerEvent.StepSkipped(steps[index].id, now)
            advance(now, events)
        }
    }

    /** Moves back one step, or restarts the first step. The step reached always starts from zero. */
    fun previous(): List<TimerEvent> = mutate { now, _ ->
        if (status.isActive) {
            if (index > 0) index--
            accumulatedMillis = 0L
            runningSinceMillis = if (status == ExecutionStatus.RUNNING) now else null
        }
    }

    /** Ends the routine now. The current step counts as completed; later steps are left unrecorded. */
    fun finish(): List<TimerEvent> = mutate { now, events ->
        if (status.isActive) {
            events += TimerEvent.StepCompleted(steps[index].id, now)
            complete(now, events)
        }
    }

    /** Ends the routine without recording the current step as completed. */
    fun cancel(): List<TimerEvent> = mutate { now, events ->
        if (status.isActive) {
            status = ExecutionStatus.CANCELLED
            runningSinceMillis = null
            events += TimerEvent.Cancelled(now)
        }
    }

    /** Applies any expiry that has happened since the last command. Safe to call at any frequency. */
    fun tick(): List<TimerEvent> = mutate { _, _ -> }

    fun snapshot(): TimerSnapshot = TimerSnapshot(status, index, elapsedAt(clock.elapsedMillis()))

    private fun mutate(change: (now: Long, events: MutableList<TimerEvent>) -> Unit): List<TimerEvent> {
        val now = clock.elapsedMillis()
        val events = mutableListOf<TimerEvent>()
        settle(now, events)
        change(now, events)
        publish(now)
        return events.toList()
    }

    private fun settle(now: Long, events: MutableList<TimerEvent>) {
        while (status == ExecutionStatus.RUNNING) {
            val duration = steps[index].durationMillis ?: return
            val elapsed = elapsedAt(now)
            if (elapsed < duration) return
            val expiredAt = now - (elapsed - duration)
            events += TimerEvent.StepCompleted(steps[index].id, expiredAt)
            advance(expiredAt, events)
        }
    }

    /** Moves to the next step, which starts at [at]. Completes the routine after the last step. */
    private fun advance(at: Long, events: MutableList<TimerEvent>) {
        if (index == steps.lastIndex) {
            complete(at, events)
        } else {
            index++
            accumulatedMillis = 0L
            runningSinceMillis = if (status == ExecutionStatus.RUNNING) at else null
        }
    }

    private fun complete(at: Long, events: MutableList<TimerEvent>) {
        status = ExecutionStatus.COMPLETED
        accumulatedMillis = 0L
        runningSinceMillis = null
        events += TimerEvent.Finished(at)
    }

    private fun elapsedAt(now: Long): Long = accumulatedMillis + (runningSinceMillis?.let { now - it } ?: 0L)

    private fun publish(now: Long) {
        val current = steps.getOrNull(index)
        val elapsed = elapsedAt(now)
        val showsCountdown = status == ExecutionStatus.NOT_STARTED || status.isActive
        val remaining = current?.durationMillis
            ?.takeIf { showsCountdown }
            ?.let { (it - elapsed).coerceAtLeast(0L) }
        _state.value = TimerState(
            status = status,
            steps = steps,
            currentIndex = index,
            currentElapsedMillis = elapsed,
            currentRemainingMillis = remaining,
            totalRemainingMillis = totalRemaining(remaining),
        )
    }

    private fun totalRemaining(currentRemaining: Long?): Long = when (status) {
        ExecutionStatus.NOT_STARTED -> steps.sumOf { it.durationMillis ?: 0L }
        ExecutionStatus.RUNNING, ExecutionStatus.PAUSED ->
            (currentRemaining ?: 0L) + steps.drop(index + 1).sumOf { it.durationMillis ?: 0L }
        ExecutionStatus.COMPLETED, ExecutionStatus.CANCELLED -> 0L
    }
}
