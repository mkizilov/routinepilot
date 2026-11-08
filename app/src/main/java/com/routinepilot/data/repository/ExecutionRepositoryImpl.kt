package com.routinepilot.data.repository

import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.repository.ExecutionRepository
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class ExecutionRepositoryImpl @Inject constructor(
    database: RoutinePilotDatabase,
) : ExecutionRepository {
    private val sessionDao = database.executionSessionDao()
    private val eventDao = database.stepCompletionEventDao()

    override fun observeSessions(routineId: UUID): Flow<List<ExecutionSession>> =
        sessionDao.observeActiveForRoutine(routineId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveSession(session: ExecutionSession) {
        sessionDao.upsert(session.toEntity(Instant.now()))
    }

    override suspend fun deleteSession(id: UUID) {
        sessionDao.markDeleted(id, Instant.now())
    }

    override fun observeCompletions(sessionId: UUID): Flow<List<StepCompletionEvent>> =
        eventDao.observeActiveForSession(sessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun recordStepCompletion(event: StepCompletionEvent) {
        eventDao.upsert(event.toEntity(Instant.now()))
    }
}
