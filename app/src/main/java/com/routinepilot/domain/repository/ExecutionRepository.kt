package com.routinepilot.domain.repository

import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.StepCompletionEvent
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface ExecutionRepository {
    /** Most recently started sessions first. */
    fun observeSessions(routineId: UUID): Flow<List<ExecutionSession>>

    suspend fun saveSession(session: ExecutionSession)

    suspend fun deleteSession(id: UUID)

    fun observeCompletions(sessionId: UUID): Flow<List<StepCompletionEvent>>

    suspend fun recordStepCompletion(event: StepCompletionEvent)
}
