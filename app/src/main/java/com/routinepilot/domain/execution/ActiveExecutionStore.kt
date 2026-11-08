package com.routinepilot.domain.execution

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

/**
 * The run in progress, saved so it survives process death. The timer is not kept alive in the background;
 * a restored run comes back paused at the last saved elapsed time.
 */
data class ActiveExecution(
    val sessionId: UUID,
    val routineId: UUID,
    val startedAt: Instant,
    val stepIds: List<UUID>,
    val stepIndex: Int,
    val elapsedMillis: Long,
    val status: ExecutionStatus,
    val focusedMillis: Long = 0L,
    /**
     * Wall-clock time of the checkpoint. Zero for runs saved before this field existed. Used only for widget display.
     */
    val savedAtEpochMillis: Long = 0L,
)

interface ActiveExecutionStore {
    /** Emits the current run, or null when no run is in progress. */
    fun observe(): Flow<ActiveExecution?>

    suspend fun save(execution: ActiveExecution)

    suspend fun clear()
}
