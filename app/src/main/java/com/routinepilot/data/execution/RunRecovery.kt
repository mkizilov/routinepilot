package com.routinepilot.data.execution

import com.routinepilot.domain.execution.ActiveExecutionStore
import com.routinepilot.domain.execution.ExecutionStatus
import com.routinepilot.domain.repository.RoutineRepository
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

data class InterruptedRun(val sessionId: UUID, val routineId: UUID, val routineTitle: String)

/**
 * Work that must happen without the app's run coordinator: right after a device reboot, and inside deferred
 * reminder jobs. Both only touch the stored run.
 */
class RunRecovery @Inject constructor(
    private val activeExecutionStore: ActiveExecutionStore,
    private val routineRepository: RoutineRepository,
) {
    /**
     * A reboot clears the monotonic clock, so a run that was RUNNING cannot keep timing. It is paused at its last
     * checkpoint and returned so the caller can tell the user. Runs that are not RUNNING are left alone.
     */
    suspend fun pauseInterruptedRun(): InterruptedRun? {
        val run = activeExecutionStore.observe().first() ?: return null
        if (run.status != ExecutionStatus.RUNNING) return null
        activeExecutionStore.save(run.copy(status = ExecutionStatus.PAUSED))
        val title = routineRepository.observeRoutine(run.routineId).first()?.title ?: return null
        return InterruptedRun(run.sessionId, run.routineId, title)
    }

    /** Returns the run only while the same run is still paused. A reminder for a run that has moved on is skipped. */
    suspend fun pausedRunForReminder(sessionId: UUID): InterruptedRun? {
        val run = activeExecutionStore.observe().first() ?: return null
        if (run.sessionId != sessionId || run.status != ExecutionStatus.PAUSED) return null
        val title = routineRepository.observeRoutine(run.routineId).first()?.title ?: return null
        return InterruptedRun(run.sessionId, run.routineId, title)
    }
}
