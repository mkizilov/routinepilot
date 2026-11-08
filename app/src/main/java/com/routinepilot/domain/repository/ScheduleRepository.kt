package com.routinepilot.domain.repository

import com.routinepilot.domain.model.ScheduledOccurrence
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeOccurrences(routineId: UUID): Flow<List<ScheduledOccurrence>>

    suspend fun saveOccurrence(occurrence: ScheduledOccurrence)

    suspend fun deleteOccurrence(id: UUID)
}
