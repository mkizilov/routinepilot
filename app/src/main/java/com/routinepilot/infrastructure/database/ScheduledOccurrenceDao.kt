package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

@Dao
interface ScheduledOccurrenceDao {
    @Query(
        "SELECT * FROM scheduled_occurrences WHERE routineId = :routineId AND deletedAt IS NULL " +
            "ORDER BY scheduledAt, id",
    )
    fun observeActiveForRoutine(routineId: UUID): Flow<List<ScheduledOccurrenceEntity>>

    @Upsert
    suspend fun upsert(occurrence: ScheduledOccurrenceEntity)

    @Query("UPDATE scheduled_occurrences SET deletedAt = :at, modifiedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: UUID, at: Instant)
}
