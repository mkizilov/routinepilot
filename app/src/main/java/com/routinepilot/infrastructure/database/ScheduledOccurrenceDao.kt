package com.routinepilot.infrastructure.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query(
        "SELECT * FROM scheduled_occurrences WHERE scheduledAt >= :from AND scheduledAt < :to " +
            "AND deletedAt IS NULL ORDER BY scheduledAt, id",
    )
    fun observeInRange(from: Instant, to: Instant): Flow<List<ScheduledOccurrenceEntity>>

    @Query("SELECT * FROM scheduled_occurrences WHERE id = :id")
    suspend fun findById(id: UUID): ScheduledOccurrenceEntity?

    /** Every row for a schedule, including deleted ones, because deleted slots must not be regenerated. */
    @Query("SELECT * FROM scheduled_occurrences WHERE scheduleId = :scheduleId")
    suspend fun allForSchedule(scheduleId: UUID): List<ScheduledOccurrenceEntity>

    /** Returns one row id per entity, or -1 where the slot already existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(occurrences: List<ScheduledOccurrenceEntity>): List<Long>

    @Upsert
    suspend fun upsert(occurrence: ScheduledOccurrenceEntity)

    /** Hard delete for rows the system drops because a rule changed. They have no history to keep. */
    @Query("DELETE FROM scheduled_occurrences WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<UUID>)

    @Query("DELETE FROM scheduled_occurrences WHERE scheduleId = :scheduleId AND status IN ('PLANNED', 'RESCHEDULED')")
    suspend fun deletePendingForSchedule(scheduleId: UUID)

    /** Soft delete for an occurrence the user removed. The slot stays claimed, so it is not regenerated. */
    @Query("UPDATE scheduled_occurrences SET deletedAt = :at, modifiedAt = :at WHERE id = :id")
    suspend fun markDeleted(id: UUID, at: Instant)
}
