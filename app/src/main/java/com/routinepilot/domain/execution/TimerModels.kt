package com.routinepilot.domain.execution

import java.util.UUID

private const val MILLIS_PER_SECOND = 1_000L

enum class ExecutionStatus {
    NOT_STARTED,
    RUNNING,
    PAUSED,
    COMPLETED,
    CANCELLED;

    val isActive: Boolean
        get() = this == RUNNING || this == PAUSED
}

/** A routine step as seen by the timer. A null duration means the step has no countdown and is never auto-advanced. */
data class TimedStep(val id: UUID, val title: String, val durationSeconds: Int?) {
    val durationMillis: Long?
        get() = durationSeconds?.let { it * MILLIS_PER_SECOND }
}

/** Events carry the monotonic time at which the change happened, not wall-clock time. */
sealed interface TimerEvent {
    val atMillis: Long

    data class StepCompleted(val stepId: UUID, override val atMillis: Long) : TimerEvent

    data class StepSkipped(val stepId: UUID, override val atMillis: Long) : TimerEvent

    data class Finished(override val atMillis: Long) : TimerEvent

    data class Cancelled(override val atMillis: Long) : TimerEvent
}

data class TimerSnapshot(val status: ExecutionStatus, val stepIndex: Int, val elapsedMillis: Long)

data class TimerState(
    val status: ExecutionStatus = ExecutionStatus.NOT_STARTED,
    val steps: List<TimedStep> = emptyList(),
    val currentIndex: Int = 0,
    val currentElapsedMillis: Long = 0L,
    val currentRemainingMillis: Long? = null,
    val totalRemainingMillis: Long = 0L,
) {
    val currentStep: TimedStep?
        get() = steps.getOrNull(currentIndex)

    val nextStep: TimedStep?
        get() = if (status.isActive) steps.getOrNull(currentIndex + 1) else null
}
