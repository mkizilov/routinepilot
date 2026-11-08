package com.routinepilot.domain.execution

import java.util.UUID

/** Everything the run screen, the foreground service, and notifications need about the process-wide run. */
data class RunSnapshot(
    val routineId: UUID? = null,
    val routineTitle: String = "",
    val timer: TimerState = TimerState(),
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val blockedByOtherRun: Boolean = false,
    val isClosed: Boolean = false,
    val foregroundUnavailable: Boolean = false,
)

/** Transitions worth telling the user about, even when the app is not on screen. */
sealed interface RunEvent {
    data class StepCompleted(
        val routineId: UUID,
        val routineTitle: String,
        val stepTitle: String,
        val nextStepTitle: String,
    ) : RunEvent

    data class RoutineFinished(
        val routineId: UUID,
        val routineTitle: String,
    ) : RunEvent
}

/** Schedules a deferrable reminder for a run that has been paused. */
interface PausedRunReminders {
    fun schedule(sessionId: UUID)

    fun cancel()
}

/** Keeps the active run alive in the background. Returns false when the platform refuses. */
interface RunForegroundLauncher {
    fun ensureRunning(): Boolean
}
