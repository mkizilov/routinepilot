package com.routinepilot.domain.repository

import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.SessionOutcome
import com.routinepilot.domain.model.StepCompletionEvent
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

interface ExecutionRepository {
    /** Most recently started sessions first. */
    fun observeSessions(routineId: UUID): Flow<List<ExecutionSession>>

    suspend fun saveSession(session: ExecutionSession)

    /**
     * Records a new session. If the routine has a pending scheduled item due within the next hour, the session is
     * linked to the earliest such item. Returns the session as stored.
     */
    suspend fun startSession(session: ExecutionSession): ExecutionSession

    /**
     * Records how a session ended. A FINISHED session also completes its linked item, if that item is still pending.
     * Ending a session that has already ended does nothing.
     */
    suspend fun endSession(
        id: UUID,
        outcome: SessionOutcome,
        endedAt: Instant,
        focusedMillis: Long,
    )

    suspend fun deleteSession(id: UUID)

    fun observeCompletions(sessionId: UUID): Flow<List<StepCompletionEvent>>

    suspend fun recordStepCompletion(event: StepCompletionEvent)
}
