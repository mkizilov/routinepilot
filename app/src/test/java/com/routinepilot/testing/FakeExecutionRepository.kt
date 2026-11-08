package com.routinepilot.testing

import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.SessionOutcome
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.repository.ExecutionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.util.UUID

class FakeExecutionRepository : ExecutionRepository {
    val sessions = MutableStateFlow<List<ExecutionSession>>(emptyList())
    val completions = MutableStateFlow<List<StepCompletionEvent>>(emptyList())

    override fun observeSessions(routineId: UUID): Flow<List<ExecutionSession>> =
        sessions.map { list -> list.filter { it.routineId == routineId } }

    override suspend fun saveSession(session: ExecutionSession) {
        sessions.update { list -> list.filterNot { it.id == session.id } + session }
    }

    /** The fake does not link occurrences, so the session is stored as given. */
    override suspend fun startSession(session: ExecutionSession): ExecutionSession {
        saveSession(session)
        return session
    }

    override suspend fun endSession(
        id: UUID,
        outcome: SessionOutcome,
        endedAt: Instant,
        focusedMillis: Long,
    ) {
        sessions.update { list ->
            list.map { session ->
                if (session.id == id && session.endedAt == null) {
                    session.copy(endedAt = endedAt, outcome = outcome, focusedMillis = focusedMillis)
                } else {
                    session
                }
            }
        }
    }

    override suspend fun deleteSession(id: UUID) {
        sessions.update { list -> list.filterNot { it.id == id } }
    }

    override fun observeCompletions(sessionId: UUID): Flow<List<StepCompletionEvent>> =
        completions.map { list -> list.filter { it.sessionId == sessionId } }

    override suspend fun recordStepCompletion(event: StepCompletionEvent) {
        completions.update { it + event }
    }
}
