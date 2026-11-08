package com.routinepilot.domain.repository

import com.routinepilot.domain.model.ScheduledOccurrence
import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface ScheduleRepository {
    fun observeOccurrences(routineId: UUID): Flow<List<ScheduledOccurrence>>

    suspend fun saveOccurrence(occurrence: ScheduledOccurrence)

    suspend fun deleteOccurrence(id: UUID)
}
