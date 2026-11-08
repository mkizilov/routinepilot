package com.routinepilot.data.repository

import androidx.room.withTransaction
import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.model.ExecutionSession
import com.routinepilot.domain.model.OccurrenceStatus
import com.routinepilot.domain.model.SessionOutcome
import com.routinepilot.domain.model.StepCompletionEvent
import com.routinepilot.domain.repository.ExecutionRepository
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class ExecutionRepositoryImpl
    @Inject
    constructor(
        private val database: RoutinePilotDatabase,
    ) : ExecutionRepository {
        private val sessionDao = database.executionSessionDao()
        private val eventDao = database.stepCompletionEventDao()
        private val occurrenceDao = database.scheduledOccurrenceDao()

        override fun observeSessions(routineId: UUID): Flow<List<ExecutionSession>> =
            sessionDao.observeActiveForRoutine(routineId).map { list -> list.map { it.toDomain() } }

        override suspend fun saveSession(session: ExecutionSession) {
            sessionDao.upsert(session.toEntity(Instant.now()))
        }

        override suspend fun startSession(session: ExecutionSession): ExecutionSession =
            database.withTransaction {
                val due = occurrenceDao.earliestPendingDue(session.routineId, session.startedAt.plus(LINK_WINDOW))
                val linked = session.copy(occurrenceId = due?.id)
                sessionDao.upsert(linked.toEntity(session.startedAt))
                linked
            }

        override suspend fun endSession(
            id: UUID,
            outcome: SessionOutcome,
            endedAt: Instant,
            focusedMillis: Long,
        ) {
            database.withTransaction<Unit> {
                val row = sessionDao.findById(id) ?: return@withTransaction
                if (row.endedAt != null) return@withTransaction
                sessionDao.upsert(
                    row.copy(endedAt = endedAt, outcome = outcome, focusedMillis = focusedMillis, modifiedAt = endedAt),
                )
                val linkedId = row.occurrenceId ?: return@withTransaction
                if (outcome != SessionOutcome.FINISHED) return@withTransaction
                val occurrence = occurrenceDao.findById(linkedId) ?: return@withTransaction
                if (occurrence.deletedAt == null && occurrence.status.isPending) {
                    occurrenceDao.upsert(occurrence.copy(status = OccurrenceStatus.COMPLETED, modifiedAt = endedAt))
                }
            }
        }

        override suspend fun deleteSession(id: UUID) {
            sessionDao.markDeleted(id, Instant.now())
        }

        override fun observeCompletions(sessionId: UUID): Flow<List<StepCompletionEvent>> =
            eventDao.observeActiveForSession(sessionId).map { list -> list.map { it.toDomain() } }

        override suspend fun recordStepCompletion(event: StepCompletionEvent) {
            eventDao.upsert(event.toEntity(Instant.now()))
        }

        private companion object {
            /** A session started up to this long before a scheduled time claims that item. */
            val LINK_WINDOW: Duration = Duration.ofHours(1)
        }
    }
