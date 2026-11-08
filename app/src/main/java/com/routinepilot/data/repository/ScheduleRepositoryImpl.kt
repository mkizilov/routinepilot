package com.routinepilot.data.repository

import com.routinepilot.data.mapping.toDomain
import com.routinepilot.data.mapping.toEntity
import com.routinepilot.domain.model.ScheduledOccurrence
import com.routinepilot.domain.repository.ScheduleRepository
import com.routinepilot.infrastructure.database.RoutinePilotDatabase
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ScheduleRepositoryImpl @Inject constructor(
    database: RoutinePilotDatabase,
) : ScheduleRepository {
    private val occurrenceDao = database.scheduledOccurrenceDao()

    override fun observeOccurrences(routineId: UUID): Flow<List<ScheduledOccurrence>> =
        occurrenceDao.observeActiveForRoutine(routineId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveOccurrence(occurrence: ScheduledOccurrence) {
        occurrenceDao.upsert(occurrence.toEntity(Instant.now()))
    }

    override suspend fun deleteOccurrence(id: UUID) {
        occurrenceDao.markDeleted(id, Instant.now())
    }
}
